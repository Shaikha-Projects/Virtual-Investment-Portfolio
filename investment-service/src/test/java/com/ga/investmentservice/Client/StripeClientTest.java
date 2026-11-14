package com.ga.investmentservice.Client;

import com.ga.investmentservice.DTO.Request.StripeCheckoutRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class StripeClientTest {
    private static final UUID REFERENCE = UUID.fromString("d77c2d55-5d1f-4680-982c-79a649bb7525");
    private static final String SUCCESS_URL = "http://localhost:5173/wallet?payment=success&session_id={CHECKOUT_SESSION_ID}";
    private static final String CANCEL_URL = "http://localhost:5173/wallet?payment=cancelled";
    private MockRestServiceServer server;
    private StripeClient client;
    private RestClient.Builder testBuilder;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        testBuilder = spy(builder);
        doReturn(testBuilder).when(testBuilder).requestFactory(any(ClientHttpRequestFactory.class));
        client = new StripeClient(testBuilder, "sk_test_unit", "2026-10-28.endive", 1000, 2000, SUCCESS_URL, CANCEL_URL);
    }

    @Test
    void createsFixedUsdSessionWithMetadataAndIdempotencyHeader() {
        expectCreation("1000", SUCCESS_URL, CANCEL_URL, session("open", "unpaid"));
        var response = client.createCheckoutSession(request("10.00"), "wallet-deposit:" + REFERENCE);
        assertEquals("cs_test_example", response.id());
        assertEquals(1000L, response.amountTotal());
        assertEquals("usd", response.currency());
        assertEquals(REFERENCE.toString(), response.clientReferenceId());
        assertEquals(REFERENCE.toString(), response.metadata().get("wallet_reference"));
        assertEquals("https://checkout.stripe.com/c/pay/example", response.url());
        assertFalse(response.livemode());
        server.verify();
    }

    @Test
    void amountConversionUsesExactCentsWithoutFloatingPointRounding() {
        expectCreation("1023", SUCCESS_URL, CANCEL_URL, session("open", "unpaid").replace("1000", "1023"));
        assertEquals(1023L, client.createCheckoutSession(request("10.23"), "wallet-deposit:" + REFERENCE).amountTotal());
        server.verify();
    }

    @Test
    void callerCanReplaySameKeyAndFrozenParameters() {
        String success = "https://example.com/original-success?session_id={CHECKOUT_SESSION_ID}";
        String cancel = "https://example.com/original-cancel";
        expectCreation("1000", success, cancel, session("open", "unpaid"));
        expectCreation("1000", success, cancel, session("open", "unpaid"));
        var request = new StripeCheckoutRequest(new BigDecimal("10.00"), REFERENCE, success, cancel);
        var first = client.createCheckoutSession(request, "wallet-deposit:" + REFERENCE);
        assertEquals(first, client.createCheckoutSession(request, "wallet-deposit:" + REFERENCE));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "complete", "expired"})
    void retrievesSessionWithoutTreatingRedirectOrUrlAsPaymentProof(String status) {
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions/cs_test_example"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk_test_unit"))
                .andRespond(withSuccess(session(status, status.equals("complete") ? "paid" : "unpaid"), MediaType.APPLICATION_JSON));
        var response = client.getCheckoutSession("cs_test_example");
        assertEquals(status, response.status());
        if (!status.equals("open")) {
            assertNull(response.url());
        }
        assertFalse(response.toString().contains("do-not-retain"));
        server.verify();
    }

    @Test
    void retrievesPaymentIntentForLaterServerSideVerification() {
        server.expect(requestTo("https://api.stripe.com/v1/payment_intents/pi_example"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk_test_unit"))
                .andRespond(withSuccess(intent(), MediaType.APPLICATION_JSON));
        var response = client.getPaymentIntent("pi_example");
        assertEquals("succeeded", response.status());
        assertEquals(1000L, response.amount());
        assertEquals(1000L, response.amountReceived());
        assertEquals(REFERENCE.toString(), response.metadata().get("wallet_reference"));
        assertFalse(response.toString().contains("do-not-retain"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.001", "10.000", "1E+100"})
    void rejectsInvalidAmountsBeforeHttp(String amount) {
        assertThrows(IllegalArgumentException.class,
                () -> client.createCheckoutSession(request(amount), "wallet-deposit:" + REFERENCE));
        server.verify();
    }

    @Test
    void rejectsMissingKeyInvalidIdsAndUnsafeReturnUrls() {
        assertThrows(IllegalArgumentException.class, () -> client.createCheckoutSession(request("10.00"), " "));
        assertThrows(IllegalArgumentException.class, () -> client.createCheckoutSession(request("10.00"), "bad\r\nkey"));
        assertThrows(IllegalArgumentException.class, () -> client.getCheckoutSession("cs_live_example"));
        assertThrows(IllegalArgumentException.class, () -> client.getCheckoutSession("../other"));
        assertThrows(IllegalArgumentException.class, () -> client.getPaymentIntent("pi_example?expand[]=customer"));
        assertThrows(IllegalArgumentException.class, () -> client.createCheckoutSession(
                new StripeCheckoutRequest(new BigDecimal("10.00"), REFERENCE, "http://example.com", CANCEL_URL), "key"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk_live_not-allowed", "", "not-a-stripe-key"})
    void acceptsOnlyTestCredentials(String key) {
        assertThrows(IllegalStateException.class,
                () -> new StripeClient(RestClient.builder(), key, "2026-10-28.endive", 1000, 2000, SUCCESS_URL, CANCEL_URL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"currency", "livemode", "id", "amount", "status", "payment_status", "object", "json"})
    void rejectsUnexpectedSessionResponses(String changed) {
        String response = switch (changed) {
            case "currency" -> session("complete", "paid").replace("\"usd\"", "\"eur\"");
            case "livemode" -> session("complete", "paid").replace("false", "true");
            case "id" -> session("complete", "paid").replace("cs_test_example", "cs_test_other");
            case "amount" -> session("complete", "paid").replace("1000", "0");
            case "status" -> session("unknown", "paid");
            case "payment_status" -> session("complete", "no_payment_required");
            case "object" -> session("complete", "paid").replace("checkout.session", "payment_intent");
            default -> "{\"private\":\"do-not-retain\"";
        };
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions/cs_test_example"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var failure = assertThrows(IllegalStateException.class, () -> client.getCheckoutSession("cs_test_example"));
        assertFalse(failure.getMessage().contains("do-not-retain"));
        assertNull(failure.getCause());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference", "metadata", "amount", "url"})
    void creationResponseMustMatchTheWalletRequest(String changed) {
        String response = switch (changed) {
            case "reference" -> session("open", "unpaid").replace("\"client_reference_id\":\"" + REFERENCE,
                    "\"client_reference_id\":\"other");
            case "metadata" -> session("open", "unpaid").replace("wallet_reference", "other");
            case "amount" -> session("open", "unpaid").replace("1000", "2000");
            default -> session("open", "unpaid").replace("checkout.stripe.com", "attacker.example.com");
        };
        expectCreation("1000", SUCCESS_URL, CANCEL_URL, response);
        assertThrows(IllegalStateException.class,
                () -> client.createCheckoutSession(request("10.00"), "wallet-deposit:" + REFERENCE));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"currency", "livemode", "amount_received", "id", "status", "empty"})
    void rejectsUnexpectedPaymentIntentResponses(String changed) {
        String response = switch (changed) {
            case "currency" -> intent().replace("\"usd\"", "\"kwd\"");
            case "livemode" -> intent().replace("false", "true");
            case "amount_received" -> intent().replace("\"amount_received\":1000", "\"amount_received\":0");
            case "id" -> intent().replace("pi_example", "pi_other");
            case "status" -> intent().replace("succeeded", "unknown");
            default -> "null";
        };
        server.expect(requestTo("https://api.stripe.com/v1/payment_intents/pi_example"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> client.getPaymentIntent("pi_example"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 409, 429, 500})
    void providerErrorsExposeOnlyHttpStatus(int status) {
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("do-not-retain provider response"));
        var failure = assertThrows(IllegalStateException.class,
                () -> client.createCheckoutSession(request("10.00"), "wallet-deposit:" + REFERENCE));
        assertTrue(failure.getMessage().contains(Integer.toString(status)));
        assertFalse(failure.getMessage().contains("do-not-retain"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void creationTimeoutIsNotAutomaticallyRetried() {
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andRespond(withException(new SocketTimeoutException("do-not-retain")));
        var failure = assertThrows(IllegalStateException.class,
                () -> client.createCheckoutSession(request("10.00"), "wallet-deposit:" + REFERENCE));
        assertEquals("Stripe session creation outcome is unresolved", failure.getMessage());
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void inquiriesHandleTimeoutsAndNetworkFailuresWithoutLeakingDetails() {
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions/cs_test_example"))
                .andRespond(withException(new SocketTimeoutException("do-not-retain")));
        server.expect(requestTo("https://api.stripe.com/v1/payment_intents/pi_example"))
                .andRespond(withException(new IOException("do-not-retain")));
        var sessionFailure = assertThrows(IllegalStateException.class, () -> client.getCheckoutSession("cs_test_example"));
        var intentFailure = assertThrows(IllegalStateException.class, () -> client.getPaymentIntent("pi_example"));
        assertNull(sessionFailure.getCause());
        assertNull(intentFailure.getCause());
        assertFalse(sessionFailure.getMessage().contains("do-not-retain"));
        assertFalse(intentFailure.getMessage().contains("do-not-retain"));
        server.verify();
    }

    @Test
    void stripeConfigurationLoadsWithoutMyFatoorahCredentials() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("application",
                    PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "stripe.enabled", "true", "myfatoorah.enabled", "false", "STRIPE_SECRET_KEY", "sk_test_unit")));
            context.registerBean(RestClient.Builder.class, () -> RestClient.builder());
            context.register(StripeClient.class, MyFatoorahClient.class);
            context.refresh();
            assertTrue(context.getBeansOfType(MyFatoorahClient.class).isEmpty());
            StripeClient configured = context.getBean(StripeClient.class);
            assertEquals(SUCCESS_URL, ReflectionTestUtils.getField(configured, "successUrl"));
            assertEquals(CANCEL_URL, ReflectionTestUtils.getField(configured, "cancelUrl"));
        }
    }

    @Test
    void savedRequestKeepsOriginalFormAndApiVersionAfterConfigurationChanges() {
        Map<String, Object> saved = client.prepareCheckoutSession(request("10.00"));
        client = new StripeClient(testBuilder, "sk_test_unit", "2027-01-01.future", 1000, 2000,
                "https://example.com/new-success", "https://example.com/new-cancel");
        expectCreation("1000", SUCCESS_URL, CANCEL_URL, session("open", "unpaid"));
        assertEquals("cs_test_example", client.createCheckoutSession(saved, "wallet-deposit:" + REFERENCE).id());
        server.verify();
    }

    @Test
    void savedRequestCannotInjectCredentialsCardDetailsOrDifferentPaymentSettings() {
        Map<String, Object> saved = new java.util.LinkedHashMap<>(client.prepareCheckoutSession(request("10.00")));
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        ((Map<?, ?>) saved.get("parameters")).forEach((name, value) -> parameters.put((String) name, (String) value));
        parameters.put("card[number]", "not-a-real-card");
        saved.put("parameters", parameters);
        assertThrows(IllegalArgumentException.class,
                () -> client.createCheckoutSession(saved, "wallet-deposit:" + REFERENCE));
        server.verify();
    }

    @Test
    void disabledProviderBeansDoNotRequireCredentials() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "stripe.enabled", "false", "myfatoorah.enabled", "false")));
            context.register(StripeClient.class, MyFatoorahClient.class);
            context.refresh();
            assertTrue(context.getBeansOfType(StripeClient.class).isEmpty());
            assertTrue(context.getBeansOfType(MyFatoorahClient.class).isEmpty());
        }
    }

    @Test
    void newRequestsUseDashboardPaymentMethods() {
        Map<String, Object> saved = client.prepareCheckoutSession(request("10.00"));
        Map<?, ?> parameters = (Map<?, ?>) saved.get("parameters");
        assertTrue(parameters.keySet().stream().noneMatch(name -> name.toString().startsWith("payment_method_types")));
        assertEquals("usd", parameters.get("line_items[0][price_data][currency]"));
        assertEquals("1000", parameters.get("line_items[0][price_data][unit_amount]"));
        expectCreation("1000", SUCCESS_URL, CANCEL_URL, session("open", "unpaid"));
        client.createCheckoutSession(saved, "wallet-deposit:" + REFERENCE);
        server.verify();
    }

    @Test
    void oldSnapshotsAreNotChangedOrRetriedWithoutTheirOriginalParameter() {
        Map<String, Object> saved = new java.util.LinkedHashMap<>(client.prepareCheckoutSession(request("10.00")));
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        ((Map<?, ?>) saved.get("parameters")).forEach((name, value) -> parameters.put((String) name, (String) value));
        parameters.put("payment_method_types[0]", "card");
        saved.put("parameters", parameters);
        Map<String, String> original = Map.copyOf(parameters);
        assertThrows(IllegalArgumentException.class,
                () -> client.createCheckoutSession(saved, "wallet-deposit:" + REFERENCE));
        assertEquals(original, saved.get("parameters"));
        assertEquals("2026-10-28.endive", saved.get("apiVersion"));
        server.verify();
    }

    private void expectCreation(String cents, String success, String cancel, String response) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("mode", "payment");
        form.add("line_items[0][price_data][currency]", "usd");
        form.add("line_items[0][price_data][unit_amount]", cents);
        form.add("line_items[0][price_data][product_data][name]", "Demonstration wallet deposit");
        form.add("line_items[0][quantity]", "1");
        form.add("automatic_tax[enabled]", "false");
        form.add("allow_promotion_codes", "false");
        form.add("adaptive_pricing[enabled]", "false");
        form.add("success_url", success);
        form.add("cancel_url", cancel);
        form.add("client_reference_id", REFERENCE.toString());
        form.add("metadata[wallet_reference]", REFERENCE.toString());
        form.add("payment_intent_data[metadata][wallet_reference]", REFERENCE.toString());
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_unit"))
                .andExpect(header("Stripe-Version", "2026-10-28.endive"))
                .andExpect(header("Idempotency-Key", "wallet-deposit:" + REFERENCE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private StripeCheckoutRequest request(String amount) {
        return new StripeCheckoutRequest(new BigDecimal(amount), REFERENCE, null, null);
    }

    private String session(String status, String paymentStatus) {
        return """
                {"id":"cs_test_example","object":"checkout.session","amount_total":1000,"currency":"usd",
                 "mode":"payment","status":"%s","payment_status":"%s","livemode":false,
                 "client_reference_id":"%s","metadata":{"wallet_reference":"%s"},
                 "payment_intent":%s,"url":%s,"expires_at":1794578807,
                 "customer_details":{"name":"do-not-retain"}}
                """.formatted(status, paymentStatus, REFERENCE, REFERENCE,
                paymentStatus.equals("paid") ? "\"pi_example\"" : "null",
                status.equals("open") ? "\"https://checkout.stripe.com/c/pay/example\"" : "null");
    }

    private String intent() {
        return """
                {"id":"pi_example","object":"payment_intent","status":"succeeded","currency":"usd",
                 "amount":1000,"amount_received":1000,"livemode":false,"metadata":{"wallet_reference":"%s"},
                 "client_secret":"do-not-retain","payment_method":{"card":{"number":"do-not-retain"}}}
                """.formatted(REFERENCE);
    }
}
