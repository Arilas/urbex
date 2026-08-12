package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDriverSectionCacheTest {

    private static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void airOnlySectionWritesAreFlushedOverExistingTerrain() {
        ProtoChunk chunk = new ProtoChunk(
                new ChunkPos(0, 0),
                UpgradeData.EMPTY,
                HEIGHT,
                palettedContainerFactory(),
                null);
        BlockPos cleared = new BlockPos(0, 64, 0);
        chunk.setBlockState(cleared, Blocks.STONE.defaultBlockState(), 0);

        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(levelFor(chunk), chunk);
        driver.current(0, cleared.getY(), 0).block(Blocks.AIR.defaultBlockState());
        driver.flushToChunk(chunk);

        assertTrue(chunk.getBlockState(cleared).isAir(),
                "an air-only cache section must still replace existing terrain");
    }

    private static PalettedContainerFactory palettedContainerFactory() {
        Holder<Biome> biome = dummyBiome();
        IdMapper<Holder<Biome>> biomeIds = new IdMapper<>();
        biomeIds.add(biome);
        return new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                Blocks.AIR.defaultBlockState(),
                null,
                Strategy.createForBiomes(biomeIds),
                biome,
                null);
    }

    private static Holder<Biome> dummyBiome() {
        BiomeSpecialEffects effects = new BiomeSpecialEffects(
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                BiomeSpecialEffects.GrassColorModifier.NONE);
        Biome biome = new Biome.BiomeBuilder()
                .temperature(0.5f)
                .downfall(0.5f)
                .specialEffects(effects)
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY)
                .build();
        return Holder.direct(biome);
    }

    private static LevelAccessor levelFor(ProtoChunk chunk) {
        return (LevelAccessor) Proxy.newProxyInstance(
                LevelAccessor.class.getClassLoader(),
                new Class<?>[]{LevelAccessor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMinY" -> HEIGHT.getMinY();
                    case "getMaxY" -> HEIGHT.getMaxY();
                    case "getHeight" -> HEIGHT.getHeight();
                    case "getSectionsCount" -> HEIGHT.getSectionsCount();
                    case "getSectionIndex" -> HEIGHT.getSectionIndex((int) args[0]);
                    case "getChunk" -> chunk;
                    case "getBlockState" -> chunk.getBlockState((BlockPos) args[0]);
                    case "hasChunk" -> true;
                    case "toString" -> "chunk-level";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }
}
