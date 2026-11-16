package com.ga.investmentservice.Config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfiguration {
    public static final String PORTFOLIO_HISTORY = "portfolioHistory";

    @Bean
    public CaffeineCacheManager portfolioHistoryCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(PORTFOLIO_HISTORY);
        manager.setAllowNullValues(false);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofSeconds(60)));
        return manager;
    }
}
