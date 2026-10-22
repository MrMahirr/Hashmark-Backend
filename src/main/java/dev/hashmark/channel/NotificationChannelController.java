package dev.hashmark.channel;

import dev.hashmark.channel.NotificationChannelService.ChannelDto;
import dev.hashmark.channel.NotificationChannelService.CreateRequest;
import dev.hashmark.channel.NotificationChannelService.TestResult;
import dev.hashmark.channel.NotificationChannelService.UpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/settings/channels")
@Tag(name = "Settings")
public class NotificationChannelController {

    private final NotificationChannelService service;

    public NotificationChannelController(NotificationChannelService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Bildirim kanallari (adres yerine maskeli ipucu)")
    public List<ChannelDto> list(@AuthenticationPrincipal Long userId) {
        return service.list(userId);
    }

    @PostMapping
    @Operation(summary = "Slack / Discord / Teams gelen webhook kanali ekle")
    public ChannelDto create(@AuthenticationPrincipal Long userId, @RequestBody CreateRequest request) {
        return service.create(userId, request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Kanalin ozet / uyari secimini degistir")
    public ChannelDto update(@AuthenticationPrincipal Long userId, @PathVariable long id,
                             @RequestBody UpdateRequest request) {
        return service.update(userId, id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Kanali sil")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Kanala deneme mesaji gonder")
    public TestResult test(@AuthenticationPrincipal Long userId, @PathVariable long id) {
        return service.test(userId, id);
    }
}
