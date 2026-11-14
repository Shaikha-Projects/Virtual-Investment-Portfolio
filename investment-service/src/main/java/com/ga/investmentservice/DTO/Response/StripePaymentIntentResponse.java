package com.ga.investmentservice.DTO.Response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StripePaymentIntentResponse(String id,
                                         String object,
                                         String status,
                                         String currency,
                                         Long amount,
                                         @JsonProperty("amount_received") Long amountReceived,
                                         Boolean livemode,
                                         Map<String, String> metadata){
}
