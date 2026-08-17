package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

public class UrbexTags {

    public static final Identifier FOLIAGE = Identifier.fromNamespaceAndPath(Urbex.MODID, "foliage");
    public static final TagKey<Block> FOLIAGE_TAG = TagKey.create(Registries.BLOCK, FOLIAGE);

    public static final Identifier ROTATABLE = Identifier.fromNamespaceAndPath(Urbex.MODID, "rotatable");
    public static final TagKey<Block> ROTATABLE_TAG = TagKey.create(Registries.BLOCK, ROTATABLE);

    public static final Identifier EASY_BREAKABLE = Identifier.fromNamespaceAndPath(Urbex.MODID, "easybreakable");
    public static final TagKey<Block> EASY_BREAKABLE_TAG = TagKey.create(Registries.BLOCK, EASY_BREAKABLE);

    public static final Identifier NOT_BREAKABLE = Identifier.fromNamespaceAndPath(Urbex.MODID, "notbreakable");
    public static final TagKey<Block> NOT_BREAKABLE_TAG = TagKey.create(Registries.BLOCK, NOT_BREAKABLE);

    public static final Identifier NEEDSPOI = Identifier.fromNamespaceAndPath(Urbex.MODID, "needspoi");
    public static final TagKey<Block> NEEDSPOI_TAG = TagKey.create(Registries.BLOCK, NEEDSPOI);

    // Conventional tag (c: namespace) replacing NeoForge's Tags.Biomes.IS_VOID
    public static final TagKey<Biome> IS_VOID = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", "is_void"));
}
