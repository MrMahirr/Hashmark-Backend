package dev.hashmark.common.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** GitHub çağrıları için bulkhead ve circuit breaker ayarları ({@code resilience.github.*}). */
@Data
@Component
@ConfigurationProperties(prefix = "resilience.github")
public class GitHubResilienceProperties {

    /** Kullanıcı isteği yolundaki (giriş, repo listesi, bağlam) eşzamanlı GitHub çağrısı üst sınırı. */
    private int interactiveMaxConcurrent = 100;
    /** Kullanıcı yolu dolu ise izin için en fazla bekleme; sonra 503. İstek iş parçacığı asılı kalmasın. */
    private long interactiveMaxWaitMs = 200;
    /** Tarama işçilerinin (tarball, blame) eşzamanlı GitHub çağrısı üst sınırı. */
    private int scanMaxConcurrent = 8;
    private long scanMaxWaitMs = 2_000;

    /** Hata oranı bu yüzdeyi aşınca devre açılır. */
    private float failureRateThreshold = 50f;
    /** Bu süreyi aşan çağrılar "yavaş" sayılır; yavaş oranı eşiği aşınca da devre açılır. */
    private long slowCallDurationMs = 8_000;
    private float slowCallRateThreshold = 80f;
    /** Oran hesaplanmadan önce gereken en az çağrı (az veride tek hata devreyi açmasın). */
    private int minimumNumberOfCalls = 10;
    /** Kayan pencere: son N çağrı. */
    private int slidingWindowSize = 20;
    /** Açık devre bu süre sonra yarı açığa geçip deneme çağrılarına izin verir. */
    private long openStateWaitMs = 15_000;
    private int permittedCallsInHalfOpen = 3;
}
