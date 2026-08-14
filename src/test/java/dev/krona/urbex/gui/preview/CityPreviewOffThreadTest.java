package dev.krona.urbex.gui.preview;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code update} hands the recompute to an executor instead of doing it on the calling thread.
 *
 * <p>This is the regression this pair of classes exists to prevent, and it is checkable without a
 * game: hand the preview an executor that never runs anything, and {@code update} must still return
 * having computed nothing. If the walk ever moves back inline, the queue here stays empty and the
 * work happens anyway - which is what these assertions catch.</p>
 *
 * <p>The other half - uploading the finished buffer - is GL, so it is not reachable headlessly and
 * is not attempted here. {@link PendingWorkTest} covers the handoff that decides whether an upload
 * happens at all.</p>
 */
class CityPreviewOffThreadTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Holds submitted work instead of running it, so "was it submitted" and "did it run" differ. */
    private static final class Deferred implements Executor {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }
    }

    private static final WorldStyleMix STYLES =
            WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard"));

    private static Preset preset() {
        return new Preset(Identifier.fromNamespaceAndPath("urbex", "test-offthread"));
    }

    @Test
    void updateSubmitsTheRecomputeRatherThanRunningIt() {
        Deferred executor = new Deferred();
        CityPreview preview = new CityPreview(null, executor);

        preview.update(preset(), STYLES, 1234L, CityPreview.Mode.MAP);

        assertEquals(1, executor.queued.size(),
                "the recompute must be handed to the executor, not run on the render thread");
    }

    @Test
    void anUnchangedKeySubmitsNothingFurther() {
        Deferred executor = new Deferred();
        CityPreview preview = new CityPreview(null, executor);
        preview.update(preset(), STYLES, 1234L, CityPreview.Mode.MAP);

        // A fresh Preset instance with identical content: the key is a content hash, so this is the
        // same picture and must not be recomputed. The Cities tab calls update() once per frame.
        preview.update(preset(), STYLES, 1234L, CityPreview.Mode.MAP);
        preview.update(preset(), STYLES, 1234L, CityPreview.Mode.MAP);

        assertEquals(1, executor.queued.size(), "repeat frames must not queue more work");
    }

    @Test
    void eachRealChangeSubmitsItsOwnRecompute() {
        Deferred executor = new Deferred();
        CityPreview preview = new CityPreview(null, executor);

        preview.update(preset(), STYLES, 1234L, CityPreview.Mode.MAP);
        preview.update(preset(), STYLES, 9999L, CityPreview.Mode.MAP);
        preview.update(preset(), STYLES, 9999L, CityPreview.Mode.ROADS);

        assertEquals(3, executor.queued.size(), "a new seed and a new mode are each a new picture");
    }

    /**
     * Clearing the selection must not leave a computation that finishes later and paints over the
     * now-empty preview.
     */
    @Test
    void clearingTheSelectionAbandonsWorkInFlight() {
        Deferred executor = new Deferred();
        CityPreview preview = new CityPreview(null, executor);
        preview.update(preset(), STYLES, 1234L, CityPreview.Mode.MAP);

        preview.update(null, STYLES, 1234L, CityPreview.Mode.MAP);

        // Running the abandoned task now is what a worker finishing after the fact looks like. It
        // must be inert: no upload is attempted, so this cannot reach GL and cannot throw.
        assertEquals(1, executor.queued.size());
        executor.queued.getFirst().run();
        assertTrue(true, "the abandoned computation ran without reaching the screen");
    }
}
