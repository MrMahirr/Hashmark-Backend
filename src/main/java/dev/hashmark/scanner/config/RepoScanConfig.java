package dev.hashmark.scanner.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Depo kökündeki {@code .hashmark.yml}: taramanın depo içinden yapılandırılması.
 *
 * <pre>
 * include:                 # verilirse yalnızca eşleşen yollar taranır
 *   - src/
 * exclude:                 # .hashmarkignore ile aynı sözdizimi, onunla birleştirilir
 *   - docs/
 *   - "*.gen.ts"
 * labels:
 *   custom: [SECURITY, OPTIMIZE]   # borç sayılan ek etiketler
 *   disable: [NOTE, INFO]          # kaydedilmeyen etiketler
 * pr:                              # pull request kontrolü (otomatik tarama açıkken)
 *   comment: true                  # PR'a borç raporu yorumu (varsayılan: açık)
 *   status: true                   # commit durumu "hashmark/debt-budget" (varsayılan: açık)
 *   budget:
 *     max-new: 5                   # PR'ın ekleyebileceği en fazla yeni borç (yoksa sınırsız)
 *     labels: {FIXME: 0, HACK: 0}  # etiket başına sınır
 * </pre>
 *
 * <p>Dosya hatalıysa tarama durmaz: geçersiz kısım yok sayılır, sebep {@link #warnings()} ile iş mesajına
 * yazılır. Hata metinlerine dosya içeriği konmaz (veri minimizasyonu); yalnızca anahtar adı ve satır.
 */
public record RepoScanConfig(List<String> include, List<String> exclude, List<String> customLabels,
                             Set<String> disabledLabels, PrSettings pr, List<String> warnings) {

    /**
     * @param maxNew      PR başına en fazla yeni borç; {@code null} = sınırsız
     * @param labelBudget etiket → en fazla yeni borç
     */
    public record PrSettings(boolean comment, boolean status, Integer maxNew, Map<String, Integer> labelBudget) {
        public static final PrSettings DEFAULT = new PrSettings(true, true, null, Map.of());

        public PrSettings {
            labelBudget = Map.copyOf(labelBudget);
        }

        public boolean hasBudget() {
            return maxNew != null || !labelBudget.isEmpty();
        }
    }

    public static final String FILE_NAME = ".hashmark.yml";

    public static final Set<String> BUILTIN_DEBT_LABELS = Set.of("TODO", "FIXME", "HACK", "XXX");
    public static final Set<String> INFO_LABELS = Set.of("NOTE", "DOC", "INFO");

    public static final RepoScanConfig DEFAULT =
            new RepoScanConfig(List.of(), List.of(), List.of(), Set.of(), PrSettings.DEFAULT, List.of());

    static final int MAX_FILE_CHARS = 64 * 1024;
    static final int MAX_PATTERNS = 100;
    static final int MAX_PATTERN_LENGTH = 200;
    static final int MAX_CUSTOM_LABELS = 10;

    /** debts.label VARCHAR(10); harfle başlar, büyük harf/rakam/alt çizgi. */
    private static final Pattern LABEL_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{1,9}$");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("version", "include", "exclude", "labels", "pr");
    private static final Set<String> LABEL_KEYS = Set.of("custom", "disable");
    private static final Set<String> PR_KEYS = Set.of("comment", "status", "budget");
    private static final Set<String> BUDGET_KEYS = Set.of("max-new", "labels");
    static final int MAX_BUDGET = 10_000;

    public RepoScanConfig {
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);
        customLabels = List.copyOf(customLabels);
        disabledLabels = Set.copyOf(disabledLabels);
        pr = pr == null ? PrSettings.DEFAULT : pr;
        warnings = List.copyOf(warnings);
    }

    /** Dosya yoksa ({@code null}) varsayılan yapılandırma. */
    public static RepoScanConfig parse(String content) {
        if (content == null || content.isBlank()) {
            return DEFAULT;
        }
        List<String> warnings = new ArrayList<>();

        Object root;
        try {
            root = yaml().load(content);
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark();
            return invalid(mark != null ? "invalid YAML at line " + (mark.getLine() + 1) : "invalid YAML");
        } catch (YAMLException e) {
            // ör. boyut / takma ad sınırı; mesaj içerik parçası taşıyabilir, kullanılmaz
            return invalid("invalid or too large YAML");
        }
        if (root == null) {
            return DEFAULT;
        }
        if (!(root instanceof Map<?, ?> map)) {
            return invalid("top level must be a mapping");
        }

        for (Object key : map.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(String.valueOf(key))) {
                warnings.add("unknown key '" + clip(String.valueOf(key)) + "'");
            }
        }
        Object version = map.get("version");
        if (version != null && !"1".equals(String.valueOf(version))) {
            warnings.add("unsupported version " + clip(String.valueOf(version)) + ", reading as version 1");
        }

        List<String> include = patterns(map.get("include"), "include", warnings);
        List<String> exclude = patterns(map.get("exclude"), "exclude", warnings);

        List<String> custom = new ArrayList<>();
        Set<String> disabled = new LinkedHashSet<>();
        Object labels = map.get("labels");
        if (labels != null) {
            if (labels instanceof Map<?, ?> labelMap) {
                for (Object key : labelMap.keySet()) {
                    if (!LABEL_KEYS.contains(String.valueOf(key))) {
                        warnings.add("unknown key 'labels." + clip(String.valueOf(key)) + "'");
                    }
                }
                custom = customLabels(labelMap.get("custom"), warnings);
                disabled = disabledLabels(labelMap.get("disable"), custom, warnings);
            } else {
                warnings.add("'labels' must be a mapping");
            }
        }
        PrSettings pr = prSettings(map.get("pr"), custom, warnings);
        return new RepoScanConfig(include, exclude, custom, disabled, pr, warnings);
    }

    private static PrSettings prSettings(Object value, List<String> custom, List<String> warnings) {
        if (value == null) {
            return PrSettings.DEFAULT;
        }
        if (!(value instanceof Map<?, ?> map)) {
            warnings.add("'pr' must be a mapping");
            return PrSettings.DEFAULT;
        }
        unknownKeys(map, PR_KEYS, "pr.", warnings);
        boolean comment = bool(map.get("comment"), true, "pr.comment", warnings);
        boolean status = bool(map.get("status"), true, "pr.status", warnings);

        Integer maxNew = null;
        Map<String, Integer> labelBudget = new java.util.TreeMap<>();
        Object budget = map.get("budget");
        if (budget instanceof Map<?, ?> budgetMap) {
            unknownKeys(budgetMap, BUDGET_KEYS, "pr.budget.", warnings);
            maxNew = budgetValue(budgetMap.get("max-new"), "pr.budget.max-new", warnings);
            Object labels = budgetMap.get("labels");
            if (labels instanceof Map<?, ?> labelMap) {
                for (Map.Entry<?, ?> e : labelMap.entrySet()) {
                    String name = String.valueOf(e.getKey()).trim().toUpperCase(Locale.ROOT);
                    if (!BUILTIN_DEBT_LABELS.contains(name) && !custom.contains(name)) {
                        warnings.add("pr.budget.labels: '" + clip(String.valueOf(e.getKey())) + "' is not a debt label");
                        continue;
                    }
                    Integer limit = budgetValue(e.getValue(), "pr.budget.labels." + name, warnings);
                    if (limit != null) {
                        labelBudget.put(name, limit);
                    }
                }
            } else if (labels != null) {
                warnings.add("'pr.budget.labels' must be a mapping");
            }
        } else if (budget != null) {
            warnings.add("'pr.budget' must be a mapping");
        }
        return new PrSettings(comment, status, maxNew, labelBudget);
    }

    private static void unknownKeys(Map<?, ?> map, Set<String> allowed, String prefix, List<String> warnings) {
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                warnings.add("unknown key '" + prefix + clip(String.valueOf(key)) + "'");
            }
        }
    }

    private static boolean bool(Object value, boolean fallback, String key, List<String> warnings) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        warnings.add("'" + key + "' must be true or false");
        return fallback;
    }

    private static Integer budgetValue(Object value, String key, List<String> warnings) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer n && n >= 0 && n <= MAX_BUDGET) {
            return n;
        }
        warnings.add("'" + key + "' must be a whole number between 0 and " + MAX_BUDGET);
        return null;
    }

    public boolean isEmpty() {
        return include.isEmpty() && exclude.isEmpty() && customLabels.isEmpty() && disabledLabels.isEmpty();
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(MAX_FILE_CHARS);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(10);
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

    /** Kullanılamayan dosya: varsayılan yapılandırma + sebep. */
    public static RepoScanConfig invalid(String reason) {
        return new RepoScanConfig(List.of(), List.of(), List.of(), Set.of(), PrSettings.DEFAULT,
                List.of(reason + "; file ignored"));
    }

    private static List<String> patterns(Object value, String key, List<String> warnings) {
        List<String> out = new ArrayList<>();
        for (String item : stringList(value, key, warnings)) {
            String pattern = item.trim();
            if (pattern.isEmpty() || pattern.startsWith("#")) {
                continue;
            }
            if (pattern.length() > MAX_PATTERN_LENGTH || pattern.contains("\n")) {
                warnings.add("'" + key + "' entry too long, skipped");
                continue;
            }
            if (pattern.startsWith("!")) {
                warnings.add("'" + key + "': negation (!) is not supported, entry skipped");
                continue;
            }
            if (out.size() >= MAX_PATTERNS) {
                warnings.add("'" + key + "' has more than " + MAX_PATTERNS + " entries, rest ignored");
                break;
            }
            out.add(pattern);
        }
        return out;
    }

    private static List<String> customLabels(Object value, List<String> warnings) {
        List<String> out = new ArrayList<>();
        for (String item : stringList(value, "labels.custom", warnings)) {
            String name = item.trim().toUpperCase(Locale.ROOT);
            if (!LABEL_NAME.matcher(name).matches()) {
                warnings.add("custom label '" + clip(item) + "' is invalid (2-10 chars: letters, digits, _)");
            } else if (BUILTIN_DEBT_LABELS.contains(name) || INFO_LABELS.contains(name)) {
                warnings.add("custom label " + name + " is built in, skipped");
            } else if (out.contains(name)) {
                continue;
            } else if (out.size() >= MAX_CUSTOM_LABELS) {
                warnings.add("more than " + MAX_CUSTOM_LABELS + " custom labels, rest ignored");
                break;
            } else {
                out.add(name);
            }
        }
        return out;
    }

    private static Set<String> disabledLabels(Object value, List<String> custom, List<String> warnings) {
        Set<String> out = new LinkedHashSet<>();
        for (String item : stringList(value, "labels.disable", warnings)) {
            String name = item.trim().toUpperCase(Locale.ROOT);
            if (BUILTIN_DEBT_LABELS.contains(name) || INFO_LABELS.contains(name) || custom.contains(name)) {
                out.add(name);
            } else {
                warnings.add("cannot disable unknown label '" + clip(item) + "'");
            }
        }
        return out;
    }

    /** Tek değer de liste sayılır ({@code exclude: docs/}). */
    private static List<String> stringList(Object value, String key, List<String> warnings) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String s) {
            return List.of(s);
        }
        if (!(value instanceof List<?> list)) {
            warnings.add("'" + key + "' must be a list of strings");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String || item instanceof Number) {
                out.add(String.valueOf(item));
            } else if (item != null) {
                warnings.add("'" + key + "' must contain only strings");
            }
        }
        return out;
    }

    private static String clip(String value) {
        String oneLine = value.replaceAll("[\\r\\n\\t]", " ");
        return oneLine.length() > 30 ? oneLine.substring(0, 29) + "…" : oneLine;
    }
}
