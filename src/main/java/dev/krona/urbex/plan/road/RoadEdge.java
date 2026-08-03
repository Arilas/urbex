package dev.krona.urbex.plan.road;

/**
 * {@code bridge} is derived from terrain by {@link BridgeDetector}, never rolled.
 * <p>
 * {@code waterSpanBlocks} is how much water the edge actually crosses, 0 when dry. Downstream
 * phases need the span, not just the flag: the current datapack's bridge is a single 16x16 chunk
 * piece, which is only correct for a river exactly that wide. A span lets P4 choose an asset, or
 * repeat a section, instead of assuming one chunk.
 */
public record RoadEdge(int fromId, int toId, RoadClass cls, boolean bridge, int waterSpanBlocks) {

    public RoadEdge asBridge(int waterSpanBlocks) {
        return new RoadEdge(fromId, toId, cls, true, waterSpanBlocks);
    }
}
