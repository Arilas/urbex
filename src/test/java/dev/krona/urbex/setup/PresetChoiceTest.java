package dev.krona.urbex.setup;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code dimensionsWithPresets} entries are {@code dimension=preset[@worldstylespec]}. The parser
 * is a small static method so it is testable headless, with no server or registry.
 */
class PresetChoiceTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    @Test
    void parsesDimensionPresetEntry() {
        Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> parsed =
                Config.parseDimensionPresetEntry("minecraft:overworld=urbex:rarecities");
        assertTrue(parsed.isPresent());
        assertEquals(dimension("minecraft:overworld"), parsed.get().getKey());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "rarecities"), parsed.get().getValue().preset());
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, parsed.get().getValue().worldStyles());
        assertTrue(parsed.get().getValue().overridesJson().isEmpty());

        Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> explicitStyle =
                Config.parseDimensionPresetEntry("minecraft:the_nether=urbex:cavern@urbex:standard");
        assertTrue(explicitStyle.isPresent());
        assertEquals(dimension("minecraft:the_nether"), explicitStyle.get().getKey());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "cavern"), explicitStyle.get().getValue().preset());
        assertEquals(WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")),
                explicitStyle.get().getValue().worldStyles());

        // A bare (unqualified) name is rejected, not defaulted to the urbex namespace: the entry
        // is logged and dropped, same as any other malformed entry.
        assertTrue(Config.parseDimensionPresetEntry("minecraft:overworld=default").isEmpty());
    }

    @Test
    void malformedEntryIsRejectedWithError() {
        assertTrue(Config.parseDimensionPresetEntry("junk").isEmpty());
        assertTrue(Config.parseDimensionPresetEntry("a=b=c=d").isEmpty());
        // A malformed style spec takes the whole entry down rather than falling back to the default
        // style: generating with the wrong style would hide a typo for the life of the world.
        assertTrue(Config.parseDimensionPresetEntry(
                "minecraft:overworld=urbex:default@urbex:standard*0").isEmpty());
        assertTrue(Config.parseDimensionPresetEntry(
                "minecraft:overworld=urbex:default@standard*0.5+urbex:cavern").isEmpty());
    }
}
