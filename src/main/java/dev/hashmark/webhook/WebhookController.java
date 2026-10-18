package dev.hashmark.webhook;

import dev.hashmark.common.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/** GitHub'ın çağırdığı kimliksiz uç; kimlik yerine HMAC imzası doğrulanır. */
@RestController
@RequestMapping("/webhooks")
@Tag(name = "Webhook", description = "GitHub push webhook (automatic scans)")
public class WebhookController {

    private final GitHubWebhookService webhookService;
    private final WebhookProperties properties;

    public WebhookController(GitHubWebhookService webhookService, WebhookProperties properties) {
        this.webhookService = webhookService;
        this.properties = properties;
    }

    @PostMapping("/github/{repoId}")
    @Operation(summary = "GitHub push teslimati (X-Hub-Signature-256 ile imzali)")
    public ResponseEntity<GitHubWebhookService.Delivery> github(
            @PathVariable long repoId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            HttpServletRequest request) throws IOException {
        byte[] body = readLimited(request, properties.getMaxPayloadBytes());
        GitHubWebhookService.Delivery delivery = webhookService.handle(repoId, event, deliveryId, signature, body);
        HttpStatus status = "pong".equals(delivery.result()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(delivery);
    }

    /** İmza tüm gövde üzerinden hesaplanır; sınırsız okuma bellek tüketimine açık olurdu. */
    static byte[] readLimited(HttpServletRequest request, int maxBytes) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "Webhook payload is too large");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(maxBytes + 1);
            if (body.length > maxBytes) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "Webhook payload is too large");
            }
            return body;
        }
    }
}
