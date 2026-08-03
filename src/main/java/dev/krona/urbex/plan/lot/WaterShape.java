package dev.krona.urbex.plan.lot;

/**
 * What kind of water frontage a lot has, derived from which of its four sides face water.
 * <p>
 * The shapes are not enumerated by hand — they fall out of the 4-bit mask, which is why this can
 * cover cases nobody thought to author a piece for. P3 authors one piece per shape; P2 decides
 * which applies. A river is frequently not one chunk wide, so a single "bridge" piece and a single
 * "canal side" piece cannot cover the real geometry.
 */
public enum WaterShape {
    /** No side faces water. */
    INLAND,
    /** One side. The plain canal or riverbank edge. */
    STRAIGHT,
    /** Two adjacent sides — an outside corner where two banks meet. */
    CORNER,
    /** Two opposite sides — a channel running straight through. */
    CHANNEL,
    /** Three sides — the tip of a peninsula. */
    PENINSULA,
    /** All four sides. */
    ISLAND;

    public static final int NORTH = 1;
    public static final int EAST = 1 << 1;
    public static final int SOUTH = 1 << 2;
    public static final int WEST = 1 << 3;

    public static WaterShape of(int mask) {
        return switch (Integer.bitCount(mask & 0b1111)) {
            case 0 -> INLAND;
            case 1 -> STRAIGHT;
            case 2 -> isOpposite(mask) ? CHANNEL : CORNER;
            case 3 -> PENINSULA;
            default -> ISLAND;
        };
    }

    private static boolean isOpposite(int mask) {
        return mask == (NORTH | SOUTH) || mask == (EAST | WEST);
    }
}
