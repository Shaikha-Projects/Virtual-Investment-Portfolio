package com.ga.investmentservice.Config;

import com.ga.investmentservice.Service.HelpDocumentLoader;
import com.ga.investmentservice.Service.HelpRetrievalService;
import com.ga.investmentservice.Service.HelpService;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HelpGeminiHttpTest {
    @TempDir
    Path cache;
    private HttpServer server;
    private Client client;
    private final HelpConfiguration config = new HelpConfiguration("unit-test-key");
    private final AtomicInteger requests = new AtomicInteger();
    private String requestBody;
    private int status = 200;
    private String responseBody;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        client = Client.builder().apiKey("unit-test-key").vertexAI(false)
                .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .timeout(2000).retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
    }

    @AfterEach
    void close() {
        client.close();
        server.stop(0);
    }

    private HelpService service() {
        var embeddings = mock(GoogleGenAiTextEmbeddingModel.class);
        when(embeddings.dimensions()).thenReturn(3);
        when(embeddings.embed(any(Document.class))).thenAnswer(call -> {
            Document document = call.getArgument(0);
            return document.getMetadata().get("title").equals("How do I deposit virtual USD?")
                    ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
        });
        when(embeddings.embed(anyString())).thenReturn(new float[]{1, 0, 0});
        var retrieval = new HelpRetrievalService(new HelpDocumentLoader(), config.helpVectorStore(embeddings),
                "test-embedding", cache.toString(), 3, 0.65);
        retrieval.initialize();
        return new HelpService(retrieval, config.helpChatModel(client, "gemini-3.5-flash-lite"));
    }

    @Test
    void realGeminiAdapterParsesTheResponseAndReturnsRetrievedSources() throws Exception {
        HelpService service = service();
        Document deposit = new HelpDocumentLoader().load().stream().filter(document ->
                document.getMetadata().get("title").equals("How do I deposit virtual USD?")).findFirst().orElseThrow();
        String answer = JsonMapper.builder().build().writeValueAsString(Map.of(
                "answer", "Open Cash Wallet and choose Continue to Stripe. Complete the sandbox checkout and wait for verification.",
                "sourceIds", java.util.List.of(deposit.getId())));
        responseBody = JsonMapper.builder().build().writeValueAsString(Map.of("candidates", java.util.List.of(Map.of(
                "content", Map.of("role", "model", "parts", java.util.List.of(Map.of("text", answer))),
                "finishReason", "STOP")), "modelVersion", "gemini-3.5-flash-lite"));
        var result = service.answer("How do I deposit virtual funds?");
        assertTrue(result.available());
        assertTrue(result.answer().contains("Stripe"));
        assertEquals(deposit.getId(), result.sources().get(0).sourceId());
        assertEquals(1, requests.get());
        var request = JsonMapper.builder().build().readTree(requestBody);
        assertEquals("application/json", request.path("generationConfig").path("responseMimeType").asText());
        assertFalse(request.has("tools"));
        assertTrue(service.answer("Should I buy this stock?").answer().contains("cannot recommend investments"));
        assertEquals(1, requests.get());
    }

    @Test
    void actualGeminiQuotaResponseIsContainedWithoutRetries() {
        status = 429;
        responseBody = "{\"error\":{\"code\":429,\"message\":\"Quota exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
        var result = service().answer("How do I deposit virtual funds?");
        assertFalse(result.available());
        assertTrue(result.sources().isEmpty());
        assertEquals(1, requests.get());
    }
}
