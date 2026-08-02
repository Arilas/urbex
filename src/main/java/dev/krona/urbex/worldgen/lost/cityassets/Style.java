package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.regassets.StyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Style {

    private final Identifier name;

    private final List<List<Pair<Float, String>>> randomPaletteChoices = new ArrayList<>();

    public Style(StyleRE object) {
        name = object.getRegistryName();
        for (List<PaletteSelector> array : object.getRandomPaletteChoices()) {
            List<Pair<Float, String>> palettes = new ArrayList<>();
            for (PaletteSelector selector : array) {
                float factor = selector.factor();
                String palette = selector.palette();
                palettes.add(Pair.of(factor, palette));
            }
            randomPaletteChoices.add(palettes);
        }
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public Palette getRandomPalette(IDimensionInfo provider, Random random) {
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
