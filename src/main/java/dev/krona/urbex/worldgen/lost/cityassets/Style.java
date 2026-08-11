package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.regassets.StyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;

public class Style {

    private final Identifier name;

    private final List<List<Pair<Float, String>>> randomPaletteChoices = new ArrayList<>();

    /**
     * Builds a fully resolved style from its {@code extends} chain, root first: a declared
     * {@code randompalettes} replaces the inherited groups unless it opts into appending, and an
     * absent one inherits them unchanged. A chain where nothing declares it is a load error.
     */
    public Style(List<StyleRE> chainRootFirst) {
        name = chainRootFirst.get(chainRootFirst.size() - 1).getRegistryName();
        List<List<PaletteSelector>> groups = new ArrayList<>();
        boolean anyGroups = false;
        for (StyleRE object : chainRootFirst) {
            if (object.getRandomPaletteChoices() != null) {
                Mergeable.apply(groups, object.getRandomPaletteChoices());
                anyGroups = true;
            }
        }
        Resolved.require(anyGroups ? groups : null, name, "randompalettes");
        for (List<PaletteSelector> array : groups) {
            List<Pair<Float, String>> palettes = new ArrayList<>();
            for (PaletteSelector selector : array) {
                float factor = selector.factor();
                String palette = selector.palette();
                palettes.add(Pair.of(factor, palette));
            }
            randomPaletteChoices.add(palettes);
        }
    }

    /** The fully-qualified id, e.g. {@code "urbex:common"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    /**
     * The resolved {@code randompalettes}, for tests. The public surface offers only a weighted
     * draw that resolves each choice against the palette registry, so without this the chain fold
     * is unobservable without a level.
     */
    List<List<Pair<Float, String>>> paletteChoices() {
        return randomPaletteChoices;
    }

    public Palette getRandomPalette(IDimensionInfo provider, RandomSource random) {
        Palette palette = new Palette("__random__");
        for (List<Pair<Float, String>> pairs : randomPaletteChoices) {
            float totalweight = 0;
            for (Pair<Float, String> pair : pairs) {
                totalweight += pair.getKey();
            }
            float r = random.nextFloat() * totalweight;
            Palette tomerge = null;
            for (Pair<Float, String> pair : pairs) {
                r -= pair.getKey();
                if (r <= 0) {
                    tomerge = AssetRegistries.PALETTES.getOrThrow(provider.getWorld(), pair.getRight());
                    break;
                }
            }
            palette.merge(tomerge);
        }

        return palette;
    }
}
