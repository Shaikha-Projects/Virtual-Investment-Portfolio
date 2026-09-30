package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.RegisterRequest;
import com.ga.investmentportfolio.DTO.Response.RegisterResponse;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Service.AuthService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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


}
