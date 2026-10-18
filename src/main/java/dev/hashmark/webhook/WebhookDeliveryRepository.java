package dev.hashmark.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.regex.Pattern;

/** GitHub teslimat kimlikleri (X-GitHub-Delivery): yeniden gönderilen teslimat ikinci kez işlenmez. */
@Repository
public class WebhookDeliveryRepository {

    private static final Pattern DELIVERY_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private final JdbcTemplate jdbcTemplate;

    public WebhookDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return teslimat ilk kez görüldüyse (ya da kimlik yok/geçersizse) true; daha önce işlendiyse false
     */
    public boolean firstSeen(String deliveryId) {
        if (deliveryId == null || !DELIVERY_ID.matcher(deliveryId).matches()) {
            return true;
        }
        return jdbcTemplate.update(
                "INSERT INTO webhook_deliveries (delivery_id) VALUES (?) ON CONFLICT (delivery_id) DO NOTHING",
                deliveryId) == 1;
    }

    /** @return silinen satır (en fazla {@code limit}) */
    public int deleteOlderThanDays(int days, int limit) {
        return jdbcTemplate.update(
                "DELETE FROM webhook_deliveries WHERE delivery_id IN (SELECT delivery_id FROM webhook_deliveries " +
                "WHERE received_at < NOW() - (? * INTERVAL '1 day') ORDER BY received_at LIMIT ?)", days, limit);
    }
}
