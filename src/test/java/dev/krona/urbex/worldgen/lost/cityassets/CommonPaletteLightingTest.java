package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSettings;
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
    private static final Path BUNDLED_PALETTES = Path.of("src/main/resources/data/urbex/urbex/palettes");
    private static final Identifier COMMON_ID = Identifier.fromNamespaceAndPath("urbex", "common");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void commonPaletteCompilesExactTypedLightPoolsWithoutRedstoneTorches() throws IOException {
        PaletteDefinition common = decodeClasspathPalette(COMMON_PALETTE);
        PaletteEntry torchMarker = entry(common, 'T');
        PaletteEntry freeMarker = entry(common, 'h');
        PaletteEntry redstoneTorch = entry(common, 'g');

        assertNull(torchMarker.getTorch());
        assertEquals(new LightSettings(
                List.of(
                        new LightSettings.Entry(6, "minecraft:lantern[hanging=false]"),
                        new LightSettings.Entry(3, "minecraft:torch"),
                        new LightSettings.Entry(1, "minecraft:end_rod[facing=up]")),
                List.of(
                        new LightSettings.Entry(8, "minecraft:wall_torch[facing=north]"),
                        new LightSettings.Entry(2, "minecraft:end_rod[facing=north]")),
                List.of(
                        new LightSettings.Entry(8, "minecraft:lantern[hanging=true]"),
                        new LightSettings.Entry(2, "minecraft:end_rod[facing=down]")),
                List.of()), torchMarker.getLight());

        assertNull(freeMarker.getTorch());
        assertEquals(new LightSettings(
                List.of(), List.of(), List.of(),
                List.of(
                        new LightSettings.Entry(6, "minecraft:glowstone"),
                        new LightSettings.Entry(2, "minecraft:sea_lantern"),
                        new LightSettings.Entry(1, "minecraft:shroomlight"),
                        new LightSettings.Entry(1, "minecraft:ochre_froglight"))), freeMarker.getLight());

        assertEquals("minecraft:redstone_torch[lit=true]", redstoneTorch.getBlock());
        assertNull(redstoneTorch.getTorch());
        assertNull(redstoneTorch.getLight());

        Palette compiled = new Palette(COMMON_ID, BuiltInRegistries.BLOCK, null, List.of(common));
        LightPool torchPool = compiled.getPalette().get('T').info().light();
        LightPool freePool = compiled.getPalette().get('h').info().light();
        assertNotNull(torchPool);
        assertNotNull(freePool);
        assertSame(Blocks.TORCH, torchPool.allCandidates().stream().toList().get(1).state().getBlock());

        Stream.concat(torchPool.allCandidates().stream(), freePool.allCandidates().stream())
                .forEach(candidate -> {
                    assertTrue(candidate.state().getLightEmission() >= 14,
                            () -> "Bundled candidate emits too little light: " + candidate.state());
                    assertFalse(candidate.state().is(Blocks.REDSTONE_TORCH));
                    assertFalse(candidate.state().is(Blocks.REDSTONE_WALL_TORCH));
                });
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
