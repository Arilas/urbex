package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accumulator that turns "the first thing wrong with this pack" into "everything wrong with this
 * pack" (issue #56).
 */
class AssetDiagnosticsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void anEmptyReportRefusesNothing() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();

        assertTrue(diagnostics.isEmpty());
        assertEquals(0, diagnostics.size());
        diagnostics.throwIfAny();
    }

    @Test
    void everyProblemIsNamedAtOnceRatherThanOnePerWorldLoad() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        diagnostics.record("urbex:buildings", id("radiotower"), "declares no 'filler'");
        diagnostics.record("urbex:parts", id("floor"), "declares no 'xsize'");

        IllegalStateException failure = assertThrows(IllegalStateException.class, diagnostics::throwIfAny);

        assertTrue(failure.getMessage().contains("urbex:radiotower"), failure.getMessage());
        assertTrue(failure.getMessage().contains("urbex:floor"), failure.getMessage());
        assertTrue(failure.getMessage().startsWith("2 Urbex asset problem(s)"), failure.getMessage());
    }

    /**
     * A stable order, so the report is the same text on two runs of the same pack and an author can
     * diff two of them. Registry-walk order is a {@code ConcurrentHashMap}'s, which is to say a
     * function of the ids' hashes.
     */
    @Test
    void problemsAreReportedInAStableOrderRatherThanRegistryWalkOrder() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        diagnostics.record("urbex:parts", id("zzz"), "second");
        diagnostics.record("urbex:buildings", id("mmm"), "first");
        diagnostics.record("urbex:parts", id("aaa"), "also second");

        assertEquals(List.of("urbex:buildings / urbex:mmm: first",
                        "urbex:parts / urbex:aaa: also second",
                        "urbex:parts / urbex:zzz: second"),
                diagnostics.problems().stream().map(AssetDiagnostics.Problem::toString).toList());
    }

    /**
     * The resolution wrappers say "Error getting resource x" at every level; what an author needs is
     * the innermost sentence, which names the field or the missing reference.
     */
    @Test
    void aWrappedFailureIsReportedByItsInnermostMessage() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Exception root = new IllegalStateException("'urbex:radiotower' declares no 'filler'");
        Exception wrapped = new RuntimeException("Error getting resource urbex:radiotower!", root);

        diagnostics.record("urbex:buildings", id("radiotower"), wrapped);

        assertEquals("urbex:buildings / urbex:radiotower: 'urbex:radiotower' declares no 'filler'",
                diagnostics.problems().getFirst().toString());
    }

    @Test
    void aProblemWithNoOneAssetToBlameStillSaysWhereItCameFrom() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        diagnostics.record("urbex:worldstyles", null, "nothing selects a city style");

        assertEquals("urbex:worldstyles: nothing selects a city style",
                diagnostics.problems().getFirst().toString());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("urbex", path);
    }
}
