package dev.hashmark.common.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Borç yorumlarında yanlışlıkla bulunan sırları (API anahtarı, token, parola) veritabanına yazmadan önce maskeler.
 * Amaç tam bir sır tarayıcısı olmak değil; bilinen biçimleri ve `anahtar = değer` kalıplarını yakalamaktır.
 */
public final class SecretMasker {

    public static final String MASK = "[REDACTED]";

    private static final List<Pattern> TOKEN_PATTERNS = List.of(
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),            // GitHub token
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),          // GitHub fine-grained PAT
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),             // AWS access key id
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"),          // Slack
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}\\b"),                // Google API key
            Pattern.compile("\\b[sr]k_(?:live|test)_[0-9A-Za-z]{16,}\\b"),  // Stripe
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"), // JWT
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)")
    );

    /**
     * `password = abc123xyz`, `api_key: "sk-..."` gibi atamalar. Düz yazıyı (ör. "password: handling")
     * yanlışlıkla maskelememek için değerin rakam veya özel karakter içermesi şart koşulur.
     */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\\b(?:password|passwd|pwd|secret|client[_-]?secret|api[_-]?key|access[_-]?key|auth[_-]?token|access[_-]?token|token)\\b\\s*[:=]\\s*[\"']?)"
                    + "(?=[^\\s\"']*[\\d_\\-/+=!@#$%^&*])[^\\s\"']{8,}");

    private SecretMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern p : TOKEN_PATTERNS) {
            result = p.matcher(result).replaceAll(MASK);
        }
        Matcher m = KEY_VALUE.matcher(result);
        return m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + MASK));
    }
}
