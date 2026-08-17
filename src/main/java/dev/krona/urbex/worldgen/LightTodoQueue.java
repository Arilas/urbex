package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.cityassets.LightSource;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Deferred light markers owned by one chunk-generation context.
 *
 * <p>The context closes and drains this queue after its final marker-producing pass. The
 * synchronized boundary makes a racing enqueue linearizable: it either completes before close
 * and is present in that snapshot, or observes the closed state and fails. No marker can arrive
 * after the snapshot and wait forever in a discarded context.</p>
 */
final class LightTodoQueue {

    record Todo(BlockPos pos, LightSource source, boolean lit) { }

    private final int ownerChunkX;
    private final int ownerChunkZ;
    private final List<Todo> pending = new ArrayList<>();
    private boolean closed;

    LightTodoQueue(int ownerChunkX, int ownerChunkZ) {
        this.ownerChunkX = ownerChunkX;
        this.ownerChunkZ = ownerChunkZ;
    }

    synchronized void add(BlockPos pos, LightSource source, boolean lit) {
        if (closed) {
            throw new IllegalStateException("Cannot admit a light marker after the generation queue was drained");
        }
        if ((pos.getX() >> 4) != ownerChunkX || (pos.getZ() >> 4) != ownerChunkZ) {
            throw new IllegalArgumentException("Light marker " + pos + " does not belong to owner chunk "
                    + ownerChunkX + "," + ownerChunkZ);
        }
        pending.add(new Todo(pos, source, lit));
    }

    synchronized List<Todo> closeAndDrain() {
        if (closed) {
            throw new IllegalStateException("Generation light queue was already drained");
        }
        closed = true;
        List<Todo> snapshot = List.copyOf(pending);
        pending.clear();
        return snapshot;
    }
}
