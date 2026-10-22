package dev.hashmark.channel;

import dev.hashmark.channel.NotificationChannelRepository.Channel;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** Kullanıcının Slack / Discord / Teams kanalları: yönetim, deneme ve gönderim. */
@Service
public class NotificationChannelService {

    private static final Logger log = LoggerFactory.getLogger(NotificationChannelService.class);
    public static final int MAX_CHANNELS = 5;

    public enum Purpose { DIGEST, ALERT }

    /** API görünümü: adres yerine yalnızca maskeli ipucu. */
    public record ChannelDto(long id, String kind, String urlHint, boolean sendDigest, boolean sendAlerts,
                             String lastStatus, String lastError, LocalDateTime lastSentAt) {
        static ChannelDto of(Channel c) {
            return new ChannelDto(c.id(), c.kind().name(), c.urlHint(), c.sendDigest(), c.sendAlerts(),
                    c.lastStatus(), c.lastError(), c.lastSentAt());
        }
    }

    public record CreateRequest(String kind, String url, Boolean sendDigest, Boolean sendAlerts) {
    }

    public record UpdateRequest(Boolean sendDigest, Boolean sendAlerts) {
    }

    public record TestResult(boolean ok, String error) {
    }

    private final NotificationChannelRepository repository;
    private final AesEncryptionUtil crypto;
    private final ChannelTransport transport;
    private final ChannelMessages messages;

    public NotificationChannelService(NotificationChannelRepository repository, AesEncryptionUtil crypto,
                                      ChannelTransport transport, ChannelMessages messages) {
        this.repository = repository;
        this.crypto = crypto;
        this.transport = transport;
        this.messages = messages;
    }

    public List<ChannelDto> list(long userId) {
        return repository.findByUserId(userId).stream().map(ChannelDto::of).toList();
    }

    public ChannelDto create(long userId, CreateRequest request) {
        ChannelKind kind = ChannelKind.parse(request.kind());
        String url = kind.validate(request.url());
        if (repository.countByUserId(userId) >= MAX_CHANNELS) {
            throw ApiException.badRequest("At most " + MAX_CHANNELS + " notification channels are allowed");
        }
        Channel saved = repository.insert(userId, kind, crypto.encrypt(url), ChannelKind.hint(url),
                request.sendDigest() == null || request.sendDigest(),
                request.sendAlerts() == null || request.sendAlerts());
        return ChannelDto.of(saved);
    }

    public ChannelDto update(long userId, long id, UpdateRequest request) {
        return repository.updateFlags(id, userId, request.sendDigest(), request.sendAlerts())
                .map(ChannelDto::of)
                .orElseThrow(() -> ApiException.notFound("Channel not found"));
    }

    public void delete(long userId, long id) {
        if (!repository.delete(id, userId)) {
            throw ApiException.notFound("Channel not found");
        }
    }

    public TestResult test(long userId, long id) {
        Channel channel = repository.findByIdForUser(id, userId)
                .orElseThrow(() -> ApiException.notFound("Channel not found"));
        ChannelTransport.Result result = send(channel, messages.test());
        return new TestResult(result.ok(), result.error());
    }

    /**
     * Mesajı kullanıcının bu amaca açık kanallarına gönderir. Bir kanalın hatası diğerlerini durdurmaz.
     *
     * @return başarıyla gönderilen kanal sayısı
     */
    public int deliver(long userId, Purpose purpose, ChatMessage message) {
        int ok = 0;
        for (Channel channel : repository.findByUserId(userId)) {
            boolean wanted = purpose == Purpose.DIGEST ? channel.sendDigest() : channel.sendAlerts();
            if (wanted && send(channel, message).ok()) {
                ok++;
            }
        }
        return ok;
    }

    public boolean hasChannels(long userId, Purpose purpose) {
        return repository.findByUserId(userId).stream()
                .anyMatch(c -> purpose == Purpose.DIGEST ? c.sendDigest() : c.sendAlerts());
    }

    private ChannelTransport.Result send(Channel channel, ChatMessage message) {
        ChannelTransport.Result result;
        try {
            // Kayıt sonrası doğrulama kuralları sıkılaştırıldıysa eski adres de reddedilir
            String url = channel.kind().validate(crypto.decrypt(channel.urlEncrypted()));
            result = transport.post(url, message.toJson(channel.kind()));
        } catch (ApiException e) {
            result = ChannelTransport.Result.failure("address no longer allowed");
        } catch (RuntimeException e) {
            log.warn("Channel {} could not be sent: {}", channel.id(), e.getClass().getSimpleName());
            result = ChannelTransport.Result.failure("unexpected error");
        }
        repository.recordResult(channel.id(), result.ok(), result.error());
        if (!result.ok()) {
            log.info("Channel delivery failed. channelId={} kind={} error={}", channel.id(), channel.kind(), result.error());
        }
        return result;
    }
}
