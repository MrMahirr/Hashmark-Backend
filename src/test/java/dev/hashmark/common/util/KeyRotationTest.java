package dev.hashmark.common.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Anahtar dönüşü (docs/KEY_ROTATION.md): kid + çift anahtar, eski biçimle uyumluluk. */
public class KeyRotationTest {

    private static final String JWT_OLD = "old-jwt-secret-old-jwt-secret-old-jwt-secret-0001";
    private static final String JWT_NEW = "new-jwt-secret-new-jwt-secret-new-jwt-secret-0002";
    private static final String ATTACKER = "attacker-secret-attacker-secret-attacker-secret!!";
    private static final String ENC_OLD = "12345678901234567890123456789012";
    private static final String ENC_NEW = "base64:" + Base64.getEncoder().encodeToString(
            "abcdefghijklmnopqrstuvwxyz012345".getBytes(StandardCharsets.UTF_8));

    private static JwtUtil jwt(String secret, String previous) {
        return new JwtUtil(secret, previous, 900000, 604800000);
    }

    static AesEncryptionUtil aes(String secret, String previous) {
        AesEncryptionUtil util = new AesEncryptionUtil();
        ReflectionTestUtils.setField(util, "secret", secret);
        ReflectionTestUtils.setField(util, "previousSecrets", previous);
        util.init();
        return util;
    }

    private static String header(String token) {
        return new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
    }

    // ---- JWT ----

    @Test
    void tokensCarryKeyId() {
        JwtUtil util = jwt(JWT_NEW, "");
        assertTrue(header(util.generateAccessToken(1L)).contains("\"kid\":\"" + util.currentKeyId() + "\""));
        assertTrue(header(util.generateRefreshToken(1L)).contains("\"kid\""));
        assertEquals(KeyIds.of(JWT_NEW), util.currentKeyId());
    }

    @Test
    void tokenSignedWithPreviousKeyStillValidDuringRotation() {
        String oldAccess = jwt(JWT_OLD, "").generateAccessToken(7L);
        String oldRefresh = jwt(JWT_OLD, "").generateRefreshToken(7L);

        JwtUtil rotated = jwt(JWT_NEW, JWT_OLD);
        assertTrue(rotated.validateAccessToken(oldAccess));
        assertEquals(7L, rotated.parseRefreshToken(oldRefresh).orElseThrow().userId());
        // Yeni token'lar yeni anahtarla imzalanır
        assertTrue(header(rotated.generateAccessToken(7L)).contains(KeyIds.of(JWT_NEW)));
    }

    @Test
    void previousKeyRemovedMeansOldTokensRejected() {
        String oldAccess = jwt(JWT_OLD, "").generateAccessToken(7L);
        assertFalse(jwt(JWT_NEW, "").validateAccessToken(oldAccess));
    }

    @Test
    void legacyTokenWithoutKidIsVerifiedWithCurrentOrPreviousKey() {
        String legacy = Jwts.builder().subject("9").claim(JwtUtil.TYPE_CLAIM, JwtUtil.TYPE_ACCESS)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(JWT_OLD.getBytes(StandardCharsets.UTF_8))).compact();
        assertFalse(header(legacy).contains("kid"));
        assertTrue(jwt(JWT_OLD, "").validateAccessToken(legacy));
        assertTrue(jwt(JWT_NEW, JWT_OLD).validateAccessToken(legacy));
        assertFalse(jwt(JWT_NEW, "").validateAccessToken(legacy));
    }

    @Test
    void kidDoesNotLetAForeignKeySignTokens() {
        JwtUtil util = jwt(JWT_NEW, "");
        // Saldırgan geçerli kid'i yazar ama kendi anahtarıyla imzalar
        String forged = Jwts.builder().header().keyId(util.currentKeyId()).and()
                .subject("1").claim(JwtUtil.TYPE_CLAIM, JwtUtil.TYPE_ACCESS)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(ATTACKER.getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertFalse(util.validateAccessToken(forged));
        String unknownKid = Jwts.builder().header().keyId("deadbeef").and().subject("1")
                .claim(JwtUtil.TYPE_CLAIM, JwtUtil.TYPE_ACCESS)
                .signWith(Keys.hmacShaKeyFor(ATTACKER.getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertFalse(util.validateAccessToken(unknownKid));
    }

    @Test
    void unsignedTokenRejected() {
        String unsigned = Jwts.builder().subject("1").claim(JwtUtil.TYPE_CLAIM, JwtUtil.TYPE_ACCESS).compact();
        assertFalse(jwt(JWT_NEW, "").validateAccessToken(unsigned));
    }

    // ---- Şifreleme ----

    /** Dönüş desteğinden önceki biçim: base64(iv||şifreli), önek yok. */
    public static String legacyEncrypt(String secret, String plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    @Test
    void newValuesAreVersionedWithKeyId() {
        AesEncryptionUtil util = aes(ENC_NEW, "");
        String value = util.encrypt("gho_x");
        assertTrue(value.startsWith("v2:" + util.currentKeyId() + ":"));
        assertTrue(util.isCurrent(value));
        assertEquals("gho_x", util.decrypt(value));
    }

    @Test
    void legacyValuesStillDecrypt() throws Exception {
        String legacy = legacyEncrypt(ENC_OLD, "gho_legacy");
        assertEquals("gho_legacy", aes(ENC_OLD, "").decrypt(legacy));
        assertEquals("gho_legacy", aes(ENC_NEW, ENC_OLD).decrypt(legacy));
        assertFalse(aes(ENC_NEW, ENC_OLD).isCurrent(legacy));
    }

    @Test
    void rotationReencryptsUnderCurrentKey() throws Exception {
        String underOld = aes(ENC_OLD, "").encrypt("gho_old");
        AesEncryptionUtil rotated = aes(ENC_NEW, ENC_OLD);
        assertEquals("gho_old", rotated.decrypt(underOld));
        assertFalse(rotated.isCurrent(underOld));

        String re = rotated.reencrypt(underOld);
        assertTrue(rotated.isCurrent(re));
        assertNotEquals(underOld, re);
        // Eski anahtar listeden çıkınca da okunur
        assertEquals("gho_old", aes(ENC_NEW, "").decrypt(re));
        // Zaten güncelse dokunulmaz
        assertEquals(re, rotated.reencrypt(re));
        assertEquals("gho_legacy",
                aes(ENC_NEW, "").decrypt(rotated.reencrypt(legacyEncrypt(ENC_OLD, "gho_legacy"))));
    }

    @Test
    void valueUnderRemovedKeyFailsClearly() {
        String underOld = aes(ENC_OLD, "").encrypt("gho_old");
        RuntimeException e = assertThrows(RuntimeException.class, () -> aes(ENC_NEW, "").decrypt(underOld));
        assertTrue(e.getMessage().contains("unknown key id"));
    }

    @Test
    void base64KeysMustBe32Bytes() {
        assertThrows(IllegalArgumentException.class,
                () -> aes("base64:" + Base64.getEncoder().encodeToString(new byte[16]), ""));
        assertThrows(IllegalArgumentException.class, () -> aes("base64:not*base64", ""));
        assertThrows(IllegalArgumentException.class, () -> aes("short", ""));
        assertThrows(IllegalArgumentException.class, () -> aes(ENC_NEW, "too-short-previous"));
    }
}
