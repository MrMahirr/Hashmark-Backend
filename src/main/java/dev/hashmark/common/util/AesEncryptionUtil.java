package dev.hashmark.common.util;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AES-256-GCM (rastgele 96 bit IV). Anahtar dönüşü (plan Faz 2): şifreli değer, şifreleyen anahtarın
 * kimliğini taşır — {@code v2:<kid>:<base64(iv||şifreli)>}. Çözme kid'e göre geçerli ya da önceki anahtarı
 * seçer; önekli olmayan eski değerler sırayla geçerli ve önceki anahtarlarla denenir (GCM etiketi yanlış
 * anahtarı reddeder). Eski değerleri yeniden şifreleme: {@code GithubTokenReencryptionJob}.
 * Dönüş adımları: docs/KEY_ROTATION.md.
 */
@Component
public class AesEncryptionUtil {

    static final String PREFIX = "v2:";
    private static final String BASE64_KEY_PREFIX = "base64:";

    @Value("${encryption.secret}")
    private String secret;

    @Value("${encryption.previous-secrets:}")
    private String previousSecrets;

    private String keyId;
    /** kid → anahtar; geçerli anahtar ilk sırada. */
    private final Map<String, SecretKeySpec> keysById = new LinkedHashMap<>();

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    @PostConstruct
    public void init() {
        keysById.clear();
        byte[] current = keyBytes(secret);
        keyId = KeyIds.of(current);
        keysById.put(keyId, new SecretKeySpec(current, "AES"));
        for (String previous : KeyIds.splitSecrets(previousSecrets)) {
            byte[] bytes = keyBytes(previous);
            keysById.putIfAbsent(KeyIds.of(bytes), new SecretKeySpec(bytes, "AES"));
        }
    }

    /**
     * Önerilen biçim {@code base64:<32 rastgele bayt>} ({@code openssl rand -base64 32}). Eski biçim: tam 32
     * karakterlik metin (UTF-8 baytları anahtar olur; entropisi metne bağlıdır).
     */
    static byte[] keyBytes(String value) {
        if (value != null && value.startsWith(BASE64_KEY_PREFIX)) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(value.substring(BASE64_KEY_PREFIX.length()).strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Encryption secret: invalid base64");
            }
            if (decoded.length != 32) {
                throw new IllegalArgumentException("Encryption secret must decode to exactly 32 bytes");
            }
            return decoded;
        }
        if (value == null || value.length() != 32) {
            throw new IllegalArgumentException("Encryption secret must be exactly 32 characters long (or base64:<32 bytes>)");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public String currentKeyId() {
        return keyId;
    }

    /** Değer geçerli anahtarla, güncel biçimde mi şifrelenmiş? */
    public boolean isCurrent(String cipherText) {
        return cipherText != null && cipherText.startsWith(PREFIX + keyId + ":");
    }

    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keysById.get(keyId), new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] ivAndCipherText = new byte[GCM_IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, ivAndCipherText, 0, GCM_IV_LENGTH);
            System.arraycopy(cipherText, 0, ivAndCipherText, GCM_IV_LENGTH, cipherText.length);

            return PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(ivAndCipherText);
        } catch (Exception e) {
            throw new RuntimeException("Error while encrypting data", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null) return null;
        if (cipherText.startsWith(PREFIX)) {
            int sep = cipherText.indexOf(':', PREFIX.length());
            if (sep < 0) {
                throw new RuntimeException("Error while decrypting data: malformed value");
            }
            SecretKeySpec key = keysById.get(cipherText.substring(PREFIX.length(), sep));
            if (key == null) {
                // Anahtar listeden erken çıkarılmış: encryption.previous-secrets'a geri eklenmeli
                throw new RuntimeException("Error while decrypting data: unknown key id");
            }
            return decryptWith(key, cipherText.substring(sep + 1));
        }
        // Dönüş desteğinden önceki biçim (öneksiz)
        RuntimeException last = null;
        for (SecretKeySpec key : keysById.values()) {
            try {
                return decryptWith(key, cipherText);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    /** Geçerli anahtarla yeniden şifreler; zaten günceldeyse aynı değeri döndürür. */
    public String reencrypt(String cipherText) {
        return cipherText == null || isCurrent(cipherText) ? cipherText : encrypt(decrypt(cipherText));
    }

    private static String decryptWith(SecretKeySpec key, String base64) {
        try {
            byte[] ivAndCipherText = Base64.getDecoder().decode(base64);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] actualCipherText = new byte[ivAndCipherText.length - GCM_IV_LENGTH];
            System.arraycopy(ivAndCipherText, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(ivAndCipherText, GCM_IV_LENGTH, actualCipherText, 0, actualCipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(actualCipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Error while decrypting data", e);
        }
    }
}
