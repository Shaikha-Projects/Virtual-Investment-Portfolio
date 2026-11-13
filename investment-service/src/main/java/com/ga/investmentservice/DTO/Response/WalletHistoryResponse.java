package com.ga.investmentservice.DTO.Response;

import com.ga.investmentservice.Enums.WalletOperationStatus;
import com.ga.investmentservice.Enums.WalletOperationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WalletHistoryResponse(UUID reference,
                                    WalletOperationType operationType,
                                    BigDecimal amount,
                                    String currency,
                                    WalletOperationStatus status,
                                    BigDecimal balanceAfter,
                                    LocalDateTime createdAt,
                                    LocalDateTime completedAt){
}
