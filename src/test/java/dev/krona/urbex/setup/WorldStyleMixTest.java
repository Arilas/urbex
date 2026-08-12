package dev.krona.urbex.setup;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mix grammar is the one serial form for a weighted world-style selection: it is what a
 * {@code dimensionsWithPresets} entry carries after the {@code @}, and what {@code UrbexData}
 * persists. One parser rather than three, so a string that loads from a save also parses from
 * config.
 */
class WorldStyleMixTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    @Test
    void singleEntryRoundTripsWithoutAWeightSuffix() {
        WorldStyleMix mix = WorldStyleMix.of(id("urbex", "standard"));
        assertTrue(mix.isSingle());
        assertEquals(id("urbex", "standard"), mix.single().orElseThrow());
        // Weight 1 is implicit, so a single style formats exactly as it did before mixing existed.
        assertEquals("urbex:standard", mix.format());
        assertEquals(mix, WorldStyleMix.parse("urbex:standard"));
    }

    @Test
    void weightedMixRoundTrips() {
        WorldStyleMix mix = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9");
        assertEquals(2, mix.entries().size());
        assertFalse(mix.isSingle());
        assertEquals(id("urbex", "standard"), mix.entries().get(0).style());
        assertEquals(0.1f, mix.entries().get(0).weight());
        assertEquals(id("urbexmt", "moderntweaks"), mix.entries().get(1).style());
        assertEquals(0.9f, mix.entries().get(1).weight());
        assertEquals("urbex:standard*0.1+urbexmt:moderntweaks*0.9", mix.format());
    }

    @Test
    void primaryIsTheHeaviestEntry() {
        assertEquals(id("urbexmt", "moderntweaks"),
                WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9").primary());
        assertEquals(id("urbexmt", "moderntweaks"),
                WorldStyleMix.parse("urbexmt:moderntweaks*0.9+urbex:standard*0.1").primary());
    }

    @Test
    void equalWeightsBreakTheTieOnIdNotOnListOrder() {
        // Registry iteration is ConcurrentHashMap bucket order, so a positional tie-break would
        // make the primary depend on file names. Lowest id string wins, both ways round.
        assertEquals(id("urbex", "standard"),
                WorldStyleMix.parse("urbexmt:moderntweaks+urbex:standard").primary());
        assertEquals(id("urbex", "standard"),
                WorldStyleMix.parse("urbex:standard+urbexmt:moderntweaks").primary());
    }

    @Test
    void reducingToPrimaryIsWhatTheExperimentalGateApplies() {
        WorldStyleMix reduced = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9")
                .reducedToPrimary();
        assertTrue(reduced.isSingle());
        assertEquals(id("urbexmt", "moderntweaks"), reduced.single().orElseThrow());
    }

    @Test
    void malformedSpecsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse(""));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("   "));
        // Unqualified ids stay an error, exactly as they are everywhere else.
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("standard"));
        // Zero and negative weights would make the weighted draw undefined.
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard*0"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard*-1"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard*notanumber"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard*1*2"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard+"));
        // A duplicate is an authoring error, not something to silently sum.
        assertThrows(IllegalArgumentException.class,
                () -> WorldStyleMix.parse("urbex:standard*0.1+urbex:standard*0.9"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.of(List.of()));
    }

    @Test
    void codecRoundTripsTheStringForm() {
        WorldStyleMix mix = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9");
        String encoded = WorldStyleMix.CODEC.encodeStart(JsonOps.INSTANCE, mix).getOrThrow().getAsString();
        assertEquals(mix.format(), encoded);
        WorldStyleMix decoded = WorldStyleMix.CODEC
                .parse(JsonOps.INSTANCE, new JsonPrimitive(encoded)).getOrThrow();
        assertEquals(mix, decoded);
    }
}
