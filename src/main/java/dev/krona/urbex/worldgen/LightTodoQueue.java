package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.cityassets.LightSource;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Surviving deferred light placeholders owned by one chunk-generation context.
 *
 * <p>The context closes and drains this queue after its final marker-producing pass. The
 * synchronized boundary makes a racing enqueue linearizable: it either completes before close
 * and is present in that snapshot, or observes the closed state and fails. No marker can arrive
 * after the snapshot and wait forever in a discarded context.</p>
 */
final class LightTodoQueue {

    record Todo(BlockPos pos, LightSource source, boolean lit, PlacementOrigin origin) {
        Todo {
            pos = pos.immutable();
        }

        Todo(BlockPos pos, LightSource source, boolean lit) {
            this(pos, source, lit, null);
        }
    }

    private final int ownerChunkX;
    private final int ownerChunkZ;
    private final Long2ObjectLinkedOpenHashMap<Todo> pending = new Long2ObjectLinkedOpenHashMap<>();
    private boolean closed;

    LightTodoQueue(int ownerChunkX, int ownerChunkZ) {
        this.ownerChunkX = ownerChunkX;
        this.ownerChunkZ = ownerChunkZ;
    }

    synchronized void add(BlockPos pos, LightSource source, boolean lit) {
        add(pos, source, lit, null);
    }

    synchronized void add(BlockPos pos, LightSource source, boolean lit, PlacementOrigin origin) {
        add(new Todo(pos, source, lit, origin));
    }

    synchronized void checkAdmission(BlockPos pos) {
        if (closed) {
            throw new IllegalStateException("Cannot admit a light marker after the generation queue was drained");
        }
        if ((pos.getX() >> 4) != ownerChunkX || (pos.getZ() >> 4) != ownerChunkZ) {
            throw new IllegalArgumentException("Light marker " + pos + " does not belong to owner chunk "
                    + ownerChunkX + "," + ownerChunkZ);
        }
    }

    /** Called only for an accepted placeholder write; the final accepted source owns the position. */
    synchronized void add(Todo todo) {
        checkAdmission(todo.pos());
        pending.put(todo.pos().asLong(), todo);
    }

    /** Ordinary writes continue after drain, when there are no queued placeholders to cancel. */
    synchronized void remove(long position) {
        pending.remove(position);
    }

    /** Discard unfinished work when the owning driver is reset or published. */
    synchronized void discard() {
        pending.clear();
        closed = true;
    }

    synchronized List<Todo> closeAndDrain() {
        if (closed) {
            throw new IllegalStateException("Generation light queue was already drained");
        }
        closed = true;
        List<Todo> snapshot = List.copyOf(pending.values());
        pending.clear();
        return snapshot;
    }
}
