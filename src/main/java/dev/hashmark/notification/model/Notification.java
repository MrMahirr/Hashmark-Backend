package dev.hashmark.notification.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class Notification {
    private Long id;
    private Long userId;
    private String title;
    private String description;
    private String type; // SUCCESS, WARNING, INFO, ERROR
    private boolean isRead;
    private LocalDateTime createdAt;
}
