package com.ga.investmentservice.Client;

import com.ga.investmentservice.DTO.Request.ExecutePaymentRequest;
import com.ga.investmentservice.DTO.Request.GetPaymentStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MyFatoorahClientTest {

    private MockRestServiceServer server;
    private MyFatoorahClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        // keep the mocked http connection for this test
        RestClient.Builder testBuilder = spy(builder);
        doReturn(testBuilder).when(testBuilder).requestFactory(any(ClientHttpRequestFactory.class));
        client = new MyFatoorahClient(testBuilder, "test-token", 1000, 2000);
    }

    @Test
    void createsHostedUsdInvoice() {
        server.expect(requestTo("https://apitest.myfatoorah.com/v2/ExecutePayment"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"PaymentMethodId":2,"InvoiceValue":10.00,"DisplayCurrencyIso":"USD",
                         "CustomerReference":"wallet-reference","CallBackUrl":"https://example.com/return",
                         "ErrorUrl":"https://example.com/error"}
                        """))
                .andRespond(withSuccess(creationResponse(), MediaType.APPLICATION_JSON));

        var response = client.executePayment(paymentRequest());

        assertEquals(7366628L, response.data().invoiceId());
        assertEquals("https://demo.myfatoorah.com/checkout", response.data().paymentUrl());
        assertEquals("wallet-reference", response.data().customerReference());
        server.verify();
    }

    @Test
    void paidInvoiceSelectsSuccessAfterFailedAttemptAndKeepsCurrenciesSeparate() {
        expectStatus(paidResponse());

        var data = client.getPaymentStatus(invoiceRequest()).data();

        assertEquals("Paid", data.invoiceStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(data.displayedAmount()));
        assertEquals(new BigDecimal("3.070"), data.invoiceValue());
        var successful = data.successfulTransaction().orElseThrow();
        assertEquals("000123", successful.paymentId());
        assertEquals("KD", successful.currency());
        assertEquals(new BigDecimal("3.070"), successful.transactionValue());
        assertEquals("KWD", successful.paidCurrency());
        assertEquals(new BigDecimal("3.070"), successful.paidCurrencyValue());
        server.verify();
    }

    @Test
    void parsesGroupedDisplayAndTransactionAmounts() {
        expectStatus(paidResponse().replace("10.000 USD", "1,000.000 USD")
                .replace("\"3.070\"", "\"1,234.000\""));
        var data = client.getPaymentStatus(invoiceRequest()).data();
        assertEquals(new BigDecimal("1000.000"), data.displayedAmount());
        assertEquals(new BigDecimal("1234.000"), data.successfulTransaction().orElseThrow().transactionValue());
        server.verify();
    }

    @Test
    void numericTransactionAmountsUseBigDecimal() {
        expectStatus(paidResponse().replace("\"3.070\"", "3.070"));
        var transaction = client.getPaymentStatus(invoiceRequest()).data().successfulTransaction().orElseThrow();
        assertEquals(0, new BigDecimal("3.070").compareTo(transaction.transactionValue()));
        server.verify();
    }

    @Test
    void pendingInvoiceWithFailedAttemptIsNotSuccessful() {
        expectStatus(paidResponse().replace("\"Paid\"", "\"Pending\"").replace("Succss", "InProgress"));
        var data = client.getPaymentStatus(invoiceRequest()).data();
        assertEquals("Pending", data.invoiceStatus());
        assertTrue(data.successfulTransaction().isEmpty());
        server.verify();
    }

    @Test
    void pendingInvoiceMayHaveNoTransactions() {
        expectStatus("""
                {"IsSuccess":true,"Data":{"InvoiceId":7366628,"CustomerReference":"wallet-reference",
                 "InvoiceStatus":"Pending","InvoiceValue":3.070,"InvoiceDisplayValue":"10.000 USD",
                 "InvoiceTransactions":[]}}
                """);
        assertTrue(client.getPaymentStatus(invoiceRequest()).data().successfulTransaction().isEmpty());
        server.verify();
    }

    @Test
    void canceledInvoiceRemainsUnpaid() {
        expectStatus(paidResponse().replace("\"Paid\"", "\"Canceled\"").replace("Succss", "Canceled"));
        assertEquals("Canceled", client.getPaymentStatus(invoiceRequest()).data().invoiceStatus());
        server.verify();
    }

    @Test
    void paymentIdInquiryCanReferToFailedAttemptOnPaidInvoice() {
        expectStatus(paidResponse());
        var data = client.getPaymentStatus(new GetPaymentStatusRequest("failed-attempt", "PaymentId")).data();
        assertEquals("000123", data.successfulTransaction().orElseThrow().paymentId());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "{\"IsSuccess\":false,\"Message\":\"private-provider-message\"}",
            "{\"IsSuccess\":true,\"Data\":{}}"})
    void rejectsUnexpectedStatusResponsesWithoutExposingResponse(String response) {
        expectStatus(response);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.getPaymentStatus(invoiceRequest()));
        assertFalse(failure.getMessage().contains("private-provider-message"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void rejectsPaidWithoutSuccessfulTransaction() {
        expectStatus(paidResponse().replace("Succss", "Authorize"));
        assertThrows(IllegalStateException.class, () -> client.getPaymentStatus(invoiceRequest()));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invoice", "reference", "payment", "currency", "display", "status", "transaction"})
    void rejectsInvalidInvoiceDetails(String field) {
        String response = switch (field) {
            case "invoice" -> paidResponse().replace("7366628", "7366629");
            case "reference" -> paidResponse().replace("wallet-reference", "");
            case "payment" -> paidResponse().replace("000123", "");
            case "currency" -> paidResponse().replace("\"Currency\":\"KD\"", "\"Currency\":\"USD\"");
            case "display" -> paidResponse().replace("10.000 USD", "10.000 KWD");
            case "status" -> paidResponse().replace("\"Paid\"", "\"Unknown\"");
            default -> paidResponse().replace("Succss", "Unknown");
        };
        expectStatus(response);
        assertThrows(IllegalStateException.class, () -> client.getPaymentStatus(invoiceRequest()));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference", "url", "direct", "missing", "unsuccessful"})
    void rejectsInvalidCreationResponses(String field) {
        String response = switch (field) {
            case "reference" -> creationResponse().replace("wallet-reference", "other-wallet");
            case "url" -> creationResponse().replace("demo.myfatoorah.com", "attacker.example.com");
            case "direct" -> creationResponse().replace("false", "true");
            case "missing" -> "{\"IsSuccess\":true,\"Data\":{}}";
            default -> "{\"IsSuccess\":false,\"Message\":\"private-provider-message\"}";
        };
        server.expect(requestTo("https://apitest.myfatoorah.com/v2/ExecutePayment"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.executePayment(paymentRequest()));
        assertFalse(failure.getMessage().contains("private-provider-message"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void httpErrorsDoNotExposeRawBodies(int status) {
        server.expect(requestTo("https://apitest.myfatoorah.com/v2/GetPaymentStatus"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("private-provider-message"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.getPaymentStatus(invoiceRequest()));
        assertTrue(failure.getMessage().contains(Integer.toString(status)));
        assertFalse(failure.getMessage().contains("private-provider-message"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void networkFailureLeavesStatusUnresolved() {
        server.expect(requestTo("https://apitest.myfatoorah.com/v2/GetPaymentStatus"))
                .andRespond(withException(new IOException("private network details")));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.getPaymentStatus(invoiceRequest()));
        assertTrue(failure.getMessage().contains("unresolved"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void statusTimeoutLeavesPaymentUnresolved() {
        server.expect(requestTo("https://apitest.myfatoorah.com/v2/GetPaymentStatus"))
                .andRespond(withException(new SocketTimeoutException("private network details")));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.getPaymentStatus(invoiceRequest()));
        assertTrue(failure.getMessage().contains("payment status is unresolved"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void creationTimeoutIsNotRetried() {
        server.expect(requestTo("https://apitest.myfatoorah.com/v2/ExecutePayment"))
                .andRespond(withException(new SocketTimeoutException("private network details")));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.executePayment(paymentRequest()));
        assertTrue(failure.getMessage().contains("invoice creation may be unresolved"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void rejectsInvalidRequestsBeforeHttpCall() {
        assertThrows(IllegalArgumentException.class, () -> client.executePayment(null));
        assertThrows(IllegalArgumentException.class, () -> client.getPaymentStatus(null));
        assertThrows(IllegalArgumentException.class,
                () -> client.getPaymentStatus(new GetPaymentStatusRequest("1", null)));
        assertThrows(IllegalStateException.class,
                () -> new MyFatoorahClient(RestClient.builder(), " ", 1000, 2000));
        server.verify();
    }

    private void expectStatus(String response) {
        server.expect(requestTo("https://apitest.myfatoorah.com/v2/GetPaymentStatus"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private ExecutePaymentRequest paymentRequest() {
        return new ExecutePaymentRequest(2, new BigDecimal("10.00"), "USD", "wallet-reference",
                "https://example.com/return", "https://example.com/error");
    }

    private GetPaymentStatusRequest invoiceRequest() {
        return new GetPaymentStatusRequest("7366628", "InvoiceId");
    }

    private String creationResponse() {
        return """
                {"IsSuccess":true,"Data":{"InvoiceId":7366628,"PaymentURL":"https://demo.myfatoorah.com/checkout",
                 "CustomerReference":"wallet-reference","IsDirectPayment":false}}
                """;
    }

    private String paidResponse() {
        return """
                {"IsSuccess":true,"Data":{"InvoiceId":7366628,"CustomerReference":"wallet-reference",
                 "InvoiceStatus":"Paid","InvoiceValue":3.070,"InvoiceDisplayValue":"10.000 USD",
                 "InvoiceTransactions":[
                  {"PaymentId":"failed-attempt","TransactionStatus":"Failed"},
                  {"PaymentId":"000123","TransactionStatus":"Succss","TransationValue":"3.070",
                   "Currency":"KD","PaidCurrency":"KWD","PaidCurrencyValue":"3.070",
                   "CardNumber":"do-not-retain","Card":{"Number":"do-not-retain"}}]}}
                """;
    }
}
