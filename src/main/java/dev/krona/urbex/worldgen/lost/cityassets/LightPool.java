package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSourceSettings;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
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
    private final Map<Placement, Integer> totalWeights;
    private final List<Candidate> allCandidates;

    private LightPool(Map<Placement, List<Candidate>> candidates, Map<Placement, Integer> totalWeights) {
        EnumMap<Placement, List<Candidate>> immutableCandidates = new EnumMap<>(Placement.class);
        EnumMap<Placement, Integer> immutableWeights = new EnumMap<>(Placement.class);
        List<Candidate> flattened = new ArrayList<>();
        for (Placement placement : Placement.values()) {
            List<Candidate> group = List.copyOf(candidates.getOrDefault(placement, List.of()));
            immutableCandidates.put(placement, group);
            immutableWeights.put(placement, totalWeights.getOrDefault(placement, 0));
            flattened.addAll(group);
        }
        this.candidates = Map.copyOf(immutableCandidates);
        this.totalWeights = Map.copyOf(immutableWeights);
        this.allCandidates = List.copyOf(flattened);
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
        EnumMap<Placement, Integer> totalWeights = new EnumMap<>(Placement.class);
        boolean[] dropped = new boolean[1];
        compileGroup(blockLookup, paletteId, marker, Placement.FLOOR, settings.floor(), candidates, totalWeights, dropped);
        compileGroup(blockLookup, paletteId, marker, Placement.WALL, settings.wall(), candidates, totalWeights, dropped);
        compileGroup(blockLookup, paletteId, marker, Placement.CEILING, settings.ceiling(), candidates, totalWeights, dropped);
        compileGroup(blockLookup, paletteId, marker, Placement.FREE, settings.free(), candidates, totalWeights, dropped);
        if (candidates.values().stream().allMatch(List::isEmpty)) {
            if (dropped[0]) {
                return null;
            }
            throw new IllegalArgumentException("Invalid light pool in palette '" + paletteId + "', marker '" + marker
                    + "': expected at least one candidate in floor, wall, ceiling, or free");
        }
        return new LightPool(candidates, totalWeights);
    }

    public List<Candidate> weightedOrder(Placement placement, RandomSource random) {
        List<Candidate> group = candidates.get(placement);
        if (group.isEmpty()) {
            return List.of();
        }
        int ticket = random.nextInt(totalWeights.get(placement));
        int accumulated = 0;
        int winner = group.size() - 1;
        for (int i = 0; i < group.size(); i++) {
            accumulated += group.get(i).weight();
            if (ticket < accumulated) {
                winner = i;
                break;
            }
        }
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
                                     Map<Placement, Integer> totalWeights,
                                     boolean[] dropped) {
        List<Candidate> compiled = new ArrayList<>(entries.size());
        int totalWeight = 0;
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
            try {
                totalWeight = Math.addExact(totalWeight, entry.weight());
            } catch (ArithmeticException e) {
                throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.block(),
                        "total weight exceeds integer range", e);
            }
            compiled.add(new Candidate(entry.weight(), state, unlit));
        }
        candidates.put(placement, List.copyOf(compiled));
        totalWeights.put(placement, totalWeight);
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
