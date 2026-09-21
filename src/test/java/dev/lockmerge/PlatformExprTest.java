package dev.lockmerge;

import dev.lockmerge.lock.PlatformExpr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformExprTest {

    @Test
    void equivalentAfterAssociativeCommutativeRewrites() {
        assertTrue(PlatformExpr.equivalent("os=linux | os=darwin", "os=darwin | os=linux"));
        assertTrue(PlatformExpr.equivalent("a & b", "b & a"));
        assertTrue(PlatformExpr.equivalent("a & (b | c)", "(a & b) | (a & c)"));
        assertTrue(PlatformExpr.equivalent("!!a", "a"));
        assertTrue(PlatformExpr.equivalent("!(a & b)", "!a | !b"));
        assertTrue(PlatformExpr.equivalent("!(a | b)", "!a & !b"));
    }

    @Test
    void differentExpressionsAreNotEquivalent() {
        assertFalse(PlatformExpr.equivalent("os=linux", "os=darwin"));
        assertFalse(PlatformExpr.equivalent("a | b", "a & b"));
        assertFalse(PlatformExpr.equivalent("a", "!a"));
    }

    @Test
    void canonicalIsSortedAndStable() {
        String a = PlatformExpr.canonical("(os=darwin | os=linux) & arch=aarch64");
        String b = PlatformExpr.canonical("arch=aarch64 & (os=linux | os=darwin)");
        assertEquals(a, b);
        assertTrue(a.startsWith("arch=aarch64&os=darwin|arch=aarch64&os=linux"), a);
    }

    @Test
    void absentAndBlankAreBothNull() {
        assertNull(PlatformExpr.canonical(null));
        assertNull(PlatformExpr.canonical("   "));
    }

    @Test
    void invalidSyntaxRaises() {
        assertThrows(PlatformExpr.SyntaxException.class,
                () -> PlatformExpr.canonical("a &"));
        assertThrows(PlatformExpr.SyntaxException.class,
                () -> PlatformExpr.canonical("(a | b"));
        assertThrows(PlatformExpr.SyntaxException.class,
                () -> PlatformExpr.canonical("a b"));
    }
}
