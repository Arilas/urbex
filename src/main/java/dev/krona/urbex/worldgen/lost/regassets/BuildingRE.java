package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class BuildingRE implements IAsset<BuildingRE>, Extendable {

    public static final Codec<BuildingRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.STRING.optionalFieldOf("refpalette").forGetter(l -> Optional.ofNullable(l.refPaletteName)),
                    PaletteRE.CODEC.optionalFieldOf("palette").forGetter(l -> Optional.ofNullable(l.localPalette)),
                    Codec.STRING.fieldOf("filler").forGetter(l -> Character.toString(l.fillerBlock)),
                    Codec.STRING.optionalFieldOf("rubble").forGetter(l -> Optional.ofNullable(l.rubbleBlock)),
                    Codec.INT.optionalFieldOf("mincellars").forGetter(l -> l.minCellars == -1 ? Optional.<Integer>empty() : Optional.of(l.minCellars)),
                    Codec.INT.optionalFieldOf("minfloors").forGetter(l -> l.minFloors == -1 ? Optional.<Integer>empty() : Optional.of(l.minFloors)),
                    Codec.INT.optionalFieldOf("maxcellars").forGetter(l -> l.maxCellars == -1 ? Optional.<Integer>empty() : Optional.of(l.maxCellars)),
                    Codec.INT.optionalFieldOf("maxfloors").forGetter(l -> l.maxFloors == -1 ? Optional.<Integer>empty() : Optional.of(l.maxFloors)),
                    Codec.BOOL.optionalFieldOf("allowDoors").forGetter(l -> Optional.ofNullable(l.allowDoors)),
                    Codec.BOOL.optionalFieldOf("allowFillers").forGetter(l -> Optional.ofNullable(l.allowFillers)),
                    Codec.BOOL.optionalFieldOf("overrideFloors").forGetter(l -> Optional.ofNullable(l.overrideFloors)),
                    Codec.FLOAT.optionalFieldOf("preferslonely").forGetter(l -> l.prefersLonely == 0 ? Optional.<Float>empty() : Optional.of(l.prefersLonely)),
                    Mergeable.codec(PartRef.CODEC).fieldOf("parts").forGetter(l -> l.parts),
                    Mergeable.codec(PartRef.CODEC).optionalFieldOf("parts2").forGetter(l -> Optional.ofNullable(l.parts2))
            ).apply(instance, BuildingRE::new));


    private Identifier name;

    private final Optional<Identifier> extendsId;

    private final int minFloors;        // -1 means "not declared here"
    private final int minCellars;       // -1 means "not declared here"
    private final int maxFloors;        // -1 means "not declared here"
    private final int maxCellars;       // -1 means "not declared here"
    private final Boolean allowDoors;	// true means generation for the door is allowed, adjacent to street and building
    private final Boolean allowFillers; // true means generation for the filler is allowed, for cellars
    private final Boolean overrideFloors;	// This overrides the citystyle/profile all min/max floors, meaning it will ONLY use this building definition's all min/max Floors.
    private final char fillerBlock;     // Block used to fill/close areas. Usually the block of the building itself
    private final String rubbleBlock;   // Block used for destroyed building rubble
    private final float prefersLonely;  // The chance this this building is alone. If 1.0f this building wants to be alone all the time

    private PaletteRE localPalette = null;
    private final String refPaletteName;

    private final Mergeable<PartRef> parts;
    private final Mergeable<PartRef> parts2;

    public BuildingRE(Optional<Identifier> extendsId,
                      Optional<String> refpalette, Optional<PaletteRE> locpalette, String filler, Optional<String> rubble,
                      Optional<Integer> minCellars, Optional<Integer> minFloors, Optional<Integer> maxCellars, Optional<Integer> maxFloors,
                      Optional<Boolean> allowDoors, Optional<Boolean> allowFillers, Optional<Boolean> overrideFloors,
                      Optional<Float> prefersLonely, Mergeable<PartRef> partRefs, Optional<Mergeable<PartRef>> partRefs2) {
        this.extendsId = extendsId;
        this.refPaletteName = refpalette.map(String::intern).orElse(null);
        this.localPalette = locpalette.orElse(null);
        this.fillerBlock = filler.charAt(0);
        this.rubbleBlock = rubble.map(String::intern).orElse(null);
        this.minCellars = minCellars.orElse(-1);
        this.maxCellars = maxCellars.orElse(-1);
        this.minFloors = minFloors.orElse(-1);
        this.maxFloors = maxFloors.orElse(-1);
        this.allowDoors = allowDoors.orElse(null);
        this.allowFillers = allowFillers.orElse(null);
        this.overrideFloors = overrideFloors.orElse(null);
        this.prefersLonely = prefersLonely.orElse(0.0f);
        this.parts = partRefs;
        this.parts2 = partRefs2.orElse(null);
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public BuildingRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }

    public int getMinFloors() {
        return minFloors;
    }

    public int getMinCellars() {
        return minCellars;
    }

    public int getMaxFloors() {
        return maxFloors;
    }

    public int getMaxCellars() {
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

    public char getFillerBlock() {
        return fillerBlock;
    }

    public Character getRubbleBlock() {
        return rubbleBlock == null ? null : rubbleBlock.charAt(0);
    }

    public float getPrefersLonely() {
        return prefersLonely;
    }

    public PaletteRE getLocalPalette() {
        return localPalette;
    }

    public String getRefPaletteName() {
        return refPaletteName;
    }

    public Mergeable<PartRef> getParts() {
        return parts;
    }

    @Nullable
    public Mergeable<PartRef> getParts2() {
        return parts2;
    }

}
