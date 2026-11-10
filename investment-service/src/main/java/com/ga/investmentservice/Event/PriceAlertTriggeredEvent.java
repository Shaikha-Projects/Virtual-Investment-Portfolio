package com.ga.investmentservice.Event;

public record PriceAlertTriggeredEvent(String emailAddress, String message) {
}
