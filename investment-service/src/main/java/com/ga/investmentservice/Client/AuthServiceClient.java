package com.ga.investmentservice.Client;

import com.ga.investmentservice.DTO.Response.UserIdentityResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class AuthServiceClient {
    private final RestClient restClient;

    public AuthServiceClient(
            RestClient.Builder builder,
            @Value("${auth.service.url}") String authUrl,
            @Value("${internal.service.key}") String serviceKey,
            @Value("${auth.service.connect-timeout}") int connectTimeout,
            @Value("${auth.service.read-timeout}") int readTimeout) {

        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("Internal service key is missing");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(readTimeout);

        this.restClient = builder.baseUrl(authUrl).requestFactory(requestFactory).defaultHeader("X-Internal-Service-Key", serviceKey).build();
    }

    //find user by ID
    public UserIdentityResponse getUserById(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Invalid user ID");
        }

        try {
            UserIdentityResponse user = restClient.get()
                    .uri("/internal/users/{userId}", userId)
                    .retrieve()
                    .body(UserIdentityResponse.class);

            return validateUser(user);

        } catch (RestClientException e) {
            throw new IllegalStateException("Authentication service request failed", e);
        }
    }

    //find user by email
    public UserIdentityResponse getUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Invalid email address");
        }

        try {
            UserIdentityResponse user = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/users/by-email")
                            .queryParam("emailAddress", email)
                            .build())
                    .retrieve()
                    .body(UserIdentityResponse.class);

            return validateUser(user);

        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "Authentication service request failed", e);
        }
    }

    private UserIdentityResponse validateUser(UserIdentityResponse user) {
        if (user == null
                || user.userId() == null
                || user.userId() <= 0
                || user.emailAddress() == null
                || user.emailAddress().isBlank()
                || user.role() == null
                || user.status() == null) {

            throw new IllegalStateException("Invalid response from Authentication service");
        }

        return user;
    }
}
