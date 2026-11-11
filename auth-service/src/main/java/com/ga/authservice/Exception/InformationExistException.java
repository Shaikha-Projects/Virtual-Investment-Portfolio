package com.ga.authservice.Exception;

public class InformationExistException extends RuntimeException{
    public InformationExistException(String message){
        super(message);
    }
}
