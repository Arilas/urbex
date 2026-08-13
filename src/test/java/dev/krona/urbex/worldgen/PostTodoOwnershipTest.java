package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.ChunkPlan;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where post-generation work is allowed to live, pinned structurally.
 * <p>
 * {@link PostTodoQueueTest} covers what the queue does. This covers the part of issue #127 that a
 * behaviour test cannot: the callbacks are on the generation context and reachable only from there.
 * Both halves are needed, because the defect was never that the queue misbehaved - it was that the
 * queue was hanging off a cached, shared, coordinate-addressed planning value, and any future edit
 * that puts one back there would leave every behaviour test green.
 */
class PostTodoOwnershipTest {

    /**
     * A {@code ChunkPlan} is cached per coordinate and shared by every generation that reads the
     * chunk, so nothing belonging to a single generation may be stored on it. Callbacks are the case
     * that actually bit (post-todos), and they are what this scans for.
     */
    @Test
    void buildingInfoHoldsNoRuntimeCallbacks() {
        for (Field field : ChunkPlan.class.getDeclaredFields()) {
            assertTrue(Consumer.class != field.getType(),
                    "ChunkPlan." + field.getName() + " is a callback on a cached planning value; "
                            + "per-generation work belongs on the ChunkGenContext");
            assertTrue(!field.getGenericType().toString().contains(Consumer.class.getName()),
                    "ChunkPlan." + field.getName() + " holds callbacks (" + field.getGenericType()
                            + ") on a cached planning value; per-generation work belongs on the "
                            + "ChunkGenContext");
        }
        assertArrayEquals(new String[0],
                Arrays.stream(ChunkPlan.class.getDeclaredMethods())
                        .map(Method::getName)
                        .filter(name -> name.toLowerCase().contains("posttodo"))
                        .sorted()
                        .toArray(String[]::new),
                "ChunkPlan must expose no post-todo API at all - an accessor is how the drain "
                        + "found its way back into the cache");
    }

    /**
     * The drain takes the context and nothing else. A {@code (coord, provider)} pair - what
     * {@code ChunkFixer.fix} used to take - is an instruction to look the work up again in
     * {@code DimensionCaches}, which is the re-fetch that could return a different, or empty,
     * entry than the one the generation wrote to.
     */
    @Test
    void chunkFixerDrainsTheContextRatherThanLookingWorkUpAgain() {
        Method[] fix = Arrays.stream(ChunkFixer.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("fix"))
                .toArray(Method[]::new);

        assertEquals(1, fix.length, "one drain entry point, not several");
        assertArrayEquals(new Class<?>[]{ChunkGenContext.class}, fix[0].getParameterTypes(),
                "ChunkFixer.fix must be handed the originating generation context; anything it "
                        + "could use to re-derive the work from a cache is the defect in issue #127");
    }
}
