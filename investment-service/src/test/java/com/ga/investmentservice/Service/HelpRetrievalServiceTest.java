package com.ga.investmentservice.Service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HelpRetrievalServiceTest {
    @TempDir
    Path cache;
    private EmbeddingModel embeddings;
    private HelpDocumentLoader loader;
    private SimpleVectorStore store;
    private HelpRetrievalService service;

    @BeforeEach
    void setup() {
        embeddings = mock(EmbeddingModel.class);
        when(embeddings.dimensions()).thenReturn(3);
        when(embeddings.embed(any(Document.class))).thenAnswer(call -> {
            Document document = call.getArgument(0);
            return document.getMetadata().get("title").equals("How do I deposit virtual USD?")
                    ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
        });
        when(embeddings.embed("How can I deposit virtual USD?")).thenReturn(new float[]{1, 0, 0});
        when(embeddings.embed("How do I bake bread?")).thenReturn(new float[]{0, 0, 1});
        loader = new HelpDocumentLoader();
        store = SimpleVectorStore.builder(embeddings).build();
        service = new HelpRetrievalService(loader, store, "test-embedding", cache.toString(), 3, 0.65);
    }

    @Test
    void splitsHeadingsWithoutMixingSiblingSectionsAndKeepsParentContext() {
        List<Document> documents = loader.split("# Guide\n\n## Wallet\nIntro.\n### Deposit\nAdd funds.\n### Withdraw\nTake funds out.\n");
        assertEquals(3, documents.size());
        assertEquals("Wallet", documents.get(0).getMetadata().get("title"));
        assertEquals("Guide > Wallet > Deposit", documents.get(1).getMetadata().get("sectionPath"));
        assertTrue(documents.get(1).getText().contains("Add funds."));
        assertFalse(documents.get(1).getText().contains("Take funds out."));
    }

    @Test
    void bundledGuideHasStableUniqueSourceIdsAndAccurateTitles() throws Exception {
        List<Document> first = loader.load();
        List<Document> second = loader.load();
        assertFalse(first.isEmpty());
        assertEquals(first.stream().map(Document::getId).toList(), second.stream().map(Document::getId).toList());
        assertEquals(first.size(), first.stream().map(Document::getId).distinct().count());
        for (Document document : first) {
            assertEquals("help/user-guide.md", document.getMetadata().get("source"));
            assertEquals(document.getId(), document.getMetadata().get("sourceId"));
            assertFalse(document.getMetadata().get("title").toString().isBlank());
        }
        Document startingBalance = first.stream().filter(document ->
                document.getMetadata().get("title").equals("What is my starting balance?")).findFirst().orElseThrow();
        assertTrue(startingBalance.getText().contains("**$0 in virtual cash**"));
        assertEquals(loader.split("# Guide\n## Wallet\nOld text.").get(0).getId(),
                loader.split("# Guide\n## Wallet\nUpdated text.").get(0).getId());
    }

    @Test
    void retrievesRelevantSectionWithTextTitleSourceAndScore() {
        service.initialize();
        List<Document> result = service.retrieve("How can I deposit virtual USD?");
        assertTrue(service.isReady());
        assertEquals(1, result.size());
        assertEquals("How do I deposit virtual USD?", result.get(0).getMetadata().get("title"));
        assertTrue(result.get(0).getText().contains("Continue to Stripe"));
        assertEquals(1.0, result.get(0).getScore(), 0.00001);
        assertEquals("help/user-guide.md", result.get(0).getMetadata().get("source"));
    }

    @Test
    void configuredLimitAndThresholdAreAppliedToRealSimilaritySearch() {
        when(embeddings.embed("Browse the guide")).thenReturn(new float[]{0, 1, 0});
        var limited = new HelpRetrievalService(loader, store, "test-embedding", cache.toString(), 1, 0.65);
        limited.initialize();
        assertEquals(1, limited.retrieve("Browse the guide").size());
        when(embeddings.embed("A less relevant question")).thenReturn(new float[]{0.8f, 0.6f, 0});
        var strict = new HelpRetrievalService(loader, store, "test-embedding", cache.toString(), 3, 0.9);
        strict.initialize();
        assertTrue(strict.retrieve("A less relevant question").isEmpty());
    }

    @Test
    void irrelevantAndInvalidQuestionsReturnNoSections() {
        service.initialize();
        assertTrue(service.retrieve("How do I bake bread?").isEmpty());
        clearInvocations(embeddings);
        assertTrue(service.retrieve(null).isEmpty());
        assertTrue(service.retrieve(" ").isEmpty());
        assertTrue(service.retrieve("x".repeat(2001)).isEmpty());
        verifyNoInteractions(embeddings);
    }

    @Test
    void repeatedInitializationAndRestartReuseTheIndexWithoutEmbeddingDocumentsAgain() throws Exception {
        service.initialize();
        int count = loader.load().size();
        verify(embeddings, times(count)).embed(any(Document.class));
        service.initialize();
        verify(embeddings, times(count)).embed(any(Document.class));
        clearInvocations(embeddings);
        var restarted = new HelpRetrievalService(loader, SimpleVectorStore.builder(embeddings).build(),
                "test-embedding", cache.toString(), 3, 0.65);
        restarted.initialize();
        assertTrue(restarted.isReady());
        verifyNoInteractions(embeddings);
        assertEquals(1, restarted.retrieve("How can I deposit virtual USD?").size());
    }

    @Test
    void previousOpenAiCacheIsNotReusedEvenWhenTheModelSettingMatches() throws Exception {
        List<Document> documents = loader.load();
        String oldFingerprint = HelpDocumentLoader.hash("help-index-v1-spring-ai-2.0.2\n" + "test-embedding"
                + documents.stream().map(document -> document.getId() + "\n" + document.getText()).toList());
        store.add(documents);
        Path oldCache = cache.resolve(oldFingerprint + ".json");
        store.save(oldCache.toFile());
        clearInvocations(embeddings);
        var migrated = new HelpRetrievalService(loader, SimpleVectorStore.builder(embeddings).build(),
                "test-embedding", cache.toString(), 3, 0.65);
        migrated.initialize();
        assertTrue(migrated.isReady());
        verify(embeddings, times(documents.size())).embed(any(Document.class));
        assertTrue(Files.exists(oldCache));
        try (var files = Files.list(cache)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void changedEmbeddingModelRebuildsTheCache() throws Exception {
        service.initialize();
        clearInvocations(embeddings);
        var changed = new HelpRetrievalService(loader, SimpleVectorStore.builder(embeddings).build(),
                "different-model", cache.toString(), 3, 0.65);
        changed.initialize();
        verify(embeddings, times(loader.load().size())).embed(any(Document.class));
    }

    @Test
    void changedGuideContentRebuildsVectorsEvenWhenSectionIdsAreUnchanged() throws Exception {
        service.initialize();
        List<Document> original = loader.load();
        Document first = original.get(0);
        var changedDocuments = new java.util.ArrayList<>(original);
        changedDocuments.set(0, Document.builder().id(first.getId()).text(first.getText() + "\nUpdated guide.")
                .metadata(first.getMetadata()).build());
        HelpDocumentLoader changedLoader = mock(HelpDocumentLoader.class);
        when(changedLoader.load()).thenReturn(changedDocuments);
        clearInvocations(embeddings);
        var changed = new HelpRetrievalService(changedLoader, SimpleVectorStore.builder(embeddings).build(),
                "test-embedding", cache.toString(), 3, 0.65);
        changed.initialize();
        assertTrue(changed.isReady());
        verify(embeddings, times(original.size())).embed(any(Document.class));
    }

    @Test
    void corruptCacheIsRebuiltFromTheBundledGuide() throws Exception {
        service.initialize();
        try (var files = Files.list(cache)) {
            Files.writeString(files.findFirst().orElseThrow(), "{}");
        }
        clearInvocations(embeddings);
        var restarted = new HelpRetrievalService(loader, SimpleVectorStore.builder(embeddings).build(),
                "test-embedding", cache.toString(), 3, 0.65);
        restarted.initialize();
        assertTrue(restarted.isReady());
        verify(embeddings, times(loader.load().size())).embed(any(Document.class));
    }

    @Test
    void partialIndexingFailureIsContainedAndCanBeRetried() {
        doReturn(new float[]{0, 1, 0}).doThrow(new IllegalStateException("provider unavailable"))
                .when(embeddings).embed(any(Document.class));
        assertDoesNotThrow(service::initialize);
        assertFalse(service.isReady());
        clearInvocations(embeddings);
        assertTrue(service.retrieve("How can I deposit virtual USD?").isEmpty());
        verifyNoInteractions(embeddings);
        doReturn(new float[]{1, 0, 0}).when(embeddings).embed(any(Document.class));
        service.initialize();
        assertTrue(service.isReady());
        assertEquals(3, service.retrieve("How can I deposit virtual USD?").size());
    }

    @Test
    void cacheWriteFailureDoesNotDisableTheInMemoryIndex() throws Exception {
        Path file = Files.createFile(cache.resolve("not-a-directory"));
        var uncached = new HelpRetrievalService(loader, store, "test-embedding", file.toString(), 3, 0.65);
        assertDoesNotThrow(uncached::initialize);
        assertTrue(uncached.isReady());
        assertEquals(1, uncached.retrieve("How can I deposit virtual USD?").size());
    }

    @Test
    void queryEmbeddingFailureIsContained() {
        service.initialize();
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.retrieve("How can I deposit virtual USD?"));
        assertEquals("AI Help retrieval is temporarily unavailable", error.getMessage());
    }

    @Test
    void geminiEmbeddingQuotaFailureDoesNotEscapeStartupOrServeAPartialIndex() {
        doThrow(new com.google.genai.errors.ClientException(429, "RESOURCE_EXHAUSTED", "quota exhausted"))
                .when(embeddings).embed(any(Document.class));
        assertDoesNotThrow(service::initialize);
        assertFalse(service.isReady());
        assertTrue(service.retrieve("How can I deposit virtual USD?").isEmpty());
    }
}
