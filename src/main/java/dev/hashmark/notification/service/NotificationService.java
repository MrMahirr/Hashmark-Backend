package dev.hashmark.notification.service;

import dev.hashmark.notification.dto.NotificationDto;
import dev.hashmark.notification.model.Notification;
import dev.hashmark.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public List<NotificationDto> getUserNotifications(Long userId, int limit) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, limit).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public void createNotification(Long userId, String title, String description, String type) {
        Notification notification = Notification.builder()
                .userId(userId)
                .title(title)
                .description(description)
                .type(type)
                .isRead(false)
                .build();
        notificationRepository.insert(notification);
    }

    public void markAsRead(Long id, Long userId) {
        notificationRepository.markAsRead(id, userId);
    }

    public void markAllAsRead(Long userId) {
        notificationRepository.markAllAsRead(userId);
    }

    private NotificationDto toDto(Notification notification) {
        return NotificationDto.builder()
                .id(notification.getId())
                .title(notification.getTitle())
                .description(notification.getDescription())
                .type(notification.getType())
                .isRead(notification.isRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
