package com.ga.investmentservice.DTO.Response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.util.StdConverter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GetPaymentStatusResponse(@JsonProperty("IsSuccess") Boolean isSuccess,
                                       @JsonProperty("Data") PaymentData data){

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentData(@JsonProperty("InvoiceId") Long invoiceId,
                              @JsonProperty("CustomerReference") String customerReference,
                              @JsonProperty("InvoiceStatus") String invoiceStatus,
                              @JsonProperty("InvoiceValue") BigDecimal invoiceValue,
                              @JsonProperty("InvoiceDisplayValue") String invoiceDisplayValue,
                              @JsonProperty("InvoiceTransactions") List<PaymentTransaction> invoiceTransactions){

        // check all attempts for a successful payment
        public Optional<PaymentTransaction> successfulTransaction() {
            if (invoiceTransactions == null) {
                return Optional.empty();
            }
            return invoiceTransactions.stream()
                    .filter(transaction -> transaction != null && "Succss".equals(transaction.transactionStatus()))
                    .findFirst();
        }

        // the displayed usd amount differs from the sandbox invoice amount
        public BigDecimal displayedAmount() {
            if (invoiceDisplayValue == null
                    || !invoiceDisplayValue.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)? USD")) {
                throw new IllegalStateException("Invalid MyFatoorah USD display amount");
            }
            return new BigDecimal(invoiceDisplayValue.substring(0, invoiceDisplayValue.length() - 4).replace(",", ""));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentTransaction(@JsonProperty("PaymentId") String paymentId,
                                     @JsonProperty("TransactionStatus") String transactionStatus,
                                     @JsonProperty("TransationValue") @JsonDeserialize(converter = MoneyConverter.class) BigDecimal transactionValue,
                                     @JsonProperty("Currency") String currency,
                                     @JsonProperty("PaidCurrency") String paidCurrency,
                                     @JsonProperty("PaidCurrencyValue") @JsonDeserialize(converter = MoneyConverter.class) BigDecimal paidCurrencyValue){
    }

    // remove commas before reading the amount
    public static class MoneyConverter extends StdConverter<String, BigDecimal> {
        @Override
        public BigDecimal convert(String value) {
            if (value == null
                    || !value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) {
                throw new IllegalStateException("Invalid MyFatoorah transaction amount");
            }
            return new BigDecimal(value.replace(",", ""));
        }
    }
}
