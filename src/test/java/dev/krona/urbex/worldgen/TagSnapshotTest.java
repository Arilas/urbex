package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.cityassets.AssetIndex;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which block tags an epoch expands, which is the one thing about {@link TagSnapshot} a unit test
 * can see.
 * <p>
 * <strong>Not membership.</strong> Block tags are bound by the server's tag manager, so nothing is
 * in any tag without a running server and every snapshot built here is empty. What the tags actually
 * contain is pinned by the digest runs, which generate against a real dedicated server: if
 * {@code urbex:lights}, {@code urbex:needspoi} or a {@code rotatable} tag stopped reaching
 * generation, the digest would move.
 * <p>
 * What is worth testing here is the coupling that is easy to get wrong instead. {@code rotatable} is
 * authored per world style, so the set of tags to expand is a property of the loaded pack rather
 * than a constant - and {@link TagSnapshot#isRotatable} answers only for tags it expanded, so a
 * style whose tag was missed would throw from a worldgen worker rather than quietly stop rotating
 * (issue #117).
 */
class TagSnapshotTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockState ANY = Blocks.OAK_STAIRS.defaultBlockState();

    private static TagKey<Block> tag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(namespace, path));
    }

    @Test
    void theDefaultTagIsExpandedEvenWhenNoWorldStyleNamesIt() {
        TagSnapshot tags = TagSnapshot.capture(AssetSnapshot.empty());

        assertDoesNotThrow(() -> tags.isRotatable(UrbexTags.ROTATABLE_TAG, ANY),
                "a world style that declares no 'rotatable' resolves urbex:rotatable, so the "
                        + "snapshot has to carry it whether or not any style mentions it");
    }

    @Test
    void everyWorldStylesOwnTagIsExpanded() {
        TagSnapshot tags = TagSnapshot.capture(worldStyles(
                worldStyle("standard", Optional.empty()),
                worldStyle("zombie", Optional.of(tag("urbexza", "rotatable")))));

        assertDoesNotThrow(() -> tags.isRotatable(tag("urbexza", "rotatable"), ANY));
        assertDoesNotThrow(() -> tags.isRotatable(UrbexTags.ROTATABLE_TAG, ANY));
    }

    /**
     * The failure this shape is chosen to make loud. A tag the snapshot never expanded can only come
     * from a world style outside the {@link AssetSnapshot} it was captured against - which a world
     * cannot produce, because it compiles its assets once and never swaps them.
     */
    @Test
    void aTagNoWorldStyleNamesIsAWiringErrorRatherThanASilentFalse() {
        TagSnapshot tags = TagSnapshot.capture(AssetSnapshot.empty());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> tags.isRotatable(tag("urbexza", "rotatable"), ANY));

        assertTrue(thrown.getMessage().contains("urbexza:rotatable"),
                "the message has to name the tag: " + thrown.getMessage());
    }

    private static AssetSnapshot worldStyles(WorldStyle... styles) {
        Map<Identifier, WorldStyle> byId = new HashMap<>();
        for (WorldStyle style : styles) {
            byId.put(style.getId(), style);
        }
        AssetSnapshot empty = AssetSnapshot.empty();
        return new AssetSnapshot(empty.variants(), empty.palettes(), empty.conditions(), empty.styles(),
                empty.parts(), empty.buildings(), empty.multiBuildings(), empty.scattered(),
                new AssetIndex<>("urbex:worldstyles", byId), empty.cityStyles(),
                empty.predefinedCities(), empty.stuff(), empty.stuffByTag(), empty.predefined());
    }

    private static WorldStyle worldStyle(String name, Optional<TagKey<Block>> rotatable) {
        return new WorldStyle(Identifier.fromNamespaceAndPath("urbex", name), List.of(new WorldStyleDefinition(
                Optional.empty(), Optional.empty(), Optional.of("urbex:outside"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(TestWiring.partSelector()),
                Optional.of(new Mergeable<>(true,
                        List.of(new CityStyleSelector(1.0f, "urbex:citystyle_common", null)))),
                Optional.empty(), rotatable)));
    }
}
