package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.RoadField;
import dev.krona.urbex.plan.grid.GridRoadField;
import dev.krona.urbex.plan.grid.GridSettings;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import org.jetbrains.annotations.Nullable;

public class DefaultDimensionInfo implements IDimensionInfo {

    // The dimension's ServerLevel, not the region of whichever chunk is generating. Final, so it
    // cannot be swapped out from under a worker thread, which is what the per-dimension lock in
    // CityFeature.place used to be protecting.
    private final WorldGenLevel world;
    private final LevelShape shape;
    private final AssetSnapshot assets;
    private final Preset profile;
    private final WorldStyleField styles;
    private final DimensionCaches caches;

    private final LevelTerrain terrain;
    private final CityGenerator feature;
    private final RoadField roadField;

    public DefaultDimensionInfo(WorldGenLevel world, AssetSnapshot assets, Preset preset,
                                WorldStyleMix worldStyles) {
        this.world = world.getLevel();
        // Resolved here, on the thread that loads the level, rather than per call from generation:
        // the dimension type fixes the two bounds and the chunk generator fixes the sea level, and
        // neither can change while the level is loaded.
        this.shape = LevelShape.of(this.world);
        this.assets = assets;
        this.profile = preset;
        this.caches = new DimensionCaches(this.world.getSeed());
        styles = WorldStyleField.resolve(assets, this.world.getSeed(), worldStyles);
        // Before the generator, and no longer on it: sampling the ground height is not generation,
        // and having the generator own it is half of why the generator and this class have to
        // construct each other (issue #129).
        terrain = new LevelTerrain(this.world, preset, caches);
        feature = new CityGenerator(this, preset);
        roadField = new GridRoadField(this.world.getSeed(), getType().identifier().toString(),
                GridSettings.fromPreset(preset));
    }

    @Override
    public long getSeed() {
        return world.getSeed();
    }

    @Override
    public AssetSnapshot assets() {
        return assets;
    }

    @Override
    public LevelShape shape() {
        return shape;
    }

    @Override
    public DimensionCaches caches() {
        return caches;
    }

    @Override
    public RoadField roadField() {
        return roadField;
    }

    @Override
    public ResourceKey<Level> getType() {
        return world.getLevel().dimension();
    }

    @Override
    public Preset getProfile() {
        return profile;
    }

    @Override
    public WorldStyleField worldStyles() {
        return styles;
    }

    @Override
    public CityGenerator getFeature() {
        return feature;
    }

    @Override
    public TerrainSampler terrain() {
        return terrain;
    }

    @Nullable
    @Override
    public ResourceKey<Level> dimension() {
        return world.getLevel().dimension();
    }
}
