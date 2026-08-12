package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.UrbexTags;
import dev.krona.urbex.worldgen.lost.BiomeInfo;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.*;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
import java.util.function.Predicate;

public class WorldStyle {

    private final Identifier name;
    private final String displayName;
    private final String outsideStyle;

    private final ScatteredSettings scatteredSettings;
    @Nonnull private final PartSelector partSelector;
    private final List<Pair<Predicate<Holder<Biome>>, Pair<Float, String>>> cityStyleSelector = new ArrayList<>();
    private final List<Pair<Predicate<Holder<Biome>>, Float>> cityBiomeMultiplier = new ArrayList<>();
    @Nonnull private final MultiSettings multiSettings;
    @Nonnull private final WorldSettings worldSettings;
    @Nonnull private final TagKey<Block> rotatableTag;

    /**
     * Builds a fully resolved world style from its {@code extends} chain, root first: every
     * settings block takes the value of the last entry that declares one, and the two selector
     * lists go through {@link Mergeable} so a declared list replaces unless it opts into appending.
     * <p>
     * {@code outsidestyle}, {@code citystyles} and the {@code parts} wiring are required of the
     * chain rather than of each file: a child that only swaps its scattered settings inherits them,
     * and a chain where nothing declares one is a load error naming the asset and the field.
     * <p>
     * The wiring used to default to Urbex's own highway and railway parts when a world style said
     * nothing, which is how a third-party pack could generate parts it never mentioned. It is
     * folded field by field, so a child can append one tunnel variant without restating the group.
     */
    public WorldStyle(Identifier id, List<WorldStyleRE> chainRootFirst) {
        name = id;
        String outside = null;
        ScatteredSettings scattered = null;
        PartSelector parts = null;
        MultiSettings multi = MultiSettings.DEFAULT;
        WorldSettings world = WorldSettings.DEFAULT;
        TagKey<Block> rotatable = null;
        List<CityStyleSelector> selectors = new ArrayList<>();
        boolean anySelectors = false;
        List<CityBiomeMultiplier> multipliers = new ArrayList<>();
        for (WorldStyleRE object : chainRootFirst) {
            if (object.getOutsideStyle() != null) {
                outside = object.getOutsideStyle();
            }
            if (object.getScatteredSettings() != null) {
                scattered = object.getScatteredSettings();
            }
            if (object.getPartSelector() != null) {
                parts = PartSelector.merge(parts, object.getPartSelector());
            }
            if (object.getMultiSettings() != null) {
                multi = object.getMultiSettings();
            }
            if (object.getWorldSettings() != null) {
                world = object.getWorldSettings();
            }
            if (object.getRotatable() != null) {
                rotatable = object.getRotatable();
            }
            if (object.getCityStyleSelectors() != null) {
                Mergeable.apply(selectors, object.getCityStyleSelectors());
                anySelectors = true;
            }
            if (object.getCityBiomeMultipliers() != null) {
                Mergeable.apply(multipliers, object.getCityBiomeMultipliers());
            }
        }
        this.displayName = displayNameOf(chainRootFirst, name);
        this.outsideStyle = Resolved.require(outside, name, "outsidestyle");
        Resolved.require(anySelectors ? selectors : null, name, "citystyles");
        this.scatteredSettings = scattered;
        this.partSelector = Resolved.require(parts, name, "parts").requireComplete(name);
        this.multiSettings = multi;
        this.worldSettings = world;
        // Optional, unlike outsidestyle: a chain that declares none keeps the behaviour every world
        // style had before the field existed, rather than being a load error.
        this.rotatableTag = rotatable == null ? UrbexTags.ROTATABLE_TAG : rotatable;
        for (CityStyleSelector selector : selectors) {
            Predicate<Holder<Biome>> predicate = biomeHolder -> true;
            if (selector.biomeMatcher() != null) {
                predicate = selector.biomeMatcher();
            }
            cityStyleSelector.add(Pair.of(predicate, Pair.of(selector.factor(), selector.citystyle())));
        }
        for (CityBiomeMultiplier multiplier : multipliers) {
            cityBiomeMultiplier.add(Pair.of(multiplier.biomeMatcher(), multiplier.multiplier()));
        }
    }

    /**
     * Folds a resolved chain's {@code name} declarations the same way every other scalar field is
     * folded - last declaring link wins - and fills the gap with {@code id} when nothing declared
     * one. {@code name} is optional like {@code rotatable} rather than required like
     * {@code outsidestyle}: a chain that names itself nowhere is labelled by its id, exactly as
     * every world style was before the field existed.
     * <p>
     * Static and taking the chain rather than reading instance state, because the world-style
     * picker needs the same label from a registry it cannot build a whole {@link WorldStyle} out of
     * (that would require the chain to be complete, which is worldgen's check to make, not a
     * dropdown's).
     */
    public static String displayNameOf(List<WorldStyleRE> chainRootFirst, Identifier id) {
        String display = null;
        for (WorldStyleRE object : chainRootFirst) {
            if (object.getDisplayName() != null) {
                display = object.getDisplayName();
            }
        }
        return display == null || display.isEmpty() ? id.toString() : display;
    }

    /** The fully-qualified id, e.g. {@code "urbex:standard"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    /**
     * What a UI should label this world style: the {@code name} the last declaring link in the chain
     * authored, or the fully-qualified id when nothing did. Never null and never empty.
     */
    public String getDisplayName() {
        return displayName;
    }

    public String getOutsideStyle() {
        return outsideStyle;
    }

    /**
     * The block tag deciding which blocks rotate with the part they sit in, rather than keeping the
     * facing their palette entry authored. Never null: a world style that declares no
     * {@code rotatable} anywhere in its chain resolves {@code urbex:rotatable}.
     */
    @Nonnull
    public TagKey<Block> getRotatableTag() {
        return rotatableTag;
    }

    /**
     * The resolved {@code citystyles}, for tests. The public surface offers only a weighted draw
     * that needs a level and a biome, so without this the chain fold is unobservable.
     */
    List<Pair<Predicate<Holder<Biome>>, Pair<Float, String>>> cityStyleSelectors() {
        return cityStyleSelector;
    }

    @Nonnull
    public PartSelector getPartSelector() {
        return partSelector;
    }

    @Nullable
    public ScatteredSettings getScatteredSettings() {
        return scatteredSettings;
    }

    @Nonnull
    public MultiSettings getMultiSettings() {
        return multiSettings;
    }

    @Nonnull
    public WorldSettings getWorldSettings() {
        return worldSettings;
    }

    public float getCityChanceMultiplier(IDimensionInfo provider, ChunkCoord coord) {
        Holder<Biome> biome = BiomeInfo.getBiomeInfo(provider, coord).getMainBiome();
        for (Pair<Predicate<Holder<Biome>>, Float> pair : cityBiomeMultiplier) {
            if (pair.getLeft().test(biome)) {
                return pair.getRight();
            }
        }
        return 1.0f;
    }

    public String getRandomCityStyle(IDimensionInfo provider, ChunkCoord coord, RandomSource random) {
        Holder<Biome> biome = BiomeInfo.getBiomeInfo(provider, coord).getMainBiome();
        List<Pair<Float, String>> ct = new ArrayList<>();
        for (Pair<Predicate<Holder<Biome>>, Pair<Float, String>> pair : cityStyleSelector) {
            if (pair.getKey().test(biome)) {
                ct.add(pair.getValue());
            }
        }

        Pair<Float, String> randomFromList = Tools.getRandomFromList(random, ct, Pair::getLeft);
        if (randomFromList == null) {
            return null;
        } else {
            return randomFromList.getRight();
        }
    }
}
