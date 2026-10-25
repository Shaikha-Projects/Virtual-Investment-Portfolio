package com.ga.investmentportfolio.Security;

import com.ga.investmentportfolio.Filter.InvestmentRateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final JwtRequestFilter jwtRequestFilter;
    private final InvestmentRateLimitFilter investmentRateLimitFilter;


    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }


    @Bean
    //config API security, allow users to register and then require authentication for all endpoints
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception{
        http
                .cors(cors -> {})
                .csrf(csrf -> csrf.disable())
                //each protected request must prove authentication using its JWT
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/register",
                                "/auth/verify",
                                "/auth/login",
                                "/auth/forgot-password",
                                "/auth/reset-password",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/uploads/**",
                                "/error"
                        ).permitAll() //public
                        .anyRequest().authenticated()); //everything else is authenticated

        http.addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        //to filter incoming requests
        http.addFilterAfter(investmentRateLimitFilter, JwtRequestFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();

        //allow requests from the React frontend
        configuration.setAllowedOrigins(List.of("http://localhost:5173"));

        // HTTP methods the frontend can use
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        );

        //allow headers such as Content-Type and Authorization
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception{
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    //to disable automatic servlet-filter registration while allowing Spring Security to use filter
    public FilterRegistrationBean<InvestmentRateLimitFilter> investmentRateLimitRegistration(
            InvestmentRateLimitFilter filter) {

        FilterRegistrationBean<InvestmentRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }


}
