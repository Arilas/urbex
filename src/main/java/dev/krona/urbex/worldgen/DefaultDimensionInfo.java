package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.grid.GridRoadField;
import dev.krona.urbex.plan.grid.GridSettings;
import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Builds one loaded level's planning inputs and its generator.
 *
 * <p>What is left of a class that used to be the planning inputs. Every field it held is a component
 * of the {@link PlanningContext} it assembles now, and the assembly is the whole of its body - which
 * is what breaks the cycle it was the other half of: {@code new CityGenerator(this, preset)} handed a
 * half-constructed object to a collaborator that read a level back out of it, so neither type could
 * be built without the other (issue #129).</p>
 */
public class DefaultDimensionInfo implements IDimensionInfo {

    private final PlanningContext planning;
    private final CityGenerator feature;

    public DefaultDimensionInfo(WorldGenLevel world, AssetSnapshot assets, Preset preset,
                                WorldStyleMix worldStyles) {
        // The dimension's ServerLevel, not the region of whichever chunk is generating. Final, so it
        // cannot be swapped out from under a worker thread, which is what the per-dimension lock in
        // CityFeature.place used to be protecting.
        WorldGenLevel level = world.getLevel();
        long seed = level.getSeed();
        DimensionCaches caches = new DimensionCaches(seed);
        planning = new PlanningContext(
                seed,
                level.getLevel().dimension(),
                preset,
                assets,
                WorldStyleField.resolve(assets, seed, worldStyles),
                new GridRoadField(seed, level.getLevel().dimension().identifier().toString(),
                        GridSettings.fromPreset(preset)),
                caches,
                // Resolved here, on the thread that loads the level, rather than per call from
                // generation: the dimension type fixes the two bounds and the chunk generator fixes
                // the sea level, and neither can change while the level is loaded.
                LevelShape.of(level),
                new LevelTerrain(level, preset, caches));
        feature = new CityGenerator(planning, preset);
    }

    @Override
    public PlanningContext planning() {
        return planning;
    }

    @Override
    public CityGenerator getFeature() {
        return feature;
    }
}
