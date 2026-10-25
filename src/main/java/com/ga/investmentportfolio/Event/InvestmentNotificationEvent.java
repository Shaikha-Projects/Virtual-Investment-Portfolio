package com.ga.investmentportfolio.Event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class InvestmentNotificationEvent {
    //to store the notification data
    private final String email;
    private final String message;

}
