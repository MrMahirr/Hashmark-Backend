package dev.hashmark.common.mail;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailTemplateEngineTest {

    @Test
    void doubleBracesEscapeAndTripleBracesAreRaw() {
        String out = EmailTemplateEngine.apply("<p>{{a}}</p><div>{{{b}}}</div>",
                Map.of("a", "<script>x</script>", "b", "<b>ok</b>"));

        assertEquals("<p>&lt;script&gt;x&lt;/script&gt;</p><div><b>ok</b></div>", out);
    }

    @Test
    void insertedValuesAreNotRescanned() {
        String out = EmailTemplateEngine.apply("{{a}}|{{{b}}}", Map.of("a", "{{b}}", "b", "{{a}}"));

        assertEquals("{{b}}|{{a}}", out);
    }

    @Test
    void undefinedVariableBecomesEmptyAndWhitespaceIsTolerated() {
        assertEquals("[]", EmailTemplateEngine.apply("[{{ missing }}]", Map.of()));
    }

    @Test
    void rendersContentInsideLayoutFromClasspath() {
        Map<String, String> values = new java.util.HashMap<>();
        for (String[] kv : new String[][]{{"lang", "tr"}, {"title", "T"}, {"preheader", "P"}, {"appUrl", "https://x.dev"},
                {"footer", "F"}, {"settingsLink", "S"}, {"heading", "Başlık"}, {"total", "5"}, {"rows", ""},
                {"delta", ""}, {"topModule", ""}}) {
            values.put(kv[0], kv[1]);
        }
        String html = new EmailTemplateEngine().render("weekly-report", values);

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("Başlık"));
        assertTrue(html.contains("https://x.dev/dashboard"));
        assertFalse(html.contains("{{"), "kalan işaretçi olmamalı");
    }
}
