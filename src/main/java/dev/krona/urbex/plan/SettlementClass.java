package dev.krona.urbex.plan;

/**
 * The settlement size classes, each placed on its own lattice.
 * <p>
 * One lattice per class rather than one lattice with a size roll: cell size controls how common a
 * class is, so hamlets can be scattered everywhere while a metropolis is a landmark, and each
 * class's density is tuned independently. The cell must be at least twice the extent so a jittered
 * centre can never push a settlement out of its own cell, which is what makes non-overlap a
 * property of the construction rather than something to check for afterwards.
 */
public enum SettlementClass {

    HAMLET(4, 24, 0.55f),
    VILLAGE(8, 48, 0.45f),
    TOWN(12, 128, 0.35f),
    CITY(32, 384, 0.30f),
    METROPOLIS(96, 1024, 0.25f);

    private final int extentChunks;
    private final int cellSizeChunks;
    private final float spawnChance;

    SettlementClass(int extentChunks, int cellSizeChunks, float spawnChance) {
        if (cellSizeChunks < extentChunks * 2) {
            throw new IllegalArgumentException(name() + ": cell must be at least twice the extent");
        }
        this.extentChunks = extentChunks;
        this.cellSizeChunks = cellSizeChunks;
        this.spawnChance = spawnChance;
    }

    public int extentChunks() {
        return extentChunks;
    }

    public int cellSizeChunks() {
        return cellSizeChunks;
    }

    public float spawnChance() {
        return spawnChance;
    }

    /**
     * True for the two smallest classes, which grow a {@code SpineGrowth} tree of a main road with
     * branches instead of {@code ArterialGrowth}'s spoke-and-ring network. Their radii are too small
     * for even one arterial segment to take a first step, and the owner's ruling was that they
     * should not be miniature cities anyway.
     */
    public boolean usesSpine() {
        return this == HAMLET || this == VILLAGE;
    }
}
