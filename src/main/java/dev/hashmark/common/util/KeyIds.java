package dev.hashmark.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Anahtar dönüşü için ortak yardımcılar. Anahtar kimliği (kid) anahtarın SHA-256 özetinin ilk 4 baytıdır:
 * yapılandırmadan deterministik çıkar (ayrı bir ad tutmaya gerek yok) ve anahtar hakkında kullanışlı bilgi
 * vermez (256 bit anahtarın 32 bitlik özeti).
 */
public final class KeyIds {

    private KeyIds() {
    }

    public static String of(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(Arrays.copyOf(digest, 4));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String of(String secret) {
        return of(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Virgülle ayrılmış önceki anahtar listesi; boşlar atılır. */
    public static List<String> splitSecrets(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
