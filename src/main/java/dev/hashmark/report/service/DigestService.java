package dev.hashmark.report.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.channel.ChannelMessages;
import dev.hashmark.channel.NotificationChannelService;
import dev.hashmark.channel.NotificationChannelService.Purpose;
import dev.hashmark.settings.model.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Bir kullanıcının dönemsel özeti: e-posta (açıksa) ve özet alan sohbet kanalları. */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private final EmailService emailService;
    private final NotificationChannelService channels;
    private final ChannelMessages messages;
    private final ReportService reportService;

    public DigestService(EmailService emailService, NotificationChannelService channels, ChannelMessages messages,
                         ReportService reportService) {
        this.emailService = emailService;
        this.channels = channels;
        this.messages = messages;
        this.reportService = reportService;
    }

    /**
     * Kullanıcının kapsamında çağrılmalı (RLS). Bir yol başarısız olsa da diğeri denenir.
     *
     * @return en az bir yoldan gönderildiyse {@code true} (o gün yeniden gönderilmez)
     */
    public boolean send(User user, UserSettings settings) {
        boolean sent = false;
        if (Boolean.TRUE.equals(settings.getEmailNotify()) && emailService.isConfigured()) {
            try {
                sent = emailService.sendWeeklyReport(user, settings);
            } catch (RuntimeException e) {
                log.warn("Digest email failed. userId={}: {}", user.getId(), e.getClass().getSimpleName());
            }
        }
        if (channels.hasChannels(user.getId(), Purpose.DIGEST)) {
            int delivered = channels.deliver(user.getId(), Purpose.DIGEST,
                    messages.digest(reportService.getSummary(user.getId(), null), settings.getDigestFrequency()));
            sent = sent || delivered > 0;
        }
        return sent;
    }
}
