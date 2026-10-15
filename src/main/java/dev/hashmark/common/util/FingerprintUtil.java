package dev.hashmark.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Borç kimliği için içerik parmak izi. V7 migrasyonundaki SQL formülüyle birebir aynı olmalıdır:
 * {@code md5(label || ':' || btrim(regexp_replace(content, '\s+', ' ', 'g')))}.
 */
public final class FingerprintUtil {

    private FingerprintUtil() {
    }

    public static String normalize(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").trim();
    }

    public static String fingerprint(String label, String content) {
        String input = (label == null ? "" : label) + ":" + normalize(content);
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md5.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
