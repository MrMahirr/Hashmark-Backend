package dev.hashmark.report.service;

import dev.hashmark.report.dto.HotspotResponse;
import dev.hashmark.report.repository.ReportRepository.HotspotRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dosya × etiket × öncelik satırlarından bir klasörün ısı haritasını kurar. */
public final class HotspotCalculator {

    public static final int MAX_CHILDREN = 50;
    public static final int TOP_FILES = 10;

    private HotspotCalculator() {
    }

    /** Kullanıcı girdisi: baştaki/sondaki "/" ve boş parçalar atılır; "." / ".." anlamsızdır, yok sayılır. */
    public static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : raw.trim().split("/")) {
            if (!part.isEmpty() && !part.equals(".") && !part.equals("..")) {
                parts.add(part);
            }
        }
        String path = String.join("/", parts);
        return path.length() > 500 ? "" : path;
    }

    private static final class Acc {
        final String name;
        final String path;
        final boolean directory;
        int debts;
        double weighted;
        int stale;
        final Set<String> files = new HashSet<>();

        Acc(String name, String path, boolean directory) {
            this.name = name;
            this.path = path;
            this.directory = directory;
        }

        void add(HotspotRow row, double weight) {
            debts += row.count();
            weighted += weight;
            stale += row.stale();
            files.add(row.filePath());
        }

        HotspotResponse.Node node() {
            return new HotspotResponse.Node(name, path, directory, debts, round(weighted), stale, files.size());
        }
    }

    /** Isıya, eşitlikte borç sayısına, sonra ada göre. */
    private static final Comparator<Acc> HOTTEST = Comparator.<Acc>comparingDouble(a -> -a.weighted)
            .thenComparingInt(a -> -a.debts)
            .thenComparing(a -> a.path);

    /**
     * @param path      {@link #normalizePath} ile temizlenmiş klasör yolu (boş = kök)
     * @param rows      yalnızca bu klasörün altındaki satırlar
     * @param truncated satırlar sınırda kesildi mi
     */
    public static HotspotResponse build(String path, List<HotspotRow> rows, boolean truncated) {
        String prefix = path.isEmpty() ? "" : path + "/";
        Map<String, Acc> children = new LinkedHashMap<>();
        Map<String, Acc> files = new LinkedHashMap<>();
        int total = 0;
        int stale = 0;
        double weighted = 0;

        for (HotspotRow row : rows) {
            if (!row.filePath().startsWith(prefix)) {
                continue;
            }
            double weight = row.count() * DebtWeights.labelWeight(row.label()) * DebtWeights.priorityFactor(row.priority());
            total += row.count();
            stale += row.stale();
            weighted += weight;

            String rest = row.filePath().substring(prefix.length());
            int slash = rest.indexOf('/');
            boolean directory = slash >= 0;
            String name = directory ? rest.substring(0, slash) : rest;
            String childPath = prefix + name;
            children.computeIfAbsent((directory ? "d:" : "f:") + name, k -> new Acc(name, childPath, directory))
                    .add(row, weight);
            files.computeIfAbsent(row.filePath(), k -> new Acc(lastSegment(k), k, false)).add(row, weight);
        }

        List<Acc> sortedChildren = children.values().stream().sorted(HOTTEST).toList();
        List<HotspotResponse.Node> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_CHILDREN, sortedChildren.size()); i++) {
            shown.add(sortedChildren.get(i).node());
        }
        List<HotspotResponse.Node> top = files.values().stream().sorted(HOTTEST).limit(TOP_FILES)
                .map(Acc::node).toList();

        return new HotspotResponse(path, total, round(weighted), stale, shown,
                Math.max(0, sortedChildren.size() - MAX_CHILDREN), top, truncated);
    }

    private static String lastSegment(String filePath) {
        int slash = filePath.lastIndexOf('/');
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
