package com.example.drinkshop.controller;

import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.security.JwtService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 登入：帳密正確就發 token，之後每個請求帶 Authorization: Bearer &lt;token&gt;。目前登入者資料仍用 GET /api/auth/me。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank(message = "請輸入帳號") String username,
                               @NotBlank(message = "請輸入密碼") String password) {}
    public record StaffInfo(long staffId, String username, String name, String level) {}
    public record LoginResponse(String token, Instant expiresAt, StaffInfo staff) {}

    private final AuthenticationManager authenticationManager;
    private final JwtService jwt;
    private final PosRepository repo;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwt, PosRepository repo) {
        this.authenticationManager = authenticationManager;
        this.jwt = jwt;
        this.repo = repo;
    }

    /** POST /api/auth/login */
    @PostMapping("/login")
    @SecurityRequirements
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        try {
            // BCrypt 比對密碼、檢查是否停用，都交給 Spring Security
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (AuthenticationException e) {
            // 帳號不存在、密碼錯、已停用都回同一句話，不讓人試出哪些帳號存在
            throw ApiException.unauthorized("帳號或密碼錯誤");
        }
        var staff = repo.staff(request.username()).orElseThrow(() -> ApiException.unauthorized("帳號或密碼錯誤"));
        var token = jwt.issue(staff.username(), staff.level());
        return new LoginResponse(token.value(), token.expiresAt(),
            new StaffInfo(staff.id(), staff.username(), staff.name(), staff.level()));
    }
}
