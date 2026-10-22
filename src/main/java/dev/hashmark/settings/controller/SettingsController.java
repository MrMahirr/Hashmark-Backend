package dev.hashmark.settings.controller;

import dev.hashmark.settings.dto.UserSettingsDto;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.service.UserSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/settings")
@Tag(name = "Settings")
public class SettingsController {

    private final UserSettingsService userSettingsService;

    public SettingsController(UserSettingsService userSettingsService) {
        this.userSettingsService = userSettingsService;
    }

    @GetMapping
    @Operation(summary = "Mevcut kullanici ayarlarini getir")
    public ResponseEntity<UserSettingsDto> getSettings(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(toDto(userSettingsService.getSettings(userId)));
    }

    @PutMapping
    @Operation(summary = "Kullanici ayarlarini guncelle")
    public ResponseEntity<UserSettingsDto> updateSettings(
            @AuthenticationPrincipal Long userId,
            @RequestBody UserSettingsDto dto
    ) {
        return ResponseEntity.ok(toDto(userSettingsService.updateSettings(userId, dto)));
    }

    static UserSettingsDto toDto(UserSettings settings) {
        return UserSettingsDto.builder()
                .emailNotify(settings.getEmailNotify())
                .notifyDay(settings.getNotifyDay())
                .digestLabels(List.copyOf(settings.labelSet()))
                .digestEmail(settings.getDigestEmail())
                .hourlyRate(settings.getHourlyRate())
                .currency(settings.getCurrency())
                .build();
    }
}
