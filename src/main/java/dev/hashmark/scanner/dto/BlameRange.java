package dev.hashmark.scanner.dto;

import java.time.LocalDateTime;

/** Bir dosyanın {@code startLine..endLine} satırlarını en son değiştiren commit. */
public record BlameRange(int startLine, int endLine, String commitSha, LocalDateTime authoredAt,
                         String authorLogin, String authorName) {

    public boolean contains(int line) {
        return line >= startLine && line <= endLine;
    }
}
