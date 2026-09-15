package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.LightSource;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Compiled socket metadata survives opportunity selection, planning and accepted chunk writes. */
class DeferredMarkerDamageTest {
    private static final long SEED = 216;
    private static final ChunkPos OWNER = new ChunkPos(3, -2);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("TRAIT.011")
    @Rule("LOAD.021")
    @Test
    void sameStateCandidatesKeepDistinctDamageAndTraitlessCandidatesKeepNone() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": {
                  "A": { "kind": "light_socket", "free": [ { "weight": 1,
                    "block": "minecraft:glowstone", "traits": {
                    "urbex:damaged": { "into": "minecraft:gold_block" } } } ] },
                  "B": { "kind": "light_socket", "free": [ { "weight": 1,
                    "block": "minecraft:glowstone", "traits": {
                    "urbex:damaged": { "into": "minecraft:diamond_block" } } } ] },
                  "N": { "kind": "light_socket", "free": [ { "weight": 1,
                    "block": "minecraft:glowstone" } ] }
                } }
                """);
        Preset preset = preset(1, 1);
        List<LightTodoQueue.Todo> todos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BlockPos pos = pos(i + 3, 80, 4);
            todos.add(new LightTodoQueue.Todo(pos, source(palette, "ABN".charAt(i), pos), true));
        }
        List<DeferredLightPlacer.Planned> planned = plan(preset, todos, true);
        assertEquals(3, planned.size());
        ProtoChunk chunk = TestChunk.emptyChunk(OWNER);
        ChunkDriver driver = driver(chunk);
        for (DeferredLightPlacer.Planned item : planned) {
            driver.currentAbsolute(item.pos()).block(item.state(), item.material());
        }
        driver.flushToChunk(chunk);
        for (int i = 0; i < 3; i++) {
            BlockPos pos = todos.get(i).pos();
            driver.currentAbsolute(pos);
            CompiledPalette.Placed target = driver.damageHere(preset, SEED);
            if (i == 2) {
                assertNull(target, "another candidate's damage cannot leak through its block state");
            } else {
                BlockState expected = (i == 0 ? Blocks.GOLD_BLOCK : Blocks.DIAMOND_BLOCK).defaultBlockState();
                assertNotNull(target);
                assertEquals(expected, target.state());
                driver.block(target);
            }
        }
        driver.actuallyGenerate(chunk);
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), chunk.getBlockState(todos.get(0).pos()));
        assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), chunk.getBlockState(todos.get(1).pos()));
        assertEquals(Blocks.GLOWSTONE.defaultBlockState(), chunk.getBlockState(todos.get(2).pos()));
    }

    @Rule("LOAD.021")
    @Rule("TRAIT.011")
    @Test
    void weightedLitCandidatesSharingAStateKeepBothDamageForms() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "L": { "kind": "light_socket", "free": [
                  { "weight": 1, "block": "minecraft:glowstone", "traits": {
                    "urbex:damaged": { "into": "minecraft:gold_block" } } },
                  { "weight": 1, "block": "minecraft:glowstone", "traits": {
                    "urbex:damaged": { "into": "minecraft:diamond_block" } } }
                ] } } }
                """);
        List<LightTodoQueue.Todo> todos = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos pos = pos(x, 80, z);
                todos.add(new LightTodoQueue.Todo(pos, source(palette, 'L', pos), true));
            }
        }
        Preset preset = preset(1, 1);
        Map<BlockPos, BlockState> forward = damageByPosition(plan(preset, todos, true), preset);
        Collections.reverse(todos);
        assertEquals(forward, damageByPosition(plan(preset, todos, true), preset));
        assertEquals(Set.of(Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.DIAMOND_BLOCK.defaultBlockState()),
                new HashSet<>(forward.values()));
    }

    @Rule("TRAIT.007")
    @Rule("TRAIT.055")
    @Test
    void unlitCandidatesAndUnsupportedSourceFallbackKeepTheirOwnDamage() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "L": {
                  "kind": "light_socket",
                  "traits": { "urbex:light": { "unlit": {
                    "block": "minecraft:barrel", "traits": {
                      "urbex:damaged": { "into": "minecraft:bricks" } } } } },
                  "floor": [ { "weight": 1, "block": "minecraft:lantern", "traits": {
                    "urbex:damaged": { "into": "minecraft:gold_block" },
                    "urbex:light": { "unlit": { "block": "minecraft:oak_planks", "traits": {
                      "urbex:damaged": { "into": "minecraft:cobweb" } } } } } } ]
                } } }
                """);
        BlockPos pos = pos(5, 80, 5);
        LightSource source = source(palette, 'L', pos);
        DeferredLightPlacer.Planned unlit = plan(preset(0, 1),
                List.of(new LightTodoQueue.Todo(pos, source, false)), true).getFirst();
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), unlit.state());
        assertEquals(Blocks.COBWEB.defaultBlockState(), damageOf(unlit, preset(0, 1)));
        DeferredLightPlacer.Planned fallback = plan(preset(1, 1),
                List.of(new LightTodoQueue.Todo(pos, source, true)), false).getFirst();
        assertEquals(Blocks.BARREL.defaultBlockState(), fallback.state());
        assertEquals(Blocks.BRICKS.defaultBlockState(), damageOf(fallback, preset(1, 1)));
    }

    @Rule("TRAIT.009")
    @Rule("WEIGHT.043")
    @Test
    void weightedSameStateUnlitSlotsKeepDamageAndAreIndependentOfTodoOrder() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "L": {
                  "kind": "light_socket", "free": [ { "weight": 1,
                    "block": "minecraft:glowstone", "traits": { "urbex:light": {
                    "unlit": { "kind": "weighted", "choices": [
                      { "share": 0.5, "block": "minecraft:stone", "traits": {
                        "urbex:damaged": { "into": "minecraft:gold_block" } } },
                      { "rest": true, "block": "minecraft:stone", "traits": {
                        "urbex:damaged": { "into": "minecraft:diamond_block" } } } ] } } } } ]
                } } }
                """);
        List<LightTodoQueue.Todo> todos = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos pos = pos(x, 80, z);
                todos.add(new LightTodoQueue.Todo(pos, source(palette, 'L', pos), false));
            }
        }
        Preset preset = preset(0, 1);
        Map<BlockPos, BlockState> forward = damageByPosition(plan(preset, todos, true), preset);
        Collections.reverse(todos);
        Map<BlockPos, BlockState> reverse = damageByPosition(plan(preset, todos, true), preset);
        assertEquals(forward, reverse);
        assertEquals(Set.of(Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.DIAMOND_BLOCK.defaultBlockState()),
                new HashSet<>(forward.values()), "both weighted slots survive the socket adapter");
    }

    @Rule("TRAIT.009")
    @Test
    void aNestedUnlitSelectionUsesTheReplacementOwnDamage() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "L": { "kind": "light_socket", "free": [
                  { "weight": 1, "block": "minecraft:glowstone", "traits": { "urbex:light": {
                    "unlit": { "block": "minecraft:stone", "traits": { "urbex:optional": {
                      "density": "lootDensity", "replacement": { "block": "minecraft:bricks",
                        "traits": { "urbex:damaged": { "into": "minecraft:cobweb" } } } } } } } } }
                ] } } }
                """);
        BlockPos pos = pos(4, 80, 4);
        Preset preset = preset(0, 0);
        DeferredLightPlacer.Planned item = plan(preset,
                List.of(new LightTodoQueue.Todo(pos, source(palette, 'L', pos), false)), true).getFirst();
        assertEquals(Blocks.BRICKS.defaultBlockState(), item.state());
        assertEquals(Blocks.COBWEB.defaultBlockState(), damageOf(item, preset));
    }

    private static List<DeferredLightPlacer.Planned> plan(Preset preset, List<LightTodoQueue.Todo> todos,
                                                        boolean supported) {
        return DeferredLightPlacer.plan(OWNER.x(), OWNER.z(), SEED, preset, todos,
                pos -> Blocks.AIR.defaultBlockState(),
                (pos, direction, stateAt) -> supported,
                (pos, attempt, stateAt) -> true);
    }

    private static BlockState damageOf(DeferredLightPlacer.Planned item, Preset preset) {
        assertNotNull(item.material());
        CompiledPalette.Placed target = item.material().damagedPlacementAt(preset, SEED,
                item.pos().getX(), item.pos().getY(), item.pos().getZ());
        assertNotNull(target);
        return target.state();
    }

    private static Map<BlockPos, BlockState> damageByPosition(List<DeferredLightPlacer.Planned> items, Preset preset) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        items.forEach(item -> result.put(item.pos(), damageOf(item, preset)));
        return result;
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos((OWNER.x() << 4) + x, y, (OWNER.z() << 4) + z);
    }

    private static ChunkDriver driver(ProtoChunk chunk) {
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        return driver;
    }

    private static LightSource source(CompiledPalette palette, char marker, BlockPos pos) {
        return palette.placedAt(marker, SEED, pos.getX(), pos.getY(), pos.getZ()).info().lightSource();
    }

    private static Preset preset(float lighting, float loot) {
        PresetDraft draft = new PresetDraft(Identifier.parse("urbex:deferred_damage_test"));
        draft.LIGHTING_DENSITY = lighting;
        draft.LOOT_DENSITY = loot;
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
                        "'urbex:deferred_damage_test'", diagnostics))
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("compile failed")));
        return new CompiledPalette(Palette.version2(Identifier.parse("urbex:deferred_damage_test"), compiled));
    }
}
