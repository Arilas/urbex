package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.palette.CompiledEntry;
import dev.krona.urbex.format.palette.CompiledTrait;
import dev.krona.urbex.format.palette.Kind;
import dev.krona.urbex.format.palette.TraitSet;
import dev.krona.urbex.format.palette.traits.Light;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A version 2 {@code light_socket}, as version 1's light machinery wants it.
 *
 * <p>{@code MODEL.073} defers a socket's placement until the chunk is assembled and fixes the order
 * opportunities are tried in; {@link OptionalLightPlacer} is what does that, and it is written against
 * {@link LightPool}. So a compiled version 2 socket has to become a {@code LightPool} before anything
 * can place it. This class is that mapping and nothing else.</p>
 *
 * <h2>Why it is a mapping between two compiled forms, and why that is worth being nervous about</h2>
 *
 * <p>Both sides are already compiled: {@link CompiledEntry#placements()} holds one entry per placement
 * list with its slots materialised, and {@code LightPool} holds weighted candidates. Nothing here
 * resolves a block, reads a registry or reads a tag — {@code LOAD.042} would forbid it and there is
 * nothing left to resolve anyway. What it does is <em>re-derive weights from slot counts</em>, and that
 * is the step to distrust: {@code docs/format/README.md} §1 is entirely about a hand-written table that
 * drifted from the thing it described, and a second representation of "how likely is this candidate" is
 * exactly that shape. It is landed on its own, with its own tests, for that reason.</p>
 *
 * <h2>Weights come back out of the slots, and the slots are the truth</h2>
 *
 * <p>{@code WEIGHT.040} materialises a weighted list to 128 slots once, at compile time, so by the time
 * a socket reaches here its candidate weights have already been apportioned and rounded — three equal
 * alternatives are 43, 43 and 42 slots, not three thirds. Counting slots is therefore not an
 * approximation of the authored weights, it is the apportionment the format actually performs, and
 * feeding it back to {@code weightedOrder} reproduces the same distribution. Reading the authored
 * weights instead would be a second rounding of an already-rounded number.</p>
 *
 * <p>Identical alternatives share one {@link CompiledEntry.Resolved} object — the per-alternative memo
 * in {@code CompiledV2Palette} guarantees it — so grouping is by identity and first appearance, which
 * keeps the candidate order the file's own. {@code weightedOrder} rotates from the winner, so the order
 * is observable and must not come from a hash.</p>
 */
final class V2Sockets {

    private V2Sockets() {
    }

    /**
     * The pool a compiled socket amounts to. Never {@code null}, and that is a difference from version
     * 1 worth stating.
     *
     * <p>{@link LightPool#compile} returns {@code null} for a socket every one of whose candidates named
     * a block this installation does not have, and its caller turns that into air — because version 1
     * discovered the case while compiling a palette and issue #91 says not to refuse a world over an
     * absent cross-mod block. Version 2 cannot reach here in that state at all: {@code WEIGHT.030}
     * drops the absent candidates and {@code DIAG.043} then refuses a marker that lost <em>every</em>
     * alternative, at load, by name. That is {@code LOAD.010} doing exactly what it says — "Every
     * {@code REJECT} rule in this specification is enforced at load. None is deferred to generation" —
     * so by the time a compiled socket exists, at least one candidate survives somewhere in it.</p>
     *
     * <p>A single placement list may still be empty; only all four at once is impossible.</p>
     */
    static LightPool poolOf(CompiledEntry socket) {
        Map<LightPool.Placement, List<LightPool.Candidate>> candidates =
                new EnumMap<>(LightPool.Placement.class);
        boolean any = false;
        for (Map.Entry<Kind.Placement, CompiledEntry> list : socket.placements().entrySet()) {
            List<LightPool.Candidate> group = candidatesOf(list.getValue());
            if (!group.isEmpty()) {
                any = true;
            }
            candidates.put(placement(list.getKey()), group);
        }
        if (!any) {
            // Unreachable through compilation, and an exception rather than a null so that it stays
            // unreachable: a socket with no candidates anywhere would place nothing and report
            // nothing, which is the silence DIAG.043 exists to have already removed.
            throw new IllegalStateException("a compiled light_socket has no candidate in any "
                    + "placement list, which DIAG.043 refuses at load");
        }
        return LightPool.of(candidates);
    }

    /**
     * One placement list's candidates: each distinct alternative once, weighted by how many slots it
     * holds, in the order the slots first present them.
     */
    private static List<LightPool.Candidate> candidatesOf(CompiledEntry list) {
        // Insertion-ordered and keyed by the shared Resolved object. A HashMap would work and would
        // shuffle the candidate order between runs, which weightedOrder's rotation makes visible in
        // the world.
        Map<CompiledEntry.Resolved, int[]> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < list.slotCount(); slot++) {
            counts.computeIfAbsent(list.slot(slot), resolved -> new int[1])[0]++;
        }
        List<LightPool.Candidate> candidates = new ArrayList<>(counts.size());
        counts.forEach((resolved, count) -> candidates.add(new LightPool.Candidate(
                count[0], resolved.state(), unlitOf(resolved.traits()))));
        return List.copyOf(candidates);
    }

    /**
     * {@code TRAIT.055}: the {@code urbex:light.unlit} that applies to this candidate, or {@code null}
     * when nothing in the socket named one.
     *
     * <p><b>TRAIT.055's precedence is already done by the time this runs, and that is the point.</b>
     * The rule says "a candidate's own {@code urbex:light.unlit} takes precedence over the socket's",
     * and {@code TRAIT.005} plus {@code TRAIT.006} deliver exactly that at stage 3: a candidate is an
     * alternative, so it inherits the socket's traits, and a candidate declaring its own
     * {@code urbex:light} replaces the inherited one whole. So a candidate that "falls back to the
     * socket's" arrives here already carrying the socket's, by inheritance, and this method reads one
     * trait rather than choosing between two.</p>
     *
     * <p>That makes {@link LightPool.Candidate#unlit()}'s null — "the candidate names none, so the
     * source's own replacement is used" — a version 1 mechanism with nothing left to do here. It is
     * still returned, for the one case inheritance cannot cover: a socket that declares no
     * {@code urbex:light} at all, whose candidates therefore inherit nothing and whose replacement is
     * the source-level one the caller supplies.</p>
     *
     * <p>A candidate that carries {@code urbex:light} with no {@code unlit} written gets air, because
     * {@code TRAIT.051} defaults it at decode — deliberately air, and not the socket's.</p>
     */
    @Nullable
    private static BlockState unlitOf(TraitSet traits) {
        CompiledTrait light = traits.traits().get(Light.TYPE.id());
        if (light == null) {
            return null;
        }
        CompiledEntry unlit = light.satellite(Light.UNLIT);
        // A representative rather than a per-position draw: LightPool.Candidate holds one state, and a
        // socket's replacement is written by the placer at a position it chose, not at a position the
        // palette addressed. A weighted unlit therefore contributes its first alternative here; the
        // per-position form is TRAIT.050's in-place light, which keeps its BlockChoice.
        return unlit == null || unlit.slotCount() == 0 ? null : unlit.slot(0).state();
    }

    /** The two {@code Placement} enums are the same four names in the same order, by MODEL.071. */
    private static LightPool.Placement placement(Kind.Placement placement) {
        return switch (placement) {
            case FLOOR -> LightPool.Placement.FLOOR;
            case WALL -> LightPool.Placement.WALL;
            case CEILING -> LightPool.Placement.CEILING;
            case FREE -> LightPool.Placement.FREE;
        };
    }
}
