package dev.hashmark.common.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.function.Supplier;

/**
 * Veritabanı satır güvenliği (RLS, V11) için iş parçacığının kimin adına çalıştığı. Çözüm sırası:
 * açık kapsam ({@link #asUser}/{@link #asSystem}) → HTTP isteğindeki JWT kimliği → hiçbiri (politika hiçbir
 * satır göstermez). Kullanıcılar arası çalışan her iş (kuyruk, saklama, haftalık rapor, metrik, giriş öncesi
 * akış) {@link #asSystem} ile **açıkça** işaretlenir; işaretsiz yol veri göremez.
 */
public final class TenantContext {

    /** userId == null ve system == false: kapsam yok. */
    public record Scope(Long userId, boolean system) {
        static final Scope NONE = new Scope(null, false);
        static final Scope SYSTEM = new Scope(null, true);
    }

    private static final ThreadLocal<Scope> EXPLICIT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Scope current() {
        Scope explicit = EXPLICIT.get();
        if (explicit != null) {
            return explicit;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long userId) {
            return new Scope(userId, false);
        }
        return Scope.NONE;
    }

    public static <T> T asSystem(Supplier<T> work) {
        return with(Scope.SYSTEM, work);
    }

    public static void asSystem(Runnable work) {
        with(Scope.SYSTEM, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T asUser(long userId, Supplier<T> work) {
        return with(new Scope(userId, false), work);
    }

    public static void asUser(long userId, Runnable work) {
        with(new Scope(userId, false), () -> {
            work.run();
            return null;
        });
    }

    private static <T> T with(Scope scope, Supplier<T> work) {
        Scope previous = EXPLICIT.get();
        EXPLICIT.set(scope);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                EXPLICIT.remove();
            } else {
                EXPLICIT.set(previous);
            }
        }
    }
}
