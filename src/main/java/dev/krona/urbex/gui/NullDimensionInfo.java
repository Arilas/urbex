package dev.krona.urbex.gui;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.RoadField;
import dev.krona.urbex.plan.grid.GridRoadField;
import dev.krona.urbex.plan.grid.GridSettings;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.DimensionCaches;
import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.WorldStyleField;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.lost.cityassets.AssetCompiler;
import dev.krona.urbex.worldgen.lost.cityassets.AssetDiagnostics;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class NullDimensionInfo implements IDimensionInfo {

    public static final int PREVIEW_WIDTH = 62;
    public static final int PREVIEW_HEIGHT = 58;

    /** What the placeholder world style calls itself, so a load error can name it. */
    private static final Identifier PLACEHOLDER_ID =
            Identifier.fromNamespaceAndPath(Urbex.MODID, "preview_placeholder");
    /** A style the bundled pack actually ships, and qualified; see {@link #placeholderStyle()}. */
    private static final String PLACEHOLDER_OUTSIDE_STYLE = Urbex.MODID + ":standard";

    private final String[] biomeMap = new String[] {
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

    private final Preset profile;
    private final WorldStyleField styles;
    private final Random random;
    private final long seed;

    private final RegistryAccess registryAccess;
    private final AssetSnapshot assets;
    @Nullable
    private final Registry<Biome> biomeRegistry;
    private final CityGenerator feature;
    private final DimensionCaches caches;
    private final RoadField roadField;

    /**
     * The preview's dimension info. Takes the whole {@link WorldStyleMix} the player chose, so a
     * mixed selection previews as a mix rather than as its primary alone - judging a balance before
     * committing to the world is the point of the control.
     * <p>
     * Every id resolves independently: one style the datapacks no longer ship falls back to the
     * placeholder for that entry alone, rather than taking the whole preview with it.
     */
    public NullDimensionInfo(Preset profile, WorldStyleMix worldStyles, long seed, @Nullable RegistryAccess registryAccess) {
        this.profile = profile;
        this.caches = new DimensionCaches(seed);
        // The preview compiles its own snapshot and owns it, rather than reaching for the server's.
        // It has no session - it runs on the client, on the world-creation screen, before any server
        // exists - and must not acquire one. Diagnostics are discarded on purpose: a broken pack is
        // the world load's business to refuse, and a preview that threw would leave the player unable
        // to see why. Individual ids still fall back to the placeholder below.
        this.assets = registryAccess == null
                ? AssetSnapshot.empty()
                : AssetCompiler.compile(registryAccess, new AssetDiagnostics());
        List<WorldStyleField.Weighted> resolvedEntries = new ArrayList<>(worldStyles.entries().size());
        for (WorldStyleMix.Entry entry : worldStyles.entries()) {
            WorldStyle resolved = null;
            if (registryAccess != null) {
                try {
                    resolved = assets.worldStyles().get(entry.style());
                } catch (RuntimeException e) {
                    // Preview only: fall back to the placeholder below if the chosen style isn't
                    // registered (e.g. a stale GUI worldStyle no longer shipped by any datapack).
                    Urbex.LOGGER.debug("Preview could not resolve worldstyle '{}'; using the placeholder.",
                            entry.style(), e);
                }
            }
            resolvedEntries.add(new WorldStyleField.Weighted(entry.weight(),
                    resolved != null ? resolved : new WorldStyle(PLACEHOLDER_ID, List.of(placeholderStyle()))));
        }
        styles = new WorldStyleField(seed, resolvedEntries);
        this.seed = seed;
        random = new Random(seed);
        feature = new CityGenerator(this, profile);
        this.registryAccess = registryAccess;
        biomeRegistry = registryAccess != null ? registryAccess.lookupOrThrow(Registries.BIOME) : null;
        // The preview's own seed and dimension, so the roads it draws are the roads the world will
        // have. Same construction as DefaultDimensionInfo; there is no server to ask.
        roadField = new GridRoadField(seed, getType().identifier().toString(), GridSettings.fromPreset(profile));
    }

    /**
     * The world style the preview falls back to when it cannot resolve the chosen one.
     * <p>
     * It is a one-entry {@code extends} chain, so it has nothing to inherit from and must declare
     * every field {@link WorldStyle} requires <em>after</em> resolution by itself - today
     * {@code outsidestyle}, {@code citystyles} and the whole of {@code parts}, down to each of the
     * twenty-two wiring components {@code PartSelector.requireComplete} checks. Anything left absent
     * is an {@link IllegalStateException} out of the constructor rather than a decode failure, and
     * this is the one place in {@code src/main} that builds a {@code WorldStyleDefinition} by hand instead
     * of decoding one, so no datapack test covers it; {@code NullDimensionInfoPlaceholderTest} does.
     * <p>
     * The lists are declared and empty rather than absent because the preview draws no parts: it
     * samples biomes, city placement, road classes and rail/highway chunk <em>types</em>, none of
     * which reads a part name. Naming real parts here would be a claim about a datapack that, on
     * this path, either isn't loaded or doesn't have the style the player asked for.
     * <p>
     * It carries no id, because a decoded world style no longer carries one: {@link #PLACEHOLDER_ID}
     * is handed to the {@link WorldStyle} constructor beside this, which is where a load error looking
     * for a name will find it (issue #128).
     */
    private static WorldStyleDefinition placeholderStyle() {
        return new WorldStyleDefinition(
                Optional.empty(),
                // No display name: this style is never offered in the picker, so nothing would read
                // one, and inventing a label here would put a name on screen if that ever changed.
                Optional.empty(),
                // Fully qualified, like every other asset reference: a bare name throws out of
                // DataTools.fromName the moment anything resolves it.
                Optional.of(PLACEHOLDER_OUTSIDE_STYLE),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new PartSelector.Decl(
                        Optional.of(new HighwayParts.Decl(
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts())),
                        Optional.of(new RailwayParts.Decl(
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts(),
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts(),
                                noParts(), noParts(), noParts(), noParts())))),
                Optional.of(new Mergeable<>(true, Collections.emptyList())),
                Optional.empty(),
                // No 'rotatable': the preview places no parts, so nothing is ever rotated, and
                // naming a tag here would be a claim about a datapack this path has not loaded.
                Optional.empty()
        );
    }

    /** One wiring component, declared as empty: the preview places no parts. */
    private static Optional<Mergeable<String>> noParts() {
        return Optional.of(new Mergeable<>(true, Collections.emptyList()));
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public WorldGenLevel getWorld() {
        return null;
    }

    @Nullable
    @Override
    public AssetSnapshot assets() {
        return assets;
    }

    @Override
    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    @Override
    public DimensionCaches caches() {
        return caches;
    }

    @Override
    public RoadField roadField() {
        return roadField;
    }

    @Override
    public ResourceKey<Level> getType() {
        return Level.OVERWORLD;
    }

    @Override
    public Preset getProfile() {
        return profile;
    }

    @Override
    public WorldStyleField worldStyles() {
        return styles;
    }

    /** The config preview renderer's own source. Nothing here places generated blocks. */
    public Random getRandom() {
        return random;
    }

    @Override
    public CityGenerator getFeature() {
        return feature;
    }

    @Override
    public ChunkHeightmap getHeightmap(ChunkCoord coord) {
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        ChunkHeightmap heightmap = new ChunkHeightmap(profile.LANDSCAPE_TYPE, profile.GROUNDLEVEL);
        char b = getBiomeChar(chunkX, chunkZ);
        int y = switch (b) {
            case 'p' -> 65;
            case '-' -> 60;
            case '=' -> 65;
            case '#' -> 95;
            case '+' -> 125;
            case '*' -> 65;
            case 'd' -> 65;
            default -> 65;
        };
        heightmap.update(y);
        return heightmap;
    }

    @Override
    public ChunkHeightmap getHeightmap(int chunkX, int chunkZ) {
        ChunkCoord coord = new ChunkCoord(getType(), chunkX, chunkZ);
        return getHeightmap(coord);
    }

    public char getBiomeChar(int chunkX, int chunkZ) {
        if (chunkX >= 0 && chunkX < PREVIEW_WIDTH && chunkZ >= 0 && chunkZ < PREVIEW_HEIGHT) {
            return biomeMap[chunkZ].charAt(chunkX);
        } else {
            return 'p';
        }
    }

//    @Override
//    public Biome[] getBiomes(int chunkX, int chunkZ) {
//        Biome[] biomes = new Biome[10*10];
//        Biome biome = Biomes.PLAINS;
//        char b = getBiomeChar(chunkX, chunkZ);
//        switch (b) {
//            case 'p': biome = Biomes.PLAINS; break;
//            case '-': biome = Biomes.OCEAN; break;
//            case '=': biome = Biomes.RIVER; break;
//            case '#': biome = Biomes.MOUNTAIN_EDGE; break;
//            case '+': biome = Biomes.MOUNTAINS; break;
//            case '*': biome = Biomes.BEACH; break;
//            case 'd': biome = Biomes.DESERT; break;
//        }
//        for (int i = 0 ; i < biomes.length ; i++) {
//            biomes[i] = biome;
//        }
//        return biomes;
//    }

    @Nullable
    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        if (biomeRegistry == null) {
            // #67: no registry access means there's no registry to resolve even a plains fallback
            // from - dereferencing it unconditionally is what used to NPE here. This is not a
            // legacy shim: there is one constructor, and the GUI passes null to it deliberately
            // whenever the world-creation context has no biome registry yet, or (in the Customize
            // screen) no parent screen at all - see CitiesTab.previewRegistries and
            // CustomizeScreen.previewRegistries. Every caller currently reachable without registry
            // access (BuildingInfo.getChunkCharacteristicsGui and friends) never dereferences this
            // result: the registryAccess()-gated rules in City that do read biomes only run when
            // we're not in this branch.
            Urbex.LOGGER.warn("NullDimensionInfo.getBiome() called without registry access; returning null.");
            return null;
        }
        ChunkPos cp = ChunkPos.containing(pos);
        char b = getBiomeChar(cp.x(), cp.z());
        ResourceKey<Biome> biome = switch (b) {
            case 'p' -> Biomes.PLAINS;
            case '-' -> Biomes.OCEAN;
            case '=' -> Biomes.RIVER;
            case '#' -> Biomes.STONY_PEAKS;
            // @todo 1.18
            case '+' -> Biomes.JAGGED_PEAKS;
            // @todo 1.18
            case '*' -> Biomes.BEACH;
            case 'd' -> Biomes.DESERT;
            // Plains fallback for anything unmapped, same as the old default branch.
            default -> Biomes.PLAINS;
        };
        return biomeRegistry.getOrThrow(biome);
    }

    @Override
    public ResourceKey<Level> dimension() {
        // Agrees with getType(): both name the overworld. dimension() used to return null here,
        // which disagreed with getType() and tripped up anything that assumed the two matched (#67).
        return Level.OVERWORLD;
    }
}
