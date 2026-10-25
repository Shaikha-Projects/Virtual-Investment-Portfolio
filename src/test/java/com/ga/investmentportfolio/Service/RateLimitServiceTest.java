package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Filter.InvestmentRateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


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

    @Test
        //to test 5 investment request, sixth request receives HTTP 429
    void shouldReturn429AfterFiveRequests() throws Exception {

        //create the filter using our existing rate limit service
        InvestmentRateLimitFilter filter = new InvestmentRateLimitFilter(rateLimitService);

        //simulate an authenticated user
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        "test@example.com",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            //send 6 requests to the BUY endpoint
            for (int i = 1; i <= 6; i++) {

                //create a fake POST request
                MockHttpServletRequest request = new MockHttpServletRequest("POST", "/portfolio/buy");
                request.setServletPath("/portfolio/buy");

                //create a fake HTTP response
                MockHttpServletResponse response = new MockHttpServletResponse();

                //create a filter chain
                MockFilterChain filterChain = new MockFilterChain();

                //execute the rate limiting filter
                filter.doFilter(request, response, filterChain);

                if (i <= 5) {
                    //first 5 requests should continue
                    assertEquals(200, response.getStatus());
                    assertEquals(1, filterChain.getRequest() == null ? 0 : 1);
                } else {
                    //sixth request should be rejected
                    assertEquals(429, response.getStatus());
                    assertNull(filterChain.getRequest());
                }
            }

        } finally {
            SecurityContextHolder.clearContext();
        }

    }
}
