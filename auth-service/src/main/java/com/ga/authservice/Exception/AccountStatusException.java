package com.ga.authservice.Exception;

public class AccountStatusException extends RuntimeException{
    public AccountStatusException(String message) {
        super(message);
    }
}
