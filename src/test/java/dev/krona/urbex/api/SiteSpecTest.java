package dev.krona.urbex.api;

import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a caller can and cannot say when defining a site.
 *
 * <p>Every failure here is one a mod author hits at initialisation, holding a stack trace, rather
 * than three hundred blocks underground wondering why the bunkers are the wrong shape. That is the
 * point of validating in the constructor: a {@link SiteSpec} is built once and generates for the
 * life of a world, so there is no cheap moment later to notice it was wrong.</p>
 */
class SiteSpecTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbextest", "bunkers");
    private static final Identifier PRESET = Identifier.fromNamespaceAndPath("urbex", "cavern");
    private static final SiteField FIELD = (x, z) -> true;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theShortestUsefulSpecIsThreeArgumentsAndABuild() {
        SiteSpec spec = SiteSpec.builder(ID, PRESET, FIELD).build();

        assertEquals(ID, spec.id());
        assertEquals(PRESET, spec.preset());
        assertEquals(WorldStyleMix.of(UrbexApi.DEFAULT_WORLD_STYLE), spec.worldStyles());
        assertNull(spec.presetOverridesJson());
        assertEquals(UrbexApi.DEFAULT_MIN_Y, spec.minY());
        assertEquals(UrbexApi.DEFAULT_MAX_Y, spec.maxY());
    }

    /**
     * The default that stops the commonest trap: a preset written for a dimension names an absolute
     * sea level, and a site under that dimension is not underwater.
     */
    @Test
    void aSiteIsDryUnlessTheCallerAsksForWater() {
        assertEquals(UrbexApi.NO_WATER, SiteSpec.builder(ID, PRESET, FIELD).build().waterY());
        assertEquals(-30, SiteSpec.builder(ID, PRESET, FIELD).waterY(-30).build().waterY());
    }

    @Test
    void anUpsideDownWindowIsRefusedNamingBothEnds() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> SiteSpec.builder(ID, PRESET, FIELD).window(20, -40).build());

        assertTrue(thrown.getMessage().contains("20") && thrown.getMessage().contains("-40"),
                "a caller who transposed two numbers should be able to see that from the message: "
                        + thrown.getMessage());
    }

    @Test
    void aWindowOfOneBlockIsLegalIfUseless() {
        assertEquals(7, SiteSpec.builder(ID, PRESET, FIELD).window(7, 7).build().minY());
    }

    @Test
    void aSingleStyleAndAOneEntryMixSayTheSameThing() {
        Identifier style = Identifier.fromNamespaceAndPath("urbextest", "bunker");

        assertEquals(SiteSpec.builder(ID, PRESET, FIELD).worldStyles(WorldStyleMix.of(style)).build(),
                SiteSpec.builder(ID, PRESET, FIELD).worldStyle(style).build());
    }

    @Test
    void everythingASiteCannotDoWithoutIsRequired() {
        assertThrows(NullPointerException.class,
                () -> SiteSpec.builder(null, PRESET, FIELD).build());
        assertThrows(NullPointerException.class,
                () -> SiteSpec.builder(ID, null, FIELD).build());
        assertThrows(NullPointerException.class,
                () -> SiteSpec.builder(ID, PRESET, null).build());
    }

    /**
     * The id is the memo key, so equal specs must be equal values - otherwise the "you registered
     * two different sites under one id" warning fires on every chunk for a caller that simply builds
     * its spec inline.
     */
    @Test
    void twoSpecsBuiltTheSameWayAreEqual() {
        assertEquals(spec(), spec());
    }

    private static SiteSpec spec() {
        return SiteSpec.builder(ID, PRESET, FIELD)
                .worldStyle(Identifier.fromNamespaceAndPath("urbex", "standard"))
                .presetOverrides("{}")
                .window(-60, 24)
                .waterY(-50)
                .build();
    }
}
