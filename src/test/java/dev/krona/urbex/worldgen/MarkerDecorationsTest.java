package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.Condition;
import dev.krona.urbex.worldgen.lost.cityassets.ConditionContext;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import dev.krona.urbex.worldgen.lost.regassets.ConditionDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production final handler with real conditions and a committed protochunk. */
class MarkerDecorationsTest {
    private static final long SEED = 226;
    private static final BlockPos AT = new BlockPos(3, 70, 5);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void finalLootUsesTheOriginalPositionStreamAndKeepsAuthoredNbt() {
        Condition pool = condition("""
                {"values":[
                  {"factor":1,"value":"minecraft:chests/simple_dungeon","inpart":"urbex:room","inbuilding":"urbex:house"},
                  {"factor":1,"value":"minecraft:chests/abandoned_mineshaft","inpart":"urbex:room","inbuilding":"urbex:house"}
                ]}
                """);
        ConditionContext origin = context("urbex:room", "urbex:house");
        CompoundTag authored = new CompoundTag();
        authored.putString("CustomName", "Supplies");
        authored.putString("id", "minecraft:mob_spawner");
        authored.putInt("x", -999);
        CompoundTag original = authored.copy();
        CompiledPalette.Placed material = new CompiledPalette.Placed(Blocks.BARREL.defaultBlockState(),
                Palette.Info.of(null, pool.getName(), null, authored));
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = driver(chunk);
        for (int x = 0; x < 16; x++) {
            driver.current(x, AT.getY(), AT.getZ()).block(material);
        }
        driver.actuallyGenerate(chunk, -1, (target, pos, actual, selected, ignoredOrigin) ->
                MarkerDecorations.apply(target, pos, actual, selected, preset(1, 1), SEED,
                        (id, random, effect) -> {
                            assertEquals(pool.getName(), id);
                            assertEquals("loot", effect);
                            return MarkerDecorations.selectCondition(pool, origin, random, effect);
                        }));

        Set<String> outcomes = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            BlockPos pos = new BlockPos(x, AT.getY(), AT.getZ());
            CompoundTag tag = chunk.getBlockEntityNbt(pos);
            assertNotNull(tag);
            String expected = pool.getRandomValue(Rng.atPos(SEED, x, pos.getY(), pos.getZ(), Rng.Purpose.LOOT), origin);
            assertEquals(expected, tag.getStringOr("LootTable", ""));
            assertEquals(0L, tag.getLongOr("LootTableSeed", -1));
            assertEquals("Supplies", tag.getStringOr("CustomName", ""));
            assertEquals("minecraft:barrel", tag.getStringOr("id", ""));
            assertEquals(x, tag.getIntOr("x", -1));
            assertEquals(pos.getY(), tag.getIntOr("y", -1));
            assertEquals(pos.getZ(), tag.getIntOr("z", -1));
            assertTrue(chunk.getBlockState(pos).is(Blocks.BARREL));
            outcomes.add(expected);
        }
        assertEquals(2, outcomes.size());
        assertEquals(original, authored, "the compiled marker's shared NBT is immutable");
    }

    @Test
    void spawnerAdmissionHappensBeforeWritingAndOnlyForAnActualSpawner() {
        CompiledPalette.Placed material = new CompiledPalette.Placed(Blocks.SPAWNER.defaultBlockState(),
                Palette.Info.of("urbex:mobs", null, null, new CompoundTag()));
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = driver(chunk);
        Preset disabled = preset(1, 0);
        driver.setMaterialPolicy((x, y, z, actual, selected, origin) ->
                MarkerDecorations.prepareState(disabled, SEED, x, y, z, actual, selected));
        driver.current(AT.getX(), AT.getY(), AT.getZ()).block(material);
        assertTrue(driver.getBlock().isAir());
        AtomicInteger finalizations = new AtomicInteger();
        driver.actuallyGenerate(chunk, -1, (target, pos, actual, selected, origin) -> finalizations.incrementAndGet());
        assertEquals(0, finalizations.get(), "denied spawner metadata must not reach finalization");
        assertNull(chunk.getBlockEntityNbt(AT));
        assertSame(Blocks.STONE.defaultBlockState(), MarkerDecorations.prepareState(disabled, SEED,
                AT.getX(), AT.getY(), AT.getZ(), Blocks.STONE.defaultBlockState(), material),
                "a non-spawner outcome is not removed by the spawner switch");
    }

    @Test
    void finalSpawnerComposesSelectedMobWithAuthoredFieldsAndAuthoritativeCoordinates() {
        Condition pool = condition("""
                {"values":[{"factor":1,"value":"minecraft:skeleton","inpart":"urbex:room"}]}
                """);
        CompoundTag authored = new CompoundTag();
        authored.putInt("Delay", 40);
        authored.putString("id", "minecraft:barrel");
        authored.putInt("y", -999);
        CompiledPalette.Placed material = new CompiledPalette.Placed(Blocks.SPAWNER.defaultBlockState(),
                Palette.Info.of(pool.getName(), null, null, authored));
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = driver(chunk);
        driver.current(AT.getX(), AT.getY(), AT.getZ()).block(material);
        driver.actuallyGenerate(chunk, -1, (target, pos, actual, selected, origin) ->
                MarkerDecorations.apply(target, pos, actual, selected, preset(1, 1), SEED,
                        (id, random, effect) -> MarkerDecorations.selectCondition(pool,
                                context("urbex:room", "urbex:house"), random, effect)));
        CompoundTag tag = chunk.getBlockEntityNbt(AT);
        assertNotNull(tag);
        SpawnData spawn = SpawnData.CODEC.parse(NbtOps.INSTANCE, tag.get("SpawnData")).getOrThrow();
        assertEquals("minecraft:skeleton", spawn.getEntityToSpawn().getStringOr("id", ""));
        assertEquals("minecraft:mob_spawner", tag.getStringOr("id", ""));
        assertEquals(AT.getY(), tag.getIntOr("y", -1));
        assertEquals(40, tag.getIntOr("Delay", -1));
    }

    @Test
    void noMatchingProceduralConditionSkipsItsEffectWithoutLosingAuthoredNbt() {
        Condition pool = condition("""
                {"values":[{"factor":1,"value":"minecraft:chests/simple_dungeon","inpart":"urbex:room"}]}
                """);
        CompoundTag authored = new CompoundTag();
        authored.putString("CustomName", "Empty supplies");
        CompiledPalette.Placed material = new CompiledPalette.Placed(Blocks.BARREL.defaultBlockState(),
                Palette.Info.of(null, pool.getName(), null, authored));
        ProtoChunk chunk = TestChunk.emptyChunk();
        MarkerDecorations.apply(chunk, AT, material.state(), material, preset(1, 1), SEED,
                (id, random, effect) -> MarkerDecorations.selectCondition(pool,
                        context(ConditionContext.NO_PART, ConditionContext.NO_PART), random, effect));
        CompoundTag tag = chunk.getBlockEntityNbt(AT);
        assertNotNull(tag);
        assertFalse(tag.contains("LootTable"));
        assertEquals("Empty supplies", tag.getStringOr("CustomName", ""));
    }

    @Test
    void lootDensityZeroDoesNotResolveThePoolAndAuthoredLootFieldsHavePrecedence() {
        CompoundTag authored = new CompoundTag();
        authored.putString("LootTable", "minecraft:chests/spawn_bonus_chest");
        authored.putLong("LootTableSeed", 91L);
        CompiledPalette.Placed material = new CompiledPalette.Placed(Blocks.BARREL.defaultBlockState(),
                Palette.Info.of(null, "urbex:loot", null, authored));
        ProtoChunk chunk = TestChunk.emptyChunk();
        MarkerDecorations.apply(chunk, AT, material.state(), material, preset(0, 1), SEED,
                (id, random, effect) -> fail("density zero must not select a condition value"));
        assertEquals(authored.get("LootTable"), chunk.getBlockEntityNbt(AT).get("LootTable"));
        MarkerDecorations.apply(chunk, AT, material.state(), material, preset(1, 1), SEED,
                (id, random, effect) -> Identifier.parse("minecraft:chests/simple_dungeon"));
        CompoundTag tag = chunk.getBlockEntityNbt(AT);
        assertEquals(authored.get("LootTable"), tag.get("LootTable"));
        assertEquals(91L, tag.getLongOr("LootTableSeed", -1));
    }

    @Test
    void replacingAFlushedLootMarkerNeverResolvesItsPoolOrRestoresItsBlock() {
        CompiledPalette.Placed lootBarrel = new CompiledPalette.Placed(Blocks.BARREL.defaultBlockState(),
                Palette.Info.of(null, "urbex:old_loot", null, null));
        for (var replacement : List.of(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.BARREL.defaultBlockState())) {
            ProtoChunk chunk = TestChunk.emptyChunk();
            ChunkDriver driver = driver(chunk);
            driver.current(AT.getX(), AT.getY(), AT.getZ()).block(lootBarrel);
            driver.flushToChunk(chunk);
            assertTrue(chunk.getBlockState(AT).is(Blocks.BARREL));

            // A later part or damage pass owns this position, even when its plain state equals
            // the old barrel. An intermediate flush must not make the old decoration permanent.
            driver.current(AT.getX(), AT.getY(), AT.getZ()).block(replacement);
            driver.actuallyGenerate(chunk, -1, (target, pos, actual, selected, origin) ->
                    MarkerDecorations.apply(target, pos, actual, selected, preset(1, 1), SEED,
                            (id, random, effect) -> fail("overwritten loot pool must not resolve: " + id)));

            assertEquals(replacement, chunk.getBlockState(AT),
                    "loot finalization must leave the later replacement in place");
            assertNull(chunk.getBlockEntityNbt(AT), "the old loot decorator must leave no queued NBT");
        }
    }

    @Test
    void minecraftLoadsTheHandlersSavedPendingLootNbtIntoAnActualContainer() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Identifier table = Identifier.parse("minecraft:chests/simple_dungeon");
        for (long lootSeed : new long[]{0L, 91L}) {
            CompoundTag authored = new CompoundTag();
            if (lootSeed != 0L) {
                authored.putLong("LootTableSeed", lootSeed);
            }
            CompiledPalette.Placed material = new CompiledPalette.Placed(Blocks.BARREL.defaultBlockState(),
                    Palette.Info.of(null, "urbex:loot", null, authored));
            ProtoChunk chunk = TestChunk.emptyChunk();
            ChunkDriver driver = driver(chunk);
            driver.current(AT.getX(), AT.getY(), AT.getZ()).block(material);
            driver.actuallyGenerate(chunk, -1, (target, pos, actual, selected, origin) ->
                    MarkerDecorations.apply(target, pos, actual, selected, preset(1, 1), SEED,
                            (id, random, effect) -> table));

            // This is the pending tag the chunk serializer saves. Minecraft's promotion path
            // feeds it to loadStatic, which constructs the actual type and calls its NBT loader.
            CompoundTag saved = chunk.getBlockEntityNbtForSaving(AT, registries);
            assertNotNull(saved);
            BarrelBlockEntity loaded = assertInstanceOf(BarrelBlockEntity.class,
                    BlockEntity.loadStatic(AT, chunk.getBlockState(AT), saved, registries));
            assertEquals(AT, loaded.getBlockPos());
            assertEquals(ResourceKey.create(Registries.LOOT_TABLE, table), loaded.getLootTable());
            assertEquals(lootSeed, loaded.getLootTableSeed());
        }
    }

    private static ChunkDriver driver(ProtoChunk chunk) {
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        return driver;
    }

    private static Preset preset(float loot, float spawner) {
        PresetDraft draft = new PresetDraft(Identifier.parse("urbex:marker_decorations_test"));
        draft.LOOT_DENSITY = loot;
        draft.SPAWNER_DENSITY = spawner;
        return draft.resolve();
    }

    private static Condition condition(String json) {
        ConditionDefinition definition = ConditionDefinition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        return new Condition(Identifier.parse("urbex:marker_decorations_test"), List.of(definition));
    }

    private static ConditionContext context(String part, String building) {
        return new ConditionContext(1, 1, 1, 3, part, ConditionContext.NO_PART, building,
                new ChunkCoord(Level.OVERWORLD, 0, 0)) {
            @Override
            public Identifier getBiome() {
                return Identifier.parse("minecraft:plains");
            }
        };
    }
}
