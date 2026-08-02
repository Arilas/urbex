package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.LostCityTerrainFeature;
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
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Stuff {

    // Stuff is picked by tag, so a pack can contribute stuff to a tag that other packs'
    // city styles also declare, while the character it uses is only defined in its own
    // palettes. Report each such combination once instead of on every city chunk.
    private static final Set<String> REPORTED_UNRESOLVED = ConcurrentHashMap.newKeySet();

    public static void generateStuff(ChunkGenContext ctx, LostCityTerrainFeature feature, BuildingInfo info) {
        // Each stuff object gets its own address, and within it each placement attempt gets its
        // own, derived from the loop indices. An attempt therefore draws the same values however
        // many attempts before it were abandoned - and whether an attempt is abandoned depends on
        // what is already in the world, which is not ours to depend on.
        int stuffOrdinal = 0;
        BiomeInfo biome = BiomeInfo.getBiomeInfo(feature.provider, info.coord);
        CompiledPalette palette = info.getCompiledPalette();
        for (String tag : info.getCityStyle().getStuffTags()) {
            List<StuffObject> stuffs = AssetRegistries.STUFF_BY_TAG.get(tag);
            if (stuffs != null) {
                for (StuffObject stuff : stuffs) {
                    StuffSettingsRE settings = stuff.getSettings();
                    Boolean inBuilding = settings.isInBuilding();
                    if (inBuilding != null && inBuilding == info.hasBuilding) {
                        IdentifierMatcher buildingMatcher = settings.getBuildingMatcher();
                        if (buildingMatcher.isAny() || buildingMatcher.test(info.buildingType.getId())) {
                            if (settings.getBiomeMatcher().test(biome.getMainBiome())) {
                                actuallyGenerateStuff(ctx, feature, info, stuff, palette, stuffOrdinal++, inBuilding == Boolean.TRUE);
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

    private static void actuallyGenerateStuff(ChunkGenContext ctx, LostCityTerrainFeature feature, BuildingInfo info, StuffObject stuff, CompiledPalette palette, int stuffOrdinal, boolean inBuilding) {
        StuffSettingsRE settings = stuff.getSettings();
        String blocks = settings.getColumn();
        if (!columnResolves(stuff, blocks, palette)) {
            return;
        }
        ChunkDriver driver = ctx.driver;
        WorldGenLevel level = info.provider.getWorld();
        int attempts = settings.getAttempts();
        Integer minheight = settings.getMinheight();
        Integer maxheight = settings.getMaxheight();
        if (minheight == null) {
            minheight = info.groundLevel;
            if (inBuilding && info.hasBuilding) {
                int lowestLevel = info.getCityGroundLevel() - info.cellars * LostCityTerrainFeature.FLOORHEIGHT;
                minheight = lowestLevel;
            }
        }
        if (maxheight == null) {
            maxheight = minheight + 20;
            if (inBuilding && info.hasBuilding) {
                maxheight = info.getCityGroundLevel() + info.getNumFloors() * LostCityTerrainFeature.FLOORHEIGHT + 10; // 10 margine above highest floor
            }
        }
        int mincount = settings.getMincount();
        int maxcount = settings.getMaxcount();
        int count = slot(ctx, stuffOrdinal, -1, 0).nextInt(maxcount - mincount) + mincount;
        for (int j = 0; j < count; j++) {
            for (int i = 0; i < attempts; i++) {
                RandomSource rand = slot(ctx, stuffOrdinal, j, i);
                int x = rand.nextInt(16);
                int y = rand.nextInt(maxheight - minheight) + minheight;
                int z = rand.nextInt(16);
                if (testBlock(driver, settings.getBlockMatcher(), x, y-1, z) && testBlock(driver, settings.getUpperBlockMatcher(), x, y + blocks.length(), z)) {
                    Boolean isSeesky = settings.isSeesky();
                    if (isSeesky == null || isSeesky == level.canSeeSky(info.getRelativePos(x, y, z))) {
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
