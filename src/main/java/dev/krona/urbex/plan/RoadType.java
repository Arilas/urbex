package dev.krona.urbex.plan;

/** How major a planned road is. Ordinal order is precedence order: later beats earlier. */
public enum RoadType {
    NONE,
    TERTIARY,
    SECONDARY,
    PRIMARY;

    public static RoadType strongest(RoadType first, RoadType second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }
}
