package dev.hashmark.debt.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Borcun GitHub'daki yerine işaret eden kalıcı bağlantı. */
public final class PermalinkBuilder {

    private PermalinkBuilder() {
    }

    /**
     * @param ref commit sha; bilinmiyorsa {@code HEAD} kullanılır (varsayılan dalın ucu)
     */
    public static String build(String repoFullName, String ref, String filePath, Integer lineNo) {
        if (repoFullName == null || filePath == null) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (String segment : filePath.split("/")) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return "https://github.com/" + repoFullName + "/blob/"
                + (ref == null || ref.isBlank() ? "HEAD" : ref) + "/" + path
                + (lineNo != null && lineNo > 0 ? "#L" + lineNo : "");
    }
}
