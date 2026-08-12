package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What a {@code /reload} may and may not do to a chunk that is already generating (issue #128).
 * <p>
 * These are the rules {@link RuntimeRepositoryTest} used to hold for whole {@link DimensionRuntime}s,
 * narrowed to the one thing a reload can actually change. A generation captures a
 * {@link TagSnapshot} once, at its start; a reload publishes a new one; the generation in flight
 * must finish against the one it captured, and the next one must see the new one. Anything weaker
 * puts one slice of a building on the old tag membership and the next slice on the new one.
 */
class TagEpochTest {

    @BeforeAll
    static void bootstrap() {
        // TagSnapshot.capture reads the block registry's tag bindings. Nothing binds a block tag
        // without a running server, so every snapshot here is empty - which is fine, because these
        // tests are about which instance a reader gets, not what is in it.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static TagSnapshot snapshot() {
        return TagSnapshot.capture(AssetSnapshot.empty());
    }

    @Test
    void aReaderKeepsTheSnapshotItWasHanded() {
        TagSnapshot first = snapshot();
        TagEpoch epoch = new TagEpoch(first);
        TagSnapshot inFlight = epoch.current();

        epoch.publish(snapshot());

        assertSame(first, inFlight, "a chunk already generating finishes on the epoch it captured");
        assertNotSame(inFlight, epoch.current(), "and the next chunk starts on the new one");
    }

    @Test
    void aSwapDecidesWhatTheNextReaderGets() {
        TagEpoch epoch = new TagEpoch(snapshot());
        TagSnapshot reloaded = snapshot();

        epoch.publish(reloaded);

        assertSame(reloaded, epoch.current());
    }

    /**
     * A reload landing while chunks are generating. Every read must answer with a complete snapshot
     * - never null, never one being filled in - which is the difference between swapping a finished
     * epoch and the clear-then-refill the old dirty-counter protocol performed.
     */
    @Test
    void aSwapOverlappingGenerationNeverExposesAnEmptySlot() throws Exception {
        TagEpoch epoch = new TagEpoch(snapshot());
        CountDownLatch readerStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<TagSnapshot>> readers = executor.submit(() -> {
                readerStarted.countDown();
                List<TagSnapshot> seen = new ArrayList<>();
                for (int i = 0; i < 10_000; i++) {
                    seen.add(epoch.current());
                }
                return seen;
            });
            Future<?> reloads = executor.submit(() -> {
                readerStarted.await();
                for (int i = 0; i < 200; i++) {
                    epoch.publish(snapshot());
                }
                return null;
            });

            reloads.get();
            for (TagSnapshot seen : readers.get()) {
                assertNotNull(seen, "a read during a reload must never find the epoch empty");
            }
        }
    }
}
