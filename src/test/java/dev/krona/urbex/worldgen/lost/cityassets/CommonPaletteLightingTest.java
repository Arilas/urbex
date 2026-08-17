package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSourceSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonPaletteLightingTest {

    private static final String COMMON_PALETTE = "data/urbex/urbex/palettes/common.json";
    private static final String OILRIG_PALETTE = "data/urbex/urbex/palettes/oilrig.json";
    private static final Path BUNDLED_PALETTES = Path.of("src/main/resources/data/urbex/urbex/palettes");
    private static final Identifier COMMON_ID = Identifier.fromNamespaceAndPath("urbex", "common");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void commonPaletteCompilesExactSocketsWithoutRedstoneTorches() throws IOException {
        PaletteDefinition common = decodeClasspathPalette(COMMON_PALETTE);
        PaletteEntry torchMarker = entry(common, 'T');
        PaletteEntry freeMarker = entry(common, 'h');
        PaletteEntry redstoneTorch = entry(common, 'g');

        assertFalse(torchMarker.isLegacyTorch());
        assertFalse(torchMarker.isLegacyLight());
        assertEquals(new LightSourceSettings(
                List.of(
                        new LightSourceSettings.Entry(6, "minecraft:lantern[hanging=false]"),
                        new LightSourceSettings.Entry(3, "minecraft:torch"),
                        new LightSourceSettings.Entry(1, "minecraft:end_rod[facing=up]")),
                List.of(
                        new LightSourceSettings.Entry(8, "minecraft:wall_torch[facing=north]"),
                        new LightSourceSettings.Entry(2, "minecraft:end_rod[facing=north]")),
                List.of(
                        new LightSourceSettings.Entry(8, "minecraft:lantern[hanging=true]"),
                        new LightSourceSettings.Entry(2, "minecraft:end_rod[facing=down]")),
                List.of(), null, null), torchMarker.getLightSource());

        assertEquals(new LightSourceSettings(
                List.of(), List.of(), List.of(),
                List.of(
                        new LightSourceSettings.Entry(6, "minecraft:glowstone"),
                        new LightSourceSettings.Entry(2, "minecraft:sea_lantern"),
                        new LightSourceSettings.Entry(1, "minecraft:shroomlight"),
                        new LightSourceSettings.Entry(1, "minecraft:ochre_froglight")),
                null, null), freeMarker.getLightSource());

        assertEquals("minecraft:redstone_torch[lit=true]", redstoneTorch.getBlock());
        assertFalse(redstoneTorch.isLegacyTorch());
        assertNull(redstoneTorch.getLightSource());

        Palette compiled = new Palette(COMMON_ID, BuiltInRegistries.BLOCK, null, List.of(common));
        LightPool torchPool = compiled.getPalette().get('T').info().lightSource().pool();
        LightPool freePool = compiled.getPalette().get('h').info().lightSource().pool();
        assertNotNull(torchPool);
        assertNotNull(freePool);
        assertSame(Blocks.TORCH, torchPool.allCandidates().stream().toList().get(1).state().getBlock());

        // Both keep air as their replacement, which is what a rejected marker has always left
        // behind. That is what makes this rename a rename: the built-in pack generates as it did.
        assertEquals(BlockChoice.AIR, compiled.getPalette().get('T').info().lightSource().unlit());
        assertEquals(BlockChoice.AIR, compiled.getPalette().get('h').info().lightSource().unlit());

        Stream.concat(torchPool.allCandidates().stream(), freePool.allCandidates().stream())
                .forEach(candidate -> {
                    assertTrue(candidate.state().getLightEmission() >= 14,
                            () -> "Bundled candidate emits too little light: " + candidate.state());
                    assertFalse(candidate.state().is(Blocks.REDSTONE_TORCH));
                    assertFalse(candidate.state().is(Blocks.REDSTONE_WALL_TORCH));
                });
    }

    @Test
    void oilrigDeckLightsAreInPlaceSourcesThatLeaveTheirFixtureBehind() throws IOException {
        PaletteDefinition oilrig = decodeClasspathPalette(OILRIG_PALETTE);
        Palette compiled = new Palette(Identifier.fromNamespaceAndPath("urbex", "oilrig"),
                BuiltInRegistries.BLOCK, null, List.of(oilrig));

        LightSource seaLantern = compiled.getPalette().get('J').info().lightSource();
        assertNull(seaLantern.pool());
        assertEquals(BlockChoice.of(Blocks.PRISMARINE_BRICKS.defaultBlockState()), seaLantern.unlit());

        LightSource wallTorch = compiled.getPalette().get('|').info().lightSource();
        assertNull(wallTorch.pool());
        assertEquals(0, ((BlockChoice.One) wallTorch.unlit()).state().getLightEmission());
        assertSame(Blocks.REDSTONE_WALL_TORCH, ((BlockChoice.One) wallTorch.unlit()).state().getBlock());
    }

    @Test
    void everyBundledPaletteDecodesThroughPaletteCodec() throws IOException {
        try (Stream<Path> files = Files.walk(BUNDLED_PALETTES)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".json"))
                    .sorted(Comparator.naturalOrder()).toList()) {
                DataResult<PaletteDefinition> decoded = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(Files.readString(path)));
                assertTrue(decoded.result().isPresent(),
                        () -> path + ": " + decoded.error().map(Object::toString).orElse("unknown decode error"));
            }
        }
    }

    private static PaletteDefinition decodeClasspathPalette(String resource) throws IOException {
        try (InputStream stream = CommonPaletteLightingTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, () -> "Missing classpath resource " + resource);
            DataResult<PaletteDefinition> decoded = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            assertTrue(decoded.result().isPresent(),
                    () -> decoded.error().map(Object::toString).orElse("unknown decode error"));
            return decoded.result().orElseThrow();
        }
    }

    private static PaletteEntry entry(PaletteDefinition palette, char marker) {
        return palette.getPaletteEntries().stream()
                .filter(entry -> entry.getChr().equals(Character.toString(marker)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing marker " + marker));
    }
}
