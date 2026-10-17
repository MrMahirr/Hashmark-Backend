package dev.hashmark.consent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Onay metinleri: {@code consent/policy-v<N>.<dil>.txt} ve {@code consent/private-repo-v<N>.<dil>.txt}.
 * Metnin SHA-256'sı onay kaydında saklanır; kullanıcıya gösterilen metinle kaydedilen metin aynı dosyadır.
 * Satır sonları normalize edilir (Windows checkout'u özeti değiştirmesin).
 */
@Component
public class ConsentPolicyCatalog {

    public static final List<String> LANGUAGES = List.of("tr", "en");

    public record Text(String kind, int version, String language, String body, String hash) {}

    private final Map<String, Text> cache = new ConcurrentHashMap<>();

    public Text policy(int version, String language) {
        return load("policy", version, language);
    }

    public Text privateRepo(int version, String language) {
        return load("private-repo", version, language);
    }

    public static String normalizeLanguage(String language) {
        return language != null && LANGUAGES.contains(language.toLowerCase()) ? language.toLowerCase() : "tr";
    }

    private Text load(String kind, int version, String language) {
        String lang = normalizeLanguage(language);
        return cache.computeIfAbsent(kind + version + lang, k -> {
            String path = "consent/" + kind + "-v" + version + "." + lang + ".txt";
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
                return new Text(kind, version, lang, body, sha256(body));
            } catch (IOException e) {
                throw new IllegalStateException("Onay metni bulunamadı: " + path, e);
            }
        });
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
