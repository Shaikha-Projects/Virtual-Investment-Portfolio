package com.ga.investmentservice.Config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import com.google.genai.Client;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import com.ga.investmentservice.Service.HelpDocumentLoader;
import com.ga.investmentservice.Service.HelpRetrievalService;
import com.ga.investmentservice.Service.HelpService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class HelpConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HelpConfiguration.class, HelpDocumentLoader.class, HelpRetrievalService.class, HelpService.class)
            .withPropertyValues("ai.help.cache-directory=${java.io.tmpdir}/investment-help-test",
                    "ai.help.retrieval.top-k=3", "ai.help.retrieval.min-score=0.65");

    @Test
    void missingFlagDoesNotCreateAiBeansOrRequireAKey() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatModel.class);
            assertThat(context).doesNotHaveBean(Client.class);
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
            assertThat(context).doesNotHaveBean(VectorStore.class);
            assertThat(context).doesNotHaveBean(HelpDocumentLoader.class);
            assertThat(context).doesNotHaveBean(HelpRetrievalService.class);
            assertThat(context).doesNotHaveBean(HelpService.class);
        });
    }

    @Test
    void disabledHelpDoesNotCreateAiBeansWithAnEmptyKey() {
        contextRunner.withPropertyValues("ai.help.enabled=false", "ai.help.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ChatModel.class);
                    assertThat(context).doesNotHaveBean(Client.class);
                    assertThat(context).doesNotHaveBean(EmbeddingModel.class);
                    assertThat(context).doesNotHaveBean(VectorStore.class);
                    assertThat(context).doesNotHaveBean(HelpDocumentLoader.class);
                    assertThat(context).doesNotHaveBean(HelpRetrievalService.class);
                    assertThat(context).doesNotHaveBean(HelpService.class);
                });
    }

    @Test
    void enabledHelpCreatesEmptyStoreAndUsesConfiguredModelsWithoutCallingAi() {
        contextRunner.withPropertyValues("ai.help.enabled=true", "ai.help.api-key=unit-test-key",
                        "ai.help.chat-model=test-chat", "ai.help.embedding-model=test-embedding",
                        "ai.help.timeout-ms=2000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context).hasSingleBean(VectorStore.class);
                    assertThat(context).hasSingleBean(Client.class);
                    assertThat(context.getBean(Client.class).vertexAI()).isFalse();
                    assertThat(context.getBean(GoogleGenAiChatModel.class).getOptions().getModel()).isEqualTo("test-chat");
                    assertThat(context.getBean(GoogleGenAiTextEmbeddingModel.class).options.getModel()).isEqualTo("test-embedding");
                });
    }

    @Test
    void enabledHelpRequiresAKey() {
        contextRunner.withPropertyValues("ai.help.enabled=true", "ai.help.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Gemini API key is required when AI Help is enabled");
                });
    }
}
