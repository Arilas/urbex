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
 * The highway part wiring of a world style, resolved. See {@link StreetParts} for the accumulate-
 * then-require shape these three wiring records share.
 */
public record HighwayParts(List<String> tunnel, List<String> open, List<String> bridge, List<String> tunnelBi,
                           List<String> openBi, List<String> bridgeBi) {

    /** The empty accumulator: no component declared yet. Holds no asset names, only nulls. */
    private static final HighwayParts NOTHING_DECLARED =
            new HighwayParts(null, null, null, null, null, null);

    /** One file's {@code parts.highways} block, exactly as written. */
    public record Decl(Optional<Mergeable<String>> tunnel, Optional<Mergeable<String>> open,
                       Optional<Mergeable<String>> bridge, Optional<Mergeable<String>> tunnelBi,
                       Optional<Mergeable<String>> openBi, Optional<Mergeable<String>> bridgeBi) {

        public static final Codec<Decl> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Tools.listOrStringList("tunnel", Decl::tunnel),
                Tools.listOrStringList("open", Decl::open),
                Tools.listOrStringList("bridge", Decl::bridge),
                Tools.listOrStringList("tunnel_bi", Decl::tunnelBi),
                Tools.listOrStringList("open_bi", Decl::openBi),
                Tools.listOrStringList("bridge_bi", Decl::bridgeBi))
                .apply(instance, Decl::new)
        );
    }

    /** @param base what earlier entries in the chain built up, or null if none declared highways */
    public static HighwayParts merge(@Nullable HighwayParts base, Decl incoming) {
        HighwayParts b = base == null ? NOTHING_DECLARED : base;
        return new HighwayParts(
                Mergeable.fold(b.tunnel(), incoming.tunnel()),
                Mergeable.fold(b.open(), incoming.open()),
                Mergeable.fold(b.bridge(), incoming.bridge()),
                Mergeable.fold(b.tunnelBi(), incoming.tunnelBi()),
                Mergeable.fold(b.openBi(), incoming.openBi()),
                Mergeable.fold(b.bridgeBi(), incoming.bridgeBi()));
    }

    /** Fails naming the asset and the JSON path of the first component nothing in the chain declared. */
    public HighwayParts requireComplete(Identifier owner, String field) {
        Resolved.require(tunnel, owner, field + ".tunnel");
        Resolved.require(open, owner, field + ".open");
        Resolved.require(bridge, owner, field + ".bridge");
        Resolved.require(tunnelBi, owner, field + ".tunnel_bi");
        Resolved.require(openBi, owner, field + ".open_bi");
        Resolved.require(bridgeBi, owner, field + ".bridge_bi");
        return this;
    }

    public List<String> tunnel(boolean bi) {
        return bi ? tunnelBi : tunnel;
    }

    public List<String> open(boolean bi) {
        return bi ? openBi : open;
    }

    public List<String> bridge(boolean bi) {
        return bi ? bridgeBi : bridge;
    }
}
