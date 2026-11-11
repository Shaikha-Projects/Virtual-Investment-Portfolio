package com.ga.investmentservice.Event;

import java.math.BigDecimal;

public record TransactionCompletedEvent(String emailAddress,
                                        String type,
                                        String assetSymbol,
                                        BigDecimal quantity,
                                        BigDecimal totalAmount) {
}
