package com.ga.investmentportfolio.Exception;

import com.ga.investmentportfolio.DTO.Response.ErrorResponse;
import com.ga.investmentportfolio.DTO.Response.MessageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    //when information (email for user) already exists
    @ExceptionHandler(InformationExistException.class)
    public ResponseEntity<ErrorResponse> handleInformationExistException(InformationExistException exception) {

        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage()); //get error message from the exception

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse); //constructs the HTTP response
    }

    //to validate user input when registration
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException exception) {

        Map<String, String> errors = new HashMap<>(); //an empty map to collect all validation

        //gets all the validation results, then loops through every validation error
        exception.getBindingResult().getFieldErrors().forEach(error ->
                        errors.put(error.getField(), error.getDefaultMessage())); //get fields and it's validation message

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    //when information not found
    @ExceptionHandler(InformationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleInformationNotFoundException(InformationNotFoundException exception){
        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage()); //get error message from the exception

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse); //constructs the HTTP response
    }

    //when token has expired
    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTokenExpiredException(TokenExpiredException exception){
        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage()); //get error message from the exception

        return ResponseEntity.status(HttpStatus.GONE).body(errorResponse); //constructs the HTTP response
    }

    //when login with invalid credentials
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(InvalidCredentialsException exception){
        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage()); //get error message from the exception

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse); //constructs the HTTP response
    }

    //when user status does not allow login
    @ExceptionHandler(AccountStatusException.class)
    public ResponseEntity<ErrorResponse> handleAccountStatusException(AccountStatusException exception){
        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage()); //get error message from the exception

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse); //constructs the HTTP response
    }

    //when user tries to upload invalid file
    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<ErrorResponse> handleInvalidFileException(InvalidFileException exception){
        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage()); //get error message from the exception

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse); //constructs the HTTP response
    }

    //when a business rule is violated
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRuleException(BusinessRuleException exception) {
        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    //when user tries login rate limit exceeded
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<MessageResponse> handleRateLimitExceeded(RateLimitExceededException ex) {

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new MessageResponse(ex.getMessage()));
    }

}
