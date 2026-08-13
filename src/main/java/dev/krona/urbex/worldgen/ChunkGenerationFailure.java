package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.ChunkCoord;

/**
 * One chunk failed to generate, and did not generate a different way instead.
 *
 * <p>This is the middle of the three failure categories issue #131 sets out, and the only one that
 * needs a type:</p>
 *
 * <ul>
 *   <li><b>Configuration or asset validation.</b> Refused where the runtime is built - a pack that
 *       does not compile refuses the world at load, naming every problem at once, and a level whose
 *       city style cannot resolve refuses the level. Nothing gets as far as a chunk.</li>
 *   <li><b>Generation failure</b> - this. The chunk is not generated, so it must not be saved as
 *       though it were; the exception leaves {@code CityFeature.generateFromPipeline} and fails the
 *       chunk task.</li>
 *   <li><b>Non-fatal diagnostic.</b> Reported through {@link ErrorLogger} without changing the
 *       result.</li>
 * </ul>
 *
 * <p>What this replaces is a {@code catch (Exception)} around the whole of generation that logged and
 * then <em>returned success</em>. A chunk that threw halfway through writing a building continued
 * through the pipeline and was saved - vanilla terrain with half a city in it, next to neighbours
 * that got theirs, and nothing in the world to say which chunks were affected. The log line was
 * there; the chunk was ruined anyway, permanently, and only a log nobody reads distinguished it from
 * a chunk that generated correctly.</p>
 *
 * <p>The commit state is carried because it is the difference between "nothing was written" and
 * "half a city was written", and a report that cannot say which is a report nobody can act on. It
 * does not change what happens - a failed chunk fails either way - it changes what the person
 * reading the log has to go and check.</p>
 */
public class ChunkGenerationFailure extends RuntimeException {

    private final ChunkCoord coord;
    private final ChunkDriver.CommitState commitState;

    public ChunkGenerationFailure(ChunkCoord coord, ChunkDriver.CommitState commitState, Throwable cause) {
        super(describe(coord, commitState), cause);
        this.coord = coord;
        this.commitState = commitState;
    }

    /** Which chunk failed. */
    public ChunkCoord coord() {
        return coord;
    }

    /** How much of that chunk had reached the world when it failed. */
    public ChunkDriver.CommitState commitState() {
        return commitState;
    }

    private static String describe(ChunkCoord coord, ChunkDriver.CommitState commitState) {
        String state = switch (commitState) {
            case BUFFERED -> "nothing was written to the world - the chunk is untouched vanilla "
                    + "terrain, and will be regenerated from scratch if it is requested again";
            case COMMITTING -> "the chunk was partway through being written, so part of its city is "
                    + "in the world and part is not";
            case COMMITTED -> "the chunk's blocks were written and it failed during post-processing, "
                    + "so its city is present but its deferred writes may not be";
        };
        return "Urbex failed to generate chunk " + coord.chunkX() + "," + coord.chunkZ() + " in '"
                + coord.dimension().identifier() + "': " + state + ". The chunk task fails rather "
                + "than saving a chunk that is quietly wrong.";
    }
}
