package com.ga.investmentservice.DTO.Response;

import com.ga.investmentservice.Enums.WalletOperationStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record WithdrawalResponse(UUID reference,
                                 WalletOperationStatus status,
                                 BigDecimal balanceAfter){
}
