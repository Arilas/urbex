package dev.krona.urbex.setup;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code dimensionsWithPresets} entries are {@code dimension=preset[@worldstyle]}. The parser is a
 * small static method so it is testable headless, with no server or registry.
 */
class PresetChoiceTest {

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
        assertEquals(Config.DEFAULT_WORLD_STYLE, parsed.get().getValue().worldStyle());
        assertTrue(parsed.get().getValue().overridesJson().isEmpty());

        Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> explicitStyle =
                Config.parseDimensionPresetEntry("minecraft:the_nether=urbex:cavern@urbex:standard");
        assertTrue(explicitStyle.isPresent());
        assertEquals(dimension("minecraft:the_nether"), explicitStyle.get().getKey());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "cavern"), explicitStyle.get().getValue().preset());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "standard"), explicitStyle.get().getValue().worldStyle());

        // A bare (unqualified) name is rejected, not defaulted to the urbex namespace: the entry
        // is logged and dropped, same as any other malformed entry.
        Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> bareName =
                Config.parseDimensionPresetEntry("minecraft:overworld=default");
        assertTrue(bareName.isEmpty());
    }

    @Test
    void malformedEntryIsRejectedWithError() {
        assertTrue(Config.parseDimensionPresetEntry("junk").isEmpty());
        assertTrue(Config.parseDimensionPresetEntry("a=b=c=d").isEmpty());
    }
}
