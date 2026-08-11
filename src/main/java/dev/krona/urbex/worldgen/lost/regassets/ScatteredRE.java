package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.cityassets.ScatteredBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A building scattered outside the cities.
 * <p>
 * {@code terrainheight} and {@code terrainfix} are optional here rather than required, because a
 * variant that only swaps its building list should not have to restate how it sits on the terrain.
 * Requiredness is checked after the chain is resolved, in {@link ScatteredBuilding}: those two
 * scalars individually, and {@code buildings}/{@code multibuilding} as a pair, of which the
 * resolved chain must leave at least one - neither is required on its own, so neither can be
 * required here. Declaring both is allowed; {@code Scattered.generate} takes the multibuilding.
 */
public class ScatteredRE implements IAsset<ScatteredRE>, Extendable {

    private static final Codec<ScatteredRE> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(Codec.STRING).optionalFieldOf("buildings").forGetter(l -> Optional.ofNullable(l.buildings)),
                    Codec.STRING.optionalFieldOf("multibuilding").forGetter(l -> Optional.ofNullable(l.multibuilding)),
                    Codec.BOOL.optionalFieldOf("rotatable").forGetter(l -> Optional.ofNullable(l.rotatable)),
                    StringRepresentable.fromEnum(ScatteredBuilding.TerrainHeight::values).optionalFieldOf("terrainheight").forGetter(l -> Optional.ofNullable(l.terrainheight)),
                    StringRepresentable.fromEnum(ScatteredBuilding.TerrainFix::values).optionalFieldOf("terrainfix").forGetter(l -> Optional.ofNullable(l.terrainfix)),
                    Codec.INT.optionalFieldOf("heightoffset").forGetter(l -> Optional.ofNullable(l.heightoffset))
            ).apply(instance, ScatteredRE::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<ScatteredRE> CODEC = RetiredKeys.reject(RAW, "scattered building");

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
                       Optional<ScatteredBuilding.TerrainHeight> terrainheight,
                       Optional<ScatteredBuilding.TerrainFix> terrainfix,
                       Optional<Integer> heightoffset) {
        this.extendsId = extendsId;
        this.buildings = buildings.orElse(null);
        this.multibuilding = multibuilding.map(String::intern).orElse(null);
        this.rotatable = rotatable.orElse(null);
        this.terrainheight = terrainheight.orElse(null);
        this.terrainfix = terrainfix.orElse(null);
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

    @Nullable
    public ScatteredBuilding.TerrainHeight getTerrainheight() {
        return terrainheight;
    }

    @Nullable
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
