package lockmerge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import lockmerge.lock.LockParser;
import lockmerge.lock.LockPrinter;
import lockmerge.model.Diagnostic;
import lockmerge.model.EdgeRef;
import lockmerge.model.LockDocument;
import lockmerge.model.NodeKey;
import lockmerge.model.ParseResult;
import org.junit.jupiter.api.Test;

class LockParserTest {

    @Test
    void fingerprintsChangeWithContent() {
        ParseResult a = LockParser.parse("a", "lockfile v1\n");
        ParseResult b = LockParser.parse("b", "lockfile v1\n\n");
        assertFalse(a.fingerprint().equals(b.fingerprint()));
        // line-ending normalized
        ParseResult c = LockParser.parse("c", "lockfile v1\r\n");
        assertEquals(a.fingerprint(), c.fingerprint());
    }

    @Test
    void reportsLineNumberedDiagnostics() {
        String bad = "lockfile v1\n"
                + "root app@1.0.0\n"
                + "package app@1.0.0\n"
                + "    source registry:acme/app\n"
                + "    integrity not-a-digest\n"
                + "    platforms os=linux |\n"
                + "end\n";
        ParseResult result = LockParser.parse("bad", bad);
        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().equals("BAD_INTEGRITY") && d.line() == 5));
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().equals("BAD_PLATFORM") && d.line() == 6));
    }

    @Test
    void danglingReferencesAreDetectedAtDocumentLevel() {
        String raw = "lockfile v1\nroot app@1.0.0\n"
                + "package app@1.0.0\n"
                + "    source registry:acme/app\n"
                + "    integrity sha256:1000abcd\n"
                + "    requires:\n"
                + "        missing@9.9.9\n"
                + "    end\n";
        LockDocument doc = TestFixtures.documentOf(raw);
        List<NodeKey> dangling = doc.danglingReferences();
        assertEquals(1, dangling.size());
        assertEquals(new NodeKey("missing", "9.9.9"), dangling.get(0));
    }

    @Test
    void requiresConditionIsStoredCanonically() {
        String raw = "lockfile v1\nroot app@1.0.0\n"
                + "package app@1.0.0\n"
                + "    source registry:acme/app\n"
                + "    integrity sha256:1000abcd\n"
                + "    requires:\n"
                + "        lib@1.0.0 when os=linux|os=macos\n"
                + "    end\n"
                + "package lib@1.0.0\n"
                + "    source registry:acme/lib\n"
                + "    integrity sha256:2000abcd\n";
        LockDocument doc = TestFixtures.documentOf(raw);
        EdgeRef edge = doc.node(new NodeKey("app", "1.0.0")).requires().get(0);
        assertEquals("os=linux | os=macos", edge.platforms());
        // printing and parsing preserves canonical condition
        LockDocument again = TestFixtures.documentOf(LockPrinter.print(doc));
        assertEquals(doc, again);
    }

    @Test
    void missingFormatAndTabsAreErrors() {
        ParseResult noHeader = LockParser.parse("x", "root app@1.0.0\n");
        assertTrue(noHeader.diagnostics().stream()
                .anyMatch(d -> d.code().equals("FORMAT_ORDER")));

        ParseResult tabs = LockParser.parse("x",
                "lockfile v1\npackage app@1.0.0\n\tsource registry:acme/app\nend\n");
        assertTrue(tabs.diagnostics().stream()
                .anyMatch(d -> d.code().equals("TAB_INDENT")));
    }

    @Test
    void duplicateRootsWarnButConflictingPinsError() {
        ParseResult duplicate = LockParser.parse("x",
                "lockfile v1\nroot app@1.0.0\nroot app@1.0.0\n");
        assertTrue(duplicate.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.WARNING
                        && d.code().equals("DUP_ROOT")));
        assertFalse(duplicate.hasErrors());

        ParseResult conflicting = LockParser.parse("x",
                "lockfile v1\nroot app@1.0.0\nroot app@2.0.0\n");
        assertTrue(conflicting.hasErrors());
    }
}
