package dev.hashmark.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FingerprintUtilTest {

    @Test
    void isStableAndWhitespaceInsensitive() {
        String a = FingerprintUtil.fingerprint("TODO", "fix   the\tcache");
        String b = FingerprintUtil.fingerprint("TODO", "  fix the cache  ");

        assertEquals(a, b);
        assertEquals(32, a.length());
    }

    @Test
    void differsByLabelAndContent() {
        assertNotEquals(FingerprintUtil.fingerprint("TODO", "x"), FingerprintUtil.fingerprint("FIXME", "x"));
        assertNotEquals(FingerprintUtil.fingerprint("TODO", "x"), FingerprintUtil.fingerprint("TODO", "y"));
    }

    @Test
    void isPlainMd5OfLabelColonNormalizedContent() throws Exception {
        // V7 migrasyonundaki md5(label || ':' || btrim(regexp_replace(content, '\s+', ' ', 'g'))) ile aynı formül
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest("TODO:fix the cache".getBytes(StandardCharsets.UTF_8));
        String expected = String.format("%032x", new BigInteger(1, digest));

        assertEquals(expected, FingerprintUtil.fingerprint("TODO", " fix  the cache "));
    }

    @Test
    void handlesNulls() {
        assertEquals(FingerprintUtil.fingerprint("", ""), FingerprintUtil.fingerprint(null, null));
    }
}
