package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSettings;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LightPool {

    public enum Placement { FLOOR, WALL, CEILING, FREE }

    public record Candidate(int weight, BlockState state) { }

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

    public static LightPool compile(HolderLookup<Block> blockLookup, Identifier paletteId, char marker,
                                    LightSettings settings) {
        EnumMap<Placement, List<Candidate>> candidates = new EnumMap<>(Placement.class);
        EnumMap<Placement, Integer> totalWeights = new EnumMap<>(Placement.class);
        compileGroup(blockLookup, paletteId, marker, Placement.FLOOR, settings.floor(), candidates, totalWeights);
        compileGroup(blockLookup, paletteId, marker, Placement.WALL, settings.wall(), candidates, totalWeights);
        compileGroup(blockLookup, paletteId, marker, Placement.CEILING, settings.ceiling(), candidates, totalWeights);
        compileGroup(blockLookup, paletteId, marker, Placement.FREE, settings.free(), candidates, totalWeights);
        if (candidates.values().stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException("Invalid light pool in palette '" + paletteId + "', marker '" + marker
                    + "': expected at least one candidate in floor, wall, ceiling, or free");
        }
        return new LightPool(candidates, totalWeights);
    }

    public static LightPool legacyTorch() {
        EnumMap<Placement, List<Candidate>> candidates = new EnumMap<>(Placement.class);
        EnumMap<Placement, Integer> totalWeights = new EnumMap<>(Placement.class);
        candidates.put(Placement.FLOOR, List.of(new Candidate(1, Blocks.TORCH.defaultBlockState())));
        candidates.put(Placement.WALL, List.of(new Candidate(1, Blocks.WALL_TORCH.defaultBlockState())));
        candidates.put(Placement.CEILING, List.of());
        candidates.put(Placement.FREE, List.of());
        totalWeights.put(Placement.FLOOR, 1);
        totalWeights.put(Placement.WALL, 1);
        totalWeights.put(Placement.CEILING, 0);
        totalWeights.put(Placement.FREE, 0);
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
                                     List<LightSettings.Entry> entries,
                                     Map<Placement, List<Candidate>> candidates,
                                     Map<Placement, Integer> totalWeights) {
        List<Candidate> compiled = new ArrayList<>(entries.size());
        int totalWeight = 0;
        for (int candidateIndex = 0; candidateIndex < entries.size(); candidateIndex++) {
            LightSettings.Entry entry = entries.get(candidateIndex);
            if (entry.weight() <= 0) {
                throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.block(),
                        "weight must be positive", null);
            }
            BlockState state;
            try {
                state = Tools.stringToState(entry.block(), blockLookup, paletteId);
            } catch (RuntimeException e) {
                throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.block(),
                        "cannot parse block state", e);
            }
            if (state.getLightEmission() <= 0) {
                throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.block(),
                        "block state emits no light", null);
            }
            validatePlacement(paletteId, marker, placement, candidateIndex, entry.block(), state);
            try {
                totalWeight = Math.addExact(totalWeight, entry.weight());
            } catch (ArithmeticException e) {
                throw invalidCandidate(paletteId, marker, placement, candidateIndex, entry.block(),
                        "total weight exceeds integer range", e);
            }
            compiled.add(new Candidate(entry.weight(), state));
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
