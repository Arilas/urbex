package dev.krona.urbex.gui.preview;

import javax.annotation.Nullable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one thread the world-creation preview computes on.
 *
 * <p>One is the right number: {@link PendingWork} keeps a single computation in flight and every new
 * request supersedes the last, so a pool would only ever have one busy thread and a queue of work
 * nobody wants any more.</p>
 *
 * <p>The thread is a daemon and is created on first use, so a player who never opens the Cities tab
 * never starts it and a player who does never has it hold the game open on exit. It is deliberately
 * not shut down when the screen closes: {@link #shutdown} exists for that, but the thread is idle
 * and cheap, and tearing it down while an abandoned computation is still running would be the one
 * way to make that computation's failure visible.</p>
 */
final class PreviewWorker {

    private PreviewWorker() {
    }

    @Nullable
    private static ExecutorService service;

    static synchronized Executor executor() {
        if (service == null) {
            service = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Urbex preview");
                thread.setDaemon(true);
                // Below the render thread on purpose: a preview is worth a frame of latency and
                // never worth one of the game's own.
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }
        return service;
    }

    /** Stops the worker. Not called in normal play; the daemon thread simply goes away on exit. */
    static synchronized void shutdown() {
        if (service != null) {
            service.shutdownNow();
            service = null;
        }
    }
}
