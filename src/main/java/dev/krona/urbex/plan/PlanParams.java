package dev.krona.urbex.plan;

/**
 * Every tunable number in one place, so that tuning by eye in the viewer does not mean hunting
 * constants through six files. P5 makes these datapack-driven; P2 only has to avoid scattering them.
 */
public record PlanParams(
        int spokeCountMin,
        int spokeCountMax,
        int ringCountMin,
        int ringCountMax,
        int segmentLengthBlocks,
        int snapRadiusBlocks,
        int maxSlopePerSegment,
        int maxBridgeSpanBlocks,
        int minBlockAreaBlocks,
        int maxLotDepthBlocks,
        int coreLotSizeBlocks,
        int fringeLotSizeBlocks,
        int probeDistanceBlocks,
        double perimeterRingFraction,
        boolean smallSettlementsEnabled,
        int spineSegmentLengthBlocks,
        float branchChance,
        int branchLengthSegments,
        int roadsideSetbackBlocks,
        int roadsideLotDepthBlocks
) {
    /**
     * Default for {@link #perimeterRingFraction}, used by the compatibility constructors below so
     * existing call sites built before this field existed don't need to be touched.
     */
    private static final double DEFAULT_PERIMETER_RING_FRACTION = 0.92;

    /** Defaults for the small-settlement spine fields, added by task 5b; see the constants below. */
    private static final boolean DEFAULT_SMALL_SETTLEMENTS_ENABLED = true;
    private static final int DEFAULT_SPINE_SEGMENT_LENGTH_BLOCKS = 16;
    private static final float DEFAULT_BRANCH_CHANCE = 0.45f;
    private static final int DEFAULT_BRANCH_LENGTH_SEGMENTS = 2;
    private static final int DEFAULT_ROADSIDE_SETBACK_BLOCKS = 3;
    private static final int DEFAULT_ROADSIDE_LOT_DEPTH_BLOCKS = 14;

    /**
     * Compatibility constructor for call sites written before {@link #smallSettlementsEnabled} and
     * its siblings existed - i.e. everything that used to build a full 14-arg {@code PlanParams}.
     * Defaults the new fields rather than forcing every constructor call in the codebase to be
     * edited the moment a new tunable is added - the same reasoning that keeps this record a flat
     * list of fields instead of a builder: adding one more knob shouldn't ripple through every
     * caller that doesn't care about it.
     */
    public PlanParams(int spokeCountMin, int spokeCountMax, int ringCountMin, int ringCountMax,
                       int segmentLengthBlocks, int snapRadiusBlocks, int maxSlopePerSegment,
                       int maxBridgeSpanBlocks, int minBlockAreaBlocks, int maxLotDepthBlocks,
                       int coreLotSizeBlocks, int fringeLotSizeBlocks, int probeDistanceBlocks,
                       double perimeterRingFraction) {
        this(spokeCountMin, spokeCountMax, ringCountMin, ringCountMax, segmentLengthBlocks,
                snapRadiusBlocks, maxSlopePerSegment, maxBridgeSpanBlocks, minBlockAreaBlocks,
                maxLotDepthBlocks, coreLotSizeBlocks, fringeLotSizeBlocks, probeDistanceBlocks,
                perimeterRingFraction, DEFAULT_SMALL_SETTLEMENTS_ENABLED,
                DEFAULT_SPINE_SEGMENT_LENGTH_BLOCKS, DEFAULT_BRANCH_CHANCE,
                DEFAULT_BRANCH_LENGTH_SEGMENTS, DEFAULT_ROADSIDE_SETBACK_BLOCKS,
                DEFAULT_ROADSIDE_LOT_DEPTH_BLOCKS);
    }

    /**
     * Compatibility constructor for call sites written before {@link #perimeterRingFraction} existed
     * (the original 13-arg shape). Chains through the constructor above.
     */
    public PlanParams(int spokeCountMin, int spokeCountMax, int ringCountMin, int ringCountMax,
                       int segmentLengthBlocks, int snapRadiusBlocks, int maxSlopePerSegment,
                       int maxBridgeSpanBlocks, int minBlockAreaBlocks, int maxLotDepthBlocks,
                       int coreLotSizeBlocks, int fringeLotSizeBlocks, int probeDistanceBlocks) {
        this(spokeCountMin, spokeCountMax, ringCountMin, ringCountMax, segmentLengthBlocks,
                snapRadiusBlocks, maxSlopePerSegment, maxBridgeSpanBlocks, minBlockAreaBlocks,
                maxLotDepthBlocks, coreLotSizeBlocks, fringeLotSizeBlocks, probeDistanceBlocks,
                DEFAULT_PERIMETER_RING_FRACTION);
    }

    public static PlanParams defaults() {
        return new PlanParams(
                3, 8,          // spokes
                1, 3,          // rings
                48,            // segment length
                24,            // snap radius
                6,             // max slope per segment
                64,            // max bridge span
                256,           // min block area
                40,            // max lot depth before an alley is needed
                12,            // core lot size
                28,            // fringe lot size
                6,             // water-side probe distance beyond a lot's edge
                DEFAULT_PERIMETER_RING_FRACTION, // perimeter ring, as a fraction of settlement radius
                DEFAULT_SMALL_SETTLEMENTS_ENABLED,     // hamlets/villages generate at all
                DEFAULT_SPINE_SEGMENT_LENGTH_BLOCKS,   // spine/branch step length
                DEFAULT_BRANCH_CHANCE,                 // chance a spine node grows a branch
                DEFAULT_BRANCH_LENGTH_SEGMENTS,         // steps a branch grows before stopping
                DEFAULT_ROADSIDE_SETBACK_BLOCKS,        // gap between road centreline and a lot's near edge
                DEFAULT_ROADSIDE_LOT_DEPTH_BLOCKS       // how far a roadside lot extends from the road
        );
    }
}
