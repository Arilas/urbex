package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.ChunkCoord;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed chunk says what it cost.
 * <p>
 * The three commit states are the three things a person reading the log has to go and check, and
 * they are not the same job: nothing written means the chunk regenerates clean if it is requested
 * again, partway written means part of a city is in the world, and written-then-failed means the
 * blocks are there but the deferred writes may not be. Until issue #131 all three were one log line
 * followed by a return of success.
 */
class ChunkGenerationFailureTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ChunkCoord COORD = new ChunkCoord(Level.OVERWORLD, 12, -7);

    @Test
    void theOriginalFailureIsAlwaysTheCause() {
        IllegalStateException original = new IllegalStateException("the actual problem");

        ChunkGenerationFailure failure =
                new ChunkGenerationFailure(COORD, ChunkDriver.CommitState.BUFFERED, original);

        assertSame(original, failure.getCause(),
                "the wrapper adds the chunk and the phase; it must never replace the failure");
    }

    @Test
    void everyFailureNamesTheChunkAndTheDimension() {
        for (ChunkDriver.CommitState state : ChunkDriver.CommitState.values()) {
            String message = new ChunkGenerationFailure(COORD, state, new RuntimeException()).getMessage();
            assertTrue(message.contains("12,-7"), "must name the chunk, was: " + message);
            assertTrue(message.contains("minecraft:overworld"),
                    "must name the dimension, was: " + message);
        }
    }

    @Test
    void eachCommitStateDescribesADifferentConsequence() {
        String buffered = messageFor(ChunkDriver.CommitState.BUFFERED);
        String committing = messageFor(ChunkDriver.CommitState.COMMITTING);
        String committed = messageFor(ChunkDriver.CommitState.COMMITTED);

        assertEquals(3, java.util.Set.of(buffered, committing, committed).size(),
                "a report that says the same thing whatever happened is a report nobody can act on");
        assertTrue(buffered.contains("nothing was written"), buffered);
        assertTrue(committing.contains("partway"), committing);
        assertTrue(committed.contains("post-processing"), committed);
    }

    @Test
    void theCoordinateAndPhaseAreReadableWithoutParsingTheMessage() {
        ChunkGenerationFailure failure =
                new ChunkGenerationFailure(COORD, ChunkDriver.CommitState.COMMITTING, new RuntimeException());

        assertEquals(COORD, failure.coord());
        assertEquals(ChunkDriver.CommitState.COMMITTING, failure.commitState());
    }

    private static String messageFor(ChunkDriver.CommitState state) {
        return new ChunkGenerationFailure(COORD, state, new RuntimeException()).getMessage();
    }
}
