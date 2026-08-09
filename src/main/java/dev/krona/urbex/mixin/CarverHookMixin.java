package dev.krona.urbex.mixin;

import dev.krona.urbex.setup.Registration;
import dev.krona.urbex.worldgen.CityFeature;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs Urbex city generation at the tail of the carver stage instead of as a decoration
 * feature.
 * <p>
 * Rationale (issue #18): a chunk's decoration step may run before or after its neighbours'
 * complete decoration passes, so a feature reading the live terrain sees neighbouring ore blobs
 * and border-crossing trees - or not - depending on worker-thread scheduling, and generated
 * output was not run-to-run reproducible. The chunk pipeline guarantees no neighbour's feature
 * pass starts until this chunk has finished CARVERS, so at this point the terrain is a pure
 * function of the seed (noise + surface + carvers), and every vanilla feature lands strictly
 * after the city, deterministically.
 * <p>
 * Applied to both generator types that implement carving; dimensions without an Urbex profile
 * return immediately inside {@link CityFeature#generateFromPipeline}.
 */
@Mixin({NoiseBasedChunkGenerator.class, FlatLevelSource.class})
public class CarverHookMixin {

    @Inject(method = "applyCarvers", at = @At("TAIL"))
    private void urbex$generateCity(WorldGenRegion region, long seed, RandomState randomState,
                                    BiomeManager biomeManager, StructureManager structureManager,
                                    ChunkAccess chunk, CallbackInfo ci) {
        CityFeature feature = Registration.cityFeature();
        if (feature != null) {
            feature.generateFromPipeline(region, chunk);
        }
    }
}
