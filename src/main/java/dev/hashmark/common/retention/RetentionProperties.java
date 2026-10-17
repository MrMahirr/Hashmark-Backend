package dev.hashmark.common.retention;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Veri saklama süreleri (plan Bölüm 5.6). Değerler gizlilik politikasıyla aynı tutulmalı. */
@Data
@Component
@ConfigurationProperties(prefix = "retention")
public class RetentionProperties {

    private boolean enabled = true;
    /** Bitmiş tarama işleri bu süreden sonra silinir (deponun son işi hariç). */
    private int scanJobsDays = 90;
    /** Bu süreden eski snapshot'lar depo başına haftada tek satıra indirgenir. */
    private int snapshotsDetailDays = 90;
    /** Bu süreden eski snapshot'lar silinir. */
    private int snapshotsMonths = 24;
    /** Okunmuş bildirimler bu süreden sonra silinir (okunmamışlar kalır). */
    private int notificationsReadDays = 180;
    /** Tek DELETE ifadesinin en fazla sileceği satır; uzun kilitleri önler. */
    private int batchSize = 5_000;
}
