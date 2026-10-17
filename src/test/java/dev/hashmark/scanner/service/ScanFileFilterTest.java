package dev.hashmark.scanner.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanFileFilterTest {

    private static final Set<String> DIRS = Set.of("node_modules", "vendor", "dist");

    private ScanFileFilter filter(String ignoreFile) {
        return new ScanFileFilter(DIRS, ignoreFile);
    }

    @Test
    void acceptsSupportedExtensionsCaseInsensitively() {
        ScanFileFilter f = filter(null);

        assertTrue(f.shouldScan("src/Main.java"));
        assertTrue(f.shouldScan("web/App.TSX"));
        assertTrue(f.shouldScan("scripts/run.sh"));
        assertTrue(f.shouldScan("db/schema.sql"));
        assertTrue(f.shouldScan("ui/page.vue"));
        assertFalse(f.shouldScan("README.md"));
        assertFalse(f.shouldScan("image.png"));
        assertFalse(f.shouldScan("Makefile"));
        assertFalse(f.shouldScan("trailingdot."));
    }

    @Test
    void excludesConfiguredDirectoriesAtAnyDepth() {
        ScanFileFilter f = filter(null);

        assertFalse(f.shouldScan("node_modules/lib/index.js"));
        assertFalse(f.shouldScan("packages/web/node_modules/x/y.js"));
        assertFalse(f.shouldScan("vendor/github.com/a/b.go"));
        assertFalse(f.shouldScan("dist/app.js"));
        assertTrue(f.shouldScan("src/vendorish/app.js"));
        // dizin adı dosya adıyla çakışırsa dosya hariç tutulmaz
        assertTrue(f.shouldScan("src/dist.js"));
    }

    @Test
    void excludesMinifiedAndGeneratedFiles() {
        ScanFileFilter f = filter(null);

        assertFalse(f.shouldScan("static/app.min.js"));
        assertFalse(f.shouldScan("static/site.min.css"));
        assertFalse(f.shouldScan("api/user.pb.go"));
        assertFalse(f.shouldScan("api/user_pb2.py"));
        assertFalse(f.shouldScan("lib/model.g.dart"));
        assertFalse(f.shouldScan("src/schema.generated.ts"));
        assertTrue(f.shouldScan("src/schema.ts"));
    }

    @Test
    void hashmarkIgnoreDirectoryAndExtensionRules() {
        ScanFileFilter f = filter("""
                # yorum
                docs/
                *.gen.js

                legacy.py
                """);

        assertFalse(f.shouldScan("docs/example.py"));
        assertFalse(f.shouldScan("pkg/docs/example.py"));
        assertTrue(f.shouldScan("docs.py"));
        assertFalse(f.shouldScan("src/api.gen.js"));
        assertFalse(f.shouldScan("deep/dir/legacy.py"));
        assertTrue(f.shouldScan("src/api.js"));
    }

    @Test
    void hashmarkIgnoreAnchoredAndGlobRules() {
        ScanFileFilter f = filter("""
                /scripts
                src/generated/**
                src/*/tmp_?.java
                """);

        assertFalse(f.shouldScan("scripts/build.py"));
        assertTrue(f.shouldScan("tools/scripts/build.py"), "başında / olan kural kökten eşleşir");
        assertFalse(f.shouldScan("src/generated/a/b/C.java"));
        assertTrue(f.shouldScan("src/other/C.java"));
        assertFalse(f.shouldScan("src/mod/tmp_1.java"));
        assertTrue(f.shouldScan("src/mod/tmp_12.java"));
    }

    @Test
    void configIncludeLimitsScanAndExcludeAddsToIgnoreFile() {
        ScanFileFilter f = new ScanFileFilter(DIRS, "legacy.py\n",
                List.of("/src", "scripts/*.sh"), List.of("*.gen.ts", "src/vendored/"));

        assertTrue(f.shouldScan("src/app.ts"));
        assertTrue(f.shouldScan("scripts/run.sh"));
        assertFalse(f.shouldScan("lib/app.ts"), "include dışı");
        assertFalse(f.shouldScan("tools/src/app.ts"), "/src yalnızca kökteki src");
        assertFalse(f.shouldScan("src/api.gen.ts"), "config exclude");
        assertFalse(f.shouldScan("src/vendored/x.ts"), "config exclude dizin");
        assertFalse(f.shouldScan("src/legacy.py"), ".hashmarkignore hâlâ geçerli");
        assertFalse(f.shouldScan("src/node_modules/x.js"), "yerleşik hariç dizinler include'u yener");
    }

    @Test
    void emptyIncludeMeansEverything() {
        ScanFileFilter f = new ScanFileFilter(DIRS, null, List.of(), List.of());

        assertTrue(f.shouldScan("any/where/app.go"));
    }

    @Test
    void negationAndBlankRulesAreIgnoredSafely() {
        ScanFileFilter f = filter("!keep.js\n\n   \n/\n");

        assertTrue(f.shouldScan("keep.js"));
        assertTrue(f.shouldScan("src/a.js"));
    }
}
