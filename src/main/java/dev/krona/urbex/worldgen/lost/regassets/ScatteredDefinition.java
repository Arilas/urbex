package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
public class ScatteredDefinition implements Extendable {

    /**
     * {@code rotatable} was decoded and then thrown away: {@link ScatteredBuilding} never copied it
     * off the chain and nothing read it, so an author who wrote it got a scattered building that
     * generated exactly as if they had not. It is rejected rather than deleted from the codec
     * because deleting it would restore the same silence from the other side - {@code
     * RecordCodecBuilder} ignores keys it does not know.
     * <p>
     * There is nothing to rotate against yet: {@code Scattered.generate} passes
     * {@code Transform.ROTATE_NONE} for every part, and a multi-chunk scattered structure (the
     * bundled {@code oilrig} is one) would need its whole grid rotated coherently across chunks
     * before the flag could mean anything.
     */
    private static final Codec<Boolean> UNSUPPORTED_ROTATABLE = Codec.BOOL.validate(
            value -> DataResult.error(() -> "This scattered building declares 'rotatable', which "
                    + "Urbex does not implement: a scattered building always generates unrotated. "
                    + "The key used to be parsed and silently ignored; remove it."));

    private static final Codec<ScatteredDefinition> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(Codec.STRING).optionalFieldOf("buildings").forGetter(l -> Optional.ofNullable(l.buildings)),
                    Codec.STRING.optionalFieldOf("multibuilding").forGetter(l -> Optional.ofNullable(l.multibuilding)),
                    UNSUPPORTED_ROTATABLE.optionalFieldOf("rotatable").forGetter(l -> Optional.<Boolean>empty()),
                    StringRepresentable.fromEnum(ScatteredBuilding.TerrainHeight::values).optionalFieldOf("terrainheight").forGetter(l -> Optional.ofNullable(l.terrainheight)),
                    StringRepresentable.fromEnum(ScatteredBuilding.TerrainFix::values).optionalFieldOf("terrainfix").forGetter(l -> Optional.ofNullable(l.terrainfix)),
                    Codec.INT.optionalFieldOf("heightoffset").forGetter(l -> Optional.ofNullable(l.heightoffset))
            ).apply(instance, ScatteredDefinition::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<ScatteredDefinition> CODEC = RetiredKeys.reject(RAW, "scattered building");

    private final Optional<Identifier> extendsId;
    private final ScatteredBuilding.TerrainHeight terrainheight;
    private final ScatteredBuilding.TerrainFix terrainfix;
    private final Integer heightoffset;
    private final Mergeable<String> buildings;
    private final String multibuilding;

    public ScatteredDefinition(Optional<Identifier> extendsId,
                       Optional<Mergeable<String>> buildings, Optional<String> multibuilding,
                       // Always empty: UNSUPPORTED_ROTATABLE fails the decode if the key is there
                       // at all, so this parameter exists only to keep the group's arity.
                       Optional<Boolean> rotatable,
                       Optional<ScatteredBuilding.TerrainHeight> terrainheight,
                       Optional<ScatteredBuilding.TerrainFix> terrainfix,
                       Optional<Integer> heightoffset) {
        this.extendsId = extendsId;
        this.buildings = buildings.orElse(null);
        this.multibuilding = multibuilding.map(String::intern).orElse(null);
        this.terrainheight = terrainheight.orElse(null);
        this.terrainfix = terrainfix.orElse(null);
        this.heightoffset = heightoffset.orElse(null);
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


}
