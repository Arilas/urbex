package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.setup.TestRegistries;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The version 2 compile context a test needs to build a part or building carrying an inline palette.
 *
 * <p>{@code VER.018} left {@link Palette#inline} with one branch, and that branch needs the registries
 * version 2 resolves against - block presence, the tag epoch, the {@code definitions} index. Production
 * gets them from {@link AssetCompiler}, once per world load. A test that builds a part directly has no
 * world, so it builds an empty one here rather than passing {@code null}, which the caller refuses by
 * name.
 *
 * <p>Empty is the right shape for these tests and not a shortcut: they are about how an inline palette
 * <em>merges</em> along a part's {@code extends} chain, which is settled before any {@code $ref} is
 * resolved. A test that needs a populated {@code definitions} registry builds its own context; this is
 * for the ones that only need the compiler to run.
 */
final class TestV2Context {

    private TestV2Context() {
    }

    static V2Palettes.Context empty() {
        return V2Palettes.context(TestRegistries.with(), BuiltInRegistries.BLOCK,
                AssetIndex.empty("urbex:conditions"));
    }

    /** One inline palette, written as the version 2 document a part would actually carry. */
    static PaletteAssetDefinition inline(String json) {
        return PaletteAssetDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
    }
}
