package dev.hashmark.scanner.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TarArchiveReaderTest {

    private TarArchiveReader reader(byte[] tar) {
        return new TarArchiveReader(new ByteArrayInputStream(tar));
    }

    @Test
    void readsRegularFilesAndSkipsDirectoriesAndGlobalHeaders() throws IOException {
        byte[] tar = new TarTestSupport()
                .globalHeader()
                .directory("repo-abc/")
                .directory("repo-abc/src/")
                .file("repo-abc/src/A.java", "class A {}")
                .file("repo-abc/B.java", "x".repeat(1500)) // 512 sınırını aşan veri + dolgu
                .tar();

        List<String> paths = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        try (TarArchiveReader r = reader(tar)) {
            TarArchiveReader.Entry e;
            while ((e = r.next()) != null) {
                paths.add(e.path());
                contents.add(new String(r.readEntry(), StandardCharsets.UTF_8));
            }
        }

        assertEquals(List.of("repo-abc/src/A.java", "repo-abc/B.java"), paths);
        assertEquals("class A {}", contents.get(0));
        assertEquals(1500, contents.get(1).length());
    }

    @Test
    void skippingUnreadEntriesKeepsStreamAligned() throws IOException {
        byte[] tar = new TarTestSupport()
                .file("r/first.txt", "a".repeat(700))
                .file("r/second.txt", "second")
                .tar();

        try (TarArchiveReader r = reader(tar)) {
            assertEquals("r/first.txt", r.next().path()); // veriyi okumadan geç
            TarArchiveReader.Entry second = r.next();
            assertEquals("r/second.txt", second.path());
            assertEquals("second", new String(r.readEntry(), StandardCharsets.UTF_8));
            assertNull(r.next());
        }
    }

    @Test
    void resolvesPaxLongPaths() throws IOException {
        String longPath = "repo-abc/" + "deep/".repeat(30) + "File.java";
        byte[] tar = new TarTestSupport().file(longPath, "ok").tar();

        try (TarArchiveReader r = reader(tar)) {
            TarArchiveReader.Entry e = r.next();
            assertEquals(longPath, e.path());
            assertEquals("ok", new String(r.readEntry(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void resolvesGnuLongNames() throws IOException {
        String longPath = "repo-abc/" + "dir/".repeat(40) + "Main.kt";
        byte[] tar = new TarTestSupport().gnuLongNameFile(longPath, "fun main() {}").tar();

        try (TarArchiveReader r = reader(tar)) {
            assertEquals(longPath, r.next().path());
        }
    }

    @Test
    void emptyAndTerminatedArchivesReturnNull() throws IOException {
        try (TarArchiveReader r = reader(new byte[0])) {
            assertNull(r.next());
        }
        try (TarArchiveReader r = reader(new TarTestSupport().tar())) {
            assertNull(r.next());
        }
    }

    @Test
    void truncatedArchiveFailsLoudly() {
        byte[] tar = new TarTestSupport().file("r/a.txt", "hello world").tar();
        byte[] cut = java.util.Arrays.copyOf(tar, 515); // başlık tam, veri yarım (11 bayttan 3'ü var)

        assertThrows(IOException.class, () -> {
            try (TarArchiveReader r = reader(cut)) {
                r.next();
                r.readEntry();
            }
        });
    }
}
