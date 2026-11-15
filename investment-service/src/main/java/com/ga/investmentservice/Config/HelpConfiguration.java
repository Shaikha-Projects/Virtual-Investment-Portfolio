package com.ga.investmentservice.Config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

@Configuration
@ConditionalOnProperty(name = "ai.help.enabled", havingValue = "true")
public class HelpConfiguration {
    public HelpConfiguration(@Value("${ai.help.api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is required when AI Help is enabled");
        }
    }

    @Bean(destroyMethod = "close")
    Client helpGenAiClient(@Value("${ai.help.api-key}") String apiKey,
                           @Value("${ai.help.timeout-ms}") int timeout) {
        return Client.builder().apiKey(apiKey).vertexAI(false)
                .httpOptions(HttpOptions.builder().timeout(timeout)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build())
                .build();
    }

    @Bean
    GoogleGenAiChatModel helpChatModel(Client helpGenAiClient,
                                      @Value("${ai.help.chat-model}") String model) {
        return GoogleGenAiChatModel.builder().genAiClient(helpGenAiClient)
                .options(GoogleGenAiChatOptions.builder().model(model)
                        .responseMimeType("application/json").googleSearchRetrieval(false).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
                .build();
    }

    @Bean
    GoogleGenAiTextEmbeddingModel helpEmbeddingModel(Client helpGenAiClient,
                                                    @Value("${ai.help.embedding-model}") String model) {
        var connection = GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(helpGenAiClient).build();
        return new GoogleGenAiTextEmbeddingModel(connection,
                GoogleGenAiTextEmbeddingOptions.builder().model(model).build(),
                new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()));
    }

    @Bean
    SimpleVectorStore helpVectorStore(GoogleGenAiTextEmbeddingModel helpEmbeddingModel) {
        return SimpleVectorStore.builder(helpEmbeddingModel).build();
    }
}
