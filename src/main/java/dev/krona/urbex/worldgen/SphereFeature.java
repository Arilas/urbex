package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.setup.Registration;
import dev.krona.urbex.worldgen.gen.Spheres;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

public class SphereFeature extends Feature<NoneFeatureConfiguration> {

    public SphereFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        if (level instanceof WorldGenRegion) {
            IDimensionInfo diminfo = Registration.cityFeature().getDimensionInfo(level);
            if (diminfo != null) {
                WorldGenRegion region = (WorldGenRegion) level;
                ChunkPos center = region.getCenter();
                Holder<Biome> biome = region.getBiome(center.getMiddleBlockPosition(60));
                if (biome.is(UrbexTags.IS_VOID)) {
                    return false;
                }

                int chunkX = center.x();
                int chunkZ = center.z();
                // See CityFeature.place: no shared mutable state left to guard.
                CityGenerator feature = diminfo.getFeature();
                // Same treatment as CityFeature.place: an exception thrown out of here
                // propagates into vanilla's feature loop and kills generation of the whole chunk,
                // so it is logged with the same context and swallowed instead.
                try {
                    Spheres.generateSpheres(feature, region, region.getChunk(chunkX, chunkZ));
                } catch (Exception e) {
                    Urbex.getLogger().error("Error generating spheres for chunk {},{} (profile={}, dimension={})",
                            chunkX, chunkZ, diminfo.getProfile().getName(), diminfo.getType().identifier(), e);
                    ErrorLogger.logChunkInfo(chunkX, chunkZ, diminfo);
                    ErrorLogger.report("There was an error generating a chunk. See log for details!");
                }
                return true;
            }
        }
        return false;
    }
}
