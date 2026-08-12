package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.lost.BiomeInfo;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
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

public class Stuff {

    // Stuff is picked by tag, so a pack can contribute stuff to a tag that other packs'
    // city styles also declare, while the character it uses is only defined in its own
    // palettes. Report each such combination once instead of on every city chunk.
    private static final Set<String> REPORTED_UNRESOLVED = ConcurrentHashMap.newKeySet();

    public static void generateStuff(ChunkGenContext ctx, CityGenerator feature, BuildingInfo info) {
        // No unloaded-registries guard here any more, and none is possible: the stuff index is a
        // component of the AssetSnapshot this chunk's provider was built with, and a snapshot cannot
        // be cleared, half-built or swapped underneath a generation (issue #128). The guard that
        // stood here detected exactly that - a reset landing mid-chunk, which emptied the tag index
        // and made this method place nothing while the chunk was written and saved anyway - and it
        // was needed because AssetRegistries.reset() was reachable from the client thread. Removing
        // the state removed the failure; there is nothing left to detect.
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
            List<StuffObject> stuffs = feature.provider.assets().stuffFor(tag);
            {
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
