package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.BuildingRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CommonLevelAccessor;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
import java.util.function.Predicate;

public class Building {

    private final Identifier name;

    private int minFloors = -1;         // -1 means default from level
    private int minCellars = -1;        // -1 means default frmo level
    private int maxFloors = -1;         // -1 means default from level
    private int maxCellars = -1;        // -1 means default frmo level
    private Boolean allowDoors = true;  // true means generation for the door is allowed, adjacent to street and building
    private Boolean allowFillers = true;  // true means generation for the filler is allowed, for cellars
    private Boolean overrideFloors = false;	// This overrides the citystyle/profile all min/max floors, meaning it will ONLY use this building definition's all min/max Floors.
    private final char fillerBlock;           // Block used to fill/close areas. Usually the block of the building itself
    private final Character rubbleBlock;      // Block used for destroyed building rubble
    private float prefersLonely = 0.0f; // The chance this this building is alone. If 1.0f this building wants to be alone all the time

    // See BuildingPart.localPalette: a reference to another palette needs the level to resolve, so
    // this one cannot be done in the constructor. Volatile; resolving it twice is harmless.
    private volatile Palette localPalette = null;
    private String refPaletteName;

    private final List<Pair<Predicate<ConditionContext>, String>> parts = new ArrayList<>();
    private final List<Pair<Predicate<ConditionContext>, String>> parts2 = new ArrayList<>();

    public Building(BuildingRE object) {
        name = object.getRegistryName();
        minFloors = object.getMinFloors();
        minCellars = object.getMinCellars();
        maxFloors = object.getMaxFloors();
        maxCellars = object.getMaxCellars();
        allowDoors = object.getAllowDoors();
        allowFillers = object.getAllowFillers();
        overrideFloors = object.getOverrideFloors();
        prefersLonely = object.getPrefersLonely();
        fillerBlock = object.getFillerBlock();
        rubbleBlock = object.getRubbleBlock();
        if (object.getLocalPalette() != null) {
            localPalette = new Palette("__local__" + object.getRegistryName().getPath());
            localPalette.parsePaletteArray(object.getLocalPalette()); // @todo get the full palette instead
        } else if (object.getRefPaletteName() != null) {
            refPaletteName = object.getRefPaletteName();
        }

        readParts(this.parts, object.getParts());
        readParts(this.parts2, object.getParts2());
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public Palette getLocalPalette(CommonLevelAccessor level) {
        Palette p = localPalette;
        if (p == null && refPaletteName != null) {
            p = AssetRegistries.PALETTES.getOrThrow(level, refPaletteName);
            localPalette = p;
        }
        return p;
    }

    private void readParts(List<Pair<Predicate<ConditionContext>, String>> p, List<PartRef> partRefs) {
        p.clear();
        if (partRefs == null) {
            return;
        }
        for (PartRef partRef : partRefs) {
            String partName = partRef.getPart();
            Predicate<ConditionContext> test = ConditionContext.parseTest(partRef);
            p.add(Pair.of(test, partName));
        }
    }

    public float getPrefersLonely() {
        return prefersLonely;
    }

    public int getMaxFloors() {
        return maxFloors;
    }

    public int getMaxCellars() {
        return maxCellars;
    }

    public int getMinFloors() {
        return minFloors;
    }

    public int getMinCellars() {
        return minCellars;
    }

    public Boolean getAllowDoors() {
    	return allowDoors;
    }

    public Boolean getAllowFillers() {
    	return allowFillers;
    }

    public Boolean getOverrideFloors() {
        return overrideFloors;
    }



    public char getFillerBlock() {
        return fillerBlock;
    }

    @Nullable
    public Character getRubbleBlock() {
        return rubbleBlock;
    }

    public String getRandomPart(RandomSource random, ConditionContext info) {
        List<String> partNames = new ArrayList<>();
        for (Pair<Predicate<ConditionContext>, String> pair : parts) {
            if (pair.getLeft().test(info)) {
                partNames.add(pair.getRight());
            }
        }
        if (partNames.isEmpty()) {
            return null;
        }
        return partNames.get(random.nextInt(partNames.size()));
    }

    public String getRandomPart2(RandomSource random, ConditionContext info) {
        List<String> partNames = new ArrayList<>();
        for (Pair<Predicate<ConditionContext>, String> pair : parts2) {
            if (pair.getLeft().test(info)) {
                partNames.add(pair.getRight());
            }
        }
        if (partNames.isEmpty()) {
            return null;
        }
        return partNames.get(random.nextInt(partNames.size()));
    }

}
