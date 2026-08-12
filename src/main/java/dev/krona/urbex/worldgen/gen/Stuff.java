package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.lost.BiomeInfo;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.StuffObject;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockMatcher;
import dev.krona.urbex.worldgen.lost.regassets.data.IdentifierMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Stuff {

    // Stuff is picked by tag, so a pack can contribute stuff to a tag that other packs'
    // city styles also declare, while the character it uses is only defined in its own
    // palettes. Report each such combination once instead of on every city chunk.
    private static final Set<String> REPORTED_UNRESOLVED = ConcurrentHashMap.newKeySet();

    /**
     * Latches while the registries are unloaded so the report below fires once per episode rather
     * than once per chunk, and re-arms as soon as they are loaded again.
     */
    private static final AtomicBoolean REPORTED_UNLOADED = new AtomicBoolean();

    public static void generateStuff(ChunkGenContext ctx, CityGenerator feature, BuildingInfo info) {
        // The index is taken once, as a snapshot, and every tag below is read out of it. That is
        // not a micro-optimisation: AssetRegistries.reset() replaces the whole index in one write
        // (see the field), so holding the reference means a reset landing while this method is
        // running cannot half-decorate the chunk - the walk either sees the old index throughout or
        // the new one throughout. Asking per tag would leave exactly that sliver.
        //
        // Generating with the registries unloaded is never legitimate here, and it is the one
        // failure in this file that says nothing: every tag misses, the loop below places nothing,
        // and the chunk is written and saved undecorated - exactly the shipped defect Task 5c
        // removed, reappearing one chunk at a time. Every other registry heals itself on the next
        // lookup (RegistryAssetRegistry.get re-resolves on a miss); this index has no lazy rebuild,
        // so nothing would ever notice.
        //
        // Both conditions are required. An empty index alone is legitimate - a pack may ship no
        // stuff files at all - and the flag alone would fire on the harmless ordering where the
        // reset lands after the snapshot was taken, which this chunk survives intact. They come off
        // one record and one volatile read on purpose: as two separate volatile fields they could be
        // observed out of step (emptied index, stale loaded == true), and the guard would wave
        // through exactly the silent chunk it exists to catch. See AssetRegistries.StuffIndex.
        //
        // No longer reachable by any path this mod owns, and kept anyway. It used to be plainly
        // reachable: AssetRegistries.reset() ran from CityFeature.cleanUp, which the generation
        // path invoked whenever a global dirty counter had been bumped, and
        // ClientPlayConnectionEvents.DISCONNECT bumped it from the client thread while a
        // single-player integrated server was still draining in-flight generation. reset() is now
        // called only when a session opens or closes, on the server thread with no level loaded
        // (GenerationSession, issue #125). This guard is what would say so if that ever stopped
        // being true - the failure it detects is otherwise completely silent.
        //
        // Logged rather than thrown, for two reasons. generateStuff is the last statement of
        // CityGenerator.doCityChunk, which generate() calls at CityGenerator.java:290, well before
        // ctx.driver.actuallyGenerate(chunk) at :310 - so a throw here would unwind past the commit
        // and lose the chunk's entire cached write set, costing the whole chunk rather than its
        // decoration. And it would route through CityFeature.generateFromPipeline's handler into
        // ErrorLogger.report, which dereferences ServerAccess.getServer() with no null check
        // (ErrorLogger.java:28-29) - during the shutdown window this fires in, that turns a
        // decoration bug into a dead worldgen worker.
        AssetRegistries.StuffIndex stuffIndex = AssetRegistries.stuffIndex();
        if (stuffIndex.byTag().isEmpty() && !stuffIndex.loaded()) {
            if (REPORTED_UNLOADED.compareAndSet(false, true)) {
                Urbex.getLogger().error(
                        "Generating chunk {},{} with the Urbex asset registries unloaded: no decoration will be " +
                                "placed in this chunk or any other until they are loaded again, and the chunks are " +
                                "saved that way. Something called AssetRegistries.reset() while generation was in " +
                                "flight. Reported once per occurrence, not once per chunk.",
                        ctx.coord.chunkX(), ctx.coord.chunkZ());
            }
            return;
        }
        if (REPORTED_UNLOADED.get()) {
            REPORTED_UNLOADED.set(false);
        }
        // Each stuff object gets its own address, and within it each placement attempt gets its
        // own, derived from the loop indices. An attempt therefore draws the same values however
        // many attempts before it were abandoned - and whether an attempt is abandoned depends on
        // what is already in the world, which is not ours to depend on.
        //
        // The ordinal is an RNG address, so what it counts has to be stable against anything that
        // is not a deliberate change to the pack. Both loops below are ordered for that reason and
        // not for tidiness: CityStyle.getStuffTags() is sorted (see the field) and each
        // list in the index is sorted by Identifier (see AssetRegistries.groupStuffByTag). Neither
        // may go back to a hash-ordered collection.
        int stuffOrdinal = 0;
        BiomeInfo biome = BiomeInfo.getBiomeInfo(feature.provider, info.coord);
        CompiledPalette palette = info.getCompiledPalette();
        for (String tag : info.getCityStyle().getStuffTags()) {
            List<StuffObject> stuffs = stuffIndex.byTag().get(tag);
            if (stuffs != null) {
                for (StuffObject stuff : stuffs) {
                    StuffSettingsRE settings = stuff.getSettings();
                    // Never null: 'inbuilding' is required of the resolved chain, precisely because
                    // the null branch this used to carry made the stuff object silently inert.
                    boolean inBuilding = settings.isInBuilding();
                    if (inBuilding == info.hasBuilding) {
                        IdentifierMatcher buildingMatcher = settings.getBuildingMatcher();
                        if (buildingMatcher.isAny() || buildingMatcher.test(info.buildingType.getId())) {
                            if (settings.getBiomeMatcher().test(biome.getMainBiome())) {
                                actuallyGenerateStuff(ctx, feature, info, stuff, palette, stuffOrdinal++, inBuilding);
                            }
                        }
                    }
                }
            }
        }
    }

    /** The stream for one placement attempt, addressed by (stuff, count index, attempt index). */
    private static RandomSource slot(ChunkGenContext ctx, int stuffOrdinal, int j, int i) {
        long address = ((long) stuffOrdinal * 4096L + (j + 1)) * 4096L + i;
        return Rng.atSlot(ctx.seed, ctx.coord.chunkX(), ctx.coord.chunkZ(), address, Rng.Purpose.STUFF);
    }

    /**
     * Whether a straight column of air runs from {@code y} to the top of the world, read
     * through the driver so it sees what this chunk's generation has actually built. The old
     * {@code level.canSeeSky} consulted the chunk heightmap, which at this point still
     * describes the pre-city vanilla terrain (issue #46) - seesky filters answered about a
     * world that no longer existed.
     */
    private static boolean seesSky(ChunkDriver driver, CityGenerator feature, int x, int y, int z) {
        int maxY = feature.provider.getWorld().getMaxY();
        for (int yy = y; yy <= maxY; yy++) {
            if (!driver.getBlock(x, yy, z).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean testBlock(ChunkDriver driver, BlockMatcher matcher, int x, int y, int z) {
        if (matcher.isAny()) {
            return true;
        }
        return matcher.test(driver.getBlock(x, y, z));
    }

    /**
     * Is every character of this column defined in the palette that is active here?
     *
     * <p>{@link CompiledPalette#get(char)} returns null for a character it does not know.
     * Feeding that null to the driver used to throw from {@link ChunkDriver#correct},
     * killing the worldgen worker and leaving the chunk ungenerated. The column is
     * checked as a whole and up front so a partly resolved one is never placed.
     *
     * <p>Asks {@link CompiledPalette#isDefined} rather than calling get(): for a weighted
     * character get() draws from the shared fastrand sequence, so probing with it would
     * shift what every later call produces.
     */
    private static boolean columnResolves(StuffObject stuff, String blocks, CompiledPalette palette) {
        for (int k = 0; k < blocks.length(); k++) {
            char c = blocks.charAt(k);
            if (!palette.isDefined(c)) {
                if (REPORTED_UNRESOLVED.add(stuff.getId() + ":" + c)) {
                    Urbex.getLogger().warn(
                            "Stuff '{}' uses character '{}', which no palette of the city style being generated defines. " +
                                    "Skipping it. Add the character to a palette used by that style, or restrict the stuff to its own tag.",
                            stuff.getId(), c);
                }
                return false;
            }
        }
        return true;
    }

    private static void actuallyGenerateStuff(ChunkGenContext ctx, CityGenerator feature, BuildingInfo info, StuffObject stuff, CompiledPalette palette, int stuffOrdinal, boolean inBuilding) {
        StuffSettingsRE settings = stuff.getSettings();
        String blocks = settings.getColumn();
        if (!columnResolves(stuff, blocks, palette)) {
            return;
        }
        ChunkDriver driver = ctx.driver;
        // No level is taken here: the sky test below is seesSky(), which reads through the driver
        // rather than the region. The old level.canSeeSky() this method used to call is gone with
        // it (#46), and so is the local that held the level for it.
        int attempts = settings.getAttempts();
        Integer minheight = settings.getMinheight();
        Integer maxheight = settings.getMaxheight();
        if (minheight == null) {
            minheight = info.groundLevel;
            if (inBuilding && info.hasBuilding) {
                int lowestLevel = info.getCityGroundLevel() - info.cellars * CityGenerator.FLOORHEIGHT;
                minheight = lowestLevel;
            }
        }
        if (maxheight == null) {
            maxheight = minheight + 20;
            if (inBuilding && info.hasBuilding) {
                maxheight = info.getCityGroundLevel() + info.getNumFloors() * CityGenerator.FLOORHEIGHT + 10; // 10 margine above highest floor
            }
        }
        int mincount = settings.getMincount();
        int maxcount = settings.getMaxcount();
        int count = Tools.randomBetween(slot(ctx, stuffOrdinal, -1, 0), mincount, maxcount);
        for (int j = 0; j < count; j++) {
            for (int i = 0; i < attempts; i++) {
                RandomSource rand = slot(ctx, stuffOrdinal, j, i);
                int x = rand.nextInt(16);
                int y = Tools.randomBetween(rand, minheight, maxheight);
                int z = rand.nextInt(16);
                if (testBlock(driver, settings.getBlockMatcher(), x, y-1, z) && testBlock(driver, settings.getUpperBlockMatcher(), x, y + blocks.length(), z)) {
                    Boolean isSeesky = settings.isSeesky();
                    if (isSeesky == null || isSeesky == seesSky(driver, feature, x, y, z)) {
                        // Iterate over all characters of the block
                        boolean ok = true;
                        for (int k = 0; k < blocks.length(); k++) {
                            if (driver.getBlock(x, y + k, z) != feature.air) {
                                ok = false;
                                break;
                            }
                        }
                        if (ok) {
                            driver.current(x, y, z);
                            for (int k = 0; k < blocks.length(); k++) {
                                BlockState block = ctx.paletteHere(palette, blocks.charAt(k));
                                driver.add(block);
                            }
                            break;
                        }
                    }
                }
            }
        }
    }
}
