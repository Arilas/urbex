package dev.krona.urbex.setup;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code dimensionsWithPresets} entries are {@code dimension=preset[@worldstylespec]}. The parser
 * is a small static method so it is testable headless, with no server or registry.
 * <p>
 * It takes the {@code experimentalMultiWorldStyles} flag as an argument rather than reading the
 * published config, which is what lets these cases run without publishing anything: the entries are
 * parsed when a config is translated, and the flag is in the same file they came from (issue #130).
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
        Optional<GlobalConfig.DimensionRule> parsed = parse("minecraft:overworld=urbex:rarecities");
        assertTrue(parsed.isPresent());
        assertEquals(dimension("minecraft:overworld"), parsed.get().dimension());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "rarecities"), parsed.get().choice().preset());
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, parsed.get().choice().worldStyles());
        assertTrue(parsed.get().choice().overridesJson().isEmpty());

        Optional<GlobalConfig.DimensionRule> explicitStyle =
                parse("minecraft:the_nether=urbex:cavern@urbex:standard");
        assertTrue(explicitStyle.isPresent());
        assertEquals(dimension("minecraft:the_nether"), explicitStyle.get().dimension());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "cavern"), explicitStyle.get().choice().preset());
        assertEquals(WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")),
                explicitStyle.get().choice().worldStyles());

        // A bare (unqualified) name is rejected, not defaulted to the urbex namespace: the entry
        // is logged and dropped, same as any other malformed entry.
        assertTrue(parse("minecraft:overworld=default").isEmpty());
    }

    @Test
    void malformedEntryIsRejectedWithError() {
        assertTrue(parse("junk").isEmpty());
        assertTrue(parse("a=b=c=d").isEmpty());
        // A malformed style spec takes the whole entry down rather than falling back to the default
        // style: generating with the wrong style would hide a typo for the life of the world.
        assertTrue(parse("minecraft:overworld=urbex:default@urbex:standard*0").isEmpty());
        assertTrue(parse("minecraft:overworld=urbex:default@standard*0.5+urbex:cavern").isEmpty());
    }

    private static Optional<GlobalConfig.DimensionRule> parse(String entry) {
        return GlobalConfig.parseDimensionEntry(entry, true);
    }
}
