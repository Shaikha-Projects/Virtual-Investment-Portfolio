package com.ga.investmentservice.DTO.Response;

import com.ga.investmentservice.Enums.WalletOperationStatus;

import java.util.UUID;

public record DepositResponse(UUID reference,
                              WalletOperationStatus status,
                              String paymentUrl){
}
