package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A grid of buildings occupying several chunks.
 * <p>
 * Every field is optional here rather than required, because requiredness is checked after the
 * {@code extends} chain is resolved, in {@link dev.krona.urbex.worldgen.lost.cityassets.MultiBuilding}.
 * Until that change this registry's every field was required, which made {@code extends} on it
 * purely decorative: a child had to restate the whole grid to decode at all.
 */
public class MultiBuildingRE implements IAsset<MultiBuildingRE>, Extendable {

    public static final Codec<MultiBuildingRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.INT.optionalFieldOf("dimx").forGetter(l -> Optional.ofNullable(l.dimX)),
                    Codec.INT.optionalFieldOf("dimz").forGetter(l -> Optional.ofNullable(l.dimZ)),
                    // A grid, not an ordered list: appending rows would contradict dimx/dimz, so a
                    // declared grid replaces the inherited one wholesale, and an absent one inherits.
                    Codec.list(Codec.list(Codec.STRING)).optionalFieldOf("buildings").forGetter(l -> Optional.ofNullable(l.buildings))
            ).apply(instance, MultiBuildingRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final Integer dimX;                 // null when this entry declares none and takes its ancestor's
    private final Integer dimZ;                 // null when this entry declares none and takes its ancestor's
    private final List<List<String>> buildings; // null when this entry declares none and takes its ancestor's

    public MultiBuildingRE(Optional<Identifier> extendsId, Optional<Integer> dimX, Optional<Integer> dimZ,
                           Optional<List<List<String>>> buildings) {
        this.extendsId = extendsId;
        this.dimX = dimX.orElse(null);
        this.dimZ = dimZ.orElse(null);
        this.buildings = buildings.orElse(null);
    }

    @Nullable
    public Integer getDimX() {
        return dimX;
    }

    @Nullable
    public Integer getDimZ() {
        return dimZ;
    }

    @Nullable
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
