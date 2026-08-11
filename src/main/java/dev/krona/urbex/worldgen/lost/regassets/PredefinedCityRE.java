package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class PredefinedCityRE implements IAsset<PredefinedCityRE>, Extendable {

    public static final Codec<PredefinedCityRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.STRING.fieldOf("dimension").forGetter(l -> l.dimension),
                    Codec.INT.fieldOf("chunkx").forGetter(l -> l.chunkX),
                    Codec.INT.fieldOf("chunkz").forGetter(l -> l.chunkZ),
                    Codec.INT.fieldOf("radius").forGetter(l -> l.radius),
                    Codec.STRING.fieldOf("citystyle").forGetter(l -> l.cityStyle),
                    Mergeable.codec(PredefinedBuilding.CODEC).optionalFieldOf("buildings").forGetter(l -> Optional.ofNullable(l.predefinedBuildings)),
                    Mergeable.codec(PredefinedStreet.CODEC).optionalFieldOf("streets").forGetter(l -> Optional.ofNullable(l.predefinedStreets))
            ).apply(instance, PredefinedCityRE::new));

    private Identifier name;

    private final Optional<Identifier> extendsId;
    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int radius;
    private final String cityStyle;
    private final Mergeable<PredefinedBuilding> predefinedBuildings;
    private final Mergeable<PredefinedStreet> predefinedStreets;

    public PredefinedCityRE(
            Optional<Identifier> extendsId,
            String dimension,
            int chunkX, int chunkZ, int radius,
            String cityStyle,
            Optional<Mergeable<PredefinedBuilding>> predefinedBuildings,
            Optional<Mergeable<PredefinedStreet>> predefinedStreets) {
        this.extendsId = extendsId;
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.radius = radius;
        this.cityStyle = cityStyle;
        this.predefinedBuildings = predefinedBuildings.orElse(null);
        this.predefinedStreets = predefinedStreets.orElse(null);
    }

    public String getDimension() {
        return dimension;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int getRadius() {
        return radius;
    }

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
