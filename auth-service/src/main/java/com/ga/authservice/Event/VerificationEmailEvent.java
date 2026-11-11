package com.ga.authservice.Event;

public record VerificationEmailEvent (String emailAddress, String token){
}
