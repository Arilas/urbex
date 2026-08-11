package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.BuildingRE;
import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
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
    private char fillerBlock;           // Block used to fill/close areas. Usually the block of the building itself
    private Character rubbleBlock;      // Block used for destroyed building rubble
    private float prefersLonely = 0.0f; // The chance this this building is alone. If 1.0f this building wants to be alone all the time

    // See BuildingPart.localPalette: a reference to another palette needs the level to resolve, so
    // this one cannot be done in the constructor. Volatile; resolving it twice is harmless.
    private volatile Palette localPalette = null;
    private String refPaletteName;

    private final List<Pair<Predicate<ConditionContext>, String>> parts = new ArrayList<>();
    private final List<Pair<Predicate<ConditionContext>, String>> parts2 = new ArrayList<>();

    /**
     * Builds a fully resolved building from its {@code extends} chain, root first: every scalar
     * takes the value of the last entry that declares one, so an entry that omits a field does not
     * blank out what an earlier one set, and the two part lists go through {@link Mergeable} so a
     * declared list replaces the inherited one unless it opts into appending.
     */
    public Building(List<BuildingRE> chainRootFirst) {
        name = chainRootFirst.get(chainRootFirst.size() - 1).getRegistryName();
        List<PartRef> partRefs = new ArrayList<>();
        List<PartRef> partRefs2 = new ArrayList<>();
        List<PaletteRE> inlinePalettes = new ArrayList<>();
        String refPalette = null;
        for (BuildingRE object : chainRootFirst) {
            if (object.getMinFloors() != -1) {
                minFloors = object.getMinFloors();
            }
            if (object.getMinCellars() != -1) {
                minCellars = object.getMinCellars();
            }
            if (object.getMaxFloors() != -1) {
                maxFloors = object.getMaxFloors();
            }
            if (object.getMaxCellars() != -1) {
                maxCellars = object.getMaxCellars();
            }
            if (object.getAllowDoors() != null) {
                allowDoors = object.getAllowDoors();
            }
            if (object.getAllowFillers() != null) {
                allowFillers = object.getAllowFillers();
            }
            if (object.getOverrideFloors() != null) {
                overrideFloors = object.getOverrideFloors();
            }
            if (object.getPrefersLonely() != 0.0f) {
                prefersLonely = object.getPrefersLonely();
            }
            fillerBlock = object.getFillerBlock();
            if (object.getRubbleBlock() != null) {
                rubbleBlock = object.getRubbleBlock();
            }
            // Inline palettes stack: they are a keyed collection like a registered palette, so a
            // later block repaints the characters it declares and keeps the rest. Naming a palette
            // with refpalette instead is a different choice, not another layer, so it drops them.
            if (object.getLocalPalette() != null) {
                inlinePalettes.add(object.getLocalPalette());
                refPalette = null;
            } else if (object.getRefPaletteName() != null) {
                refPalette = object.getRefPaletteName();
                inlinePalettes.clear();
            }
            Mergeable.apply(partRefs, object.getParts());
            if (object.getParts2() != null) {
                Mergeable.apply(partRefs2, object.getParts2());
            }
        }

        if (!inlinePalettes.isEmpty()) {
            localPalette = Palette.inline(name, inlinePalettes); // @todo get the full palette instead
        } else if (refPalette != null) {
            refPaletteName = refPalette;
        }

        readParts(this.parts, partRefs);
        readParts(this.parts2, partRefs2);
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
