package dev.hashmark.settings.service;

import dev.hashmark.settings.dto.UserSettingsDto;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserSettingsService {

    private final UserSettingsRepository userSettingsRepository;

    public UserSettingsService(UserSettingsRepository userSettingsRepository) {
        this.userSettingsRepository = userSettingsRepository;
    }

    public UserSettings getSettings(Long userId) {
        Optional<UserSettings> settingsOpt = userSettingsRepository.findByUserId(userId);
        
        if (settingsOpt.isPresent()) {
            return settingsOpt.get();
        }
        
        // Return and save default settings
        UserSettings defaultSettings = UserSettings.builder()
                .userId(userId)
                .emailNotify(true)
                .notifyDay("MON")
                .build();
                
        return userSettingsRepository.save(defaultSettings);
    }

    public UserSettings updateSettings(Long userId, UserSettingsDto dto) {
        UserSettings settings = UserSettings.builder()
                .userId(userId)
                .emailNotify(dto.getEmailNotify())
                .notifyDay(dto.getNotifyDay())
                .build();
                
        return userSettingsRepository.save(settings);
    }
}
