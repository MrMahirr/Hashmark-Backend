package dev.hashmark.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationDto {
    private Long id;
    private String title;
    private String description;
    private String type;
    private boolean isRead;
    private LocalDateTime createdAt;
}
