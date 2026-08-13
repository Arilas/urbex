package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
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

import java.lang.reflect.Proxy;
import java.util.Optional;

/**
 * One empty chunk and a level that contains only it, for driving a {@link ChunkDriver} in a unit
 * test.
 *
 * <p>The level is a proxy rather than a real {@code ServerLevel}: what the driver asks of it is a
 * height range, one chunk, and block reads, and standing a real level up to answer three questions
 * would put a chunk generator, a registry access and a save directory in the way of them.</p>
 */
final class TestChunk {

    static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);

    private TestChunk() {
    }

    static ProtoChunk emptyChunk() {
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, HEIGHT, containerFactory(), null);
    }

    static LevelAccessor levelFor(ProtoChunk chunk) {
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

    private static PalettedContainerFactory containerFactory() {
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
