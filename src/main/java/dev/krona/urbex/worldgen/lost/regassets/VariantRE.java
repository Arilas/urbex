package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A weighted set of blockstates a palette marker can resolve to.
 * <p>
 * {@code blocks} is optional here rather than required, because requiredness is checked after the
 * {@code extends} chain is resolved, in {@link dev.krona.urbex.worldgen.lost.cityassets.Variant}.
 */
public class VariantRE implements IAsset<VariantRE>, Extendable {

    private static final Codec<VariantRE> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(BlockEntry.CODEC).optionalFieldOf("blocks").forGetter(l -> Optional.ofNullable(l.blocks))
            ).apply(instance, VariantRE::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<VariantRE> CODEC = RetiredKeys.reject(RAW, "variant");

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final Mergeable<BlockEntry> blocks;   // null when this entry declares none

    public VariantRE(Optional<Identifier> extendsId, Optional<Mergeable<BlockEntry>> entries) {
        this.extendsId = extendsId;
        this.blocks = entries.orElse(null);
    }

    @Nullable
    public Mergeable<BlockEntry> getBlocks() {
        return blocks;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public VariantRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}
