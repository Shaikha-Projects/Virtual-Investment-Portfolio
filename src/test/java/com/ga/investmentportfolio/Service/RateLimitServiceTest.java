package com.ga.investmentportfolio.Service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;


public class RateLimitServiceTest {
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        //create a fresh rate limit service before each test
        rateLimitService = new RateLimitService();
    }

    @Test
    void shouldAllowFiveRequestsAndRejectSixth() {
        String email = "test@example.com";
        String endpoint = "/portfolio/buy";

        //first 5 requests should be allowed
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimitService.allowInvestmentRequest(email, endpoint));
        }

        //sixth request should be rejected
        assertFalse(rateLimitService.allowInvestmentRequest(email, endpoint));

    }
}
