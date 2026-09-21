package dev.lockmerge;

import dev.lockmerge.lock.LockParser;
import dev.lockmerge.lock.LockPrinter;
import dev.lockmerge.model.LockDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockFormatTest {

    private static final String DOC = """
            lockfile v1
            # leading comment
            root app@1.0.0
            root tool@2.0.0 [os=linux | os=darwin]

            package app@1.0.0
              source registry:https://repo.example.com/app-1.0.0.tgz
              integrity sha256:000000000000000000000000000000000000000000000000000000000000aaaa
              child libx@1.0.0
              child opt@0.9.0 [!os=windows]

            package libx@1.0.0
              source registry:https://repo.example.com/libx-1.0.0.tgz
              integrity sha256:111111111111111111111111111111111111111111111111111111111111bbbb

            package opt@0.9.0
              source registry:https://repo.example.com/opt-0.9.0.tgz
              integrity sha256:222222222222222222222222222222222222222222222222222222222222cccc
            """;

    @Test
    void parsePrintParseIsEquivalent() {
        LockDocument first = Fixtures.doc(DOC);
        String printed = LockPrinter.print(first);
        LockDocument second = Fixtures.doc(printed);

        assertEquals(first.roots().size(), second.roots().size());
        assertEquals(first.nodes().size(), second.nodes().size());
        // Roots are sorted and the condition survives print in canonical form.
        assertEquals("app@1.0.0", second.roots().get(0).name() + "@"
                + second.roots().get(0).version());
        assertEquals("os=darwin|os=linux", second.roots().get(1).condition());
        // Printing again is byte-identical (idempotent).
        assertEquals(printed, LockPrinter.print(second));
    }

    @Test
    void printerUsesStableOrdering() {
        LockDocument doc = Fixtures.doc(DOC);
        String p1 = LockPrinter.print(doc);
        String p2 = LockPrinter.print(Fixtures.doc(DOC));
        assertEquals(p1, p2);
        int appPos = p1.indexOf("package app@1.0.0");
        int libxPos = p1.indexOf("package libx@1.0.0");
        int optPos = p1.indexOf("package opt@0.9.0");
        assertTrue(appPos < libxPos);
        assertTrue(libxPos < optPos);
    }

    @Test
    void rejectsBadHeaderAndIntegrity() {
        LockParser.Result badHeader = LockParser.parse("package x@1.0.0\n");
        assertTrue(badHeader.hasErrors());

        String badIntegrity = """
                lockfile v1
                package x@1.0.0
                  source registry:x
                  integrity md5:nope
                """;
        assertTrue(LockParser.parse(badIntegrity).hasErrors());

        String duplicate = """
                lockfile v1
                package x@1.0.0
                  source registry:x
                  integrity sha256:000000000000000000000000000000000000000000000000000000000000aaaa
                package x@1.0.0
                  source registry:y
                  integrity sha256:000000000000000000000000000000000000000000000000000000000000aaaa
                """;
        assertTrue(LockParser.parse(duplicate).hasErrors());
        assertFalse(LockParser.parse(DOC).hasErrors());
    }
}
