package dev.hashmark.common.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Hız sınırı matrisi ({@code rate-limit.*}): istek/dakika. 0 = o kural kapalı. Bir istek birden fazla kurala
 * takılabilir; hepsinden geçmesi gerekir. nginx {@code limit_req} IP başına ilk savunmadır, bunlar ikincisi.
 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    // ---- uç bazlı (pahalı ya da kötüye kullanıma açık uçlar)
    /** {@code /auth/**}, IP başına (kimlik uçlarına kaba kuvvet). */
    private int authPerMinute = 30;
    /** {@code POST /scan/**}, kullanıcı başına (GitHub kotası). */
    private int scanPerMinute = 10;
    /** {@code GET /debts/export}, kullanıcı başına (20.000 satırlık akış). */
    private int exportPerMinute = 10;
    /** {@code POST /repos/sync}, kullanıcı başına (GitHub repo listesi). */
    private int syncPerMinute = 6;
    /** {@code GET /debts/{id}/context}, kullanıcı başına (canlı GitHub dosya isteği). */
    private int contextPerMinute = 60;
    /** {@code /public/**}, IP başına (kimliksiz, önbelleksiz uçlar). */
    private int publicPerMinute = 120;
    /**
     * {@code /webhooks/**}, depo adresi başına. GitHub teslimatları paylaşımlı IP'lerden gelir; bu yüzden bu yolda
     * IP tabanı uygulanmaz, imza doğrulamadan önceki tek uygulama içi sınır budur.
     */
    private int webhookPerMinute = 60;

    // ---- genel taban (her istek)
    /** Kimlikli her istek, kullanıcı başına. */
    private int userPerMinute = 600;
    /** Her istek, IP başına (NAT arkasındaki ekipleri boğmayacak kadar yüksek; asıl iş nginx'te). */
    private int ipPerMinute = 1200;

    // ---- hesap bazlı (kimlik uçları; IP değişse de hesap başına)
    /** Bir kullanıcının refresh token yenileme sayısı. */
    private int refreshPerAccountPerMinute = 20;
    /** Bir GitHub hesabının giriş (callback) sayısı. */
    private int loginPerAccountPerMinute = 10;
}
