package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.Parts;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.Orientation;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.world.level.block.state.BlockState;

public class Bridges {

    public static void generateBridges(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info) {
        if (info.getHighwayXLevel() == 0 || info.getHighwayZLevel() == 0) {
            // If there is a highway at level 0 we cannot generate bridge parts. If there
            // is no highway or a highway at level 1 then bridge sections can generate just fine
            return;
        }
        BuildingPart bt = info.hasXBridge(info.provider);
        if (bt != null) {
            generateBridge(ctx, feature, info, bt, Orientation.X);
        } else {
            bt = info.hasZBridge(info.provider);
            if (bt != null) {
                generateBridge(ctx, feature, info, bt, Orientation.Z);
            }
        }
    }

    private static void generateBridge(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, BuildingPart bt, Orientation orientation) {
        CompiledPalette compiledPalette = Parts.computePalette(feature, info, bt);
        ChunkDriver driver = ctx.driver;
        // The opportunistic bridge parts are authored one block above the street surface, as a deck
        // slung over a gap. A planned primary bridge is the road itself carried onward, so its deck
        // sits at the street surface and its markings line up with the road at either end.
        int bridgeLevel = info.getPlannedBridge() != null
                ? info.profile.groundLevel()
                : info.profile.groundLevel() + 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                driver.current(x, bridgeLevel, z);
                int l = 0;
                while (l < bt.getSliceCount()) {
                    Character c = orientation == Orientation.X ? bt.getPaletteChar(x, l, z) : bt.getPaletteChar(z, l, x); // @todo general rotation system?
                    // One lookup (LOAD.022). getInfo answers null for every version 2 marker, so
                    // asking it here lost a version 2 bridge light silently.
                    CompiledPalette.Placed placed = ctx.placedHere(compiledPalette, c);
                    BlockState b = placed == null ? null : placed.state();
                    Palette.Info inf = placed == null ? null : placed.info();
                    if (inf != null) {
                        if (inf.lightSource() != null) {
                            b = Parts.handleLightSource(ctx, feature, inf.lightSource(), b,
                                    driver.getCurrentCopy());
                        }
                    }
                    driver.add(b);
                    l++;
                }
            }
        }

        Character support = bt.getMetaChar(BuildingPart.META_SUPPORT);
        if (info.profile.bridgeSupports() && support != null) {
            BlockState sup = ctx.paletteAt(compiledPalette, support, 7, info.groundLevel, 7);
            // Everything below the deck is measured from the deck, not from the ground: the pillar
            // and the two side lips belong one block under whichever level the deck landed on. Read
            // off GROUNDLEVEL instead and a planned bridge, whose deck sits a block lower, would
            // have its supports written straight through its own road surface.
            int underDeck = bridgeLevel - 1;
            ChunkPlan minDir = orientation.getMinDir().get(info);
            ChunkPlan maxDir = orientation.getMaxDir().get(info);
            if (minDir.hasBridge(info.provider, orientation) != null && maxDir.hasBridge(info.provider, orientation) != null) {
                // Needs support
                for (int y = info.waterLevel - 10; y <= underDeck; y++) {
                    driver.current(7, y, 7).block(sup);
                    driver.current(7, y, 8).block(sup);
                    driver.current(8, y, 7).block(sup);
                    driver.current(8, y, 8).block(sup);
                }
            }
            if (minDir.hasBridge(info.provider, orientation) == null) {
                // Connection to the side section
                if (orientation == Orientation.X) {
                    int x = 0;
                    driver.current(x, underDeck, 6);
                    for (int z = 6; z <= 9; z++) {
                        driver.block(sup).incZ();
                    }
                } else {
                    int z = 0;
                    driver.current(6, underDeck, z);
                    for (int x = 6; x <= 9; x++) {
                        driver.block(sup).incX();
                    }
                }
            }
            if (maxDir.hasBridge(info.provider, orientation) == null) {
                // Connection to the side section
                if (orientation == Orientation.X) {
                    int x = 15;
                    driver.current(x, underDeck, 6);
                    for (int z = 6; z <= 9; z++) {
                        driver.block(sup).incZ();
                    }
                } else {
                    int z = 15;
                    driver.current(6, underDeck, z);
                    for (int x = 6; x <= 9; x++) {
                        driver.block(sup).incX();
                    }
                }
            }
        }
    }
}
