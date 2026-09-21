package lockmerge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import lockmerge.platform.PlatformExpr;
import lockmerge.platform.PlatformSyntaxException;
import org.junit.jupiter.api.Test;

class PlatformExprTest {

    @Test
    void reorderingAndWhitespaceAreEquivalent() {
        PlatformExpr a = PlatformExpr.parse("os=macos | os=linux");
        PlatformExpr b = PlatformExpr.parse("  os=linux|os=macos  ");
        assertEquals(a.canonical(), b.canonical());
        assertEquals(a, b);
    }

    @Test
    void doubleNegationAndDeMorganAreEquivalent() {
        PlatformExpr direct = PlatformExpr.parse("os=linux & arch=arm64");
        PlatformExpr doubleNeg = PlatformExpr.parse("!!(os=linux & arch=arm64)");
        PlatformExpr deMorgan = PlatformExpr.parse(
                "!(!os=linux | !arch=arm64)");
        assertEquals(direct.canonical(), doubleNeg.canonical());
        assertEquals(direct.canonical(), deMorgan.canonical());
    }

    @Test
    void bareTagsAndComparisonsNormalize() {
        assertEquals("linux", PlatformExpr.parse("Linux").canonical());
        assertEquals("!linux", PlatformExpr.parse("!linux").canonical());
        assertEquals("arch!=x86", PlatformExpr.parse("arch!=x86").canonical());
    }

    @Test
    void contradictoryAndAbsorbsToFalseAndTrueAbsorbsOr() {
        assertTrue(PlatformExpr.parse("os=linux & !os=linux").isAlwaysFalse());
        assertTrue(PlatformExpr.parse("os=linux | true").isAlwaysTrue());
        assertEquals("false", PlatformExpr.parse("false & os=linux").canonical());
    }

    @Test
    void syntacticallyDifferentButEqualConditionsCompareBySemantics() {
        PlatformExpr a = PlatformExpr.parse("(a | b) & c");
        PlatformExpr b = PlatformExpr.parse("(a & c) | (b & c)");
        assertEquals(a.canonical(), b.canonical());
    }

    @Test
    void genuinelyDifferentConditionsAreNotEqual() {
        PlatformExpr a = PlatformExpr.parse("os=linux | arch=arm64");
        PlatformExpr b = PlatformExpr.parse("os=linux");
        assertFalse(a.canonical().equals(b.canonical()));
    }

    @Test
    void invalidExpressionsRaiseSyntaxErrors() {
        assertThrows(PlatformSyntaxException.class, () -> PlatformExpr.parse("os="));
        assertThrows(PlatformSyntaxException.class, () -> PlatformExpr.parse("a |"));
        assertThrows(PlatformSyntaxException.class, () -> PlatformExpr.parse("(a"));
        assertThrows(PlatformSyntaxException.class, () -> PlatformExpr.parse("a b"));
    }

    @Test
    void canonicalFormReparsesToSameCanonical() {
        List<String> samples = List.of(
                "!(os=windows) & (arch=arm64 | arch=amd64)",
                "a | b | c | a",
                "!!(!a & b)",
                "linux");
        for (String sample : samples) {
            String canonical = PlatformExpr.parse(sample).canonical();
            assertEquals(canonical, PlatformExpr.parse(canonical).canonical(), sample);
        }
    }
}
