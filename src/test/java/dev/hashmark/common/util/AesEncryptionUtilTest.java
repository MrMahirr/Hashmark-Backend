package dev.hashmark.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AesEncryptionUtilTest {

    private AesEncryptionUtil aesEncryptionUtil;
    private final String validSecret = "12345678901234567890123456789012";

    @BeforeEach
    void setUp() {
        aesEncryptionUtil = new AesEncryptionUtil();
        ReflectionTestUtils.setField(aesEncryptionUtil, "secret", validSecret);
        aesEncryptionUtil.init();
    }

    @Test
    void testEncryptAndDecrypt() {
        String plainText = "ghp_AbCdEf1234567890";
        
        String cipherText = aesEncryptionUtil.encrypt(plainText);
        
        assertNotNull(cipherText);
        assertNotEquals(plainText, cipherText);
        
        String decryptedText = aesEncryptionUtil.decrypt(cipherText);
        
        assertEquals(plainText, decryptedText);
    }

    @Test
    void testEncryptNull() {
        assertNull(aesEncryptionUtil.encrypt(null));
    }

    @Test
    void testDecryptNull() {
        assertNull(aesEncryptionUtil.decrypt(null));
    }

    @Test
    void testDifferentEncryptionForSameText() {
        String plainText = "same_text";
        String cipher1 = aesEncryptionUtil.encrypt(plainText);
        String cipher2 = aesEncryptionUtil.encrypt(plainText);
        
        // Due to random IV, they should be different
        assertNotEquals(cipher1, cipher2);
        
        // But both should decrypt to the same text
        assertEquals(plainText, aesEncryptionUtil.decrypt(cipher1));
        assertEquals(plainText, aesEncryptionUtil.decrypt(cipher2));
    }
}
