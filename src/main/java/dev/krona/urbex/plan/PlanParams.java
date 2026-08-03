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
        int probeDistanceBlocks
) {
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
                6              // water-side probe distance beyond a lot's edge
        );
    }
}
