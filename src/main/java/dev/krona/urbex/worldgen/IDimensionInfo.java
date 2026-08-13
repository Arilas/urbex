package dev.krona.urbex.worldgen;

/**
 * What pairs a dimension's planning inputs with the generator that draws them.
 *
 * <p>Two methods, down from thirteen. Everything planning reads is a {@link PlanningContext} now - a
 * value, with no level, no generator, and one dimension key rather than two - and this is all that is
 * left of the service bag (issue #129).</p>
 *
 * <p>Pairing those two is {@link DimensionRuntime}'s job, so this interface goes when the
 * world-creation preview stops needing an implementation of it. It is deliberately not deleted in
 * the same change that moved every call site off it: one is a mechanical migration the compiler
 * checks exhaustively, the other decides who owns what.</p>
 */
public interface IDimensionInfo {

    /** Everything a chunk here is planned against. */
    PlanningContext planning();

    /** The generator those plans are drawn by. */
    CityGenerator getFeature();
}
