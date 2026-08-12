package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for the resolved-{@code BlockState} cache surviving {@link Preset#copy()}:
 * {@link Presets#applyOverrides} is {@code copy()} followed by applying the override's sections,
 * and {@code TerrainSettings.apply()} writes {@code LIQUID_BLOCK}/{@code BASE_BLOCK} (the string
 * fields) directly with no setter to invalidate a cache. If {@code copy()} carried the cache over,
 * a base preset whose {@code getLiquidBlock()}/{@code getBaseBlock()} had already been resolved
 * before an override changed those strings would keep returning the pre-override block. Needs MC
 * bootstrap because {@code getLiquidBlock()}/{@code getBaseBlock()} resolve through
 * {@code BuiltInRegistries.BLOCK} for real, which is the actual invariant under test (not just
 * "the cache field is null").
 */
class PresetOverrideCacheTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "override-cache-test");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static PresetDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return PresetDefinition.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }

    @Test
    void applyOverridesReflectsNewBlocksEvenAfterBaseCacheWasWarmed() {
        Preset base = new Preset(ID);

        // Warm the cache with the base preset's code-default blocks before overriding.
        assertEquals(Blocks.WATER.defaultBlockState(), base.getLiquidBlock());
        assertEquals(Blocks.STONE.defaultBlockState(), base.getBaseBlock());

        PresetDefinition overrides = decode("{\"terrain\":{\"liquidBlock\":\"minecraft:lava\","
                + "\"baseBlock\":\"minecraft:granite\"}}");

        Preset overridden = Presets.applyOverrides(base, overrides);

        assertEquals(Blocks.LAVA.defaultBlockState(), overridden.getLiquidBlock());
        assertEquals(Blocks.GRANITE.defaultBlockState(), overridden.getBaseBlock());

        // The original base preset (and its cache) must be untouched by the override.
        assertEquals(Blocks.WATER.defaultBlockState(), base.getLiquidBlock());
        assertEquals(Blocks.STONE.defaultBlockState(), base.getBaseBlock());
    }
}
