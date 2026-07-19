package dev.hashmark.settings.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSettings {
    private Long id;
    private Long userId;
    private Boolean emailNotify;
    private String notifyDay;
    private LocalDateTime createdAt;
}
