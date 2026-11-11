package com.ga.authservice.Event;

public record UserVerifiedEvent (Long userId, String emailAddress){
}
