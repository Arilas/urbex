package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.BiomeInfo;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.*;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
import java.util.function.Predicate;

public class WorldStyle {

    private final Identifier name;
    private final String outsideStyle;

    private final ScatteredSettings scatteredSettings;
    @Nonnull private final PartSelector partSelector;
    private final List<Pair<Predicate<Holder<Biome>>, Pair<Float, String>>> cityStyleSelector = new ArrayList<>();
    private final List<Pair<Predicate<Holder<Biome>>, Float>> cityBiomeMultiplier = new ArrayList<>();
    @Nonnull private final MultiSettings multiSettings;
    @Nonnull private final WorldSettings worldSettings;

    /**
     * Builds a fully resolved world style from its {@code extends} chain, root first: every
     * settings block takes the value of the last entry that declares one, and the two selector
     * lists go through {@link Mergeable} so a declared list replaces unless it opts into appending.
     */
    public WorldStyle(List<WorldStyleRE> chainRootFirst) {
        name = chainRootFirst.get(chainRootFirst.size() - 1).getRegistryName();
        String outside = null;
        ScatteredSettings scattered = null;
        PartSelector parts = PartSelector.DEFAULT;
        MultiSettings multi = MultiSettings.DEFAULT;
        WorldSettings world = WorldSettings.DEFAULT;
        List<CityStyleSelector> selectors = new ArrayList<>();
        List<CityBiomeMultiplier> multipliers = new ArrayList<>();
        for (WorldStyleRE object : chainRootFirst) {
            outside = object.getOutsideStyle();
            if (object.getScatteredSettings() != null) {
                scattered = object.getScatteredSettings();
            }
            if (object.getPartSelector() != null) {
                parts = object.getPartSelector();
            }
            if (object.getMultiSettings() != null) {
                multi = object.getMultiSettings();
            }
            if (object.getWorldSettings() != null) {
                world = object.getWorldSettings();
            }
            Mergeable.apply(selectors, object.getCityStyleSelectors());
            if (object.getCityBiomeMultipliers() != null) {
                Mergeable.apply(multipliers, object.getCityBiomeMultipliers());
            }
        }
        this.outsideStyle = outside;
        this.scatteredSettings = scattered;
        this.partSelector = parts;
        this.multiSettings = multi;
        this.worldSettings = world;
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

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public String getOutsideStyle() {
        return outsideStyle;
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
