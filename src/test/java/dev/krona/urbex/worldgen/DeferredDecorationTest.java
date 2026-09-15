package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.*;
import dev.krona.urbex.worldgen.gen.Damage;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real compiled replacements commit their own NBT through the driver's production finalizer. */
class DeferredDecorationTest {
    private static final long SEED = 226;
    private static final ChunkPos OWNER = new ChunkPos(-3, 2);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("TRAIT.040")
    @Rule("LOAD.021")
    @Test
    void weightedSameStateSocketCandidatesCommitDistinctNbt() {
        CompiledPalette palette = palette("""
                {"version":2,"palette":{"L":{"kind":"light_socket","free":[
                  {"weight":1,"block":"minecraft:campfire","traits":{
                    "urbex:block_entity":{"nbt":{"Custom":11}}}},
                  {"weight":1,"block":"minecraft:campfire","traits":{
                    "urbex:block_entity":{"nbt":{"Custom":22}}}}
                ]}}}
                """);
        List<LightTodoQueue.Todo> todos = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) todos.add(todo(palette, 'L', pos(x, z), true));
        }
        Map<BlockPos, Integer> forward = commitSockets(plan(todos, true));
        Collections.reverse(todos);
        assertEquals(forward, commitSockets(plan(todos, true)), "queue order cannot choose the NBT");
        assertEquals(Set.of(11, 22), new HashSet<>(forward.values()));
    }

    @Rule("TRAIT.040")
    @Rule("TRAIT.096")
    @Rule("TRAIT.055")
    @Test
    void unlitAndUnsupportedResultsInheritOuterDecoratorsUnlessTheyOverrideThem() {
        CompiledPalette palette = palette("""
                {"version":2,"palette":{
                  "I":{"kind":"light_socket","free":[{"weight":1,"block":"minecraft:campfire",
                    "traits":{"urbex:block_entity":{"nbt":{"Custom":11}},
                      "urbex:light":{"unlit":"minecraft:barrel"}}}]},
                  "O":{"kind":"light_socket","free":[{"weight":1,"block":"minecraft:campfire",
                    "traits":{"urbex:block_entity":{"nbt":{"Custom":11}},
                      "urbex:light":{"unlit":{"block":"minecraft:barrel","traits":{
                        "urbex:block_entity":{"nbt":{"Custom":22}}}}}}}]},
                  "F":{"kind":"light_socket","traits":{
                    "urbex:block_entity":{"nbt":{"Custom":33}},
                    "urbex:light":{"unlit":"minecraft:barrel"}},
                    "floor":[{"weight":1,"block":"minecraft:campfire","traits":{
                      "urbex:block_entity":{"nbt":{"Custom":44}}}}]}
                }}
                """);
        BlockPos inherited = pos(3, 4);
        BlockPos own = pos(5, 4);
        assertEquals(Map.of(inherited, 11, own, 22), commitSockets(plan(List.of(
                todo(palette, 'I', inherited, false), todo(palette, 'O', own, false)), true)));
        BlockPos fallback = pos(7, 4);
        assertEquals(Map.of(fallback, 33), commitSockets(plan(
                List.of(todo(palette, 'F', fallback, true)), false)));
    }

    @Rule("TRAIT.007")
    @Rule("TRAIT.040")
    @Rule("TRAIT.011")
    @Test
    void repeatedDamageCommitsOnlyTheSurvivingSatelliteNbt() {
        CompiledPalette palette = palette("""
                {"version":2,"palette":{
                  "A":{"block":"minecraft:chest","traits":{
                    "urbex:block_entity":{"nbt":{"Custom":1}},
                    "urbex:damaged":{"into":{"block":"minecraft:chest","traits":{
                      "urbex:block_entity":{"nbt":{"Custom":2}},
                      "urbex:damaged":{"into":{"block":"minecraft:barrel","traits":{
                        "urbex:block_entity":{"nbt":{"Custom":3}}}}}}}}}},
                  "B":{"block":"minecraft:chest","traits":{
                    "urbex:block_entity":{"nbt":{"Custom":1}},
                    "urbex:damaged":{"into":"minecraft:barrel"}}}
                }}
                """);
        ProtoChunk chunk = TestChunk.emptyChunk(OWNER);
        ChunkDriver driver = driver(chunk);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos pos = pos(x, z);
                driver.currentAbsolute(pos).block(palette.placedAt((x & 1) == 0 ? 'A' : 'B', SEED,
                        pos.getX(), pos.getY(), pos.getZ()));
            }
        }
        driver.flushToChunk(chunk);
        TagSnapshot tags = TagSnapshot.capture();
        for (int pass = 0; pass < 2; pass++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos pos = pos(x, z);
                    driver.currentAbsolute(pos);
                    // A's second pass advances through an equal-state chest to a barrel. Leave B's
                    // traitless satellite intact to prove it did not inherit its parent's NBT.
                    if (pass == 0 || (x & 1) == 0) {
                        Damage.applyAtCursor(driver, preset(), SEED, tags, .6f, 63,
                                Blocks.WATER.defaultBlockState());
                    }
                }
            }
        }
        finish(driver, chunk);
        int decoratedBarrels = 0;
        int plainBarrels = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos pos = pos(x, z);
                if (chunk.getBlockState(pos).is(Blocks.BARREL)) {
                    if ((x & 1) == 0) {
                        assertEquals(3, chunk.getBlockEntityNbt(pos).getIntOr("Custom", -1));
                        decoratedBarrels++;
                    } else {
                        assertNull(chunk.getBlockEntityNbt(pos), "damage does not inherit decorators");
                        plainBarrels++;
                    }
                } else if (chunk.getBlockState(pos).isAir()) {
                    assertNull(chunk.getBlockEntityNbt(pos), "destroyed materials leave no NBT");
                }
            }
        }
        assertTrue(decoratedBarrels > 20 && plainBarrels > 20);
    }

    private static Map<BlockPos, Integer> commitSockets(List<DeferredLightPlacer.Planned> planned) {
        ProtoChunk chunk = TestChunk.emptyChunk(OWNER);
        ChunkDriver driver = driver(chunk);
        for (DeferredLightPlacer.Planned item : planned) {
            driver.currentAbsolute(item.pos()).block(item.state(), item.material(), item.origin());
        }
        driver.flushToChunk(chunk);
        finish(driver, chunk);
        Map<BlockPos, Integer> result = new HashMap<>();
        for (DeferredLightPlacer.Planned item : planned) {
            CompoundTag nbt = chunk.getBlockEntityNbt(item.pos());
            assertNotNull(nbt);
            assertEquals(item.pos().getX(), nbt.getIntOr("x", 0));
            assertEquals(item.pos().getY(), nbt.getIntOr("y", 0));
            assertEquals(item.pos().getZ(), nbt.getIntOr("z", 0));
            result.put(item.pos(), nbt.getIntOr("Custom", -1));
        }
        return result;
    }

    private static void finish(ChunkDriver driver, ProtoChunk chunk) {
        driver.actuallyGenerate(chunk, 0, (target, pos, state, material, origin) -> {
            if (material.info() != null && material.info().tag() != null) {
                MarkerDecorations.applyNbt(target, pos, state, material.info().tag(), null);
            }
        });
    }

    private static List<DeferredLightPlacer.Planned> plan(List<LightTodoQueue.Todo> todos, boolean supported) {
        return DeferredLightPlacer.plan(OWNER.x(), OWNER.z(), SEED, preset(), todos,
                pos -> Blocks.AIR.defaultBlockState(), (pos, direction, stateAt) -> supported,
                (pos, attempt, stateAt) -> true);
    }

    private static LightTodoQueue.Todo todo(CompiledPalette palette, char marker, BlockPos pos, boolean lit) {
        return new LightTodoQueue.Todo(pos, palette.placedAt(marker, SEED, pos.getX(), pos.getY(), pos.getZ())
                .info().lightSource(), lit);
    }

    private static BlockPos pos(int x, int z) {
        return new BlockPos((OWNER.x() << 4) + x, 80, (OWNER.z() << 4) + z);
    }

    private static ChunkDriver driver(ProtoChunk chunk) {
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        return driver;
    }

    private static Preset preset() {
        PresetDraft draft = new PresetDraft(Identifier.parse("urbex:decoration_test"));
        draft.LIGHTING_DENSITY = 1;
        draft.LOOT_DENSITY = 1;
        return draft.resolve();
    }

    private static CompiledPalette palette(String json) {
        PaletteV2Definition definition = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        Diagnostics diagnostics = new Diagnostics();
        CompiledV2Palette compiled = NodeResolver.resolve(definition, diagnostics)
                .flatMap(resolved -> CompiledV2Palette.compile(resolved,
                        Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("minecraft", "urbex")),
                        TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                        "'urbex:decoration_test'", diagnostics))
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("compile failed")));
        return new CompiledPalette(Palette.version2(Identifier.parse("urbex:decoration_test"), compiled));
    }
}
