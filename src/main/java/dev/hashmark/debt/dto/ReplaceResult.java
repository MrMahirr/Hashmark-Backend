package dev.hashmark.debt.dto;

import java.util.Map;

/** Bir taramanın veritabanına uygulanmasının özeti. */
public record ReplaceResult(int added, int resolved, int openTotal, Map<String, Integer> openByLabel) {
}
