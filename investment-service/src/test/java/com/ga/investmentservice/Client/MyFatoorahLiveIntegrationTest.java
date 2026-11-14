package com.ga.investmentservice.Client;

import com.ga.investmentservice.DTO.Request.GetPaymentStatusRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

// enable this test and set its token in the run configuration
@EnabledIfEnvironmentVariable(named = "RUN_MYFATOORAH_LIVE_TEST", matches = "true")
class MyFatoorahLiveIntegrationTest {

    @Test
    void previouslyPaidSandboxInvoiceIsParsedCorrectly() {
        String apiToken = System.getenv("MYFATOORAH_API_TOKEN");
        assertTrue(apiToken != null && !apiToken.isBlank(),
                "MYFATOORAH_API_TOKEN must be configured in the JUnit run environment");

        MyFatoorahClient client = new MyFatoorahClient(RestClient.builder(), apiToken, 3000, 10000);

        // read the existing invoice without starting the application
        var response = client.getPaymentStatus(new GetPaymentStatusRequest("7366628", "InvoiceId"));

        assertEquals(Boolean.TRUE, response.isSuccess());
        assertNotNull(response.data());
        var data = response.data();
        assertEquals(Long.valueOf(7366628), data.invoiceId());
        assertEquals("Paid", data.invoiceStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(data.displayedAmount()),
                "Expected a USD display amount of 10.00");
        assertTrue(data.successfulTransaction().isPresent(),
                "Expected at least one transaction with status Succss");
    }
}
