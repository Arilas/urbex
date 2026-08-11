package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A city placed at a fixed spot rather than by the road field.
 * <p>
 * Every scalar is optional here rather than required, because requiredness is checked after the
 * {@code extends} chain is resolved, in
 * {@link dev.krona.urbex.worldgen.lost.cityassets.PredefinedCity} - so a sibling city can be
 * "the same city, elsewhere" by declaring nothing but {@code extends} and its two chunk coordinates.
 */
public class PredefinedCityRE implements IAsset<PredefinedCityRE>, Extendable {

    public static final Codec<PredefinedCityRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.STRING.optionalFieldOf("dimension").forGetter(l -> Optional.ofNullable(l.dimension)),
                    Codec.INT.optionalFieldOf("chunkx").forGetter(l -> Optional.ofNullable(l.chunkX)),
                    Codec.INT.optionalFieldOf("chunkz").forGetter(l -> Optional.ofNullable(l.chunkZ)),
                    Codec.INT.optionalFieldOf("radius").forGetter(l -> Optional.ofNullable(l.radius)),
                    Codec.STRING.optionalFieldOf("citystyle").forGetter(l -> Optional.ofNullable(l.cityStyle)),
                    Mergeable.codec(PredefinedBuilding.CODEC).optionalFieldOf("buildings").forGetter(l -> Optional.ofNullable(l.predefinedBuildings)),
                    Mergeable.codec(PredefinedStreet.CODEC).optionalFieldOf("streets").forGetter(l -> Optional.ofNullable(l.predefinedStreets))
            ).apply(instance, PredefinedCityRE::new));

    private Identifier name;

    private final Optional<Identifier> extendsId;
    // Null on any of these means "not declared here", so the chain reads it from an ancestor.
    private final String dimension;
    private final Integer chunkX;
    private final Integer chunkZ;
    private final Integer radius;
    private final String cityStyle;
    private final Mergeable<PredefinedBuilding> predefinedBuildings;
    private final Mergeable<PredefinedStreet> predefinedStreets;

    public PredefinedCityRE(
            Optional<Identifier> extendsId,
            Optional<String> dimension,
            Optional<Integer> chunkX, Optional<Integer> chunkZ, Optional<Integer> radius,
            Optional<String> cityStyle,
            Optional<Mergeable<PredefinedBuilding>> predefinedBuildings,
            Optional<Mergeable<PredefinedStreet>> predefinedStreets) {
        this.extendsId = extendsId;
        this.dimension = dimension.orElse(null);
        this.chunkX = chunkX.orElse(null);
        this.chunkZ = chunkZ.orElse(null);
        this.radius = radius.orElse(null);
        this.cityStyle = cityStyle.orElse(null);
        this.predefinedBuildings = predefinedBuildings.orElse(null);
        this.predefinedStreets = predefinedStreets.orElse(null);
    }

    @Nullable
    public String getDimension() {
        return dimension;
    }

    @Nullable
    public Integer getChunkX() {
        return chunkX;
    }

    @Nullable
    public Integer getChunkZ() {
        return chunkZ;
    }

    @Nullable
    public Integer getRadius() {
        return radius;
    }

    @Nullable
    public String getCityStyle() {
        return cityStyle;
    }

    @Nullable
    public Mergeable<PredefinedBuilding> getPredefinedBuildings() {
        return predefinedBuildings;
    }

    @Nullable
    public Mergeable<PredefinedStreet> getPredefinedStreets() {
        return predefinedStreets;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public PredefinedCityRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}
