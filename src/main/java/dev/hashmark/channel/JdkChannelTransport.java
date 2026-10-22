package dev.hashmark.channel;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JDK HttpClient ile gönderim. Yönlendirme <b>izlenmez</b> (izinli bir adres başka bir hedefe sektirilemesin),
 * kısa zaman aşımları var ve yanıt gövdesi okunmaz (yalnızca durum kodu).
 */
@Component
public class JdkChannelTransport implements ChannelTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public Result post(String url, String json) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "Hashmark")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300 ? Result.success() : Result.failure("HTTP " + status);
        } catch (HttpTimeoutException e) {
            return Result.failure("timeout");
        } catch (IOException e) {
            return Result.failure("connection failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure("interrupted");
        }
    }
}
