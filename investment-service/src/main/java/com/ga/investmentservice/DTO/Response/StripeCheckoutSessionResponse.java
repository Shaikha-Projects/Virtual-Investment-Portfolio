package com.ga.investmentservice.DTO.Response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StripeCheckoutSessionResponse(String id,
                                           String object,
                                           @JsonProperty("amount_total") Long amountTotal,
                                           String currency,
                                           String mode,
                                           String status,
                                           @JsonProperty("payment_status") String paymentStatus,
                                           Boolean livemode,
                                           @JsonProperty("client_reference_id") String clientReferenceId,
                                           Map<String, String> metadata,
                                           @JsonProperty("payment_intent") String paymentIntent,
                                           String url,
                                           @JsonProperty("expires_at") Long expiresAt){
}
