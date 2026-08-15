package dev.hashmark.settings.controller;

import dev.hashmark.settings.dto.UserSettingsDto;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.service.UserSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
        UserSettings settings = userSettingsService.getSettings(userId);
        
        UserSettingsDto dto = UserSettingsDto.builder()
                .emailNotify(settings.getEmailNotify())
                .notifyDay(settings.getNotifyDay())
                .build();
                
        return ResponseEntity.ok(dto);
    }

    @PutMapping
    @Operation(summary = "Kullanici ayarlarini guncelle")
    public ResponseEntity<UserSettingsDto> updateSettings(
            @AuthenticationPrincipal Long userId,
            @RequestBody UserSettingsDto dto
    ) {
        UserSettings settings = userSettingsService.updateSettings(userId, dto);
        
        UserSettingsDto responseDto = UserSettingsDto.builder()
                .emailNotify(settings.getEmailNotify())
                .notifyDay(settings.getNotifyDay())
                .build();
                
        return ResponseEntity.ok(responseDto);
    }
}
