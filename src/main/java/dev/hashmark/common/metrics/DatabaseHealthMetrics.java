package dev.hashmark.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Otomatik vakum ve tablo büyümesi göstergeleri (plan Faz 2: "otomatik vakum izleme").
 * Ayrı bir postgres_exporter yerine uygulama okur (yeni bileşen yok); sonuç 60 sn önbelleklenir,
 * böylece her Prometheus çekimi katalog sorgusu çalıştırmaz.
 */
@Component
public class DatabaseHealthMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthMetrics.class);

    /** Büyüyen/sık değişen tablolar; yenisi eklenince buraya da eklenir. */
    static final List<String> TABLES = List.of(
            "debts", "scan_jobs", "scan_snapshots", "notifications", "refresh_tokens", "repos", "users");

    private static final long CACHE_MS = 60_000;

    record TableStats(double liveRows, double deadRows, double totalBytes, double secondsSinceVacuum) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<String, TableStats> cache;
    private volatile long cachedAt;

    @Autowired
    public DatabaseHealthMetrics(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    DatabaseHealthMetrics(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** RLS uygulama rolü için etkin mi: süper kullanıcı/BYPASSRLS değil ve tablo sahibi değil (V11). */
    private volatile Boolean rlsEnforced;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("hashmark.db.rls.enforced", () -> {
                    Boolean enforced = rlsEnforced();
                    return enforced == null ? Double.NaN : (enforced ? 1.0 : 0.0);
                })
                .description("1: uygulama rolü RLS'e tabi (ayrı, sahip olmayan rol); 0: politika atlanıyor")
                .register(registry);
        for (String table : TABLES) {
            gauge(registry, "hashmark.db.table.live.rows", "Tablodaki canlı satır (tahmini)", table, TableStats::liveRows);
            gauge(registry, "hashmark.db.table.dead.rows", "Vakum bekleyen ölü satır", table, TableStats::deadRows);
            gauge(registry, "hashmark.db.table.size.bytes", "Tablo + indeks + TOAST boyutu", table, TableStats::totalBytes);
            gauge(registry, "hashmark.db.table.vacuum.age.seconds",
                    "Son (oto)vakumdan bu yana geçen süre; hiç vakumlanmadıysa NaN", table, TableStats::secondsSinceVacuum);
        }
    }

    private void gauge(MeterRegistry registry, String name, String description, String table,
                       ToDoubleFunction<TableStats> value) {
        Gauge.builder(name, () -> {
                    TableStats s = stats().get(table);
                    return s == null ? Double.NaN : value.applyAsDouble(s);
                })
                .description(description)
                .tag("table", table)
                .register(registry);
    }

    /** Rol çalışırken değişmez; bir kez başarıyla okunduktan sonra önbellekte kalır. */
    public Boolean rlsEnforced() {
        Boolean cached = rlsEnforced;
        if (cached != null) {
            return cached;
        }
        try {
            cached = jdbc.queryForObject(
                    "SELECT NOT (r.rolsuper OR r.rolbypassrls) AND c.relowner <> r.oid " +
                    "FROM pg_roles r, pg_class c WHERE r.rolname = current_user AND c.oid = to_regclass('debts')",
                    Boolean.class);
            rlsEnforced = cached;
            return cached;
        } catch (Exception e) {
            log.warn("RLS durumu okunamadı: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    Map<String, TableStats> stats() {
        long now = clock.millis();
        Map<String, TableStats> current = cache;
        if (current != null && now - cachedAt < CACHE_MS) {
            return current;
        }
        try {
            Map<String, TableStats> fresh = new HashMap<>();
            jdbc.query(
                    "SELECT relname, n_live_tup, n_dead_tup, pg_total_relation_size(relid) AS bytes, " +
                    "EXTRACT(EPOCH FROM (NOW() - GREATEST(last_vacuum, last_autovacuum))) AS vacuum_age " +
                    "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname::text = ANY(?)",
                    rs -> {
                        double age = rs.getDouble("vacuum_age");
                        if (rs.wasNull()) {
                            age = Double.NaN;
                        }
                        fresh.put(rs.getString("relname"), new TableStats(
                                rs.getLong("n_live_tup"), rs.getLong("n_dead_tup"), rs.getLong("bytes"), age));
                    },
                    (Object) TABLES.toArray(String[]::new));
            cache = Map.copyOf(fresh);
        } catch (Exception e) {
            log.warn("Veritabanı tablo metrikleri okunamadı: {}", e.getClass().getSimpleName());
            cache = Map.of();
        }
        cachedAt = now;
        return cache;
    }
}
