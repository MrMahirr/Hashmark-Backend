package dev.hashmark.debt.dto;

import java.util.Map;

/** Bir taramanın veritabanına uygulanmasının özeti. */
/** @param reopened elle "çözüldü" işaretlenip kod hâlâ bulunduğu için yeniden açılan borç sayısı */
public record ReplaceResult(int added, int resolved, int openTotal, Map<String, Integer> openByLabel, int reopened) {
}
