package com.ga.investmentservice.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
@Slf4j
@ConditionalOnProperty(name = "ai.help.enabled", havingValue = "true")
public class HelpRetrievalService {
    private final HelpDocumentLoader loader;
    private final SimpleVectorStore vectorStore;
    private final String embeddingModel;
    private final Path cacheDirectory;
    private final int topK;
    private final double threshold;
    private boolean ready;

    public HelpRetrievalService(HelpDocumentLoader loader, SimpleVectorStore vectorStore,
                                @Value("${ai.help.embedding-model}") String embeddingModel,
                                @Value("${ai.help.cache-directory}") String cacheDirectory,
                                @Value("${ai.help.retrieval.top-k}") int topK,
                                @Value("${ai.help.retrieval.min-score}") double threshold) {
        this.loader = loader;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.cacheDirectory = Path.of(cacheDirectory);
        this.topK = topK;
        this.threshold = threshold;
        if (topK < 1 || topK > 10 || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Invalid help retrieval settings");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void initialize() {
        if (ready) return;
        List<Document> documents = List.of();
        try {
            documents = loader.load();
            if (documents.isEmpty()) throw new IllegalStateException("Help documentation is empty");
            String fingerprint = HelpDocumentLoader.hash("help-index-v2-google-genai-spring-ai-2.0.2\n" + embeddingModel
                    + documents.stream().map(document -> document.getId() + "\n" + document.getText()).toList());
            Path cache = cacheDirectory.resolve(fingerprint + ".json");
            if (!loadCache(cache, documents)) {
                vectorStore.add(documents);
                saveCache(cache);
            }
            ready = true;
        } catch (Exception e) {
            // do not serve a partially built index
            vectorStore.doDelete(documents.stream().map(Document::getId).toList());
            log.warn("AI Help indexing is unavailable; other application features remain available");
        }
    }

    public synchronized List<Document> retrieve(String question) {
        if (!ready || question == null || question.isBlank() || question.length() > 2000) return List.of();
        try {
            return vectorStore.similaritySearch(SearchRequest.builder().query(question.strip())
                    .topK(topK).similarityThreshold(threshold).build());
        } catch (Exception e) {
            log.warn("AI Help retrieval is temporarily unavailable");
            throw new IllegalStateException("AI Help retrieval is temporarily unavailable");
        }
    }

    public synchronized boolean isReady() {
        return ready;
    }

    private boolean loadCache(Path cache, List<Document> documents) {
        try {
            if (!Files.isRegularFile(cache) || Files.size(cache) > 16 * 1024 * 1024) return false;
            JsonMapper mapper = JsonMapper.builder().build();
            var saved = mapper.readTree(cache.toFile());
            if (!saved.isObject() || saved.size() != documents.size()) return false;
            for (Document document : documents) {
                var section = saved.path(document.getId());
                if (!document.getId().equals(section.path("id").asText())
                        || !document.getText().equals(section.path("text").asText())
                        || !mapper.valueToTree(document.getMetadata()).equals(section.path("metadata"))
                        || !section.path("embedding").isArray() || section.path("embedding").isEmpty()) return false;
            }
            vectorStore.load(cache.toFile());
            return true;
        } catch (Exception e) {
            log.warn("AI Help cache could not be loaded; rebuilding the approved guide index");
            return false;
        }
    }

    private void saveCache(Path cache) {
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            temporary = Files.createTempFile(cacheDirectory, "help-", ".json");
            vectorStore.save(temporary.toFile());
            Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // keep the in-memory index if the cache cannot be saved
            log.warn("AI Help cache could not be saved; the current index remains available");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            }
        }
    }
}
