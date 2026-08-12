package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A building: its parts, its filler, and the limits generation places it under.
 * <p>
 * Every field is optional here, {@code filler} and {@code parts} included, because requiredness is
 * checked after the {@code extends} chain is resolved, in
 * {@link dev.krona.urbex.worldgen.lost.cityassets.Building}. Undeclared is represented by null
 * throughout rather than by a sentinel: {@code -1} floors or a {@code preferslonely} of {@code 0.0}
 * are values a file may legitimately mean, and under a sentinel a child could not override an
 * inherited {@code 0.8} back down to {@code 0.0}.
 */
public class BuildingDefinition implements Extendable {

    private static final Codec<BuildingDefinition> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.STRING.optionalFieldOf("refpalette").forGetter(l -> Optional.ofNullable(l.refPaletteName)),
                    PaletteDefinition.CODEC.optionalFieldOf("palette").forGetter(l -> Optional.ofNullable(l.localPalette)),
                    DataTools.PALETTE_CHAR.optionalFieldOf("filler").forGetter(l -> Optional.ofNullable(l.fillerBlock)),
                    DataTools.PALETTE_CHAR.optionalFieldOf("rubble").forGetter(l -> Optional.ofNullable(l.rubbleBlock)),
                    Codec.INT.optionalFieldOf("mincellars").forGetter(l -> Optional.ofNullable(l.minCellars)),
                    Codec.INT.optionalFieldOf("minfloors").forGetter(l -> Optional.ofNullable(l.minFloors)),
                    Codec.INT.optionalFieldOf("maxcellars").forGetter(l -> Optional.ofNullable(l.maxCellars)),
                    Codec.INT.optionalFieldOf("maxfloors").forGetter(l -> Optional.ofNullable(l.maxFloors)),
                    Codec.BOOL.optionalFieldOf("allowDoors").forGetter(l -> Optional.ofNullable(l.allowDoors)),
                    Codec.BOOL.optionalFieldOf("allowFillers").forGetter(l -> Optional.ofNullable(l.allowFillers)),
                    Codec.BOOL.optionalFieldOf("overrideFloors").forGetter(l -> Optional.ofNullable(l.overrideFloors)),
                    Codec.FLOAT.optionalFieldOf("preferslonely").forGetter(l -> Optional.ofNullable(l.prefersLonely)),
                    Mergeable.codec(PartRef.CODEC).optionalFieldOf("parts").forGetter(l -> Optional.ofNullable(l.parts)),
                    Mergeable.codec(PartRef.CODEC).optionalFieldOf("parts2").forGetter(l -> Optional.ofNullable(l.parts2))
            ).apply(instance, BuildingDefinition::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<BuildingDefinition> CODEC = RetiredKeys.reject(RAW, "building");



    private final Optional<Identifier> extendsId;

    // Null throughout means "not declared here", so the chain reads the field from an ancestor.
    private final Integer minFloors;
    private final Integer minCellars;
    private final Integer maxFloors;
    private final Integer maxCellars;
    private final Boolean allowDoors;	// true means generation for the door is allowed, adjacent to street and building
    private final Boolean allowFillers; // true means generation for the filler is allowed, for cellars
    private final Boolean overrideFloors;	// This overrides the citystyle/profile all min/max floors, meaning it will ONLY use this building definition's all min/max Floors.
    private final Character fillerBlock; // Block used to fill/close areas. Usually the block of the building itself
    private final Character rubbleBlock;   // Block used for destroyed building rubble
    private final Float prefersLonely;  // The chance this this building is alone. If 1.0f this building wants to be alone all the time

    private PaletteDefinition localPalette = null;
    private final String refPaletteName;

    private final Mergeable<PartRef> parts;
    private final Mergeable<PartRef> parts2;

    public BuildingDefinition(Optional<Identifier> extendsId,
                      Optional<String> refpalette, Optional<PaletteDefinition> locpalette, Optional<Character> filler, Optional<Character> rubble,
                      Optional<Integer> minCellars, Optional<Integer> minFloors, Optional<Integer> maxCellars, Optional<Integer> maxFloors,
                      Optional<Boolean> allowDoors, Optional<Boolean> allowFillers, Optional<Boolean> overrideFloors,
                      Optional<Float> prefersLonely, Optional<Mergeable<PartRef>> partRefs, Optional<Mergeable<PartRef>> partRefs2) {
        this.extendsId = extendsId;
        this.refPaletteName = refpalette.map(String::intern).orElse(null);
        this.localPalette = locpalette.orElse(null);
        this.fillerBlock = filler.orElse(null);
        this.rubbleBlock = rubble.orElse(null);
        this.minCellars = minCellars.orElse(null);
        this.maxCellars = maxCellars.orElse(null);
        this.minFloors = minFloors.orElse(null);
        this.maxFloors = maxFloors.orElse(null);
        this.allowDoors = allowDoors.orElse(null);
        this.allowFillers = allowFillers.orElse(null);
        this.overrideFloors = overrideFloors.orElse(null);
        this.prefersLonely = prefersLonely.orElse(null);
        this.parts = partRefs.orElse(null);
        this.parts2 = partRefs2.orElse(null);
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }



    @Nullable
    public Integer getMinFloors() {
        return minFloors;
    }

    @Nullable
    public Integer getMinCellars() {
        return minCellars;
    }

    @Nullable
    public Integer getMaxFloors() {
        return maxFloors;
    }

    @Nullable
    public Integer getMaxCellars() {
        return maxCellars;
    }

    @Nullable
    public Boolean getAllowDoors() {
        return allowDoors;
    }

    @Nullable
    public Boolean getAllowFillers() {
        return allowFillers;
    }

    @Nullable
    public Boolean getOverrideFloors() {
        return overrideFloors;
    }

    @Nullable
    public Character getFillerBlock() {
        return fillerBlock;
    }

    @Nullable
    public Character getRubbleBlock() {
        return rubbleBlock;
    }

    @Nullable
    public Float getPrefersLonely() {
        return prefersLonely;
    }

    public PaletteDefinition getLocalPalette() {
        return localPalette;
    }

    public String getRefPaletteName() {
        return refPaletteName;
    }

    @Nullable
    public Mergeable<PartRef> getParts() {
        return parts;
    }

    @Nullable
    public Mergeable<PartRef> getParts2() {
        return parts2;
    }

}
