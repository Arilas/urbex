package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.palette.Apportion;
import dev.krona.urbex.format.palette.Fraction;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSourceSettings;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LightPool {

    public enum Placement { FLOOR, WALL, CEILING, FREE }

    /**
     * One compiled candidate: the lit state, and the state that stands in its place when this
     * socket's light is off. {@code unlit} is null when the candidate names none, in which case the
     * source's own replacement is used.
     */
    public record Candidate(int weight, BlockState state, @Nullable BlockState unlit) { }

    private final Map<Placement, List<Candidate>> candidates;
    /**
     * {@code WEIGHT.043}: each placement list materialised to {@link CompiledPalette#SLOTS} slots, each
     * slot holding the index of the candidate that owns it.
     *
     * <p>Built once, here, for the reason {@code WEIGHT.040} materialises a weighted node once at
     * compile time: placement runs per marker in a chunk, and apportioning a list there would put an
     * allocation and a division loop in a path that only has to index an array.</p>
     */
    private final Map<Placement, int[]> slots;
    private final List<Candidate> allCandidates;

    private LightPool(Map<Placement, List<Candidate>> candidates) {
        EnumMap<Placement, List<Candidate>> immutableCandidates = new EnumMap<>(Placement.class);
        EnumMap<Placement, int[]> immutableSlots = new EnumMap<>(Placement.class);
        List<Candidate> flattened = new ArrayList<>();
        for (Placement placement : Placement.values()) {
            List<Candidate> group = List.copyOf(candidates.getOrDefault(placement, List.of()));
            immutableCandidates.put(placement, group);
            immutableSlots.put(placement, slotsOf(group));
            flattened.addAll(group);
        }
        this.candidates = Map.copyOf(immutableCandidates);
        this.slots = Map.copyOf(immutableSlots);
        this.allCandidates = List.copyOf(flattened);
    }

    /**
     * One placement list's candidates, expanded to one slot index per slot.
     *
     * <p>Through {@link Apportion#slots}, which is the function a {@code weighted} node's own choices go
     * through — {@code WEIGHT.060} to {@code WEIGHT.062}, largest remainder with ties to declaration
     * order and every alternative guaranteed a slot. {@code WEIGHT.043} says "selected by the same
     * rules", and this is what the same rules are; a second apportionment beside it is the drift
     * {@code docs/format/README.md} §1 is entirely about.</p>
     *
     * <p><b>It is the identity on a version 2 pool, which is what makes conversion lossless.</b> A
     * version 2 socket arrives with its placement list already apportioned to 128 slots and
     * {@code V2Sockets} counting them back into weights, so those shares are exact 128ths and come back
     * unchanged. A version 1 pool arrives with the weights the file wrote, and reaches the same numbers:
     * the bundled pack's {@code 6, 3, 1} floor list becomes {@code 77, 38, 13} either way, and its
     * {@code 8, 2} wall list {@code 102, 26}. The two formats therefore place the same light at the same
     * position, which is the whole of {@code VER.021} for a socket and was false before this.</p>
     *
     * <p><b>Why not {@code CompiledPalette.distributeSlots}, which this used first.</b> That function
     * reads a version 1 weight as an absolute slot count and <em>clips</em> a list totalling more than
     * 128, so {@code [1000, 1]} became {@code [128, 0]} and the second candidate could never be placed.
     * A version 1 socket weight was never a slot count — it was a ticket share, and {@code [1000, 1]}
     * placed its second candidate one time in a thousand. Clipping silently deleted it, and the guard
     * added to notice that refused the world instead, which is
     * {@link dev.krona.urbex.format.palette.V1ToV2 VER.004}'s "version 1 does not become stricter"
     * broken retroactively on packs that load today. {@code Apportion} gives it one slot of 128 by
     * {@code WEIGHT.062} — rarer than it was, because a socket has 128 slots and not 1001, and present,
     * which is the property that matters.</p>
     */
    private static int[] slotsOf(List<Candidate> group) {
        if (group.isEmpty()) {
            return new int[0];
        }
        int[] perCandidate = apportion(group);
        int[] owner = new int[CompiledPalette.SLOTS];
        int slot = 0;
        for (int i = 0; i < perCandidate.length; i++) {
            for (int taken = 0; taken < perCandidate[i]; taken++) {
                owner[slot++] = i;
            }
        }
        // Only reachable through the WEIGHT.062-less branch below, where the slots past the budget
        // belong to nobody. Candidate 0 is the file's first, which is what a socket falls back to
        // everywhere else it has to name one (LightPool.representative).
        while (slot < owner.length) {
            owner[slot++] = 0;
        }
        return owner;
    }

    /**
     * How many of the 128 slots each candidate takes.
     *
     * <p>The long branch is {@code WEIGHT.063}'s condition arriving in a registry that has no rule
     * against it. {@code Apportion.slots} has a precondition of at most one share per slot, because past
     * that {@code WEIGHT.062} cannot be satisfied — version 2 refuses such a node at load with
     * {@code DIAG.044}, and version 1 has no such rule and must not grow one ({@code VER.004}). So a
     * placement list of more than 128 candidates is apportioned without the every-candidate-gets-one
     * guarantee rather than refused: the ones past the budget get no slot, which is what a ticket share
     * below 1/128 amounted to anyway. The largest authored list in the measured corpus holds four.</p>
     */
    private static int[] apportion(List<Candidate> group) {
        long total = 0;
        for (Candidate candidate : group) {
            total += candidate.weight();
        }
        List<Fraction> shares = new ArrayList<>(group.size());
        for (Candidate candidate : group) {
            shares.add(Fraction.of(candidate.weight(), total));
        }
        if (group.size() <= CompiledPalette.SLOTS) {
            return Apportion.slots(shares, CompiledPalette.SLOTS);
        }
        int[] slots = new int[group.size()];
        int assigned = 0;
        for (int i = 0; i < group.size(); i++) {
            slots[i] = (int) (group.get(i).weight() * (long) CompiledPalette.SLOTS / total);
            assigned += slots[i];
        }
        for (int i = 0; i < slots.length && assigned < CompiledPalette.SLOTS; i++) {
            slots[i]++;
            assigned++;
        }
        return slots;
    }

    /**
     * Compiles one marker's light pool, or returns {@code null} when every candidate it declared
     * names a block this game does not have.
     * <p>
     * Null and empty are different answers and only one of them is the author's fault. A pool that
     * declares no candidates anywhere is a mistake in the file and still refuses the world naming
     * it; a pool whose candidates all came from an uninstalled mod is issue #91's case, and refusing
     * the world over that is exactly what #91 says not to do. Before this, an absent block resolved
     * to air and was rejected two lines later for emitting no light - a load error that named the
     * right file and gave entirely the wrong reason.
     */
    @Nullable
    public static LightPool compile(HolderLookup<Block> blockLookup, Identifier paletteId, char marker,
                                    LightSourceSettings settings) {
        EnumMap<Placement, List<Candidate>> candidates = new EnumMap<>(Placement.class);
        boolean[] dropped = new boolean[1];
        compileGroup(blockLookup, paletteId, marker, Placement.FLOOR, settings.floor(), candidates, dropped);
        compileGroup(blockLookup, paletteId, marker, Placement.WALL, settings.wall(), candidates, dropped);
        compileGroup(blockLookup, paletteId, marker, Placement.CEILING, settings.ceiling(), candidates, dropped);
        compileGroup(blockLookup, paletteId, marker, Placement.FREE, settings.free(), candidates, dropped);
        if (candidates.values().stream().allMatch(List::isEmpty)) {
            if (dropped[0]) {
                return null;
            }
            throw new IllegalArgumentException("Invalid light pool in palette '" + paletteId + "', marker '" + marker
                    + "': expected at least one candidate in floor, wall, ceiling, or free");
        }
        return new LightPool(candidates);
    }

    /**
     * A pool from candidates that are already compiled, totalling their weights here.
     * <p>
     * {@link #compile} is version 1's entry point: it takes {@link LightSourceSettings}, resolves block
     * strings and keeps its own running totals. A version 2 socket arrives with its blocks already
     * resolved and its weights already apportioned, so it needs the constructor and not the compiler -
     * and the totals are derived rather than passed, because a total that disagrees with its candidates
     * is a bug {@link #weightedOrder} would express as a silently biased draw.
     */
    public static LightPool of(Map<Placement, List<Candidate>> candidates) {
        for (Placement placement : Placement.values()) {
            for (Candidate candidate : candidates.getOrDefault(placement, List.of())) {
                if (candidate.weight() <= 0) {
                    throw new IllegalArgumentException("a light pool candidate weighs "
                            + candidate.weight() + ", and a candidate with no slot can never be drawn");
                }
            }
        }
        return new LightPool(candidates);
    }

    /**
     * {@code WEIGHT.043}: this placement list's candidates, the one this position addresses first.
     *
     * <p><b>Addressed, not drawn.</b> This used to take a {@link RandomSource} and draw
     * {@code nextInt(total)} — a sequential ticket, so which light a marker got depended on how many
     * other markers the chunk had planned before it, and on how many placement opportunities had already
     * been tried and rejected at this one. {@code WEIGHT.043} says a placement list is "selected by the
     * same rules, addressed by the same position" as a weighted node, and {@code WEIGHT.042} says
     * selection "draws from no sequential stream". Both are now true of a socket, and neither was.</p>
     *
     * <p>The address is the marker's own block position under {@code Rng.Purpose.LIGHTING_VARIANT},
     * which is the address version 1 already seeded its stream from — so what changed is that the slot
     * is read there rather than a stream allocated there and a ticket drawn from it. The lit and unlit
     * passes therefore still agree, which is what keeps a fixture in one place: the candidate a marker
     * would light is the candidate whose replacement stands there while it is dark.</p>
     *
     * <p>{@code WEIGHT.041}'s marker is not in the address and does not need to be. It exists so that
     * two weighted markers at one block do not share a draw; a socket <em>is</em> the marker at its
     * position, and no second socket can occupy it.</p>
     *
     * <p>The list is rotated from the winner rather than reduced to it, because
     * {@link OptionalLightPlacer} falls through to the next candidate when the world will not accept
     * one. The rotation keeps the file's own order behind the winner, so which candidate is tried second
     * is the author's decision and not a hash's.</p>
     */
    public List<Candidate> weightedOrder(Placement placement, long seed, int x, int y, int z) {
        List<Candidate> group = candidates.get(placement);
        if (group.isEmpty()) {
            return List.of();
        }
        int winner = slots.get(placement)[
                Rng.indexAtPos(seed, x, y, z, Rng.Purpose.LIGHTING_VARIANT, CompiledPalette.SLOTS)];
        List<Candidate> ordered = new ArrayList<>(group.size());
        for (int offset = 0; offset < group.size(); offset++) {
            ordered.add(group.get((winner + offset) % group.size()));
        }
        return List.copyOf(ordered);
    }

    public boolean hasCandidates(Placement placement) {
        return !candidates.get(placement).isEmpty();
    }

    public BlockState representative() {
        return allCandidates.getFirst().state();
    }

    public Collection<Candidate> allCandidates() {
        return allCandidates;
    }

    private static void compileGroup(HolderLookup<Block> blockLookup, Identifier paletteId,
                                     char marker, Placement placement,
                                     List<LightSourceSettings.Entry> entries,
                                     Map<Placement, List<Candidate>> candidates,
                                     boolean[] dropped) {
        List<Candidate> compiled = new ArrayList<>(entries.size());
        for (int candidateIndex = 0; candidateIndex < entries.size(); candidateIndex++) {
            LightSourceSettings.Entry entry = entries.get(candidateIndex);
            if (entry.weight() <= 0) {
                throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.block(),
                        "weight must be positive", null);
            }
            // Dropped rather than rejected: a candidate list is a weighted draw like any other, so
            // an absent block hands its weight to the lights this game does have (issue #91). Null
            // and thrown are different answers here - resolveState only returns null for a block
            // this game does not have, and still throws for a property expression that is wrong
            // whatever is installed, which is what keeps this candidate's context in the message.
            BlockState state;
            try {
                state = Tools.resolveState(entry.block(), blockLookup, paletteId);
            } catch (RuntimeException e) {
                throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.block(),
                        "cannot parse block state", e);
            }
            if (state == null) {
                dropped[0] = true;
                continue;
            }
            if (state.getLightEmission() <= 0) {
                throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.block(),
                        "block state emits no light", null);
            }
            validatePlacement(paletteId, marker, placement, candidateIndex, entry.block(), state);
            // Dropped like any other absent block (issue #91): the candidate keeps its lit state and
            // falls back to the source's own replacement, rather than the whole candidate vanishing
            // because a mod that supplied only its unlit form is not installed.
            BlockState unlit = null;
            if (entry.unlit() != null) {
                try {
                    unlit = Tools.resolveState(entry.unlit(), blockLookup, paletteId);
                } catch (RuntimeException e) {
                    throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.unlit(),
                            "cannot parse block state", e);
                }
                if (unlit != null && unlit.getLightEmission() > 0) {
                    throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.unlit(),
                            "an unlit replacement must emit no light", null);
                }
                if (unlit != null) {
                    validatePlacement(paletteId, marker, placement, candidateIndex, entry.unlit(), unlit);
                }
            }
            compiled.add(new Candidate(entry.weight(), state, unlit));
        }
        candidates.put(placement, List.copyOf(compiled));
    }

    private static void validatePlacement(Identifier paletteId, char marker, Placement placement,
                                          int candidateIndex, String block, BlockState state) {
        boolean sixWayFacing = state.hasProperty(BlockStateProperties.FACING);
        boolean horizontalFacing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
        boolean hanging = state.hasProperty(BlockStateProperties.HANGING);
        if ((placement == Placement.FLOOR || placement == Placement.CEILING)
                && horizontalFacing && !sixWayFacing) {
            throw invalidCandidate(paletteId, marker, placement, candidateIndex, block,
                    "cannot orient a horizontal-only state toward vertical support", null);
        }
        if (placement == Placement.WALL && hanging && !sixWayFacing && !horizontalFacing) {
            throw invalidCandidate(paletteId, marker, placement, candidateIndex, block,
                    "cannot orient a hanging-only state to a wall", null);
        }
    }

    private static IllegalArgumentException invalidCandidate(Identifier paletteId, char marker, Placement placement,
                                                             int candidateIndex, String block,
                                                             String problem, Throwable cause) {
        String message = "Invalid light candidate in palette '" + paletteId + "', marker '" + marker
                + "', placement '" + placement.name().toLowerCase(Locale.ROOT) + "', candidate #"
                + (candidateIndex + 1) + " '" + block + "': " + problem;
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }
}
