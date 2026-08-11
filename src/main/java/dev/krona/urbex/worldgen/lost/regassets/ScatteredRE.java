package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.cityassets.ScatteredBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class ScatteredRE implements IAsset<ScatteredRE>, Extendable {

    public static final Codec<ScatteredRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(Codec.STRING).optionalFieldOf("buildings").forGetter(l -> Optional.ofNullable(l.buildings)),
                    Codec.STRING.optionalFieldOf("multibuilding").forGetter(l -> Optional.ofNullable(l.multibuilding)),
                    Codec.BOOL.optionalFieldOf("rotatable").forGetter(l -> Optional.ofNullable(l.rotatable)),
                    StringRepresentable.fromEnum(ScatteredBuilding.TerrainHeight::values).fieldOf("terrainheight").forGetter(l -> l.terrainheight),
                    StringRepresentable.fromEnum(ScatteredBuilding.TerrainFix::values).fieldOf("terrainfix").forGetter(l -> l.terrainfix),
                    Codec.INT.optionalFieldOf("heightoffset").forGetter(l -> Optional.ofNullable(l.heightoffset))
            ).apply(instance, ScatteredRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final ScatteredBuilding.TerrainHeight terrainheight;
    private final ScatteredBuilding.TerrainFix terrainfix;
    private final Integer heightoffset;
    private final Boolean rotatable;
    private final Mergeable<String> buildings;
    private final String multibuilding;

    public ScatteredRE(Optional<Identifier> extendsId,
                       Optional<Mergeable<String>> buildings, Optional<String> multibuilding,
                       Optional<Boolean> rotatable,
                       ScatteredBuilding.TerrainHeight terrainheight, ScatteredBuilding.TerrainFix terrainfix,
                       Optional<Integer> heightoffset) {
        this.extendsId = extendsId;
        this.buildings = buildings.orElse(null);
        this.multibuilding = multibuilding.map(String::intern).orElse(null);
        this.rotatable = rotatable.orElse(null);
        this.terrainheight = terrainheight;
        this.terrainfix = terrainfix;
        this.heightoffset = heightoffset.orElse(null);
    }

    @Nullable
    public Boolean isRotatable() {
        return rotatable;
    }

    @Nullable
    public Mergeable<String> getBuildings() {
        return buildings;
    }

    @Nullable
    public String getMultibuilding() {
        return multibuilding;
    }

    public ScatteredBuilding.TerrainHeight getTerrainheight() {
        return terrainheight;
    }

    public ScatteredBuilding.TerrainFix getTerrainfix() {
        return terrainfix;
    }

    @Nullable
    public Integer getHeightoffset() {
        return heightoffset;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public ScatteredRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}
