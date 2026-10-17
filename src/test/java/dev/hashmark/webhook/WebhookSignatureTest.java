package dev.hashmark.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureTest {

    private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchesGitHubDocumentationVector() {
        // docs.github.com → "Validating webhook deliveries" test değerleri
        String expected = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

        assertEquals(expected, WebhookSignature.sign("It's a Secret to Everybody", BODY));
        assertTrue(WebhookSignature.isValid("It's a Secret to Everybody", BODY, expected));
        assertTrue(WebhookSignature.isValid("It's a Secret to Everybody", BODY, expected.toUpperCase().replace("SHA256", "sha256")));
    }

    @Test
    void rejectsMissingMalformedOrForeignSignatures() {
        String good = WebhookSignature.sign("s", BODY);

        assertFalse(WebhookSignature.isValid("s", BODY, null));
        assertFalse(WebhookSignature.isValid("s", BODY, ""));
        assertFalse(WebhookSignature.isValid("s", BODY, good.substring("sha256=".length())));
        assertFalse(WebhookSignature.isValid("s", BODY, "sha1=" + good.substring(7)));
        assertFalse(WebhookSignature.isValid("other", BODY, good));
        assertFalse(WebhookSignature.isValid("s", "Hello, World?".getBytes(StandardCharsets.UTF_8), good));
        assertFalse(WebhookSignature.isValid(null, BODY, good));
    }

    @Test
    void secretsAreRandomHex() {
        String a = WebhookSignature.newSecret();

        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
        assertNotEquals(a, WebhookSignature.newSecret());
    }
}
