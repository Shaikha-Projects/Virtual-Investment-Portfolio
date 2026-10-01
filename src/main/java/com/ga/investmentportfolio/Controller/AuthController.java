package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.ChangePasswordRequest;
import com.ga.investmentportfolio.DTO.Request.LoginRequest;
import com.ga.investmentportfolio.DTO.Request.RegisterRequest;
import com.ga.investmentportfolio.DTO.Response.LoginResponse;
import com.ga.investmentportfolio.DTO.Response.RegisterResponse;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Service.AuthService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@AllArgsConstructor
public class AuthController {

    private AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest registerRequest){

        System.out.println("controller calling register ==>");

        RegisterResponse response = authService.register(registerRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);

    }

    @GetMapping("/verify")
    public ResponseEntity<RegisterResponse> verifyEmail(@RequestParam String token){
        System.out.println("controller calling verifyEmail ==>");

        authService.verifyEmail(token);

        return ResponseEntity.ok(new RegisterResponse("Email verified successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        LoginResponse response = authService.login(loginRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/change-password")
    public ResponseEntity<RegisterResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication){
        String emailAddress = authentication.getName();

        authService.changePassword(request, emailAddress);
        return ResponseEntity.ok(new RegisterResponse("Password changed successfully"));
    }
}
