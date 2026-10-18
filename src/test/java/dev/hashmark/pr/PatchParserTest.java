package dev.hashmark.pr;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchParserTest {

    @Test
    void tracksNewLineNumbersAcrossContextRemovalsAndHunks() {
        String patch = """
                @@ -1,4 +1,5 @@
                 package a;
                -int old = 1;
                +int x = 1; // TODO: new
                +int y = 2;
                 class A {}
                @@ -20,3 +21,4 @@ class A {}
                 void f() {
                +  // FIXME: added later
                 }
                \\ No newline at end of file""";

        assertEquals(Set.of(2, 3, 22), PatchParser.addedLines(patch));
    }

    @Test
    void newFileIsAllAdded() {
        assertEquals(Set.of(1, 2), PatchParser.addedLines("@@ -0,0 +1,2 @@\n+a\n+b"));
    }

    @Test
    void emptyOrMissingPatch() {
        assertTrue(PatchParser.addedLines(null).isEmpty());
        assertTrue(PatchParser.addedLines("").isEmpty());
        assertTrue(PatchParser.addedLines("+not inside a hunk").isEmpty());
    }

    @Test
    void crlfPatches() {
        assertEquals(Set.of(2), PatchParser.addedLines("@@ -1,1 +1,2 @@\r\n a\r\n+b\r\n"));
    }
}
