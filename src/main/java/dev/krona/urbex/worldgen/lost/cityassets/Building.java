package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.BuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.block.Block;
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

    // The palette this building paints with: its own inline one, or the refpalette it names,
    // resolved by the compiler before this object is published (issue #128). It used to be filled
    // lazily from the first chunk that asked, which is why it needed a level.
    private Palette localPalette = null;
    private String refPaletteName;

    private final List<Pair<Predicate<ConditionContext>, String>> parts = new ArrayList<>();
    private final List<Pair<Predicate<ConditionContext>, String>> parts2 = new ArrayList<>();

    /**
     * Builds a fully resolved building from its {@code extends} chain, root first: every scalar
     * takes the value of the last entry that declares one, so an entry that omits a field does not
     * blank out what an earlier one set, and the two part lists go through {@link Mergeable} so a
     * declared list replaces the inherited one unless it opts into appending.
     * <p>
     * {@code filler} and {@code parts} are required of the chain rather than of each file, so a
     * building that only repaints what it extends need declare neither; a chain where nothing
     * declares one is a load error naming the asset and the field.
     * <p>
     * "Declared" is read from a null rather than from a sentinel, so a child can set
     * {@code preferslonely} back to {@code 0.0} or {@code maxfloors} back to {@code -1} against an
     * ancestor that set something else. The defaults the fields start at are this class's own
     * documented fallbacks - {@code -1} for "take the level's limit" - not markers for "undeclared".
     */
    public Building(Identifier id, HolderLookup<Block> blockLookup, @Nullable AssetIndex<Variant> variants,
                        AssetIndex<Palette> palettes, List<BuildingDefinition> chainRootFirst) {
        name = id;
        List<PartRef> partRefs = new ArrayList<>();
        boolean anyParts = false;
        List<PartRef> partRefs2 = new ArrayList<>();
        List<PaletteDefinition> inlinePalettes = new ArrayList<>();
        String refPalette = null;
        Character filler = null;
        for (BuildingDefinition object : chainRootFirst) {
            if (object.getMinFloors() != null) {
                minFloors = object.getMinFloors();
            }
            if (object.getMinCellars() != null) {
                minCellars = object.getMinCellars();
            }
            if (object.getMaxFloors() != null) {
                maxFloors = object.getMaxFloors();
            }
            if (object.getMaxCellars() != null) {
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
            if (object.getPrefersLonely() != null) {
                prefersLonely = object.getPrefersLonely();
            }
            if (object.getFillerBlock() != null) {
                filler = object.getFillerBlock();
            }
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
            if (object.getParts() != null) {
                Mergeable.apply(partRefs, object.getParts());
                anyParts = true;
            }
            if (object.getParts2() != null) {
                Mergeable.apply(partRefs2, object.getParts2());
            }
        }
        fillerBlock = Resolved.require(filler, name, "filler");
        Resolved.require(anyParts ? partRefs : null, name, "parts");

        if (!inlinePalettes.isEmpty()) {
            localPalette = Palette.inline(blockLookup, variants, name, inlinePalettes); // @todo get the full palette instead
        } else if (refPalette != null) {
            refPaletteName = refPalette;
            // Resolved here, not on the first chunk that asks. The lazy version cached the answer on
            // this asset and needed a CommonLevelAccessor to reach the palette registry, so a
            // refpalette naming something absent surfaced from a worldgen worker (issue #128).
            localPalette = palettes.getOrThrow(refPalette);
        }

        readParts(this.parts, partRefs);
        readParts(this.parts2, partRefs2);
    }

    /** The fully-qualified id, e.g. {@code "urbex:radiotower"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    public Palette getLocalPalette() {
        return localPalette;
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

    /**
     * The {@code parts2[]} entry for a floor whose {@code parts[]} entry is {@code currentPart}.
     * <p>
     * Takes the part rather than a ready-made context, and derives one with
     * {@link ConditionContext#withPart}, so that the {@code parts2} context's {@code belowPart} is
     * always {@code floorContext}'s - a caller advancing its own {@code belowPart} local to
     * {@code currentPart} before calling this can no longer reach it, which is exactly what all
     * three floor loops used to do, making a {@code parts2[]} {@code belowpart} a duplicate of its
     * {@code inpart}. That is the invariant; it is not a check. {@code currentPart} is an untyped
     * positional {@code String}, so passing {@code floorContext.getBelowPart()} still compiles and
     * still produces the wrong condition - and deliberately is not rejected, because a building that
     * repeats a part on consecutive floors legitimately has them equal ({@code library00} has one
     * non-top part entry, so every non-top floor of one draws the same part).
     * <p>
     * {@code floorContext} is the same context {@link #getRandomPart} was given.
     */
    public String getRandomPart2(RandomSource random, ConditionContext floorContext, String currentPart) {
        ConditionContext info = floorContext.withPart(currentPart);
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
