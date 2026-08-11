package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.cityassets.Resolved;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * A world style's {@code parts} block, resolved: the highway and railway wiring.
 * <p>
 * Both groups are required of the chain as a whole rather than of each file, the same way
 * {@code outsidestyle} is - see {@link dev.krona.urbex.worldgen.lost.cityassets.WorldStyle}, which
 * is where the requirement is raised.
 */
public record PartSelector(HighwayParts highwayParts, RailwayParts railwayParts) {

    /** One file's {@code parts} block, exactly as written: either group may be absent. */
    public record Decl(Optional<HighwayParts.Decl> highways, Optional<RailwayParts.Decl> railways) {

        public static final Codec<Decl> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                HighwayParts.Decl.CODEC.optionalFieldOf("highways").forGetter(Decl::highways),
                RailwayParts.Decl.CODEC.optionalFieldOf("railways").forGetter(Decl::railways)
        ).apply(instance, Decl::new));
    }

    /** @param base what earlier entries in the chain built up, or null if none declared {@code parts} */
    public static PartSelector merge(@Nullable PartSelector base, Decl incoming) {
        HighwayParts highways = base == null ? null : base.highwayParts();
        RailwayParts railways = base == null ? null : base.railwayParts();
        return new PartSelector(
                incoming.highways().map(d -> HighwayParts.merge(highways, d)).orElse(highways),
                incoming.railways().map(d -> RailwayParts.merge(railways, d)).orElse(railways));
    }

    /**
     * Fails naming the asset and the JSON path of the first wiring field nothing in the chain
     * declared.
     */
    public PartSelector requireComplete(Identifier owner) {
        Resolved.require(highwayParts, owner, "parts.highways").requireComplete(owner, "parts.highways");
        Resolved.require(railwayParts, owner, "parts.railways").requireComplete(owner, "parts.railways");
        return this;
    }
}
