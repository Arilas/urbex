package dev.krona.urbex.worldgen.lost.regassets.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStyleSelectorEdgeTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void selectorWithoutEdgeIsBaseOnly() {
        CityStyleSelector selector = decode("""
                {"factor": 0.5, "citystyle": "test:base"}
                """);

        assertEquals("test:base", selector.selection().styleAt(0.1f));
        assertTrue(selector.edge().isEmpty());
    }

    @Test
    void completeEdgeRoundTripsAndUsesStrictBoundary() {
        CityStyleSelector selector = decode("""
                {
                  "factor": 0.5,
                  "citystyle": "test:base",
                  "edge": {"citystyle": "test:edge", "threshold": 0.4}
                }
                """);

        assertEquals("test:edge", selector.selection().styleAt(0.399f));
        assertEquals("test:base", selector.selection().styleAt(0.4f));
        assertEquals("test:base", selector.selection().styleAt(0.8f));
        assertTrue(encode(selector).has("edge"));
    }

    @Test
    void edgeWithoutCityStyleIsRejected() {
        assertError("""
                {"factor": 0.5, "citystyle": "test:base", "edge": {"threshold": 0.4}}
                """, "citystyle");
    }

    @Test
    void edgeWithoutThresholdIsRejected() {
        assertError("""
                {"factor": 0.5, "citystyle": "test:base", "edge": {"citystyle": "test:edge"}}
                """, "threshold");
    }

    @Test
    void edgeWithBlankCityStyleIsRejected() {
        assertError("""
                {"factor": 0.5, "citystyle": "test:base", "edge": {"citystyle": "", "threshold": 0.4}}
                """, "Unqualified datapack reference ''");
    }

    @Test
    void edgeWithUnqualifiedCityStyleIsRejected() {
        assertError("""
                {"factor": 0.5, "citystyle": "test:base", "edge": {"citystyle": "edge", "threshold": 0.4}}
                """, "Unqualified datapack reference 'edge'");
    }

    @Test
    void edgeWithNaNThresholdIsRejected() {
        assertNonFiniteThresholdError("NaN");
    }

    @Test
    void edgeWithPositiveInfinityThresholdIsRejected() {
        assertNonFiniteThresholdError("Infinity");
    }

    @Test
    void edgeWithZeroThresholdIsRejected() {
        assertThresholdError("0.0");
    }

    @Test
    void edgeWithNegativeThresholdIsRejected() {
        assertThresholdError("-0.1");
    }

    @Test
    void edgeWithThresholdAboveOneIsRejected() {
        assertThresholdError("1.1");
    }

    @Test
    void oneSelectorCannotBorrowAnotherSelectorsEdge() {
        List<CityStyleSelector> selectors = CityStyleSelector.CODEC.listOf().parse(JsonOps.INSTANCE,
                JsonParser.parseString("""
                        [
                          {"factor": 0.5, "citystyle": "test:first", "edge": {"citystyle": "test:first_edge", "threshold": 0.4}},
                          {"factor": 0.5, "citystyle": "test:second"}
                        ]
                        """)).getOrThrow();

        assertEquals("test:first_edge", selectors.getFirst().selection().styleAt(0.1f));
        assertEquals("test:second", selectors.get(1).selection().styleAt(0.1f));
        assertTrue(selectors.get(1).edge().isEmpty());
    }

    private static CityStyleSelector decode(String json) {
        return CityStyleSelector.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static JsonObject encode(CityStyleSelector selector) {
        return CityStyleSelector.CODEC.encodeStart(JsonOps.INSTANCE, selector).getOrThrow().getAsJsonObject();
    }

    private static void assertThresholdError(String threshold) {
        assertError("""
                {"factor": 0.5, "citystyle": "test:base", "edge": {"citystyle": "test:edge", "threshold": %s}}
                """.formatted(threshold), "Edge threshold must be finite and satisfy 0 < threshold <= 1");
    }

    private static void assertNonFiniteThresholdError(String threshold) {
        assertError("""
                {"factor": 0.5, "citystyle": "test:base", "edge": {"citystyle": "test:edge", "threshold": %s}}
                """.formatted(threshold), "Not a number");
    }

    private static void assertError(String json, String expectedText) {
        DataResult<CityStyleSelector> result = CityStyleSelector.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString(json));
        String message = result.error().orElseThrow(() -> new AssertionError("expected decode failure")).message();
        assertTrue(message.contains(expectedText), () -> "expected '" + expectedText + "' in: " + message);
    }
}
