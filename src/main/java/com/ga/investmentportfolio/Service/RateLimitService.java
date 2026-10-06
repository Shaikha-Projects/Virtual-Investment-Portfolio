package com.ga.investmentportfolio.Service;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;
import java.time.Duration;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    //buckets for login attempts
    private final ConcurrentHashMap<String, Bucket> loginBuckets = new ConcurrentHashMap<>();

    //buckets for forgot password requests
    private final ConcurrentHashMap<String, Bucket> forgotPasswordBuckets = new ConcurrentHashMap<>();

    //creates a new rate limit bucket
    private Bucket createBucket() {
        //to give the bucket 3 tokens every 1 minute.
        Refill refill = Refill.intervally(3, Duration.ofMinutes(1));

        //the bucket can hold a maximum of 3 login attempts(tokens)
        Bandwidth limit = Bandwidth.classic(3, refill);

        //newly created bucket starts with tokens for 3 requests
        return Bucket.builder().addLimit(limit).build();
    }

    //call this method when someone tries to log in
    public boolean allowRequest(String email) {
        //look for the bucket belonging to this email
        Bucket bucket = loginBuckets.computeIfAbsent(email, key -> createBucket());
        return bucket.tryConsume(1);//consume 1 token from this email's bucket
    }

    //call this method when someone tries to reset password
    public boolean allowForgotPasswordRequest(String email) {
        //look for the bucket belonging to this email
        Bucket bucket = forgotPasswordBuckets.computeIfAbsent(email, key -> createBucket());
        return bucket.tryConsume(1);
    }
}
