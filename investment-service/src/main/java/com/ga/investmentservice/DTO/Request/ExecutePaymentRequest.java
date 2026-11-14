package com.ga.investmentservice.DTO.Request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record ExecutePaymentRequest(@JsonProperty("PaymentMethodId") Integer paymentMethodId,
                                    @JsonProperty("InvoiceValue") BigDecimal invoiceValue,
                                    @JsonProperty("DisplayCurrencyIso") String displayCurrencyIso,
                                    @JsonProperty("CustomerReference") String customerReference,
                                    @JsonProperty("CallBackUrl") String callBackUrl,
                                    @JsonProperty("ErrorUrl") String errorUrl){
}
