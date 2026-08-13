package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world-creation preview's placeholder world style must satisfy the same post-resolution rules
 * every datapack world style does.
 * <p>
 * {@link NullDimensionInfo} builds it whenever it cannot resolve the chosen one - either because it
 * was handed no {@code RegistryAccess} at all (the Cities tab and the Customize screen both pass
 * null deliberately when the biome registry or the parent screen is absent) or because the lookup
 * failed, which is what a stale GUI world style no longer shipped by any datapack looks like. The
 * placeholder is therefore a live user-facing path, not a theoretical one, and it is built from a
 * hand-written {@code WorldStyleDefinition} rather than from JSON - so nothing about it is checked by the
 * datapack tests.
 * <p>
 * That is exactly how it broke: {@code parts} became required of a resolved chain after the
 * placeholder was last touched, the placeholder went on declaring none, and constructing it started
 * throwing {@link IllegalStateException} - taking the create-world screen down, because the preview
 * is driven from the render pass. This test is the guard: every field {@code WorldStyle} requires
 * after resolution has to be declared here, and every asset name has to name its namespace.
 */
class NullDimensionInfoPlaceholderTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static NullDimensionInfo placeholderPreview() {
        Preset preset = new Preset(Identifier.fromNamespaceAndPath("urbex", "test-placeholder"));
        // Null registry access is the GUI's own fallback, and it is also the only way to reach the
        // placeholder without a loaded registry to fail a lookup against.
        return new NullDimensionInfo(preset, WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")),
                1234L, null);
    }

    @Test
    void thePreviewBuildsWithoutRegistryAccess() {
        NullDimensionInfo diminfo = assertDoesNotThrow(NullDimensionInfoPlaceholderTest::placeholderPreview,
                "the preview must fall back to the placeholder world style, not throw");
        assertNotNull(diminfo.planning().worldStyles().primary(), "the placeholder world style must be built");
    }

    @Test
    void thePlaceholderDeclaresEveryFieldRequiredAfterResolution() {
        WorldStyle style = placeholderPreview().planning().worldStyles().primary();

        // outsidestyle and citystyles are required by WorldStyle itself; parts is required and then
        // required component by component by PartSelector.requireComplete. Reaching this line at all
        // means Resolved.require accepted them, so the assertions below only pin what is observable.
        assertNotNull(style.getOutsideStyle(), "outsidestyle must be declared");
        PartSelector parts = style.getPartSelector();
        assertNotNull(parts, "parts must be declared");
        assertNotNull(parts.highwayParts(), "parts.highways must be declared");
        assertNotNull(parts.railwayParts(), "parts.railways must be declared");
        assertEveryComponentDeclared(parts.highwayParts(), HighwayParts.class);
        assertEveryComponentDeclared(parts.railwayParts(), RailwayParts.class);
    }

    /**
     * Reflective rather than a written-out list of the twenty-two component names: a component added
     * to either wiring record must be declared by the placeholder too, and a test that enumerates
     * them by hand would keep passing when one is added and left out.
     */
    private static void assertEveryComponentDeclared(Object wiring, Class<?> record) {
        for (RecordComponent component : record.getRecordComponents()) {
            Object value = assertDoesNotThrow(() -> component.getAccessor().invoke(wiring));
            assertNotNull(value, record.getSimpleName() + "." + component.getName()
                    + " is undeclared in the preview placeholder");
        }
    }

    @Test
    void thePlaceholderNamesNoUnqualifiedAsset() {
        WorldStyle style = placeholderPreview().planning().worldStyles().primary();
        // A bare name would throw out of DataTools.fromName the moment anything resolved it, which is
        // the load error this branch introduced for datapacks - src/main must not write one either.
        Identifier outside = assertDoesNotThrow(() -> DataTools.fromName(style.getOutsideStyle()),
                "the placeholder's outsidestyle must name its namespace");
        assertTrue(style.getOutsideStyle().contains(":"), "outsidestyle must be fully qualified");
        assertNotNull(outside);
    }
}
