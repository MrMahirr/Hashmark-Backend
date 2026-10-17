package dev.hashmark;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Postgres sunucu logunun yorum metni taşımaması için gereken bayraklar compose'tan yanlışlıkla silinmesin.
 * Bayrakların etkisi PG 18'de elle doğrulandı (yavaş sorgu parametresi ve "Failing row contains" DETAIL
 * satırı bayraklar yokken loglanıyordu). Ayrıntı: docs/POSTGRES_TUNING.md → Veri minimizasyonu.
 */
class ComposePrivacyConfigTest {

    @Test
    void postgresServerLogDoesNotRecordBindParametersOrRowDetail() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"), StandardCharsets.UTF_8);
        assertTrue(compose.contains("log_parameter_max_length=0"), "yavaş sorgu logu parametreleri yazar");
        assertTrue(compose.contains("log_error_verbosity=terse"), "hata logu satır içeriğini (DETAIL) yazar");
    }

    @Test
    void jdbcDriverDoesNotEmbedServerDetailInExceptions() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertTrue(yml.contains("logServerErrorDetail: false"), "pgjdbc istisna mesajına satır içeriğini gömer");
    }
}
