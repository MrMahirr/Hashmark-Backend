package dev.hashmark.channel;

import dev.hashmark.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelKindTest {

    @Test
    void acceptsProviderWebhookAddresses() {
        assertEquals("https://hooks.slack.com/services/T0/B0/xyz",
                ChannelKind.SLACK.validate("  https://hooks.slack.com/services/T0/B0/xyz "));
        ChannelKind.SLACK.validate("https://hooks.slack.com/triggers/T0/1/abc");
        ChannelKind.DISCORD.validate("https://discord.com/api/webhooks/123/token-abc");
        ChannelKind.DISCORD.validate("https://discordapp.com/api/webhooks/123/token");
        ChannelKind.TEAMS.validate("https://contoso.webhook.office.com/webhookb2/abc@def/IncomingWebhook/x/y");
        ChannelKind.TEAMS.validate("https://prod-12.westeurope.logic.azure.com:443/workflows/abc/triggers/manual/paths/invoke?sig=x");
    }

    @Test
    void rejectsAnythingElse() {
        String[] slackBad = {
                "http://hooks.slack.com/services/T0/B0/x",          // https değil
                "https://hooks.slack.com.evil.net/services/x",       // alan adı sonu
                "https://evil.net/hooks.slack.com/services/x",
                "https://user@hooks.slack.com/services/x",           // kullanıcı bilgisi
                "https://hooks.slack.com:8443/services/x",           // port
                "https://hooks.slack.com/api/other",                 // yol
                "https://hooks.slack.com/services/../admin",
                "https://hooks.slack.com/services/x#frag",
                "https://127.0.0.1/services/x",
                "javascript:alert(1)",
                "",
                "https://hooks.slack.com/services/" + "x".repeat(500),
        };
        for (String url : slackBad) {
            assertThrows(ApiException.class, () -> ChannelKind.SLACK.validate(url), url);
        }
        // Bir sağlayıcının adresi başka türle kaydedilemez
        assertThrows(ApiException.class, () -> ChannelKind.DISCORD.validate("https://hooks.slack.com/services/x"));
        assertThrows(ApiException.class, () -> ChannelKind.TEAMS.validate("https://webhook.office.com/x"));
        assertThrows(ApiException.class, () -> ChannelKind.TEAMS.validate("https://office.com.logic.azure.com.attacker.io/x"));
    }

    @Test
    void errorMessageAndHintNeverContainTheSecretPath() {
        ApiException e = assertThrows(ApiException.class,
                () -> ChannelKind.SLACK.validate("https://evil.net/services/SECRET123"));
        assertFalse(e.getMessage().contains("SECRET123"));

        String hint = ChannelKind.hint("https://hooks.slack.com/services/T0/B0/abcdefSECRETwxyz");
        assertEquals("hooks.slack.com/…wxyz", hint);
        assertFalse(hint.contains("SECRET"));
    }

    @Test
    void parsesKindCaseInsensitively() {
        assertEquals(ChannelKind.TEAMS, ChannelKind.parse(" teams "));
        assertThrows(ApiException.class, () -> ChannelKind.parse("email"));
        assertThrows(ApiException.class, () -> ChannelKind.parse(null));
        assertTrue(ChannelKind.values().length == 3);
    }
}
