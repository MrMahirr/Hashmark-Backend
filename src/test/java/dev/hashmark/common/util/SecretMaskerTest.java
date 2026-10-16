package dev.hashmark.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretMaskerTest {

    @Test
    void masksKnownTokenFormats() {
        String out = SecretMasker.mask("remove ghp_abcdefghijklmnopqrstuvwxyz0123456789 and AKIAABCDEFGHIJKLMNOP");
        assertFalse(out.contains("ghp_"));
        assertFalse(out.contains("AKIA"));
        assertTrue(out.contains(SecretMasker.MASK));
    }

    @Test
    void masksKeyValueAssignments() {
        assertEquals("move password = " + SecretMasker.MASK + " to vault",
                SecretMasker.mask("move password = hunter2hunter2 to vault"));
        assertEquals("api_key: \"" + SecretMasker.MASK + "\"", SecretMasker.mask("api_key: \"sk-12345678\""));
    }

    @Test
    void leavesPlainProseAlone() {
        String text = "fix token: handling of expired sessions";
        assertEquals(text, SecretMasker.mask(text));
        assertEquals("password: handling", SecretMasker.mask("password: handling"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertNull(SecretMasker.mask(null));
        assertEquals("", SecretMasker.mask(""));
    }
}
