package dev.hashmark.scanner.service;

import dev.hashmark.scanner.config.RepoScanConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parser'ın tanıdığı etiketler: yerleşikler + depo yapılandırmasındaki özel etiketler.
 * {@code NOTE/DOC/INFO} dışındaki her etiket borçtur (özel etiketler dahil).
 */
public final class LabelRules {

    private static final List<String> BUILTIN_ORDER =
            List.of("TODO", "FIXME", "HACK", "XXX", "NOTE", "DOC", "INFO");

    // BUILTIN_ORDER'dan sonra gelmeli (statik başlatma sırası)
    public static final LabelRules DEFAULT = new LabelRules(List.of(), Set.of());

    private final Set<String> custom;
    private final Set<String> disabled;
    private final Pattern explicitLabel;

    private LabelRules(List<String> customLabels, Set<String> disabledLabels) {
        this.custom = Set.copyOf(customLabels);
        this.disabled = Set.copyOf(disabledLabels);
        List<String> names = new ArrayList<>(BUILTIN_ORDER);
        names.addAll(customLabels);
        // @todo / TODO(mahir): / FIXME[P1] - / NOTE ...
        this.explicitLabel = Pattern.compile(
                "^@?(" + String.join("|", names.stream().map(Pattern::quote).toList()) + ")"
                        + "(?![A-Za-z0-9_])\\s*(?:[(\\[]([^)\\]]{0,60})[)\\]])?\\s*(:|-|–|—)?\\s*(.*)$",
                Pattern.CASE_INSENSITIVE);
    }

    public static LabelRules of(RepoScanConfig config) {
        if (config == null || (config.customLabels().isEmpty() && config.disabledLabels().isEmpty())) {
            return DEFAULT;
        }
        return new LabelRules(config.customLabels(), config.disabledLabels());
    }

    public static boolean isDebt(String label) {
        return label != null && !RepoScanConfig.INFO_LABELS.contains(label);
    }

    public boolean isCustom(String label) {
        return custom.contains(label);
    }

    public boolean isEnabled(String label) {
        return !disabled.contains(label);
    }

    Pattern explicitLabel() {
        return explicitLabel;
    }
}
