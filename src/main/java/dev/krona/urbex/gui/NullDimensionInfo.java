package dev.krona.urbex.gui;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.RoadField;
import dev.krona.urbex.plan.grid.GridRoadField;
import dev.krona.urbex.plan.grid.GridSettings;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.DimensionCaches;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class NullDimensionInfo implements IDimensionInfo {

    public static final int PREVIEW_WIDTH = 62;
    public static final int PREVIEW_HEIGHT = 58;

    private final String[] biomeMap = new String[] {
            "ddddddddddddddddddddddppppppppppppppp==ppppppppppppppppppppppp",
            "ddddddddddddddddddddpppppppppppppppp==pppppppppppppppppppppppp",
            "ddddddddddddddddddddpppppppppppppp===ppppppppppppppppppppppppp",
            "pddddddddddddddddpppppppppppppppppp==ppppppppppppppppppppppppp",
            "pppdddddddppppppppppppppppppppppppp==ppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppp==pppppppppp----------pppp",
            "ppppppppppppppppppppppppppppppppppppp==ppppppp--------------pp",
            "pppppppppppppppppppppppppppppppppppppp==ppppp-----------------",
            "pppppppppppppppppppppppppppppppppppppp===pppp-----------------",
            "ppppppppppppppppppppppppppppppppppppppp===ppppp---------------",
            "pppppppppppppppppppppppppppppppppppppppp==--pp----------------",
            "pppppppppppppppppppppppppppppppppppppppp*---------------------",
            "pppppppppppppppppppppppppppppppppppppp****--------------------",
            "ppppppppppppppppppppppppppppppppppppp***----------------------",
            "pppppppppppppppppppppppppppppppppppp**------------------------",
            "ppppppppppppppppppppppppppppppppppppp**-----------------------",
            "ppppppppppppppppppppppppppppppppppppppp*----------------------",
            "pppppppppppppppppppppppppppppppppppppp**----------------------",
            "ppppp###pppppppppppppppppppppppppppppp**----------------------",
            "ppppp####ppppppp#####pppppppppppppppppp*----------------------",
            "pppppp#####pp##+++#####ppppppppppppp*****---------------------",
            "pppppppp#####++++####pppppppppppppp**------pp----p------------",
            "ppppppppp##++++++###pppppppppppppppp***---pppp--ppp-----------",
            "ppppppppp###+++++++#####ppppppppppppp---pppppppppppp---------p",
            "pppppppp##p##+++++++###ppppppppppppppppppppppppppppp---------p",
            "pppppppppp#####++++####ppppppppppppppppppppppppppppppppp----pp",
            "pppppppppppp###+++++###ppppppppppppppppppppppppppppppppppppppp",
            "ppppppppppppp####++++####ppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppp####++######pppppppppppppppppppppppppppppppppppp",
            "ppppppppppppppp#+++####ppppppppppppppppppppppppppppppppppppppp",
            "ppppppppppppp####pp#####pppppppppppppppppppppppppppppppppppppp",
            "pppppppppp#####ppppppppppppppppppppppppppppppppppppppppppppppp",
            "ppppppppppp###pppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp"
    };

    private final Preset profile;
    private final WorldStyle style;
    private final Random random;
    private final long seed;

    private final RegistryAccess registryAccess;
    @Nullable
    private final Registry<Biome> biomeRegistry;
    private final CityGenerator feature;
    private final DimensionCaches caches;
    private final RoadField roadField;

    public NullDimensionInfo(Preset profile, Identifier worldStyle, long seed, @Nullable RegistryAccess registryAccess) {
        this.profile = profile;
        this.caches = new DimensionCaches(seed);
        WorldStyle resolved = null;
        if (registryAccess != null) {
            try {
                resolved = AssetRegistries.WORLDSTYLES.get(registryAccess, worldStyle);
            } catch (RuntimeException e) {
                // Preview only: fall back to the placeholder below if the chosen style isn't
                // registered (e.g. a stale GUI worldStyle no longer shipped by any datapack).
                Urbex.LOGGER.debug("Preview could not resolve worldstyle '{}'; using the placeholder.", worldStyle, e);
            }
        }
        style = resolved != null ? resolved : new WorldStyle(List.of(new WorldStyleRE(
                Optional.empty(),
                "standard",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new Mergeable<>(true, Collections.emptyList()),
                Optional.empty()
        )));
        this.seed = seed;
        random = new Random(seed);
        feature = new CityGenerator(this, profile);
        this.registryAccess = registryAccess;
        biomeRegistry = registryAccess != null ? registryAccess.lookupOrThrow(Registries.BIOME) : null;
        // The preview's own seed and dimension, so the roads it draws are the roads the world will
        // have. Same construction as DefaultDimensionInfo; there is no server to ask.
        roadField = new GridRoadField(seed, getType().identifier().toString(), GridSettings.fromPreset(profile));
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public WorldGenLevel getWorld() {
        return null;
    }

    @Nullable
    @Override
    public RegistryAccess registryAccess() {
        return registryAccess;
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
        return Level.OVERWORLD;
    }

    @Override
    public Preset getProfile() {
        return profile;
    }

    @Override
    public WorldStyle getWorldStyle() {
        return style;
    }

    /** The config preview renderer's own source. Nothing here places generated blocks. */
    public Random getRandom() {
        return random;
    }

    @Override
    public CityGenerator getFeature() {
        return feature;
    }

    @Override
    public ChunkHeightmap getHeightmap(ChunkCoord coord) {
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        ChunkHeightmap heightmap = new ChunkHeightmap(profile.LANDSCAPE_TYPE, profile.GROUNDLEVEL);
        char b = getBiomeChar(chunkX, chunkZ);
        int y = switch (b) {
            case 'p' -> 65;
            case '-' -> 60;
            case '=' -> 65;
            case '#' -> 95;
            case '+' -> 125;
            case '*' -> 65;
            case 'd' -> 65;
            default -> 65;
        };
        heightmap.update(y);
        return heightmap;
    }

    @Override
    public ChunkHeightmap getHeightmap(int chunkX, int chunkZ) {
        ChunkCoord coord = new ChunkCoord(getType(), chunkX, chunkZ);
        return getHeightmap(coord);
    }

    public char getBiomeChar(int chunkX, int chunkZ) {
        if (chunkX >= 0 && chunkX < PREVIEW_WIDTH && chunkZ >= 0 && chunkZ < PREVIEW_HEIGHT) {
            return biomeMap[chunkZ].charAt(chunkX);
        } else {
            return 'p';
        }
    }

//    @Override
//    public Biome[] getBiomes(int chunkX, int chunkZ) {
//        Biome[] biomes = new Biome[10*10];
//        Biome biome = Biomes.PLAINS;
//        char b = getBiomeChar(chunkX, chunkZ);
//        switch (b) {
//            case 'p': biome = Biomes.PLAINS; break;
//            case '-': biome = Biomes.OCEAN; break;
//            case '=': biome = Biomes.RIVER; break;
//            case '#': biome = Biomes.MOUNTAIN_EDGE; break;
//            case '+': biome = Biomes.MOUNTAINS; break;
//            case '*': biome = Biomes.BEACH; break;
//            case 'd': biome = Biomes.DESERT; break;
//        }
//        for (int i = 0 ; i < biomes.length ; i++) {
//            biomes[i] = biome;
//        }
//        return biomes;
//    }

    @Nullable
    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        if (biomeRegistry == null) {
            // #67: no registry access (the deprecated no-registry constructor) means there's no
            // registry to resolve even a plains fallback from - dereferencing it unconditionally is
            // what used to NPE here. Every caller currently reachable without registry access
            // (BuildingInfo.getChunkCharacteristicsGui and friends) never dereferences this result:
            // the registryAccess()-gated rules in City that do read biomes only run when we're not
            // in this branch.
            Urbex.LOGGER.warn("NullDimensionInfo.getBiome() called without registry access; returning null.");
            return null;
        }
        ChunkPos cp = ChunkPos.containing(pos);
        char b = getBiomeChar(cp.x(), cp.z());
        ResourceKey<Biome> biome = switch (b) {
            case 'p' -> Biomes.PLAINS;
            case '-' -> Biomes.OCEAN;
            case '=' -> Biomes.RIVER;
            case '#' -> Biomes.STONY_PEAKS;
            // @todo 1.18
            case '+' -> Biomes.JAGGED_PEAKS;
            // @todo 1.18
            case '*' -> Biomes.BEACH;
            case 'd' -> Biomes.DESERT;
            // Plains fallback for anything unmapped, same as the old default branch.
            default -> Biomes.PLAINS;
        };
        return biomeRegistry.getOrThrow(biome);
    }

    @Override
    public ResourceKey<Level> dimension() {
        // Agrees with getType(): both name the overworld. dimension() used to return null here,
        // which disagreed with getType() and tripped up anything that assumed the two matched (#67).
        return Level.OVERWORLD;
    }
}
