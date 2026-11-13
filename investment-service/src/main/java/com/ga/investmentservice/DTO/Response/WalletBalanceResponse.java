package com.ga.investmentservice.DTO.Response;

import java.math.BigDecimal;

public record WalletBalanceResponse(BigDecimal cashBalance,
                                    BigDecimal reservedCash,
                                    BigDecimal availableCash){
}
