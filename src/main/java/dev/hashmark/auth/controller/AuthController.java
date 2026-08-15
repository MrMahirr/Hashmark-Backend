package dev.hashmark.auth.controller;

import dev.hashmark.auth.dto.LoginResponse;
import dev.hashmark.auth.dto.RefreshRequest;
import dev.hashmark.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Authentication Endpoints")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/github")
    @Operation(summary = "GitHub OAuth URL dondur")
    @ApiResponse(responseCode = "200")
    public Map<String, String> githubLoginUrl() {
        return Map.of("authUrl", authService.initiateLogin());
    }

    @GetMapping("/callback")
    @Operation(summary = "OAuth callback - JWT dondur")
    @ApiResponse(responseCode = "200")
    public LoginResponse callback(@RequestParam String code) {
        return authService.handleCallback(code);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Access token yenile")
    @ApiResponse(responseCode = "200")
    public Map<String, String> refresh(@RequestBody RefreshRequest request) {
        return Map.of("accessToken", authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Cikis yap")
    @ApiResponse(responseCode = "204")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}
