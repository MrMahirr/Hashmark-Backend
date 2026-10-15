package dev.hashmark.common.mail;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hashmark e-posta şablonları: içerik parçası, ortak {@code layout.html} içine yerleştirilir.
 *
 * <ul>
 *   <li>{@code {{ad}}} — değer HTML kaçışlanarak yazılır (kullanıcı metni için güvenli).</li>
 *   <li>{@code {{{ad}}}} — değer olduğu gibi yazılır (yalnızca kodun ürettiği güvenilir HTML).</li>
 * </ul>
 * Tanımsız değişken boş string olur. Şablonlar sınıf yolundan bir kez okunup önbelleğe alınır.
 */
@Component
public class EmailTemplateEngine {

    static final String LAYOUT = "layout";
    private static final String DIR = "templates/email/";
    /** Tek geçiş: üçlü (ham) ve ikili (kaçışlı) işaretçiler; eklenen değerler yeniden taranmaz. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}}|\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * @param template içerik şablonu adı (örn. {@code weekly-report})
     * @param values   hem içerik hem düzen için değişkenler ({@code title}, {@code lang}, {@code appUrl} ...)
     */
    public String render(String template, Map<String, String> values) {
        String content = apply(load(template), values);
        Map<String, String> layoutValues = new java.util.HashMap<>(values);
        layoutValues.put("content", content);
        return apply(load(LAYOUT), layoutValues);
    }

    static String apply(String source, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(source);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            boolean raw = matcher.group(1) != null;
            String name = raw ? matcher.group(1) : matcher.group(2);
            String value = values.getOrDefault(name, "");
            String text = value == null ? "" : value;
            matcher.appendReplacement(out, Matcher.quoteReplacement(raw ? text : HtmlUtils.htmlEscape(text, "UTF-8")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String load(String name) {
        return cache.computeIfAbsent(name, key -> {
            try (var in = new ClassPathResource(DIR + key + ".html").getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Email template not found: " + key, e);
            }
        });
    }
}
