package dev.hashmark.consent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Kullanıcı onayı (plan 5.10, K7). Metinler: {@code resources/consent/}. */
@Data
@Component
@ConfigurationProperties(prefix = "consent")
public class ConsentProperties {

    /**
     * true: onaysız kullanıcı veri işleyen hiçbir ucu kullanamaz (403 CONSENT_REQUIRED), tarama işçisi ve
     * haftalık özet de durur; özel depo ek onay olmadan taranmaz. Metin hukuki incelemeden geçene kadar false.
     */
    private boolean enforced = false;
    /** Geçerli politika sürümü; artırılınca herkesten yeniden onay istenir. */
    private int policyVersion = 1;
    /** Özel depo uyarı metninin sürümü. */
    private int privateRepoVersion = 1;
}
