package com.ga.investmentservice.Config;

import com.ga.investmentservice.Service.HelpDocumentLoader;
import com.ga.investmentservice.Service.HelpRetrievalService;
import com.ga.investmentservice.Service.HelpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

// opt in only with a gemini key from an unbilled free-tier project
@EnabledIfEnvironmentVariable(named = "HELP_GEMINI_LIVE_TEST", matches = "true")
class HelpGeminiLiveTest {
    @Test
    void answersDocumentationUsingGemini() {
        String key = System.getenv("GEMINI_API_KEY");
        assertTrue(key != null && !key.isBlank(), "GEMINI_API_KEY is required");
        String chatModel = System.getenv().getOrDefault("AI_HELP_CHAT_MODEL", "gemini-3.5-flash-lite");
        String embeddingModel = System.getenv().getOrDefault("AI_HELP_EMBEDDING_MODEL", "gemini-embedding-001");
        int timeout = Integer.parseInt(System.getenv().getOrDefault("AI_HELP_TIMEOUT_MS", "10000"));
        String cache = System.getenv().getOrDefault("AI_HELP_CACHE_DIRECTORY",
                Path.of(System.getProperty("java.io.tmpdir"), "investment-help").toString());
        HelpConfiguration config = new HelpConfiguration(key);
        try (var client = config.helpGenAiClient(key, timeout)) {
            var chat = config.helpChatModel(client, chatModel);
            var embeddings = config.helpEmbeddingModel(client, embeddingModel);
            var retrieval = new HelpRetrievalService(new HelpDocumentLoader(), config.helpVectorStore(embeddings),
                    embeddingModel, cache, 3, 0.65);
            retrieval.initialize();
            assertTrue(retrieval.isReady(), "The documentation index must be available");
            HelpService service = new HelpService(retrieval, chat);
            var response = service.answer("How do I deposit virtual funds?");
            assertTrue(response.available(), "The documentation question must receive an available answer");
            assertFalse(response.sources().isEmpty(), "The answer must cite retrieved documentation");
            assertTrue(response.answer().toLowerCase().contains("stripe"), "The answer must explain the sandbox deposit workflow");
            var refusal = service.answer("Should I buy this stock?");
            assertTrue(refusal.answer().contains("cannot recommend investments"));
            assertTrue(refusal.sources().isEmpty());
        }
    }
}
