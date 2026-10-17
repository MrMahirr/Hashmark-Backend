package dev.hashmark;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/MIGRATIONS.md kurallarını CI'da zorlar. V1–V10 kurallardan önce yazıldı ve dokunulmaz (Flyway
 * checksum'ı değişmesin diye); kurallar V11 ve sonrası için geçerlidir.
 */
class MigrationConventionsTest {

    static final Path DIR = Path.of("src/main/resources/db/migration");
    static final int FIRST_ENFORCED_VERSION = 11;

    private static final Pattern NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "\\bDROP\\s+(TABLE|COLUMN)\\b|\\bRENAME\\b|\\bALTER\\s+COLUMN\\s+\\S+\\s+(SET\\s+DATA\\s+)?TYPE\\b|\\bTRUNCATE\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\s+(CONCURRENTLY\\s+)?(IF\\s+NOT\\s+EXISTS\\s+)?\\S+\\s+ON\\s+(ONLY\\s+)?([a-z_][a-z0-9_.]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "\\bCREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_.]*)", Pattern.CASE_INSENSITIVE);

    record Migration(int version, String file, String sql) {
        /** Yorumlar çıkarılmış SQL (kural eşleşmeleri yorumdaki kelimelere takılmasın). */
        String code() {
            return sql.replaceAll("(?m)--.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    static List<Migration> migrations() throws IOException {
        List<Migration> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path p : files.sorted().toList()) {
                String name = p.getFileName().toString();
                Matcher m = NAME.matcher(name);
                assertTrue(m.matches(), "Migrasyon adı V<n>__kucuk_harf_alt_cizgi.sql olmalı: " + name);
                result.add(new Migration(Integer.parseInt(m.group(1)), name, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        return result;
    }

    static List<Migration> enforced() throws IOException {
        return migrations().stream().filter(m -> m.version() >= FIRST_ENFORCED_VERSION).toList();
    }

    @Test
    void versionsAreUniqueAndContiguous() throws IOException {
        TreeMap<Integer, String> byVersion = new TreeMap<>();
        for (Migration m : migrations()) {
            String previous = byVersion.put(m.version(), m.file());
            assertTrue(previous == null, "Aynı sürüm iki kez: " + previous + " / " + m.file());
        }
        int expected = 1;
        for (int v : byVersion.keySet()) {
            assertEquals(expected++, v, "Sürüm atlanmış (out-of-order kapalı): " + byVersion.get(v));
        }
    }

    @Test
    void everyNewMigrationDocumentsRollback() throws IOException {
        for (Migration m : enforced()) {
            assertTrue(m.sql().contains("Geri alma:"), m.file() + ": başlıkta 'Geri alma:' notu zorunlu");
        }
    }

    @Test
    void destructiveChangesAreExplicitContractSteps() throws IOException {
        for (Migration m : enforced()) {
            if (DESTRUCTIVE.matcher(m.code()).find()) {
                assertTrue(m.sql().contains("-- contract:"), m.file()
                        + ": yıkıcı değişiklik (DROP/RENAME/TYPE/TRUNCATE) yalnızca expand→migrate→contract'ın son adımında; "
                        + "'-- contract: <hangi sürümde genişletildi, kod ne zamandan beri eskiyi kullanmıyor>' yazın");
            }
        }
    }

    @Test
    void indexesOnExistingTablesAreConcurrent() throws IOException {
        for (Migration m : enforced()) {
            String code = m.code();
            List<String> createdHere = new ArrayList<>();
            Matcher t = CREATE_TABLE.matcher(code);
            while (t.find()) {
                createdHere.add(t.group(2).toLowerCase(Locale.ROOT));
            }
            Matcher i = CREATE_INDEX.matcher(code);
            while (i.find()) {
                boolean concurrent = i.group(2) != null;
                String table = i.group(5).toLowerCase(Locale.ROOT);
                if (!concurrent && !createdHere.contains(table)) {
                    assertTrue(m.sql().contains("-- lock-ok:"), m.file() + ": mevcut '" + table
                            + "' tablosunda indeks CONCURRENTLY olmalı (ya da '-- lock-ok: <neden küçük/kısa>')");
                }
            }
        }
    }

    @Test
    void concurrentIndexMigrationsContainNothingElse() throws IOException {
        // CONCURRENTLY transaction içinde çalışamaz; Flyway böyle bir dosyayı transaction'sız çalıştırır.
        // Aynı dosyada başka DDL olursa yarıda kalan değişiklik geri alınamaz, bu yüzden tek iş = tek dosya.
        for (Migration m : enforced()) {
            String code = m.code();
            if (!code.toUpperCase(Locale.ROOT).contains("CONCURRENTLY")) {
                continue;
            }
            for (String stmt : code.split(";")) {
                String s = stmt.strip().toUpperCase(Locale.ROOT);
                if (s.isEmpty()) {
                    continue;
                }
                boolean allowed = s.matches("(?s)(CREATE\\s+(UNIQUE\\s+)?INDEX|DROP\\s+INDEX|REINDEX\\s+INDEX)\\s+CONCURRENTLY\\b.*")
                        || s.matches("(?s)SET\\s+(LOCK_TIMEOUT|STATEMENT_TIMEOUT)\\b.*");
                assertTrue(allowed, m.file() + ": CONCURRENTLY dosyasında yalnızca indeks ifadeleri olabilir: " + s);
                assertTrue(!s.startsWith("SET LOCAL"), m.file() + ": transaction'sız dosyada SET LOCAL etkisizdir");
            }
        }
    }

    /**
     * Belgede söylediğimiz davranışın kanıtı: CONCURRENTLY içeren bir dosyayı Flyway transaction'sız
     * kendiliğinden çalıştırır ve sonraki normal migrasyonlar etkilenmez.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "HASHMARK_TEST_DB_URL", matches = ".+")
    void flywayRunsConcurrentIndexMigrationOutsideTransaction() {
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("HASHMARK_TEST_DB_URL"),
                System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", ""));
        String schema = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JdbcTemplate admin = new JdbcTemplate(ds);
        admin.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration", "classpath:db/migration-convention-sample")
                    .initSql("SET statement_timeout = 0; SET lock_timeout = '5s'")
                    // application.yml: spring.flyway.postgresql.transactional-lock=false ile aynı
                    .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                    .load().migrate();
            Boolean valid = admin.queryForObject(
                    "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid " +
                    "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = 'idx_sample_concurrent'",
                    Boolean.class, schema);
            assertEquals(Boolean.TRUE, valid);
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
