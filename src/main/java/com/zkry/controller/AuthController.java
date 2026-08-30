package com.zkry.controller;

import com.zkry.domain.dto.auth.LoginRequest;
import com.zkry.domain.dto.auth.RegisterRequest;
import com.zkry.domain.dto.user.UpdateUserRequest;
import com.zkry.domain.vo.AuthSessionView;
import com.zkry.domain.vo.SystemUserView;
import com.zkry.security.TokenAuthenticationFilter;
import com.zkry.security.VoyagePrincipal;
import com.zkry.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public AuthSessionView login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/register")
    public AuthSessionView register(@RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping("/me")
    public SystemUserView me(@AuthenticationPrincipal VoyagePrincipal principal) {
        return authService.current(principal.userId());
    }

    @PutMapping("/profile")
    public SystemUserView updateProfile(
        @AuthenticationPrincipal VoyagePrincipal principal,
        @RequestBody UpdateUserRequest request
    ) {
        return authService.updateProfile(principal.userId(), request);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        authService.logout(TokenAuthenticationFilter.bearer(request.getHeader("Authorization")));
    }
}
