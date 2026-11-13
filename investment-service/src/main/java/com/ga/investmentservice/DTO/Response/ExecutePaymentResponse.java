package com.ga.investmentservice.DTO.Response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutePaymentResponse(@JsonProperty("IsSuccess") Boolean isSuccess,
                                     @JsonProperty("Data") PaymentData data){

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentData(@JsonProperty("InvoiceId") Long invoiceId,
                              @JsonProperty("PaymentURL") String paymentUrl,
                              @JsonProperty("CustomerReference") String customerReference,
                              @JsonProperty("IsDirectPayment") Boolean isDirectPayment){
    }
}
