package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world style may name the block tag that decides what rotates with its part.
 * <p>
 * The default is what matters most here: every asset written before this field existed, and every
 * asset that simply does not care, must keep resolving to {@code urbex:rotatable}. The field is how
 * a pack says "these blocks are rotatable <em>in my world style</em>" without shipping a file in
 * Urbex's namespace, which is a merge into every other style whether it wants it or not.
 */
class WorldStyleRotatableTagTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static TagKey<Block> tag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(namespace, path));
    }

    private static WorldStyleRE worldStyle(String name, Optional<TagKey<Block>> rotatable) {
        return new WorldStyleRE(Optional.empty(), Optional.empty(), Optional.of("urbex:outside"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(TestWiring.partSelector()),
                Optional.of(new Mergeable<>(true,
                        List.of(new CityStyleSelector(1.0f, "urbex:citystyle_common", null)))),
                Optional.empty(), rotatable)
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", name));
    }

    @Test
    void aChainDeclaringNothingResolvesToUrbexRotatable() {
        WorldStyle resolved = new WorldStyle(List.of(worldStyle("standard", Optional.empty())));

        assertEquals(tag("urbex", "rotatable"), resolved.getRotatableTag(),
                "every asset written before this field existed must keep the old behaviour");
    }

    @Test
    void whatTheChildDeclaresWins() {
        WorldStyle resolved = new WorldStyle(List.of(
                worldStyle("standard", Optional.empty()),
                worldStyle("zombie", Optional.of(tag("urbexza", "rotatable")))));

        assertEquals(tag("urbexza", "rotatable"), resolved.getRotatableTag());
    }

    @Test
    void aChildThatOmitsItInheritsRatherThanRevertingToTheDefault() {
        WorldStyle resolved = new WorldStyle(List.of(
                worldStyle("standard", Optional.of(tag("urbexza", "rotatable"))),
                worldStyle("child", Optional.empty())));

        assertEquals(tag("urbexza", "rotatable"), resolved.getRotatableTag(),
                "absence means inherit, not revert");
    }

    @Test
    void aReferenceWithoutTheHashIsALoadError() {
        var result = WorldStyleRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"outsidestyle": "urbex:outside", "rotatable": "urbexza:rotatable"}
                """));

        assertTrue(result.isError(), "a tag reference is written with a leading '#'");
        assertTrue(result.error().orElseThrow().message().contains("#"),
                "the message should show the shape it wanted: "
                        + result.error().orElseThrow().message());
    }

    @Test
    void anUnqualifiedReferenceIsALoadErrorRatherThanAMinecraftDefault() {
        var result = WorldStyleRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"outsidestyle": "urbex:outside", "rotatable": "#rotatable"}
                """));

        assertTrue(result.isError(),
                "TagKey.hashedCodec would silently make this minecraft:rotatable; we do not");
        assertTrue(result.error().orElseThrow().message().contains("rotatable"),
                result.error().orElseThrow().message());
    }

    @Test
    void aDeclaredTagRoundTripsThroughTheCodec() {
        var decoded = WorldStyleRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"outsidestyle": "urbex:outside", "rotatable": "#urbexza:rotatable"}
                """)).getOrThrow();

        assertEquals(tag("urbexza", "rotatable"), decoded.getRotatable());
    }
}
