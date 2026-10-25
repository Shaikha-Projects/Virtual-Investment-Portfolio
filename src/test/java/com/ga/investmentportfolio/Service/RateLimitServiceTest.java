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
    //to test 5 investment request, the 6th should be rejected
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


    @Test
    //to track different user investment requests
    void shouldTrackUsersSeparately() {
        String email1 = "test1@example.com";
        String email2 = "test2@example.com";
        String endpoint = "/portfolio/buy";

        //first 5 requests should be allowed
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimitService.allowInvestmentRequest(email1, endpoint));
            assertTrue(rateLimitService.allowInvestmentRequest(email2, endpoint));
        }

        //sixth request should be rejected
        assertFalse(rateLimitService.allowInvestmentRequest(email1, endpoint));
        assertFalse(rateLimitService.allowInvestmentRequest(email2, endpoint));

    }
    @Test
    //test that all five BUY requests doesn't prevent the same user from making SELL requests
    void shouldTrackEndpointsSeparately() {
        String email = "test@example.com";
        String buyEndpoint = "/portfolio/buy";
        String sellEndpoint = "/portfolio/sell";

        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimitService.allowInvestmentRequest(email, buyEndpoint));
            assertTrue(rateLimitService.allowInvestmentRequest(email, sellEndpoint));
        }

        //sixth request should be rejected
        assertFalse(rateLimitService.allowInvestmentRequest(email, buyEndpoint));
        assertFalse(rateLimitService.allowInvestmentRequest(email, sellEndpoint));

    }
}
