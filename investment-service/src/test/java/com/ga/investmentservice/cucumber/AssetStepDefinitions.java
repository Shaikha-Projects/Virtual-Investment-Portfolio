package com.ga.investmentservice.cucumber;

import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.DTO.Response.UserIdentityResponse;
import com.ga.investmentservice.Enums.AssetStatus;
import com.ga.investmentservice.Enums.AssetType;
import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Repository.AssetRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.After;
import io.cucumber.java.en.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AssetStepDefinitions {
    private static final String EMAIL = "bdd-investor@example.com";
    @LocalServerPort private int port;
    @Autowired private AssetRepository repository;
    @Autowired private AuthServiceClient authServiceClient;
    @Autowired private ApplicationContext context;
    private final List<Asset> catalog = new ArrayList<>();
    private String token;
    private Response response;

    @Before
    public void resetScenario() {
        assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty(), "BDD must never create a database connection pool");
        assertTrue(mockingDetails(repository).isMock());
        assertTrue(mockingDetails(authServiceClient).isMock());
        assertTrue(context.getBeansOfType(org.springframework.kafka.core.KafkaTemplate.class).isEmpty());
        assertTrue(context.getBeansOfType(com.ga.investmentservice.Client.StripeClient.class).isEmpty());
        assertTrue(context.getBeansOfType(com.google.genai.Client.class).isEmpty());
        reset(repository, authServiceClient);
        catalog.clear();
        token = null;
        response = null;
    }

    @Given("the controlled asset catalog contains:")
    public void catalog(DataTable table) {
        long id = 1;
        for (Map<String, String> row : table.asMaps()) {
            Asset asset = new Asset();
            asset.setId(id++);
            asset.setSymbol(row.get("symbol"));
            asset.setName(row.get("name"));
            asset.setAssetType(AssetType.valueOf(row.get("type")));
            asset.setCurrentPrice(new BigDecimal(row.get("price")));
            asset.setAssetStatus(AssetStatus.valueOf(row.get("status")));
            catalog.add(asset);
        }
        when(repository.findByAssetStatus(any())).thenAnswer(call -> catalog.stream()
                .filter(asset -> asset.getAssetStatus() == call.getArgument(0)).toList());
        when(repository.findByAssetStatusAndAssetType(any(), any())).thenAnswer(call -> catalog.stream()
                .filter(asset -> asset.getAssetStatus() == call.getArgument(0)
                        && asset.getAssetType() == call.getArgument(1)).toList());
        when(repository.findByAssetStatusAndSymbolContainingIgnoreCaseOrAssetStatusAndNameContainingIgnoreCase(
                any(), anyString(), any(), anyString())).thenAnswer(call -> catalog.stream()
                .filter(asset -> (asset.getAssetStatus() == call.getArgument(0)
                        && asset.getSymbol().toLowerCase(Locale.ROOT).contains(call.<String>getArgument(1).toLowerCase(Locale.ROOT)))
                        || (asset.getAssetStatus() == call.getArgument(2)
                        && asset.getName().toLowerCase(Locale.ROOT).contains(call.<String>getArgument(3).toLowerCase(Locale.ROOT))))
                .toList());
    }

    @Given("an authenticated investor")
    public void authenticated() {
        when(authServiceClient.getUserByEmail(EMAIL)).thenReturn(new UserIdentityResponse(42L, EMAIL, "USER", "ACTIVE"));
        token = Jwts.builder().setSubject(EMAIL).setExpiration(new Date(System.currentTimeMillis() + 120000))
                .signWith(SignatureAlgorithm.HS256, CucumberSpringConfiguration.TEST_SECRET).compact();
    }

    @Given("an unauthenticated visitor")
    public void unauthenticated() { token = null; }

    private RequestSpecification request() {
        RequestSpecification request = RestAssured.given().baseUri("http://127.0.0.1").port(port).accept(ContentType.JSON);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    @When("the investor requests GET {string}")
    @When("the visitor requests GET {string}")
    public void requestAssets(String path) {
        assertEquals("/assets", path);
        response = request().get(path);
        if (token != null) verify(repository).findByAssetStatus(AssetStatus.ACTIVE);
    }

    @When("the investor requests assets with search {string}")
    public void search(String search) {
        response = request().queryParam("search", search).get("/assets");
        verify(repository).findByAssetStatusAndSymbolContainingIgnoreCaseOrAssetStatusAndNameContainingIgnoreCase(
                AssetStatus.ACTIVE, search, AssetStatus.ACTIVE, search);
    }

    @When("the investor requests assets with type {string}")
    public void filter(String type) {
        response = request().queryParam("type", type).get("/assets");
        verify(repository).findByAssetStatusAndAssetType(AssetStatus.ACTIVE, AssetType.valueOf(type));
    }

    @Then("the response status should be {int}")
    public void status(int expected) {
        response.then().statusCode(expected);
        if (expected == 200) response.then().contentType(ContentType.JSON);
    }

    @Then("the response should be a JSON array of valid asset objects")
    public void assetObjects() {
        List<Map<String, Object>> assets = response.jsonPath().getList("$");
        assertFalse(assets.isEmpty());
        for (Map<String, Object> asset : assets) {
            assertEquals(Set.of("assetId", "symbol", "name", "assetType", "currentPrice", "assetStatus"), asset.keySet());
            assertInstanceOf(Number.class, asset.get("assetId"));
            assertInstanceOf(Number.class, asset.get("currentPrice"));
            for (String field : List.of("symbol", "name", "assetType", "assetStatus")) {
                assertFalse(((String) asset.get(field)).isBlank());
            }
            Asset expected = catalog.stream().filter(item -> item.getSymbol().equals(asset.get("symbol"))).findFirst().orElseThrow();
            assertEquals(0, expected.getCurrentPrice().compareTo(new BigDecimal(asset.get("currentPrice").toString())));
        }
    }

    @Then("the returned symbols should be exactly {string}")
    public void symbols(String expected) {
        List<String> actual = new ArrayList<>(response.jsonPath().getList("symbol", String.class));
        List<String> wanted = new ArrayList<>(Arrays.asList(expected.split(",")));
        actual.sort(String::compareTo);
        wanted.sort(String::compareTo);
        assertEquals(wanted, actual);
    }

    @Then("every returned asset should have status {string}")
    public void assetStatus(String expected) { allEqual("assetStatus", expected); }

    @Then("every returned asset should have type {string}")
    public void assetType(String expected) { allEqual("assetType", expected); }

    private void allEqual(String field, String expected) {
        List<String> values = response.jsonPath().getList(field, String.class);
        assertFalse(values.isEmpty());
        values.forEach(value -> assertEquals(expected, value));
    }

    @Then("the response should be an empty JSON array")
    public void empty() { assertEquals(List.of(), response.jsonPath().getList("$")); }

    @After
    public void verifyBoundaries() {
        if (token == null) verifyNoInteractions(authServiceClient, repository);
        else verify(authServiceClient).getUserByEmail(EMAIL);
        verifyNoMoreInteractions(authServiceClient, repository);
    }
}
