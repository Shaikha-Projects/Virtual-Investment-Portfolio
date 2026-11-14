package com.ga.investmentservice.Event;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletCompletedEvent(Long userId,
                                   UUID reference,
                                   String operationType,
                                   BigDecimal amount,
                                   String status) {
}
