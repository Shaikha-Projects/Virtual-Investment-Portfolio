package com.ga.investmentportfolio.Filter;

import com.ga.investmentportfolio.Service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class InvestmentRateLimitFilter extends OncePerRequestFilter {
    //to automatically apply rate limiting to selected API endpoints

    private final RateLimitService rateLimitService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        String endpoint = request.getServletPath();

        //check buy and sell and asset endpoint
        boolean isBuy = method.equals("POST") && endpoint.equals("/portfolio/buy");
        boolean isSell = method.equals("POST") && endpoint.equals("/portfolio/sell");

        //check limit order placement
        boolean isPlaceOrder = method.equals("POST") && endpoint.equals("/orders");

        //check limit order cancellation
        boolean isCancelOrder = method.equals("PATCH") && endpoint.matches("/orders/\\d+/cancel");

        //check if request is protected
        if (!isBuy && !isSell && !isPlaceOrder && !isCancelOrder) {
            filterChain.doFilter(request, response);
            return;
        }

        //retrieve authenticated user
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        //use the same bucket for all cancellation requests
        String rateLimitEndpoint = isCancelOrder ? "/orders/{id}/cancel" : endpoint;

        //handle unauthenticated requests
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        //retrieve email
        String email = authentication.getName();

        //check whether the user exceeded the rate limit
        if (!rateLimitService.allowInvestmentRequest(email, rateLimitEndpoint)) {

            //return HTTP 429
            response.setStatus(429); //too many requests
            response.setContentType("application/json");

            response.getWriter().write(
                    "{\"message\":\"Too many requests. Please try again later.\"}"
            );

            return;
        }

        //allow the request to continue
        filterChain.doFilter(request, response);
    }
}
