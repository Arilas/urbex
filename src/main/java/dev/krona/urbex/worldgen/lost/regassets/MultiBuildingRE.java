package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public class MultiBuildingRE implements IAsset<MultiBuildingRE>, Extendable {

    public static final Codec<MultiBuildingRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.INT.fieldOf("dimx").forGetter(l -> l.dimX),
                    Codec.INT.fieldOf("dimz").forGetter(l -> l.dimZ),
                    // A grid, not an ordered list: appending rows would contradict dimx/dimz, so a
                    // declared grid replaces the inherited one wholesale.
                    Codec.list(Codec.list(Codec.STRING)).fieldOf("buildings").forGetter(l -> l.buildings)
            ).apply(instance, MultiBuildingRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final int dimX;
    private final int dimZ;
    private final List<List<String>> buildings;

    public MultiBuildingRE(Optional<Identifier> extendsId, int dimX, int dimZ, List<List<String>> buildings) {
        this.extendsId = extendsId;
        this.dimX = dimX;
        this.dimZ = dimZ;
        this.buildings = buildings;
    }

    public int getDimX() {
        return dimX;
    }

    public int getDimZ() {
        return dimZ;
    }

    public List<List<String>> getBuildings() {
        return buildings;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public MultiBuildingRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}
