package com.ga.investmentportfolio.Event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class WelcomeEmailEvent {
    private final String emailAddress;
}
