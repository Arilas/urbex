package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A style: the groups of palettes a building can be painted from.
 * <p>
 * {@code randompalettes} is optional here rather than required, because requiredness is checked
 * after the {@code extends} chain is resolved, in
 * {@link dev.krona.urbex.worldgen.lost.cityassets.Style}.
 */
public class StyleRE implements Extendable {

    private static final Codec<StyleRE> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(Codec.list(PaletteSelector.CODEC)).optionalFieldOf("randompalettes").forGetter(l -> Optional.ofNullable(l.randomPaletteChoices))
            ).apply(instance, StyleRE::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<StyleRE> CODEC = RetiredKeys.reject(RAW, "style");


    private final Optional<Identifier> extendsId;
    private final Mergeable<List<PaletteSelector>> randomPaletteChoices;   // null when undeclared

    public StyleRE(Optional<Identifier> extendsId, Optional<Mergeable<List<PaletteSelector>>> randomPaletteChoices) {
        this.extendsId = extendsId;
        this.randomPaletteChoices = randomPaletteChoices.orElse(null);
    }

    @Nullable
    public Mergeable<List<PaletteSelector>> getRandomPaletteChoices() {
        return randomPaletteChoices;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }


}
