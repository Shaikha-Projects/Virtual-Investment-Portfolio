package com.ga.investmentportfolio.Event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class TransactionEmailEvent {
    private final String emailAddress;
    private final String type;
    private final String assetSymbol;
    private final BigDecimal quantity;
    private final BigDecimal totalAmount;
}
