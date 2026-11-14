package com.ga.investmentservice.DTO.Request;

import java.math.BigDecimal;
import java.util.UUID;

public record StripeCheckoutRequest(BigDecimal amount,
                                    UUID walletReference,
                                    String successUrl,
                                    String cancelUrl){
}
