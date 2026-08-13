package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.StyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.RandomSource;

public class Style {

    private final Identifier name;

    private final List<List<Pair<Float, Palette>>> randomPaletteChoices = new ArrayList<>();

    /** One merged palette per set of choices; see {@link #compose}. */
    private final Map<List<Palette>, Palette> composed = new ConcurrentHashMap<>();

    /**
     * Builds a fully resolved style from its {@code extends} chain, root first: a declared
     * {@code randompalettes} replaces the inherited groups unless it opts into appending, and an
     * absent one inherits them unchanged. A chain where nothing declares it is a load error.
     */
    /**
     * @param palettes the compiled palettes this style's {@code randompalettes} name. Resolved here
     *                 rather than on the first chunk that needs one: the lookup used to happen inside
     *                 {@link #getRandomPalette}, from a worldgen worker, which is why that method took
     *                 a {@code PlanningContext} it otherwise had no use for (issue #128).
     */
    public Style(Identifier id, AssetIndex<Palette> palettes, List<StyleDefinition> chainRootFirst) {
        name = id;
        List<List<PaletteSelector>> groups = new ArrayList<>();
        boolean anyGroups = false;
        for (StyleDefinition object : chainRootFirst) {
            if (object.getRandomPaletteChoices() != null) {
                Mergeable.apply(groups, object.getRandomPaletteChoices());
                anyGroups = true;
            }
        }
        Resolved.require(anyGroups ? groups : null, name, "randompalettes");
        for (List<PaletteSelector> array : groups) {
            // Weights checked before references are resolved, so a group nothing could ever be drawn
            // from says exactly that rather than reporting whichever of its palettes is missing. Both
            // are load errors; this one is about the group and is the more useful of the two.
            float total = 0;
            for (PaletteSelector selector : array) {
                total += selector.factor();
            }
            requireDrawable(array, total);
            List<Pair<Float, Palette>> resolved = new ArrayList<>(array.size());
            for (PaletteSelector selector : array) {
                resolved.add(Pair.of(selector.factor(), palettes.getOrThrow(selector.palette())));
            }
            randomPaletteChoices.add(resolved);
        }
    }

    /**
     * A group whose weights cannot produce a winner is a load error, because the alternative is a
     * {@code NullPointerException} out of {@link #getRandomPalette}'s {@code merge} on a worldgen
     * worker thread - the group is walked subtracting each factor from {@code r} and taking the
     * first that drives it to zero, so an empty group or one whose factors sum to zero (or less)
     * leaves nothing selected and the caller merges null.
     */
    private void requireDrawable(List<PaletteSelector> palettes, float total) {
        if (palettes.isEmpty()) {
            throw new IllegalStateException("Style '" + name + "' declares an empty "
                    + "'randompalettes' group; every group must offer at least one palette");
        }
        if (total <= 0) {
            throw new IllegalStateException("Style '" + name + "' declares a 'randompalettes' group "
                    + "whose factors total " + total + "; no palette could ever be drawn from it. "
                    + "At least one palette in each group needs a factor above zero.");
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
     * draw, so without this the chain fold is unobservable.
     */
    List<List<Pair<Float, Palette>>> paletteChoices() {
        return randomPaletteChoices;
    }

    public Palette getRandomPalette(RandomSource random) {
        List<Palette> chosen = new ArrayList<>(randomPaletteChoices.size());
        for (List<Pair<Float, Palette>> pairs : randomPaletteChoices) {
            float totalweight = 0;
            for (Pair<Float, Palette> pair : pairs) {
                totalweight += pair.getKey();
            }
            float r = random.nextFloat() * totalweight;
            // The last entry, not null, when the subtractions never drive r to zero. The
            // constructor has already refused a group that cannot produce a winner, so reaching
            // here means float drift: summing the factors and subtracting them again are not the
            // same arithmetic, and r can survive the whole walk by an ulp. Falling out with null
            // and merging it is a NullPointerException on a worldgen worker; the last entry is the
            // one the walk was an ulp short of choosing.
            Pair<Float, Palette> chosenPair = pairs.get(pairs.size() - 1);
            for (Pair<Float, Palette> pair : pairs) {
                r -= pair.getKey();
                if (r <= 0) {
                    chosenPair = pair;
                    break;
                }
            }
            chosen.add(chosenPair.getRight());
        }
        return compose(List.copyOf(chosen));
    }

    /**
     * The merged palette for one set of choices, built once per set.
     *
     * <p>This used to build a fresh {@code Palette("__random__")} on every call - one per chunk -
     * which is why memoizing anything downstream of it did nothing: the compiled-palette cache keyed
     * on this object and every chunk handed it a new one, so 1028 lookups hit 13 times. Keyed on the
     * chosen palettes it is bounded by what the style declares (issue #53).</p>
     *
     * <p>Safe to share because nothing writes to the result: {@code getRandomPalette} is the only
     * caller of {@code Palette.merge} outside asset compilation, and {@code ChunkPlan.palette} is
     * written once and only read.</p>
     *
     * <p>get / compute-outside / putIfAbsent, not {@code computeIfAbsent}: merging inside a
     * {@code ConcurrentHashMap} bin lock stalls every other worldgen thread whose key shares the
     * bin, and racing threads merge equal palettes from immutable inputs.</p>
     */
    private Palette compose(List<Palette> chosen) {
        Palette existing = composed.get(chosen);
        if (existing != null) {
            return existing;
        }
        Palette palette = new Palette("__random__");
        for (Palette part : chosen) {
            palette.merge(part);
        }
        Palette raced = composed.putIfAbsent(chosen, palette);
        return raced != null ? raced : palette;
    }
}
