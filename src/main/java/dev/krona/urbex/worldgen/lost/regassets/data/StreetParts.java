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
 * One street part family, resolved: the parts to place for each junction shape a street chunk can
 * take.
 * <p>
 * A component is null until some entry in the {@code extends} chain declares it, and
 * {@link #requireComplete} turns a component still null at the end of the chain into a load error.
 * Nothing in generation ever sees a null one, so the components are read as plain lists.
 */
public record StreetParts(List<String> straight, List<String> end, List<String> bend,
                          List<String> t, List<String> none, List<String> all, List<String> connector,
                          List<String> stair) {

    /** The empty accumulator: no component declared yet. Holds no asset names, only nulls. */
    private static final StreetParts NOTHING_DECLARED =
            new StreetParts(null, null, null, null, null, null, null, null);

    /**
     * One file's {@code parts} block, exactly as written: every component optional, because a child
     * that adds one street variant must not have to restate the other seven.
     */
    public record Decl(Optional<Mergeable<String>> straight, Optional<Mergeable<String>> end,
                       Optional<Mergeable<String>> bend, Optional<Mergeable<String>> t,
                       Optional<Mergeable<String>> none, Optional<Mergeable<String>> all,
                       Optional<Mergeable<String>> connector, Optional<Mergeable<String>> stair) {

        public static final Codec<Decl> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Tools.listOrStringList("straight", Decl::straight),
                Tools.listOrStringList("end", Decl::end),
                Tools.listOrStringList("bend", Decl::bend),
                Tools.listOrStringList("t", Decl::t),
                Tools.listOrStringList("none", Decl::none),
                Tools.listOrStringList("all", Decl::all),
                Tools.listOrStringList("connector", Decl::connector),
                Tools.listOrStringList("stair", Decl::stair))
                .apply(instance, Decl::new)
        );
    }

    /**
     * Folds one chain entry's declaration onto what the chain has accumulated, component by
     * component: a component the entry does not mention keeps the inherited value, a bare array
     * replaces it, and {@code {"replace": false, ...}} appends to it.
     * <p>
     * Per component rather than per block, because the block used to be swapped whole - which could
     * express "these are my street parts" but not "append two variants to the ones I inherit".
     *
     * @param base what earlier entries in the chain built up, or null if none declared this family
     */
    public static StreetParts merge(@Nullable StreetParts base, Decl incoming) {
        StreetParts b = base == null ? NOTHING_DECLARED : base;
        return new StreetParts(
                Mergeable.fold(b.straight(), incoming.straight()),
                Mergeable.fold(b.end(), incoming.end()),
                Mergeable.fold(b.bend(), incoming.bend()),
                Mergeable.fold(b.t(), incoming.t()),
                Mergeable.fold(b.none(), incoming.none()),
                Mergeable.fold(b.all(), incoming.all()),
                Mergeable.fold(b.connector(), incoming.connector()),
                Mergeable.fold(b.stair(), incoming.stair()));
    }

    /**
     * Fails naming the asset and the JSON path of the first component nothing in the chain declared.
     *
     * @param field the block's key path, e.g. {@code "streetblocks.largeparts"}
     */
    public StreetParts requireComplete(Identifier owner, String field) {
        Resolved.require(straight, owner, field + ".straight");
        Resolved.require(end, owner, field + ".end");
        Resolved.require(bend, owner, field + ".bend");
        Resolved.require(t, owner, field + ".t");
        Resolved.require(none, owner, field + ".none");
        Resolved.require(all, owner, field + ".all");
        Resolved.require(connector, owner, field + ".connector");
        Resolved.require(stair, owner, field + ".stair");
        return this;
    }
}
