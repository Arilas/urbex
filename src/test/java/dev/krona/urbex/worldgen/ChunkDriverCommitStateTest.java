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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fault injection at the commit point.
 * <p>
 * The driver buffers every write into its own section cache and puts them into the world in one
 * pass, so "did this chunk reach the world" has three answers and only the driver knows which. Issue
 * #131 makes a failed chunk say which, and this is the case that cannot be reasoned about from the
 * code alone: a failure <em>during</em> the write leaves some sections placed and some not, and the
 * state must reflect that rather than the intent.
 */
class ChunkDriverCommitStateTest {

    private static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aDriverThatHasNotFlushedHasWrittenNothing() {
        ProtoChunk chunk = protoChunk();
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(levelFor(chunk, false), chunk);
        driver.current(0, 64, 0).block(Blocks.STONE.defaultBlockState());

        assertEquals(ChunkDriver.CommitState.BUFFERED, driver.commitState(),
                "blocks written into the driver are not blocks written into the world");
    }

    @Test
    void aCompletedFlushIsCommitted() {
        ProtoChunk chunk = protoChunk();
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(levelFor(chunk, false), chunk);
        driver.current(0, 64, 0).block(Blocks.STONE.defaultBlockState());

        driver.flushToChunk(chunk);

        assertEquals(ChunkDriver.CommitState.COMMITTED, driver.commitState());
    }

    @Test
    void aFlushThatFailsPartwayStaysCommitting() {
        ProtoChunk chunk = protoChunk();
        ChunkDriver driver = new ChunkDriver();
        // A level that throws when the flush reaches for a section: the driver is midway through
        // putting its buffer into the world, which is exactly the state that must not be reported as
        // either "nothing happened" or "everything happened".
        driver.setPrimer(levelFor(chunk, true), chunk);
        driver.current(0, 64, 0).block(Blocks.STONE.defaultBlockState());

        assertThrows(RuntimeException.class, () -> driver.flushToChunk(chunk));

        assertEquals(ChunkDriver.CommitState.COMMITTING, driver.commitState(),
                "a flush that threw partway must not claim to have committed, nor to have written "
                        + "nothing - part of this chunk's city may be in the world");
    }

    private static ProtoChunk protoChunk() {
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, HEIGHT,
                palettedContainerFactory(), null);
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
                0, Optional.empty(), Optional.empty(), Optional.empty(),
                BiomeSpecialEffects.GrassColorModifier.NONE);
        return Holder.direct(new Biome.BiomeBuilder()
                .temperature(0.5f)
                .downfall(0.5f)
                .specialEffects(effects)
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY)
                .build());
    }

    private static LevelAccessor levelFor(ProtoChunk chunk, boolean failOnChunkLookup) {
        return (LevelAccessor) Proxy.newProxyInstance(
                LevelAccessor.class.getClassLoader(),
                new Class<?>[]{LevelAccessor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMinY" -> HEIGHT.getMinY();
                    case "getMaxY" -> HEIGHT.getMaxY();
                    case "getHeight" -> HEIGHT.getHeight();
                    case "getSectionsCount" -> HEIGHT.getSectionsCount();
                    case "getSectionIndex" -> HEIGHT.getSectionIndex((int) args[0]);
                    case "getChunk" -> {
                        if (failOnChunkLookup) {
                            throw new IllegalStateException("injected fault during commit");
                        }
                        yield chunk;
                    }
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
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }
}
