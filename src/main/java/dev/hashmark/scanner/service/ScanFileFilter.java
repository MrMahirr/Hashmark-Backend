package dev.hashmark.scanner.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hangi dosyaların taranacağına karar verir: desteklenen uzantı, hariç tutulan klasörler,
 * üretilmiş/minify dosyalar ve repo kökündeki {@code .hashmarkignore} kuralları.
 *
 * <p>{@code .hashmarkignore} sözdizimi (gitignore'un sade bir alt kümesi):
 * <ul>
 *   <li>{@code # yorum}, boş satırlar yok sayılır</li>
 *   <li>{@code dizin/} — o adda herhangi bir dizin</li>
 *   <li>{@code *.ext}, {@code dosya.js} — herhangi bir derinlikte dosya adı eşleşmesi</li>
 *   <li>{@code src/gen/**} veya {@code /docs} — kökten itibaren yol eşleşmesi ({@code *}, {@code **}, {@code ?})</li>
 * </ul>
 * Negasyon ({@code !}) desteklenmez. {@code .hashmark.yml} içindeki {@code include}/{@code exclude} listeleri
 * de aynı sözdizimini kullanır.
 */
public final class ScanFileFilter {

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "cs", "swift", "dart",
            "ts", "tsx", "js", "jsx", "mjs", "cjs", "vue", "svelte",
            "py", "pyw", "rb", "go", "rs", "php",
            "c", "h", "cpp", "cc", "cxx", "hpp",
            "css", "scss", "less", "html", "htm",
            "sql", "sh", "bash", "zsh", "yml", "yaml", "toml");

    private static final List<String> GENERATED_MARKERS = List.of(
            ".min.", ".bundle.", ".generated.", ".pb.go", "_pb2.py", ".g.dart", ".designer.cs");

    private final Set<String> excludedDirs;
    private final List<IgnoreRule> ignoreRules;
    /** Boşsa her yol dahildir; değilse yol en az bir kurala uymalı ({@code .hashmark.yml} include). */
    private final List<IgnoreRule> includeRules;

    public ScanFileFilter(Set<String> excludedDirs, String ignoreFileContent) {
        this(excludedDirs, ignoreFileContent, List.of(), List.of());
    }

    /**
     * @param includePatterns {@code .hashmark.yml} include (aynı sözdizimi)
     * @param excludePatterns {@code .hashmark.yml} exclude; {@code .hashmarkignore} kurallarına eklenir
     */
    public ScanFileFilter(Set<String> excludedDirs, String ignoreFileContent, List<String> includePatterns,
                          List<String> excludePatterns) {
        this.excludedDirs = excludedDirs;
        this.ignoreRules = new ArrayList<>(parseIgnoreRules(ignoreFileContent));
        this.ignoreRules.addAll(parseIgnoreRules(String.join("\n", excludePatterns)));
        this.includeRules = parseIgnoreRules(String.join("\n", includePatterns));
    }

    public boolean hasSupportedExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Dosya taranmalı mı? ({@code path} repo köküne göre, '/' ayraçlı) */
    public boolean shouldScan(String path) {
        if (!hasSupportedExtension(path)) {
            return false;
        }
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (excludedDirs.contains(segments[i])) {
                return false;
            }
        }
        String lower = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        for (String marker : GENERATED_MARKERS) {
            if (lower.contains(marker)) {
                return false;
            }
        }
        for (IgnoreRule rule : ignoreRules) {
            if (rule.matches(path, segments)) {
                return false;
            }
        }
        if (includeRules.isEmpty()) {
            return true;
        }
        for (IgnoreRule rule : includeRules) {
            if (rule.matches(path, segments)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private static List<IgnoreRule> parseIgnoreRules(String content) {
        List<IgnoreRule> rules = new ArrayList<>();
        if (content == null) {
            return rules;
        }
        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            boolean dirOnly = line.endsWith("/");
            if (dirOnly) {
                line = line.substring(0, line.length() - 1);
            }
            boolean anchored = line.startsWith("/") || line.contains("/");
            if (line.startsWith("/")) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                continue;
            }
            rules.add(new IgnoreRule(globToRegex(line), anchored, dirOnly));
        }
        return rules;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private record IgnoreRule(Pattern pattern, boolean anchored, boolean dirOnly) {

        boolean matches(String path, String[] segments) {
            if (anchored) {
                // kökten itibaren: yolun kendisi veya üst dizinlerinden biri eşleşmeli
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < segments.length; i++) {
                    if (i > 0) {
                        prefix.append('/');
                    }
                    prefix.append(segments[i]);
                    boolean isFile = i == segments.length - 1;
                    if (dirOnly && isFile) {
                        continue;
                    }
                    if (pattern.matcher(prefix).matches()) {
                        return true;
                    }
                }
                return false;
            }
            for (int i = 0; i < segments.length; i++) {
                boolean isFile = i == segments.length - 1;
                if (dirOnly && isFile) {
                    continue;
                }
                if (pattern.matcher(segments[i]).matches()) {
                    return true;
                }
            }
            return false;
        }
    }
}
