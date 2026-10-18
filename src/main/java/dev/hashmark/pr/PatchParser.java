package dev.hashmark.pr;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub compare yanıtındaki birleşik diff ({@code files[].patch}) içinden yeni dosyada <b>eklenen</b> satırların
 * numaralarını çıkarır. Bağlam ({@code ' '}) satırları yeni satır sayacını ilerletir, silinen ({@code '-'})
 * satırlar ilerletmez; "\ No newline at end of file" yok sayılır.
 */
public final class PatchParser {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private PatchParser() {
    }

    public static Set<Integer> addedLines(String patch) {
        Set<Integer> added = new HashSet<>();
        if (patch == null || patch.isEmpty()) {
            return added;
        }
        int next = -1;
        for (String line : patch.split("\\r?\\n", -1)) {
            Matcher hunk = HUNK.matcher(line);
            if (hunk.find()) {
                next = Integer.parseInt(hunk.group(1));
                continue;
            }
            if (next < 0) {
                continue; // ilk hunk başlığından önceki satırlar
            }
            char kind = line.isEmpty() ? ' ' : line.charAt(0);
            switch (kind) {
                case '+' -> added.add(next++);
                case '-', '\\' -> { /* yeni dosyada satır yok */ }
                default -> next++;
            }
        }
        return added;
    }
}
