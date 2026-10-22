package dev.hashmark.channel;

import dev.hashmark.channel.NotificationChannelRepository.Channel;
import dev.hashmark.channel.NotificationChannelService.CreateRequest;
import dev.hashmark.channel.NotificationChannelService.Purpose;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationChannelServiceTest {

    private static final String SLACK = "https://hooks.slack.com/services/T0/B0/secretpart";

    private final NotificationChannelRepository repository = mock(NotificationChannelRepository.class);
    private final AesEncryptionUtil crypto = mock(AesEncryptionUtil.class);
    private final ChannelTransport transport = mock(ChannelTransport.class);
    private final NotificationChannelService service = new NotificationChannelService(repository, crypto, transport,
            new ChannelMessages("https://app.example", "tr"));

    private static Channel channel(long id, ChannelKind kind, boolean digest, boolean alerts) {
        return new Channel(id, 1L, kind, "enc" + id, "hint", digest, alerts, null, null, null, null);
    }

    @Test
    void createValidatesEncryptsAndStoresOnlyAHint() {
        when(repository.countByUserId(1L)).thenReturn(0);
        when(crypto.encrypt(SLACK)).thenReturn("ENC");
        when(repository.insert(1L, ChannelKind.SLACK, "ENC", "hooks.slack.com/…part", true, false))
                .thenReturn(new Channel(9, 1L, ChannelKind.SLACK, "ENC", "hooks.slack.com/…part", true, false,
                        null, null, null, null));

        var dto = service.create(1L, new CreateRequest("slack", SLACK, null, false));

        assertEquals("hooks.slack.com/…part", dto.urlHint());
        assertFalse(dto.toString().contains("secretpart"));
    }

    @Test
    void createRejectsBadAddressAndTooManyChannels() {
        assertThrows(ApiException.class, () -> service.create(1L, new CreateRequest("slack", "https://evil.net/x", null, null)));
        when(repository.countByUserId(1L)).thenReturn(NotificationChannelService.MAX_CHANNELS);
        assertThrows(ApiException.class, () -> service.create(1L, new CreateRequest("slack", SLACK, null, null)));
        verify(repository, never()).insert(anyLong(), eq(ChannelKind.SLACK), anyString(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void deliverSendsOnlyToChannelsForThatPurposeAndRecordsResults() {
        when(repository.findByUserId(1L)).thenReturn(List.of(
                channel(1, ChannelKind.SLACK, true, false),
                channel(2, ChannelKind.DISCORD, false, true),
                channel(3, ChannelKind.SLACK, true, true)));
        when(crypto.decrypt("enc1")).thenReturn(SLACK);
        when(crypto.decrypt("enc3")).thenReturn("https://evil.net/services/x"); // kurallar sıkılaştı
        when(transport.post(eq(SLACK), contains("\"text\""))).thenReturn(ChannelTransport.Result.success());

        int ok = service.deliver(1L, Purpose.DIGEST, new ChatMessage("T", List.of("a"), null, null));

        assertEquals(1, ok);
        verify(repository).recordResult(1L, true, null);
        verify(repository).recordResult(3L, false, "address no longer allowed");
        verify(repository, never()).recordResult(eq(2L), anyBoolean(), any());
    }

    @Test
    void testRequiresOwnedChannel() {
        when(repository.findByIdForUser(5L, 1L)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.test(1L, 5L));

        when(repository.findByIdForUser(6L, 1L)).thenReturn(Optional.of(channel(6, ChannelKind.SLACK, true, true)));
        when(crypto.decrypt("enc6")).thenReturn(SLACK);
        when(transport.post(eq(SLACK), anyString())).thenReturn(ChannelTransport.Result.failure("HTTP 404"));
        var result = service.test(1L, 6L);
        assertFalse(result.ok());
        assertEquals("HTTP 404", result.error());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
