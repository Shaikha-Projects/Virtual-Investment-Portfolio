package com.ga.investmentservice.Client;

import com.ga.investmentservice.DTO.Request.ExecutePaymentRequest;
import com.ga.investmentservice.DTO.Request.GetPaymentStatusRequest;
import com.ga.investmentservice.DTO.Response.ExecutePaymentResponse;
import com.ga.investmentservice.DTO.Response.GetPaymentStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "myfatoorah.enabled", havingValue = "true", matchIfMissing = true)
public class MyFatoorahClient {
    private final RestClient restClient;

    public MyFatoorahClient(
            RestClient.Builder builder,
            @Value("${myfatoorah.api.token}") String apiToken,
            @Value("${myfatoorah.api.connect-timeout}") int connectTimeout,
            @Value("${myfatoorah.api.read-timeout}") int readTimeout) {

        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("MyFatoorah API token is missing");
        }
        if (connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalArgumentException("MyFatoorah timeouts must be positive");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = builder.baseUrl("https://apitest.myfatoorah.com")
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBearerAuth(apiToken))
                .defaultStatusHandler(status -> status.isError(), (request, response) -> {
                    throw new IllegalStateException("MyFatoorah HTTP request failed (status " + response.getStatusCode().value() + ")");
                })
                .build();
    }

    //create a hosted sandbox invoice, without sending card details
    public ExecutePaymentResponse executePayment(ExecutePaymentRequest request) {
        if (request == null || request.paymentMethodId() == null || request.paymentMethodId() <= 0
                || request.invoiceValue() == null || request.invoiceValue().signum() <= 0
                || !"USD".equals(request.displayCurrencyIso())
                || request.customerReference() == null || request.customerReference().isBlank()
                || !isHttpsUrl(request.callBackUrl()) || !isHttpsUrl(request.errorUrl())) {
            throw new IllegalArgumentException("Invalid MyFatoorah payment request");
        }

        try {
            ExecutePaymentResponse response = restClient.post()
                    .uri("/v2/ExecutePayment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ExecutePaymentResponse.class);

            if (response == null || !Boolean.TRUE.equals(response.isSuccess()) || response.data() == null
                    || response.data().invoiceId() == null || response.data().invoiceId() <= 0
                    || !request.customerReference().equals(response.data().customerReference())
                    || !Boolean.FALSE.equals(response.data().isDirectPayment())
                    || !isHostedPaymentUrl(response.data().paymentUrl())) {
                throw new IllegalStateException("Invalid or unsuccessful MyFatoorah payment response");
            }
            return response;
        } catch (RestClientException e) {
            // keep provider details out of error messages
            // do not retry invoice creation after a timeout
            throw new IllegalStateException("MyFatoorah payment request failed; invoice creation may be unresolved");
        }
    }

    // look up the saved invoice, payment, or customer reference
    public GetPaymentStatusResponse getPaymentStatus(GetPaymentStatusRequest request) {
        if (request == null || request.key() == null || request.key().isBlank() || request.keyType() == null
                || !List.of("InvoiceId", "PaymentId", "CustomerReference").contains(request.keyType())) {
            throw new IllegalArgumentException("Invalid MyFatoorah status request");
        }

        try {
            GetPaymentStatusResponse response = restClient.post()
                    .uri("/v2/GetPaymentStatus")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GetPaymentStatusResponse.class);

            if (response == null || !Boolean.TRUE.equals(response.isSuccess()) || response.data() == null) {
                throw new IllegalStateException("Invalid or unsuccessful MyFatoorah status response");
            }

            GetPaymentStatusResponse.PaymentData data = response.data();
            if (data.invoiceId() == null || data.invoiceId() <= 0
                    || data.customerReference() == null || data.customerReference().isBlank()
                    || data.invoiceValue() == null || data.invoiceValue().signum() <= 0
                    || data.invoiceStatus() == null
                    || !List.of("Paid", "Pending", "Canceled").contains(data.invoiceStatus())
                    || data.displayedAmount().signum() <= 0
                    || data.invoiceTransactions() == null
                    || data.invoiceTransactions().stream().anyMatch(transaction -> transaction == null
                            || transaction.transactionStatus() == null
                            || !List.of("Succss", "Failed", "Canceled", "InProgress", "Authorize")
                                    .contains(transaction.transactionStatus()))) {
                throw new IllegalStateException("Invalid MyFatoorah invoice details");
            }

            if (("InvoiceId".equals(request.keyType()) && !data.invoiceId().toString().equals(request.key()))
                    || ("CustomerReference".equals(request.keyType()) && !data.customerReference().equals(request.key()))
                    || ("PaymentId".equals(request.keyType()) && data.invoiceTransactions().stream()
                            .noneMatch(transaction -> request.key().equals(transaction.paymentId())))) {
                throw new IllegalStateException("MyFatoorah invoice identity does not match the inquiry");
            }

            for (GetPaymentStatusResponse.PaymentTransaction transaction : data.invoiceTransactions()) {
                if ("Succss".equals(transaction.transactionStatus())
                        && (transaction.paymentId() == null || transaction.paymentId().isBlank()
                            || transaction.transactionValue() == null || transaction.transactionValue().signum() <= 0
                            || !("KD".equals(transaction.currency()) || "KWD".equals(transaction.currency())))) {
                    throw new IllegalStateException("Invalid MyFatoorah successful transaction");
                }
            }
            if ("Paid".equals(data.invoiceStatus()) != data.successfulTransaction().isPresent()) {
                throw new IllegalStateException("Inconsistent MyFatoorah payment status");
            }
            return response;
        } catch (RestClientException e) {
            throw new IllegalStateException("MyFatoorah status request failed; payment status is unresolved");
        }
    }

    private boolean isHttpsUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && !"localhost".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isHostedPaymentUrl(String value) {
        if (!isHttpsUrl(value)) {
            return false;
        }
        String host = URI.create(value).getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("myfatoorah.com") || host.endsWith(".myfatoorah.com");
    }
}
