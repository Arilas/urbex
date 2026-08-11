package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.cityassets.Resolved;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * The railway part wiring of a world style, resolved. See {@link StreetParts} for the accumulate-
 * then-require shape these three wiring records share.
 */
public record RailwayParts(List<String> stationUnderground, List<String> stationOpen, List<String> stationOpenRoof,
                           List<String> stationUndergroundStairs, List<String> stationStaircase, List<String> stationStaircaseSurface,
                           List<String> railsHorizontal, List<String> railsHorizontalEnd, List<String> railsHorizontalWater,
                           List<String> railsVertical, List<String> railsVerticalWater,
                           List<String> rails3Split, List<String> railsBend, List<String> railsFlat,
                           List<String> railsDown1, List<String> railsDown2) {

    /** The empty accumulator: no component declared yet. Holds no asset names, only nulls. */
    private static final RailwayParts NOTHING_DECLARED = new RailwayParts(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    /** One file's {@code parts.railways} block, exactly as written. */
    public record Decl(Optional<Mergeable<String>> stationUnderground, Optional<Mergeable<String>> stationOpen,
                       Optional<Mergeable<String>> stationOpenRoof, Optional<Mergeable<String>> stationUndergroundStairs,
                       Optional<Mergeable<String>> stationStaircase, Optional<Mergeable<String>> stationStaircaseSurface,
                       Optional<Mergeable<String>> railsHorizontal, Optional<Mergeable<String>> railsHorizontalEnd,
                       Optional<Mergeable<String>> railsHorizontalWater, Optional<Mergeable<String>> railsVertical,
                       Optional<Mergeable<String>> railsVerticalWater, Optional<Mergeable<String>> rails3Split,
                       Optional<Mergeable<String>> railsBend, Optional<Mergeable<String>> railsFlat,
                       Optional<Mergeable<String>> railsDown1, Optional<Mergeable<String>> railsDown2) {

        public static final Codec<Decl> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Tools.listOrStringList("stationunderground", Decl::stationUnderground),
                Tools.listOrStringList("stationopen", Decl::stationOpen),
                Tools.listOrStringList("stationopenroof", Decl::stationOpenRoof),
                Tools.listOrStringList("stationundergroundstairs", Decl::stationUndergroundStairs),
                Tools.listOrStringList("stationstaircase", Decl::stationStaircase),
                Tools.listOrStringList("stationstaircasesurface", Decl::stationStaircaseSurface),
                Tools.listOrStringList("railshorizontal", Decl::railsHorizontal),
                Tools.listOrStringList("railshorizontalend", Decl::railsHorizontalEnd),
                Tools.listOrStringList("railshorizontalwater", Decl::railsHorizontalWater),
                Tools.listOrStringList("railsvertical", Decl::railsVertical),
                Tools.listOrStringList("railsverticalwater", Decl::railsVerticalWater),
                Tools.listOrStringList("rails3split", Decl::rails3Split),
                Tools.listOrStringList("railsbend", Decl::railsBend),
                Tools.listOrStringList("railsflat", Decl::railsFlat),
                Tools.listOrStringList("railsdown1", Decl::railsDown1),
                Tools.listOrStringList("railsdown2", Decl::railsDown2))
                .apply(instance, Decl::new)
        );
    }

    /** @param base what earlier entries in the chain built up, or null if none declared railways */
    public static RailwayParts merge(@Nullable RailwayParts base, Decl incoming) {
        RailwayParts b = base == null ? NOTHING_DECLARED : base;
        return new RailwayParts(
                Mergeable.fold(b.stationUnderground(), incoming.stationUnderground()),
                Mergeable.fold(b.stationOpen(), incoming.stationOpen()),
                Mergeable.fold(b.stationOpenRoof(), incoming.stationOpenRoof()),
                Mergeable.fold(b.stationUndergroundStairs(), incoming.stationUndergroundStairs()),
                Mergeable.fold(b.stationStaircase(), incoming.stationStaircase()),
                Mergeable.fold(b.stationStaircaseSurface(), incoming.stationStaircaseSurface()),
                Mergeable.fold(b.railsHorizontal(), incoming.railsHorizontal()),
                Mergeable.fold(b.railsHorizontalEnd(), incoming.railsHorizontalEnd()),
                Mergeable.fold(b.railsHorizontalWater(), incoming.railsHorizontalWater()),
                Mergeable.fold(b.railsVertical(), incoming.railsVertical()),
                Mergeable.fold(b.railsVerticalWater(), incoming.railsVerticalWater()),
                Mergeable.fold(b.rails3Split(), incoming.rails3Split()),
                Mergeable.fold(b.railsBend(), incoming.railsBend()),
                Mergeable.fold(b.railsFlat(), incoming.railsFlat()),
                Mergeable.fold(b.railsDown1(), incoming.railsDown1()),
                Mergeable.fold(b.railsDown2(), incoming.railsDown2()));
    }

    /** Fails naming the asset and the JSON path of the first component nothing in the chain declared. */
    public RailwayParts requireComplete(Identifier owner, String field) {
        Resolved.require(stationUnderground, owner, field + ".stationunderground");
        Resolved.require(stationOpen, owner, field + ".stationopen");
        Resolved.require(stationOpenRoof, owner, field + ".stationopenroof");
        Resolved.require(stationUndergroundStairs, owner, field + ".stationundergroundstairs");
        Resolved.require(stationStaircase, owner, field + ".stationstaircase");
        Resolved.require(stationStaircaseSurface, owner, field + ".stationstaircasesurface");
        Resolved.require(railsHorizontal, owner, field + ".railshorizontal");
        Resolved.require(railsHorizontalEnd, owner, field + ".railshorizontalend");
        Resolved.require(railsHorizontalWater, owner, field + ".railshorizontalwater");
        Resolved.require(railsVertical, owner, field + ".railsvertical");
        Resolved.require(railsVerticalWater, owner, field + ".railsverticalwater");
        Resolved.require(rails3Split, owner, field + ".rails3split");
        Resolved.require(railsBend, owner, field + ".railsbend");
        Resolved.require(railsFlat, owner, field + ".railsflat");
        Resolved.require(railsDown1, owner, field + ".railsdown1");
        Resolved.require(railsDown2, owner, field + ".railsdown2");
        return this;
    }
}
