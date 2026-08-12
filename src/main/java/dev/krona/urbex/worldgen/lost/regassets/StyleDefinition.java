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
public class StyleDefinition implements Extendable {

    private static final Codec<StyleDefinition> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(Codec.list(PaletteSelector.CODEC)).optionalFieldOf("randompalettes").forGetter(l -> Optional.ofNullable(l.randomPaletteChoices))
            ).apply(instance, StyleDefinition::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<StyleDefinition> CODEC = RetiredKeys.reject(RAW, "style");


    private final Optional<Identifier> extendsId;
    private final Mergeable<List<PaletteSelector>> randomPaletteChoices;   // null when undeclared

    public StyleDefinition(Optional<Identifier> extendsId, Optional<Mergeable<List<PaletteSelector>>> randomPaletteChoices) {
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
