package dev.hashmark.auth.controller;

import dev.hashmark.auth.dto.UserProfileDto;
import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.auth.service.AuthService;
import dev.hashmark.common.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import dev.hashmark.repo.repository.RepoRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@Tag(name = "User", description = "User Endpoints")
public class UserController {

    private final UserRepository userRepository;
    private final RepoRepository repoRepository;

    private final AuthService authService;
    private final dev.hashmark.consent.ConsentService consentService;

    public UserController(UserRepository userRepository, RepoRepository repoRepository, AuthService authService,
                          dev.hashmark.consent.ConsentService consentService) {
        this.userRepository = userRepository;
        this.repoRepository = repoRepository;
        this.authService = authService;
        this.consentService = consentService;
    }

    @GetMapping("/me")
    @Operation(summary = "Mevcut kullanici profil bilgilerini getir")
    public ResponseEntity<UserProfileDto> getMe(@AuthenticationPrincipal Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("User not found"));

        var consent = consentService.status(userId);
        UserProfileDto dto = UserProfileDto.builder()
                .consentRequired(consent.required())
                .consentVersion(consent.acceptedVersion())
                .consentAcceptedAt(consent.acceptedAt())
                .name(user.getName())
                .email(user.getEmail())
                .githubLogin(user.getGithubLogin())
                .avatarUrl(user.getAvatarUrl())
                .githubConnected(user.getGithubToken() != null && !user.getGithubToken().isBlank())
                .createdAt(user.getCreatedAt())
                .build();

        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/github-connection")
    @Operation(summary = "GitHub baglantisini kes (erisim jetonunu sil; deleteData=true ise depo ve borclari da sil)")
    public ResponseEntity<Void> disconnectGithub(@AuthenticationPrincipal Long userId,
                                                 @RequestParam(defaultValue = "false") boolean deleteData) {
        userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
        userRepository.clearGithubToken(userId);
        if (deleteData) {
            repoRepository.deleteAllByUserId(userId);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/account")
    @Operation(summary = "Hesabi ve tum verileri kalici olarak sil (KVKK/GDPR)")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal Long userId) {
        userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
        authService.revokeAllSessions(userId);
        userRepository.deleteById(userId);
        return ResponseEntity.noContent().build();
    }
}
