package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;

/**
 * Which settlement, if any, covers a chunk.
 * <p>
 * Classes are tested largest first, so where two classes' cells would both produce a settlement
 * covering this chunk, the larger wins and the smaller is simply never reported. That per-chunk rule
 * is not, on its own, enough: a smaller settlement's cell can straddle the edge of a bigger one, so
 * only part of the smaller settlement's footprint is ever shadowed chunk-by-chunk while the rest
 * still gets reported, leaving its bounding box touching the bigger one's. {@link #candidateFor} closes
 * that gap by discarding a smaller candidate outright &mdash; everywhere, not just at the shared
 * chunks &mdash; whenever its bounds touch any strictly larger class's bounds anywhere nearby.
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

    /** The settlement covering this chunk, or {@code null}. */
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
     * either because that cell rolled no settlement, or because the raw candidate's bounds touch a
     * strictly larger class's bounds and is therefore fully shadowed (see class doc).
     */
    private static Settlement candidateFor(long seed, int chunkX, int chunkZ, SettlementClass cls) {
        int cell = cls.cellSizeChunks();
        int cellX = Math.floorDiv(chunkX, cell);
        int cellZ = Math.floorDiv(chunkZ, cell);

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
     * Whether {@code candidate}'s bounds touch a raw (unmasked) candidate of any class larger than
     * {@code cls}. A candidate's footprint is tiny next to a larger class's cell, so at most a
     * handful of that larger lattice's cells can possibly reach it; every one of them is checked.
     * Using each larger class's raw candidate (rather than its own possibly-shadowed result) is
     * still correct: if that larger candidate is itself shadowed by something bigger still, this
     * candidate is shadowed by that same bigger class directly, since every strictly larger class is
     * checked independently here.
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
                    Settlement other = rawCandidateForCell(seed, cellX, cellZ, bigger);
                    if (other != null && other.boundsChunks().intersects(bounds)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
