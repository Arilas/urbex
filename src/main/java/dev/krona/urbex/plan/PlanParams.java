package dev.krona.urbex.plan;

import dev.krona.urbex.plan.road.RoadClass;

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
        int roadsideLotDepthBlocks,
        int arterialHalfWidthBlocks,
        int collectorHalfWidthBlocks,
        int localHalfWidthBlocks
) {

    /**
     * The carriageway's half-width for {@code cls}, centre to edge - what both lot pipelines now
     * inset by (whole-branch review, final pass) after {@code RoadClass}'s own doc claimed for a
     * while that the class "drives width" when no width existed anywhere in this record.
     * {@code RoadEdge}'s stored position is the road's centreline, not its near edge, so a lot has to
     * clear this distance from that line before {@link #roadsideSetbackBlocks()} (an additional
     * building-to-kerb gap) even applies.
     */
    public int roadHalfWidthBlocks(RoadClass cls) {
        return switch (cls) {
            case ARTERIAL -> arterialHalfWidthBlocks;
            case COLLECTOR -> collectorHalfWidthBlocks;
            case LOCAL -> localHalfWidthBlocks;
        };
    }
    /**
     * Default for {@link #perimeterRingFraction}, used by the compatibility constructors below so
     * existing call sites built before this field existed don't need to be touched.
     */
    private static final double DEFAULT_PERIMETER_RING_FRACTION = 0.92;

    /** Defaults for the small-settlement spine fields, added by task 5b; see the constants below. */
    private static final boolean DEFAULT_SMALL_SETTLEMENTS_ENABLED = true;
    /**
     * How far a spine or branch step travels, and - since a second review round found the dependency
     * had been coupled the wrong way round - the thing {@code RoadsideLots} now derives lot width
     * from, not the other way around. An earlier version of this constant was raised from 16 to 24 so
     * a lot sized from {@link #coreLotSizeBlocks}/{@link #fringeLotSizeBlocks} would fit inside one
     * segment; that stretched every spine/branch step by 50%, which for {@code HAMLET} (radius 32,
     * so 1-2 steps per arm to begin with) nearly halved its whole network and left 29% of hamlets on
     * flat terrain a bare two-edge line with no branches at all - a hamlet with no branches is just a
     * road, not "a main road with some additional branches." Segment length should answer "how
     * frequently does this class's road network turn or branch," which is a property of the class,
     * not of a lot-sizing constant meant for an unrelated pipeline (block subdivision has its own
     * concentric-band sizing that never touches this field). 16 restores hamlet branching; lot width
     * follows from it instead.
     */
    private static final int DEFAULT_SPINE_SEGMENT_LENGTH_BLOCKS = 16;
    private static final float DEFAULT_BRANCH_CHANCE = 0.45f;
    private static final int DEFAULT_BRANCH_LENGTH_SEGMENTS = 2;
    private static final int DEFAULT_ROADSIDE_SETBACK_BLOCKS = 3;
    private static final int DEFAULT_ROADSIDE_LOT_DEPTH_BLOCKS = 14;

    /**
     * Defaults for {@link #roadHalfWidthBlocks}, added by the whole-branch review's final pass.
     * Ordered ARTERIAL &gt; COLLECTOR &gt; LOCAL, matching {@code RoadClass}'s own doc ("Drives
     * width") - the first time anything actually did. {@code ArterialGrowth} only ever builds
     * ARTERIAL (spokes) and COLLECTOR (rings, the perimeter ring) edges, so a block's outline is
     * never bounded by LOCAL; {@code SpineGrowth} only ever builds COLLECTOR (the spine itself) and
     * LOCAL (branches). Every class that appears in a settlement's road graph has a real width here.
     */
    private static final int DEFAULT_ARTERIAL_HALF_WIDTH_BLOCKS = 3;
    private static final int DEFAULT_COLLECTOR_HALF_WIDTH_BLOCKS = 2;
    private static final int DEFAULT_LOCAL_HALF_WIDTH_BLOCKS = 1;

    /**
     * Compatibility constructor for call sites written before {@link #roadHalfWidthBlocks} existed -
     * i.e. everything that used to build a full 20-arg {@code PlanParams}. Defaults the new fields
     * rather than forcing every constructor call in the codebase to be edited the moment a new
     * tunable is added - the same reasoning as the compatibility constructors below it.
     */
    public PlanParams(int spokeCountMin, int spokeCountMax, int ringCountMin, int ringCountMax,
                       int segmentLengthBlocks, int snapRadiusBlocks, int maxSlopePerSegment,
                       int maxBridgeSpanBlocks, int minBlockAreaBlocks, int maxLotDepthBlocks,
                       int coreLotSizeBlocks, int fringeLotSizeBlocks, int probeDistanceBlocks,
                       double perimeterRingFraction, boolean smallSettlementsEnabled,
                       int spineSegmentLengthBlocks, float branchChance, int branchLengthSegments,
                       int roadsideSetbackBlocks, int roadsideLotDepthBlocks) {
        this(spokeCountMin, spokeCountMax, ringCountMin, ringCountMax, segmentLengthBlocks,
                snapRadiusBlocks, maxSlopePerSegment, maxBridgeSpanBlocks, minBlockAreaBlocks,
                maxLotDepthBlocks, coreLotSizeBlocks, fringeLotSizeBlocks, probeDistanceBlocks,
                perimeterRingFraction, smallSettlementsEnabled, spineSegmentLengthBlocks, branchChance,
                branchLengthSegments, roadsideSetbackBlocks, roadsideLotDepthBlocks,
                DEFAULT_ARTERIAL_HALF_WIDTH_BLOCKS, DEFAULT_COLLECTOR_HALF_WIDTH_BLOCKS,
                DEFAULT_LOCAL_HALF_WIDTH_BLOCKS);
    }

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
                DEFAULT_ROADSIDE_LOT_DEPTH_BLOCKS,      // how far a roadside lot extends from the road
                DEFAULT_ARTERIAL_HALF_WIDTH_BLOCKS,     // ARTERIAL centreline-to-kerb, in blocks
                DEFAULT_COLLECTOR_HALF_WIDTH_BLOCKS,    // COLLECTOR centreline-to-kerb, in blocks
                DEFAULT_LOCAL_HALF_WIDTH_BLOCKS         // LOCAL centreline-to-kerb, in blocks
        );
    }
}
