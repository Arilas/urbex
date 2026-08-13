package dev.krona.urbex.gui.preview;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import javax.annotation.Nullable;

/**
 * The world-creation preview's terrain: a hand-drawn 62x58 biome bitmap, and heights derived from it.
 *
 * <p>The preview draws a fixed landscape - a coastline, a river, a mountain range, a desert - so that
 * the effect of a preset can be judged against terrain features rather than against whatever the seed
 * happens to produce. It is not the terrain the world will have, and never was; what it is for is
 * comparing two presets, or two positions of one slider, on the same ground.</p>
 *
 * <p>Extracted from {@code NullDimensionInfo}, which held the bitmap alongside a world style, a
 * generator, a road field and a preset while answering {@code null} to "what level are you?". The
 * bitmap is the only part of that the production planner actually needs from a preview (issue
 * #129).</p>
 */
public final class PreviewTerrain implements TerrainSampler {

    public static final int WIDTH = 62;
    public static final int HEIGHT = 58;

    private static final String[] BIOME_MAP = new String[] {
            "ddddddddddddddddddddddppppppppppppppp==ppppppppppppppppppppppp",
            "ddddddddddddddddddddpppppppppppppppp==pppppppppppppppppppppppp",
            "ddddddddddddddddddddpppppppppppppp===ppppppppppppppppppppppppp",
            "pddddddddddddddddpppppppppppppppppp==ppppppppppppppppppppppppp",
            "pppdddddddppppppppppppppppppppppppp==ppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppp==pppppppppp----------pppp",
            "ppppppppppppppppppppppppppppppppppppp==ppppppp--------------pp",
            "pppppppppppppppppppppppppppppppppppppp==ppppp-----------------",
            "pppppppppppppppppppppppppppppppppppppp===pppp-----------------",
            "ppppppppppppppppppppppppppppppppppppppp===ppppp---------------",
            "pppppppppppppppppppppppppppppppppppppppp==--pp----------------",
            "pppppppppppppppppppppppppppppppppppppppp*---------------------",
            "pppppppppppppppppppppppppppppppppppppp****--------------------",
            "ppppppppppppppppppppppppppppppppppppp***----------------------",
            "pppppppppppppppppppppppppppppppppppp**------------------------",
            "ppppppppppppppppppppppppppppppppppppp**-----------------------",
            "ppppppppppppppppppppppppppppppppppppppp*----------------------",
            "pppppppppppppppppppppppppppppppppppppp**----------------------",
            "ppppp###pppppppppppppppppppppppppppppp**----------------------",
            "ppppp####ppppppp#####pppppppppppppppppp*----------------------",
            "pppppp#####pp##+++#####ppppppppppppp*****---------------------",
            "pppppppp#####++++####pppppppppppppp**------pp----p------------",
            "ppppppppp##++++++###pppppppppppppppp***---pppp--ppp-----------",
            "ppppppppp###+++++++#####ppppppppppppp---pppppppppppp---------p",
            "pppppppp##p##+++++++###ppppppppppppppppppppppppppppp---------p",
            "pppppppppp#####++++####ppppppppppppppppppppppppppppppppp----pp",
            "pppppppppppp###+++++###ppppppppppppppppppppppppppppppppppppppp",
            "ppppppppppppp####++++####ppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppp####++######pppppppppppppppppppppppppppppppppppp",
            "ppppppppppppppp#+++####ppppppppppppppppppppppppppppppppppppppp",
            "ppppppppppppp####pp#####pppppppppppppppppppppppppppppppppppppp",
            "pppppppppp#####ppppppppppppppppppppppppppppppppppppppppppppppp",
            "ppppppppppp###pppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp",
            "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp"
    };

    private final Preset preset;
    @Nullable
    private final RegistryAccess registryAccess;
    @Nullable
    private final Registry<Biome> biomeRegistry;

    public PreviewTerrain(Preset preset, @Nullable RegistryAccess registryAccess) {
        this.preset = preset;
        this.registryAccess = registryAccess;
        this.biomeRegistry = registryAccess != null ? registryAccess.lookupOrThrow(Registries.BIOME) : null;
    }

    /** The bitmap character at a chunk, for the renderer that colours the map from it. */
    public char biomeChar(int chunkX, int chunkZ) {
        if (chunkX >= 0 && chunkX < WIDTH && chunkZ >= 0 && chunkZ < HEIGHT) {
            return BIOME_MAP[chunkZ].charAt(chunkX);
        }
        return 'p';
    }

    @Override
    public ChunkHeightmap heightmap(ChunkCoord coord) {
        ChunkHeightmap heightmap = new ChunkHeightmap(preset.landscapeType(), preset.groundLevel());
        heightmap.update(groundAt(coord.chunkX(), coord.chunkZ()));
        return heightmap;
    }

    /**
     * The preview's terrain is flat within a chunk, so the four extra samples are the chunk's own
     * height and the min/max collapse to it. A real generator's four corners disagree; a bitmap's
     * cannot.
     */
    @Override
    public void sampleAccurateHeight(ChunkHeightmap heightmap, int chunkX, int chunkZ) {
        int ground = groundAt(chunkX, chunkZ);
        heightmap.accurateHeights(ground, ground, ground, ground);
    }

    private int groundAt(int chunkX, int chunkZ) {
        return switch (biomeChar(chunkX, chunkZ)) {
            case '-' -> 60;
            case '#' -> 95;
            case '+' -> 125;
            // 'p', '=', '*', 'd' and anything unmapped: the bitmap's baseline.
            default -> 65;
        };
    }

    @Nullable
    @Override
    public Holder<Biome> biome(BlockPos pos) {
        if (biomeRegistry == null) {
            // #67: no registry access means there is no registry to resolve even a plains fallback
            // from - dereferencing it unconditionally is what used to NPE here. The GUI passes null
            // deliberately whenever the world-creation context has no biome registry yet, or (in the
            // Customize screen) no parent screen at all. Every caller reachable in that state is
            // gated on registryAccess() and never dereferences this result.
            Urbex.LOGGER.warn("Preview terrain asked for a biome without registry access; returning null.");
            return null;
        }
        ChunkPos cp = ChunkPos.containing(pos);
        ResourceKey<Biome> biome = switch (biomeChar(cp.x(), cp.z())) {
            case '-' -> Biomes.OCEAN;
            case '=' -> Biomes.RIVER;
            case '#' -> Biomes.STONY_PEAKS;
            case '+' -> Biomes.JAGGED_PEAKS;
            case '*' -> Biomes.BEACH;
            case 'd' -> Biomes.DESERT;
            // Plains for 'p' and for anything unmapped, same as the old default branch.
            default -> Biomes.PLAINS;
        };
        return biomeRegistry.getOrThrow(biome);
    }

    @Nullable
    @Override
    public RegistryAccess registryAccess() {
        return registryAccess;
    }
}
