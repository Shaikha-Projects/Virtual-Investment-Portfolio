package com.ga.investmentservice.Service;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;
import java.time.Duration;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    //buckets for investment API requests
    private final ConcurrentHashMap<String, Bucket> investmentBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> helpBuckets = new ConcurrentHashMap<>();

    public boolean allowHelpRequest(String email) {
        Bucket bucket = helpBuckets.computeIfAbsent(email, key -> Bucket.builder()
                .addLimit(Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)))).build());
        return bucket.tryConsume(1);
    }

    //method to create limit rate investment bucket
    private Bucket createInvestmentBucket() {
        //to give the bucket 5 attempts every 1 minute.
        Refill refill = Refill.intervally(5, Duration.ofMinutes(1));

        //bucket can hold a max of 5 attempts
        Bandwidth limit = Bandwidth.classic(5, refill);

        //newly created bucket starts with tokens for 5 requests
        return Bucket.builder().addLimit(limit).build();
    }

    //connect bucket to investmentBuckets map
    public boolean allowInvestmentRequest(String email, String endpoint) {
        //create unique key
        String key = email + ":" + endpoint;

        //retrieve or create a bucket
        Bucket bucket = investmentBuckets.computeIfAbsent(key, k-> createInvestmentBucket());

        //consume one token
        return bucket.tryConsume(1);
    }
}
