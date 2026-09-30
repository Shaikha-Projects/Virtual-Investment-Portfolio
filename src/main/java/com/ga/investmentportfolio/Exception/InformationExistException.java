package com.ga.investmentportfolio.Exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

public class InformationExistException extends RuntimeException{
    public InformationExistException(String message){
        super(message);
    }
}
