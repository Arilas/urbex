package dev.krona.urbex.plan.district;

/**
 * Concentric bands from the settlement centre, plus terrain-driven specials.
 * <p>
 * This is the single knob that makes a citadel-and-suburbs medieval town and a
 * downtown-and-sprawl modern city the same model: the rings are the same, only the parameters and
 * (later) the palette differ.
 */
public enum District {
    CORE,
    INNER,
    OUTER,
    FRINGE,
    WATERFRONT
}
