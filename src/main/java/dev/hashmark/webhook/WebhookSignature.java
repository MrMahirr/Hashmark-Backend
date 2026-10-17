package dev.hashmark.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** GitHub {@code X-Hub-Signature-256} (HMAC-SHA256, hex) üretimi ve sabit zamanlı doğrulaması. */
public final class WebhookSignature {

    private static final String PREFIX = "sha256=";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSignature() {
    }

    /** 32 rastgele bayt, hex (64 karakter). */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Başlık yoksa, biçimi bozuksa ya da imza tutmuyorsa false. */
    public static boolean isValid(String secret, byte[] body, String signatureHeader) {
        if (secret == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signatureHeader.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
