package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightTodoQueueTest {

    @Test
    void closeAndDrainRejectsLateEnqueueInsteadOfSilentlyDroppingIt() {
        LightTodoQueue queue = new LightTodoQueue(3, -2);
        BlockPos admitted = new BlockPos(49, 70, -31);
        queue.add(admitted, null, true);

        List<LightTodoQueue.Todo> drained = queue.closeAndDrain();

        assertEquals(List.of(new LightTodoQueue.Todo(admitted, null, true)), drained);
        assertThrows(IllegalStateException.class,
                () -> queue.add(new BlockPos(50, 70, -31), null, true));
    }

    @Test
    void racingEnqueueIsEitherInAtomicDrainOrExplicitlyRejected() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int iteration = 0; iteration < 100; iteration++) {
                LightTodoQueue queue = new LightTodoQueue(3, -2);
                BlockPos marker = new BlockPos(49, 70, -31);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Boolean> enqueue = executor.submit(() -> {
                    start.await();
                    try {
                        queue.add(marker, null, true);
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                });
                Future<List<LightTodoQueue.Todo>> drain = executor.submit(() -> {
                    start.await();
                    return queue.closeAndDrain();
                });

                boolean accepted = enqueue.get();
                List<LightTodoQueue.Todo> drained = drain.get();
                if (accepted) {
                    assertEquals(List.of(new LightTodoQueue.Todo(marker, null, true)), drained);
                } else {
                    assertTrue(drained.isEmpty());
                }
            }
        }
    }

    @Test
    void generationQueueRejectsMarkersFromAnotherChunk() {
        LightTodoQueue queue = new LightTodoQueue(3, -2);

        assertThrows(IllegalArgumentException.class,
                () -> queue.add(new BlockPos(64, 70, -31), null, true));
    }
}
