package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.TimedCache;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public class BiomeInfo {

    private final Holder<Biome> mainBiome;

    private BiomeInfo(Holder<Biome> mainBiome) {
        this.mainBiome = mainBiome;
    }

    public static BiomeInfo getBiomeInfo(IDimensionInfo provider, ChunkCoord coord) {
        BiomeInfo info = provider.caches().biomeInfo.get(coord);
        if (info != null) {
            return info;
        }
        ChunkHeightmap heightmap = provider.getHeightmap(coord);
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        // Fully built before it is published, so the mainBiome can be final and a reader can never
        // catch it half-constructed.
        info = new BiomeInfo(provider.getBiome(new BlockPos((chunkX << 4) + 8, heightmap.getHeight(), (chunkZ << 4) + 8)));
        BiomeInfo raced = provider.caches().biomeInfo.putIfAbsent(coord, info);
        return raced != null ? raced : info;
    }

    public Holder<Biome> getMainBiome() {
        return mainBiome;
    }
}
