package com.ga.investmentservice.Client;

import com.ga.investmentservice.DTO.Request.StripeCheckoutRequest;
import com.ga.investmentservice.DTO.Response.StripeCheckoutSessionResponse;
import com.ga.investmentservice.DTO.Response.StripePaymentIntentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "stripe.enabled", havingValue = "true")
public class StripeClient {
    private final RestClient restClient;
    private final String successUrl;
    private final String cancelUrl;
    private final String apiVersion;

    public StripeClient(
            RestClient.Builder builder,
            @Value("${stripe.secret-key}") String secretKey,
            @Value("${stripe.api-version}") String apiVersion,
            @Value("${stripe.connect-timeout}") int connectTimeout,
            @Value("${stripe.read-timeout}") int readTimeout,
            @Value("${stripe.success-url}") String successUrl,
            @Value("${stripe.cancel-url}") String cancelUrl) {

        if (secretKey == null || !(secretKey.startsWith("sk_test_") || secretKey.startsWith("rk_test_"))
                || secretKey.contains("\r") || secretKey.contains("\n")) {
            throw new IllegalStateException("A Stripe sandbox secret key is required");
        }
        if (apiVersion == null || apiVersion.isBlank() || apiVersion.contains("\r") || apiVersion.contains("\n")
                || connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalArgumentException("Invalid Stripe client configuration");
        }
        this.successUrl = successUrl;
        this.cancelUrl = cancelUrl;
        this.apiVersion = apiVersion;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = builder.baseUrl("https://api.stripe.com")
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBearerAuth(secretKey))
                .defaultHeader("Stripe-Version", apiVersion)
                .defaultStatusHandler(status -> status.isError(), (request, response) -> {
                    throw new IllegalStateException("Stripe HTTP request failed (status " + response.getStatusCode().value() + ")");
                })
                .build();
    }

    // create checkout using the same request key for retries
    public StripeCheckoutSessionResponse createCheckoutSession(StripeCheckoutRequest request, String idempotencyKey) {
        return createCheckoutSession(prepareCheckoutSession(request), idempotencyKey);
    }

    // save the exact request before calling stripe
    public Map<String, Object> prepareCheckoutSession(StripeCheckoutRequest request) {
        if (request == null || request.walletReference() == null || request.amount() == null
                || request.amount().signum() <= 0 || request.amount().scale() > 2) {
            throw new IllegalArgumentException("Invalid Stripe Checkout request");
        }

        long amountInCents;
        try {
            amountInCents = request.amount().movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Stripe amount cannot be represented as integer cents");
        }
        // keep the original return urls for recovery
        String returnSuccessUrl = request.successUrl() == null ? successUrl : request.successUrl();
        String returnCancelUrl = request.cancelUrl() == null ? cancelUrl : request.cancelUrl();
        if (!isReturnUrl(returnSuccessUrl) || !isReturnUrl(returnCancelUrl)) {
            throw new IllegalArgumentException("Invalid Stripe return URLs");
        }

        String reference = request.walletReference().toString();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("mode", "payment");
        form.add("line_items[0][price_data][currency]", "usd");
        form.add("line_items[0][price_data][unit_amount]", Long.toString(amountInCents));
        form.add("line_items[0][price_data][product_data][name]", "Demonstration wallet deposit");
        form.add("line_items[0][quantity]", "1");
        form.add("automatic_tax[enabled]", "false");
        form.add("allow_promotion_codes", "false");
        form.add("adaptive_pricing[enabled]", "false");
        form.add("success_url", returnSuccessUrl);
        form.add("cancel_url", returnCancelUrl);
        form.add("client_reference_id", reference);
        form.add("metadata[wallet_reference]", reference);
        form.add("payment_intent_data[metadata][wallet_reference]", reference);

        return Map.of("apiVersion", apiVersion, "parameters", Map.copyOf(form.toSingleValueMap()));
    }

    // retry only with the saved request
    public StripeCheckoutSessionResponse createCheckoutSession(Map<String, Object> savedRequest, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255
                || idempotencyKey.contains("\r") || idempotencyKey.contains("\n")) {
            throw new IllegalArgumentException("Invalid Stripe idempotency key");
        }
        Map<String, String> parameters = checkoutParameters(savedRequest);
        var form = new LinkedMultiValueMap<String, String>();
        parameters.forEach(form::add);
        long amountInCents = Long.parseLong(parameters.get("line_items[0][price_data][unit_amount]"));
        String reference = parameters.get("client_reference_id");

        try {
            StripeCheckoutSessionResponse response = restClient.post()
                    .uri("/v1/checkout/sessions")
                    .header("Idempotency-Key", idempotencyKey)
                    .header("Stripe-Version", (String) savedRequest.get("apiVersion"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(StripeCheckoutSessionResponse.class);

            validateSession(response);
            if (response.amountTotal() != amountInCents || !reference.equals(response.clientReferenceId())
                    || response.metadata() == null || !reference.equals(response.metadata().get("wallet_reference"))) {
                throw new IllegalStateException("Stripe Checkout response does not match the request");
            }
            return response;
        } catch (RestClientException e) {
            // keep provider responses out of error messages
            // recovery must use the same key and request
            throw new IllegalStateException("Stripe session creation outcome is unresolved");
        }
    }

    private Map<String, String> checkoutParameters(Map<String, Object> savedRequest) {
        if (savedRequest == null || !(savedRequest.get("apiVersion") instanceof String version)
                || !version.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:\\.[A-Za-z]+)?")
                || !(savedRequest.get("parameters") instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Invalid saved Stripe Checkout request");
        }
        Set<String> expected = Set.of("mode", "line_items[0][price_data][currency]",
                "line_items[0][price_data][unit_amount]", "line_items[0][price_data][product_data][name]",
                "line_items[0][quantity]", "automatic_tax[enabled]", "allow_promotion_codes",
                "adaptive_pricing[enabled]", "success_url", "cancel_url", "client_reference_id",
                "metadata[wallet_reference]", "payment_intent_data[metadata][wallet_reference]");
        if (!values.keySet().equals(expected)) {
            //reject old snapshots instead of changing their saved parameters
            throw new IllegalArgumentException("Invalid saved Stripe Checkout parameters");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String name : expected) {
            if (!(values.get(name) instanceof String value)) {
                throw new IllegalArgumentException("Invalid saved Stripe Checkout parameter value");
            }
            parameters.put(name, value);
        }
        String reference = parameters.get("client_reference_id");
        try {
            UUID.fromString(reference);
            if (Long.parseLong(parameters.get("line_items[0][price_data][unit_amount]")) <= 0) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid saved Stripe amount or reference");
        }
        if (!"payment".equals(parameters.get("mode"))
                || !"usd".equals(parameters.get("line_items[0][price_data][currency]"))
                || !"1".equals(parameters.get("line_items[0][quantity]"))
                || !"false".equals(parameters.get("automatic_tax[enabled]"))
                || !"false".equals(parameters.get("allow_promotion_codes"))
                || !"false".equals(parameters.get("adaptive_pricing[enabled]"))
                || !reference.equals(parameters.get("metadata[wallet_reference]"))
                || !reference.equals(parameters.get("payment_intent_data[metadata][wallet_reference]"))
                || !isReturnUrl(parameters.get("success_url")) || !isReturnUrl(parameters.get("cancel_url"))) {
            throw new IllegalArgumentException("Saved Stripe parameters must describe a fixed USD wallet deposit");
        }
        return parameters;
    }

    public StripeCheckoutSessionResponse getCheckoutSession(String sessionId) {
        if (sessionId == null || !sessionId.matches("cs_test_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Invalid Stripe sandbox Session ID");
        }
        try {
            StripeCheckoutSessionResponse response = restClient.get()
                    .uri("/v1/checkout/sessions/{id}", sessionId)
                    .retrieve()
                    .body(StripeCheckoutSessionResponse.class);
            validateSession(response);
            if (!sessionId.equals(response.id())) {
                throw new IllegalStateException("Stripe Session ID does not match the inquiry");
            }
            return response;
        } catch (RestClientException e) {
            throw new IllegalStateException("Stripe Session inquiry failed; payment status is unresolved");
        }
    }

    public StripePaymentIntentResponse getPaymentIntent(String paymentIntentId) {
        if (paymentIntentId == null || !paymentIntentId.matches("pi_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Invalid Stripe PaymentIntent ID");
        }
        try {
            StripePaymentIntentResponse response = restClient.get()
                    .uri("/v1/payment_intents/{id}", paymentIntentId)
                    .retrieve()
                    .body(StripePaymentIntentResponse.class);
            if (response == null || !paymentIntentId.equals(response.id()) || !"payment_intent".equals(response.object())
                    || !Boolean.FALSE.equals(response.livemode()) || !"usd".equals(response.currency())
                    || response.amount() == null || response.amount() <= 0
                    || response.amountReceived() == null || response.amountReceived() < 0
                    || response.amountReceived() > response.amount() || response.status() == null
                    || !List.of("requires_payment_method", "requires_confirmation", "requires_action", "processing",
                            "requires_capture", "canceled", "succeeded").contains(response.status())
                    || ("succeeded".equals(response.status()) && !response.amount().equals(response.amountReceived()))) {
                throw new IllegalStateException("Invalid Stripe PaymentIntent response");
            }
            return response;
        } catch (RestClientException e) {
            throw new IllegalStateException("Stripe PaymentIntent inquiry failed; payment status is unresolved");
        }
    }

    private void validateSession(StripeCheckoutSessionResponse response) {
        if (response == null || response.id() == null || !response.id().matches("cs_test_[A-Za-z0-9]+")
                || !"checkout.session".equals(response.object()) || !Boolean.FALSE.equals(response.livemode())
                || !"usd".equals(response.currency()) || !"payment".equals(response.mode())
                || response.amountTotal() == null || response.amountTotal() <= 0
                || response.status() == null || !List.of("open", "complete", "expired").contains(response.status())
                || response.paymentStatus() == null || !List.of("paid", "unpaid").contains(response.paymentStatus())
                || (response.paymentIntent() != null && !response.paymentIntent().matches("pi_[A-Za-z0-9]+"))
                || ("paid".equals(response.paymentStatus())
                    && (!"complete".equals(response.status()) || response.paymentIntent() == null))
                || ("open".equals(response.status()) && !isCheckoutUrl(response.url()))
                || (response.url() != null && !isCheckoutUrl(response.url()))) {
            throw new IllegalStateException("Invalid Stripe Checkout Session response");
        }
    }

    private boolean isReturnUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value.replace("{CHECKOUT_SESSION_ID}", "session"));
            String host = uri.getHost();
            return host != null && uri.getUserInfo() == null
                    && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                        && List.of("localhost", "127.0.0.1", "[::1]").contains(host)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isCheckoutUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "checkout.stripe.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
