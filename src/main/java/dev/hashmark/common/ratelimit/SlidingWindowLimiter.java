package dev.hashmark.common.ratelimit;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Kayan pencereli, bellek içi sayaç (tek instance için; çoklu instance'ta ortak depo Faz 4). Anahtar başına
 * pencere içindeki istek zamanlarını tutar. Anahtar sayısı eşiği aşınca boşalan pencereler temizlenir; böylece
 * rastgele IP/anahtar yağmuru belleği sınırsız büyütemez.
 */
public class SlidingWindowLimiter {

    private static final int PURGE_THRESHOLD = 10_000;

    private final LongSupplier clock;
    private final Map<String, Window> hits = new ConcurrentHashMap<>();

    private static final class Window {
        final ArrayDeque<Long> times = new ArrayDeque<>();
        final long windowMs;

        Window(long windowMs) {
            this.windowMs = windowMs;
        }
    }

    public SlidingWindowLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    public SlidingWindowLimiter() {
        this(System::currentTimeMillis);
    }

    /**
     * @return izin varsa 0; aksi halde pencerenin açılmasına kalan süre (ms). {@code limit <= 0} sınırsızdır.
     */
    public long tryAcquire(String key, int limit, long windowMs) {
        if (limit <= 0) {
            return 0;
        }
        long now = clock.getAsLong();
        if (hits.size() > PURGE_THRESHOLD) {
            hits.entrySet().removeIf(e -> {
                Window w = e.getValue();
                synchronized (w) {
                    return w.times.isEmpty() || now - w.times.peekLast() > w.windowMs;
                }
            });
        }
        Window window = hits.computeIfAbsent(key, k -> new Window(windowMs));
        synchronized (window) {
            while (!window.times.isEmpty() && now - window.times.peekFirst() >= windowMs) {
                window.times.pollFirst();
            }
            if (window.times.size() >= limit) {
                return Math.max(1, windowMs - (now - window.times.peekFirst()));
            }
            window.times.addLast(now);
            return 0;
        }
    }

    int keyCount() {
        return hits.size();
    }
}
