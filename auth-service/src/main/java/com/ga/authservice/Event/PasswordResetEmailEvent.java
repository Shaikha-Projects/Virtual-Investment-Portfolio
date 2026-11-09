package com.ga.authservice.Event;

public record PasswordResetEmailEvent (String emailAddress, String token){
}
