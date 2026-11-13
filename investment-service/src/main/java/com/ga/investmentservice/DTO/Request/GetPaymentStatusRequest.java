package com.ga.investmentservice.DTO.Request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetPaymentStatusRequest(@JsonProperty("Key") String key,
                                      @JsonProperty("KeyType") String keyType){
}
