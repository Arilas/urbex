package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything wrong with a datapack, collected before any of it is reported.
 *
 * <p>Asset resolution used to stop at the first broken file. That is the right <em>outcome</em> -
 * the world must not load - but the wrong report: an author with four typos fixed one, reloaded the
 * world, found the second, and went round again. Four world loads to learn four things the compiler
 * knew on the first (issue #56).</p>
 *
 * <p>Each problem carries where it was found - registry, then asset id - so a message like
 * "declares no 'filler'" is attributable without the reader having to guess which of thirty
 * buildings raised it. Problems are reported in a stable order (registry, then asset id) rather
 * than in the order the registries happened to be walked, so re-running the pass produces the same
 * text and two runs can be diffed.</p>
 */
public final class AssetDiagnostics {

    /**
     * @param registry the registry being resolved, e.g. {@code urbex/buildings}
     * @param asset    the asset that failed, or null when the problem belongs to the sweep rather
     *                 than to one entry
     * @param message  what is wrong, already phrased for an author
     */
    public record Problem(String registry, @Nullable Identifier asset, String message) {
        @Override
        public String toString() {
            return registry + (asset == null ? "" : " / " + asset) + ": " + message;
        }
    }

    private final List<Problem> problems = new ArrayList<>();

    public synchronized void record(String registry, @Nullable Identifier asset, String message) {
        problems.add(new Problem(registry, asset, message));
    }

    /**
     * Records a resolution failure, using the deepest message in the chain.
     *
     * <p>The wrappers above it say "Error getting resource x" at every level; what an author needs
     * is the innermost sentence, which names the field or the missing reference. The wrapping is
     * still in the log - this is the summary line, not the stack trace.</p>
     */
    public void record(String registry, @Nullable Identifier asset, Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        record(registry, asset, message == null ? root.toString() : message);
    }

    public synchronized boolean isEmpty() {
        return problems.isEmpty();
    }

    public synchronized int size() {
        return problems.size();
    }

    /** Every problem, in a stable order. */
    public synchronized List<Problem> problems() {
        return problems.stream()
                .sorted(Comparator.comparing(Problem::registry)
                        .thenComparing(problem -> problem.asset() == null ? "" : problem.asset().toString())
                        .thenComparing(Problem::message))
                .toList();
    }

    /** One block of text: a headline, then one line per problem. */
    public String format(String headline) {
        StringBuilder text = new StringBuilder(headline);
        for (Problem problem : problems()) {
            text.append("\n  - ").append(problem);
        }
        return text.toString();
    }

    /**
     * Refuses the world if anything is wrong, naming everything at once.
     *
     * @throws IllegalStateException listing every problem
     */
    public void throwIfAny() {
        if (isEmpty()) {
            return;
        }
        throw new IllegalStateException(format(
                size() + " Urbex asset problem(s) must be fixed before this world can load:"));
    }
}
