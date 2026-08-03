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
        double perimeterRingFraction
) {
    /**
     * Default for {@link #perimeterRingFraction}, used by the 13-arg compatibility constructor
     * below so existing call sites built before this field existed don't need to be touched.
     */
    private static final double DEFAULT_PERIMETER_RING_FRACTION = 0.92;

    /**
     * Compatibility constructor for call sites written before {@link #perimeterRingFraction}
     * existed. Defaults it rather than forcing every constructor call in the codebase to be edited
     * the moment a new tunable is added - the same reasoning that keeps this record a flat list of
     * fields instead of a builder: adding one more knob shouldn't ripple through every caller that
     * doesn't care about it.
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
                DEFAULT_PERIMETER_RING_FRACTION // perimeter ring, as a fraction of settlement radius
        );
    }
}
