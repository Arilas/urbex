package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;

/**
 * Which settlement, if any, covers a chunk.
 * <p>
 * A naive per-chunk rule &mdash; try classes largest first, and let the larger one win any chunk
 * both would cover &mdash; is not enough on its own: a smaller settlement's cell can straddle the
 * edge of a bigger one, so only part of the smaller settlement's footprint would ever be shadowed
 * chunk-by-chunk while the rest still got reported, leaving its bounding box touching the bigger
 * one's. {@link #shadowedByLargerClass} closes that gap by discarding a smaller candidate outright
 * &mdash; everywhere, not just at the shared chunks &mdash; whenever its bounds touch a class larger
 * than it anywhere nearby, recursively, so a class that would itself be discarded can never shadow
 * anything either.
 */
public final class SettlementMap {

    private SettlementMap() {
    }

    private static final SettlementClass[] LARGEST_FIRST = {
            SettlementClass.METROPOLIS,
            SettlementClass.CITY,
            SettlementClass.TOWN,
            SettlementClass.VILLAGE,
            SettlementClass.HAMLET
    };

    /**
     * The settlement covering this chunk, or {@code null}.
     * <p>
     * The {@code LARGEST_FIRST} iteration order is not load-bearing: {@link #shadowedByLargerClass}
     * already resolves each candidate against every strictly-larger class on its own, independent of
     * the order classes are tried in here. Largest-first is kept only because it lets this loop
     * return as soon as it finds a match, without changing which settlement, if any, that is.
     */
    public static Settlement at(long seed, int chunkX, int chunkZ, PlanParams params) {
        for (SettlementClass cls : LARGEST_FIRST) {
            Settlement s = candidateFor(seed, chunkX, chunkZ, cls);
            if (s != null && s.boundsChunks().contains(new Vec2(chunkX, chunkZ))) {
                return s;
            }
        }
        return null;
    }

    /**
     * The settlement of {@code cls} owned by the cell containing this chunk, or {@code null} &mdash;
     * either because that cell rolled no settlement, or because the candidate is shadowed by a
     * strictly larger class (see class doc). Delegates to {@link #resolvedCandidateForCell} once the
     * chunk coordinate has been reduced to this class's own cell coordinate.
     */
    private static Settlement candidateFor(long seed, int chunkX, int chunkZ, SettlementClass cls) {
        int cell = cls.cellSizeChunks();
        int cellX = Math.floorDiv(chunkX, cell);
        int cellZ = Math.floorDiv(chunkZ, cell);
        return resolvedCandidateForCell(seed, cellX, cellZ, cls);
    }

    /** The raw candidate for {@code cls} in this cell, or {@code null} if it does not exist or is shadowed. */
    private static Settlement resolvedCandidateForCell(long seed, int cellX, int cellZ, SettlementClass cls) {
        Settlement candidate = rawCandidateForCell(seed, cellX, cellZ, cls);
        if (candidate == null || shadowedByLargerClass(seed, candidate, cls)) {
            return null;
        }
        return candidate;
    }

    /** The settlement {@code cls} would place in cell ({@code cellX}, {@code cellZ}), ignoring other classes. */
    private static Settlement rawCandidateForCell(long seed, int cellX, int cellZ, SettlementClass cls) {
        int cell = cls.cellSizeChunks();
        // Shift this class onto its own key range so two classes never draw the same stream at the
        // same cell coordinates: without it, e.g. TOWN's EXISTS roll at cell (0,0) would reuse
        // exactly the bits VILLAGE's EXISTS roll used at cell (0,0), silently correlating them.
        long key = cls.ordinal() * 100L;

        if (Hash.unit(Hash.at(seed, cellX, cellZ, key + PlanPurpose.SETTLEMENT_EXISTS.key()))
                >= cls.spawnChance()) {
            return null;
        }

        // Jitter within the cell, keeping half the extent clear of every edge so the settlement
        // cannot cross into a neighbouring cell.
        int margin = cls.extentChunks() / 2 + 1;
        int span = cell - 2 * margin;
        int offsetX = Hash.index(Hash.at(seed, cellX, cellZ, key + PlanPurpose.SETTLEMENT_JITTER_X.key()), span);
        int offsetZ = Hash.index(Hash.at(seed, cellX, cellZ, key + PlanPurpose.SETTLEMENT_JITTER_Z.key()), span);

        return new Settlement(cls, cellX * cell + margin + offsetX, cellZ * cell + margin + offsetZ);
    }

    /**
     * Whether {@code candidate}'s bounds touch the <em>resolved</em> candidate of any class larger
     * than {@code cls} &mdash; i.e. one that would itself survive {@link #resolvedCandidateForCell},
     * not merely one that rolled into existence. A candidate's footprint is tiny next to a larger
     * class's cell, so at most a handful of that larger lattice's cells can possibly reach it; every
     * one of them is checked.
     * <p>
     * This recurses into {@link #resolvedCandidateForCell} rather than the raw candidate on purpose:
     * a larger candidate that would itself be shadowed by something bigger still is never reported by
     * {@link #at}, so it must not be allowed to shadow anything either &mdash; otherwise a three-level
     * chain (X touches Y, Y is shadowed by Z, X does not touch Z) would discard X for no reason, even
     * though Y was never going to be reported anyway. The recursion always terminates: each call only
     * ever descends into classes strictly larger than its own, and there are five classes total, so
     * the call stack is at most four deep (from {@code HAMLET} up through {@code METROPOLIS}).
     */
    private static boolean shadowedByLargerClass(long seed, Settlement candidate, SettlementClass cls) {
        Rect bounds = candidate.boundsChunks();
        for (SettlementClass bigger : SettlementClass.values()) {
            if (bigger.ordinal() <= cls.ordinal()) {
                continue;
            }
            int bigCell = bigger.cellSizeChunks();
            int minCellX = Math.floorDiv(bounds.minX(), bigCell);
            int maxCellX = Math.floorDiv(bounds.maxX(), bigCell);
            int minCellZ = Math.floorDiv(bounds.minZ(), bigCell);
            int maxCellZ = Math.floorDiv(bounds.maxZ(), bigCell);
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    Settlement other = resolvedCandidateForCell(seed, cellX, cellZ, bigger);
                    if (other != null && other.boundsChunks().intersects(bounds)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
