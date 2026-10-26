package com.ga.investmentportfolio.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
    //to keep scheduling enabled normally but disables it when the test profile is active
}
