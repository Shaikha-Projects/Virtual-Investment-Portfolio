package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.*;
import com.ga.investmentportfolio.DTO.Response.LoginResponse;
import com.ga.investmentportfolio.DTO.Response.RegisterResponse;
import com.ga.investmentportfolio.Exception.RateLimitExceededException;
import com.ga.investmentportfolio.Service.AuthService;
import com.ga.investmentportfolio.Service.RateLimitService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/auth")
@AllArgsConstructor
@Tag(name = "Authentication", description = "User registration, email verification, login and password management")
public class AuthController {

    private AuthService authService;
    private final RateLimitService rateLimitService;

    @Operation(summary = "Register a new user",
            description = "Creates a new user account and sends an email verification link.",
            security = {})
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid registration data"),
            @ApiResponse(responseCode = "409", description = "Email address already exists")})
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest registerRequest){
        RegisterResponse response = authService.register(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Verify user email",
            description = "Verifies a user's email address using the verification token sent after registration.",
            security = {})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email verified successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid verification token"),
            @ApiResponse(responseCode = "410", description = "Verification token has expired")})
    @GetMapping("/verify")
    public ResponseEntity<RegisterResponse> verifyEmail(@RequestParam String token){
        authService.verifyEmail(token);
        return ResponseEntity.ok(new RegisterResponse("Email verified successfully"));
    }

    @Operation(summary = "Login",
            description = "Authenticates a verified active user and returns a JWT for accessing protected endpoints.",
            security = {})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password"),
            @ApiResponse(responseCode = "403", description = "Account is not eligible to log in"),
            @ApiResponse(responseCode = "429", description = "Too many login attempts")})
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        if(!rateLimitService.allowRequest(loginRequest.getEmailAddress())){
            throw new RateLimitExceededException("Too many login attempts. Please try again in one minute.");
        }
        LoginResponse response = authService.login(loginRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Change password",
            description = "Changes the password of the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password changed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid password data"),
            @ApiResponse(responseCode = "401", description = "Authentication required")})
    @PostMapping("/change-password")
    public ResponseEntity<RegisterResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication){
        String emailAddress = authentication.getName();

        authService.changePassword(request, emailAddress);
        return ResponseEntity.ok(new RegisterResponse("Password changed successfully"));
    }


    @Operation(summary = "Request password reset",
            description = "Sends a password reset email when password recovery is requested.",
            security = {})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset email sent successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "429", description = "Too many login attempts")})
    @PostMapping("/forgot-password")
    public ResponseEntity<RegisterResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request){

        if (!rateLimitService.allowForgotPasswordRequest(request.getEmailAddress())) {
            throw new RateLimitExceededException("Too many password reset attempts. Please try again in one minute.");
        }
        authService.forgotPassword(request);
        return ResponseEntity.ok(new RegisterResponse("Password reset email sent successfully"));
    }

    @Operation(summary = "Reset password",
            description = "Resets the user's password using a valid password reset token.",
            security = {})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid reset request"),
            @ApiResponse(responseCode = "410", description = "Password reset token has expired")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<RegisterResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request){

        authService.resetPassword(request);
        return ResponseEntity.ok(new RegisterResponse("Password reset successfully"));
    }
}
