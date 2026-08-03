package dev.krona.urbex.plan.road;

/**
 * {@code bridge} is derived from terrain by {@link BridgeDetector}, never rolled.
 * <p>
 * {@code waterSpanBlocks} is how much water the edge actually crosses, 0 when dry. Downstream
 * phases need the span, not just the flag: the current datapack's bridge is a single 16x16 chunk
 * piece, which is only correct for a river exactly that wide. A span lets P4 choose an asset, or
 * repeat a section, instead of assuming one chunk.
 * <p>
 * {@code waterSpanBlocks} is measured by sampling every 4 blocks and multiplying the longest run of
 * wet samples by 4, so a run of {@code N} samples reports {@code N * 4} where the true first-to-last
 * wet distance is only {@code (N - 1) * 4} — it over-reports by roughly one sampling interval, most
 * noticeably on short crossings (a single wet sample reports 4 blocks of water for what could be a
 * span under a block). This bias is one-directional: it never under-reports, so a crossing is never
 * sized smaller than what is actually there.
 */
public record RoadEdge(int fromId, int toId, RoadClass cls, boolean bridge, int waterSpanBlocks) {

    public RoadEdge asBridge(int waterSpanBlocks) {
        return new RoadEdge(fromId, toId, cls, true, waterSpanBlocks);
    }
}
