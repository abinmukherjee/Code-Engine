package com.distributedjudge.controller;

import com.distributedjudge.dto.AuthRequest;
import com.distributedjudge.dto.AuthResponse;
import com.distributedjudge.dto.UserResponse;
import com.distributedjudge.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Profile("!worker")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/password")
    public AuthResponse password(@Valid @RequestBody AuthRequest request) {
        return authService.loginWithPassword(request);
    }

    @PostMapping("/email")
    public AuthResponse emailOnly(@Valid @RequestBody AuthRequest request) {
        return authService.loginWithEmail(request);
    }

    @GetMapping("/me")
    public UserResponse me(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {
        return authService.me(authorizationHeader);
    }
}
