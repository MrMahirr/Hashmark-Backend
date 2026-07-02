package dev.hashmark.auth.service;

import dev.hashmark.auth.dto.GitHubUserDto;
import dev.hashmark.auth.dto.LoginResponse;
import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final GitHubOAuthService gitHubOAuthService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final AesEncryptionUtil aesEncryptionUtil;

    @Value("${jwt.access-token-expiry}")
    private long accessTokenExpiry;

    private final Set<String> tokenBlacklist = ConcurrentHashMap.newKeySet();

    public AuthService(GitHubOAuthService gitHubOAuthService, UserRepository userRepository, JwtUtil jwtUtil, AesEncryptionUtil aesEncryptionUtil) {
        this.gitHubOAuthService = gitHubOAuthService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.aesEncryptionUtil = aesEncryptionUtil;
    }

    public String initiateLogin() {
        return gitHubOAuthService.buildAuthorizationUrl();
    }

    public LoginResponse handleCallback(String code) {
        String githubToken = gitHubOAuthService.exchangeCodeForToken(code);
        GitHubUserDto userDto = gitHubOAuthService.fetchUserInfo(githubToken);

        String encryptedToken = aesEncryptionUtil.encrypt(githubToken);

        User userToSave = User.builder()
                .githubId(userDto.getId())
                .email(userDto.getEmail())
                .name(userDto.getName() != null ? userDto.getName() : userDto.getLogin())
                .githubToken(encryptedToken)
                .build();

        User savedUser = userRepository.save(userToSave);

        String accessToken = jwtUtil.generateAccessToken(savedUser.getId());
        String refreshToken = jwtUtil.generateRefreshToken(savedUser.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessTokenExpiry)
                .build();
    }

    public String refresh(String refreshToken) {
        if (tokenBlacklist.contains(refreshToken)) {
            throw ApiException.unauthorized("Refresh token is invalidated");
        }
        
        if (!jwtUtil.validateToken(refreshToken)) {
            throw ApiException.unauthorized("Invalid refresh token");
        }

        Long userId = jwtUtil.extractUserId(refreshToken);
        return jwtUtil.generateAccessToken(userId);
    }

    public void logout(String refreshToken) {
        if (refreshToken != null) {
            tokenBlacklist.add(refreshToken);
        }
    }
}
