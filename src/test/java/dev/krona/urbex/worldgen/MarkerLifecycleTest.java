package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.worldgen.gen.Damage;
import dev.krona.urbex.worldgen.lost.DamageArea;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.ConditionContext;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Accepted writes, shape corrections and final decoration share one surviving material owner. */
class MarkerLifecycleTest {
    private static final PlacementOrigin PROCEDURAL = new PlacementOrigin(null, null);
    private static final PlacementOrigin FRONT = new PlacementOrigin(null, "urbex:front");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void onlySurvivingDecoratorsFinalizeAndSameTypeReplacementsDiscardOldNbt() {
        ProtoChunk chunk = TestChunk.emptyChunk(new ChunkPos(7, -11));
        BlockPos kept = new BlockPos(115, 80, -172);
        BlockPos literal = kept.east();
        BlockPos untouched = kept.above(3);
        for (BlockPos position : List.of(kept, literal, untouched)) {
            chunk.setBlockState(position, Blocks.CHEST.defaultBlockState(), 0);
            chunk.setBlockEntityNbt(nbt(position, "old"));
        }
        ChunkDriver driver = driver(chunk);
        CompiledPalette.Placed original = chest("first");
        CompiledPalette.Placed replacement = chest("second");
        driver.currentAbsolute(kept).block(original.state(), original, FRONT);
        driver.flushToChunk(chunk);
        driver.currentAbsolute(kept).block(replacement);
        assertSame(PROCEDURAL, driver.originHere());
        driver.block((BlockState) null);
        driver.block(Blocks.STRUCTURE_VOID.defaultBlockState(), original, FRONT);
        assertSame(PROCEDURAL, driver.originHere(), "transparent writes cannot change ownership");
        driver.currentAbsolute(literal).block(original.state(), original, FRONT);
        driver.block(Blocks.CHEST.defaultBlockState());
        assertNull(driver.originHere(), "an accepted same-state literal erases authored ownership");

        List<BlockPos> finalized = new ArrayList<>();
        driver.actuallyGenerate(chunk, 0L, (target, position, actual, material, origin) -> {
            assertNull(target.getBlockEntityNbt(position), "same-type old NBT must be gone before decorators run");
            assertSame(replacement, material);
            assertSame(PROCEDURAL, origin);
            assertEquals(material.state(), actual);
            finalized.add(position);
            target.setBlockEntityNbt(nbt(position, material.info().tag().getStringOr("Owner", "")));
        });
        assertEquals(List.of(kept), finalized);
        assertEquals("second", chunk.getBlockEntityNbt(kept).getStringOr("Owner", ""));
        assertNull(chunk.getBlockEntityNbt(literal));
        assertEquals("old", chunk.getBlockEntityNbt(untouched).getStringOr("Owner", ""));
        assertEquals(ConditionContext.NO_PART, PROCEDURAL.part());
        assertNull(driver.currentAbsolute(kept).originHere(), "publication releases ownership");
        driver.block(original.state(), original, FRONT);
        assertNull(driver.originHere(), "late writes cannot recreate released ownership");
    }

    @Test
    void sameBlockNeighborShapeChangesPreserveVanillaNbtAndAuthoredOwnership() {
        ProtoChunk chunk = TestChunk.emptyChunk();
        BlockPos vanilla = new BlockPos(5, 80, 5);
        BlockPos authored = new BlockPos(5, 84, 5);
        BlockState disconnected = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
        chunk.setBlockState(vanilla, disconnected, 0);
        chunk.setBlockEntityNbt(nbt(vanilla, "vanilla"));
        ChunkDriver driver = driver(chunk);
        CompiledPalette.Placed material = new CompiledPalette.Placed(disconnected, chest("authored").info());
        driver.currentAbsolute(authored).block(disconnected, material, FRONT);
        driver.currentAbsolute(vanilla.east()).block(Blocks.STONE.defaultBlockState());
        driver.currentAbsolute(authored.east()).block(Blocks.STONE.defaultBlockState());
        List<BlockPos> finalized = new ArrayList<>();
        driver.actuallyGenerate(chunk, 0L, (target, position, actual, source, origin) -> {
            assertEquals(authored, position);
            assertSame(material, source);
            assertSame(FRONT, origin);
            assertEquals(ChestType.SINGLE, actual.getValue(ChestBlock.TYPE));
            finalized.add(position);
        });
        assertEquals(List.of(authored), finalized);
        assertEquals(ChestType.SINGLE, chunk.getBlockState(vanilla).getValue(ChestBlock.TYPE),
                "the untouched neighbor must actually undergo a shape write for this regression");
        assertEquals("vanilla", chunk.getBlockEntityNbt(vanilla).getStringOr("Owner", ""));
    }

    @Test
    void spawnerAdmissionRunsAtEveryDestinationAndDeniedAirHasNoDecorator() {
        ProtoChunk chunk = TestChunk.emptyChunk(new ChunkPos(7, -11));
        ChunkDriver driver = driver(chunk);
        CompiledPalette.Placed spawner = new CompiledPalette.Placed(Blocks.SPAWNER.defaultBlockState(),
                Palette.Info.of("urbex:mobs", null, null, null));
        Map<BlockPos, PlacementOrigin> admitted = new HashMap<>();
        List<BlockPos> calls = new ArrayList<>();
        driver.setMaterialPolicy((x, y, z, actual, material, origin) -> {
            assertSame(spawner, material);
            BlockPos position = new BlockPos(x, y, z);
            calls.add(position);
            if ((y & 1) == 0) {
                admitted.put(position, origin);
                return actual;
            }
            return Blocks.AIR.defaultBlockState();
        });
        driver.setBlockRange(3, 80, 4, 84, spawner);
        driver.current(4, 80, 4).add(spawner.state(), spawner, FRONT).add(spawner);
        assertEquals(List.of(new BlockPos(115, 80, -172), new BlockPos(115, 81, -172),
                new BlockPos(115, 82, -172), new BlockPos(115, 83, -172),
                new BlockPos(116, 80, -172), new BlockPos(116, 81, -172)), calls);
        List<Long> finalized = new ArrayList<>();
        driver.actuallyGenerate(chunk, 0L, (target, position, actual, material, origin) -> {
            assertTrue(admitted.containsKey(position));
            assertSame(admitted.get(position), origin);
            assertSame(spawner, material);
            assertEquals(Blocks.SPAWNER.defaultBlockState(), actual);
            finalized.add(position.asLong());
        });
        assertEquals(admitted.keySet().stream().map(BlockPos::asLong).sorted().toList(), finalized,
                "finalization is sorted and includes only admitted survivors");
        assertTrue(chunk.getBlockState(new BlockPos(115, 81, -172)).isAir());
    }

    @Test
    void ordinaryRangesKeepTheirBulkPathAndRejectedWindowWritesKeepExistingNbt() {
        ProtoChunk chunk = TestChunk.emptyChunk();
        BlockPos outside = new BlockPos(3, 85, 4);
        chunk.setBlockState(outside, Blocks.CHEST.defaultBlockState(), 0);
        chunk.setBlockEntityNbt(nbt(outside, "untouched"));
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk, 80, 83);
        driver.setDefaultOrigin(PROCEDURAL);
        List<BlockPos> policy = new ArrayList<>();
        driver.setMaterialPolicy((x, y, z, actual, material, origin) -> {
            policy.add(new BlockPos(x, y, z));
            return actual;
        });
        driver.setBlockRange(3, 78, 4, 86, chest("fill"));
        driver.currentAbsolute(outside).block(chest("rejected"));
        List<BlockPos> finalized = new ArrayList<>();
        driver.actuallyGenerate(chunk, 0L, (target, position, actual, material, origin) -> finalized.add(position));
        assertEquals(List.of(new BlockPos(3, 80, 4)), policy, "coordinate-free decorators retain the bulk path");
        assertEquals(4, finalized.size());
        assertEquals("untouched", chunk.getBlockEntityNbt(outside).getStringOr("Owner", ""));
    }

    @Test
    void explosionReplacementKeepsTheOriginalPartUntilFinalDecoration() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "X": { "block": "minecraft:stone", "traits": {
                  "urbex:damaged": { "into": { "block": "minecraft:chest", "traits": {
                    "urbex:block_entity": { "nbt": { "Owner": "damaged" } } } } } } } } }
                """);
        long seed = 226;
        Preset preset = new PresetDraft(Identifier.parse("urbex:marker_lifecycle")).resolve();
        TagSnapshot tags = TagSnapshot.capture();
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = driver(chunk);
        CompiledPalette.Placed source = palette.placedAt('X', seed, 0, 80, 0);
        BlockPos position = null;
        for (int x = 1; x < 15; x++) {
            if (DamageArea.decide(seed, source.state(), tags, x, 80, 4, .6f, true) == DamageArea.Decision.REPLACE) {
                position = new BlockPos(x, 80, 4);
                break;
            }
        }
        assertNotNull(position, "fixture must reach the replacement branch");
        driver.currentAbsolute(position).block(source.state(), source, FRONT);
        assertTrue(Damage.applyAtCursor(driver, preset, seed, tags, .6f, 63, Blocks.WATER.defaultBlockState()));
        assertSame(FRONT, driver.originHere());
        List<PlacementOrigin> finalized = new ArrayList<>();
        driver.actuallyGenerate(chunk, 0L, (target, at, actual, material, origin) -> {
            assertEquals(Blocks.CHEST.defaultBlockState(), actual);
            assertEquals("damaged", material.info().tag().getStringOr("Owner", ""));
            finalized.add(origin);
        });
        assertEquals(List.of(FRONT), finalized);
    }

    @Test
    void reinitializingTheDriverDoesNotFinalizeThePreviousChunksMaterials() {
        ChunkDriver driver = driver(TestChunk.emptyChunk());
        driver.current(3, 80, 4).block(chest("old").state(), chest("old"), FRONT);
        ProtoChunk next = TestChunk.emptyChunk(new ChunkPos(1, 1));
        driver.setPrimer(TestChunk.levelFor(next), next);
        driver.setDefaultOrigin(PROCEDURAL);
        driver.current(3, 80, 4).block(chest("new"));
        List<BlockPos> finalized = new ArrayList<>();
        driver.actuallyGenerate(next, 0L, (target, position, actual, material, origin) -> {
            assertSame(PROCEDURAL, origin);
            assertEquals("new", material.info().tag().getStringOr("Owner", ""));
            finalized.add(position);
        });
        assertEquals(List.of(new BlockPos(19, 80, 20)), finalized);
    }

    private static ChunkDriver driver(ProtoChunk chunk) {
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        driver.setDefaultOrigin(PROCEDURAL);
        return driver;
    }

    private static CompiledPalette.Placed chest(String owner) {
        CompoundTag data = new CompoundTag();
        data.putString("Owner", owner);
        return new CompiledPalette.Placed(Blocks.CHEST.defaultBlockState(), Palette.Info.of(null, null, null, data));
    }

    private static CompoundTag nbt(BlockPos position, String owner) {
        CompoundTag data = new CompoundTag();
        data.putString("id", "minecraft:chest");
        data.putInt("x", position.getX());
        data.putInt("y", position.getY());
        data.putInt("z", position.getZ());
        data.putString("Owner", owner);
        return data;
    }

    private static CompiledPalette palette(String json) {
        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition definition = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        CompiledV2Palette compiled = NodeResolver.resolve(definition, diagnostics)
                .flatMap(resolved -> CompiledV2Palette.compile(resolved,
                        Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft")),
                        TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                        "'urbex:marker_lifecycle'", diagnostics))
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("compile failed")));
        return new CompiledPalette(Palette.version2(Identifier.parse("urbex:marker_lifecycle"), compiled));
    }
}
