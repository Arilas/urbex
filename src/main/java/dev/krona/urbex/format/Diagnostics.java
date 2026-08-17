package dev.krona.urbex.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A collector for the diagnostics one decode produced.
 * <p>
 * {@code DIAG.903} is the reason this is a collector and not a throw: "Diagnostics are collected and
 * reported together, not thrown at the first failure". A palette with four misspelt keys is four
 * lines the author fixes in one pass; reporting the first and stopping is four load-fail-edit cycles,
 * which is what version 1 did with every field it checked at all.
 * <p>
 * {@code DIAG.904} is the reason {@link Level} has exactly two constants and no room for a third: a
 * warning does not refuse the world and an error does, and every diagnostic is one or the other. A
 * third level - "info", "hint" - would be a diagnostic nobody has to act on and nobody reads, which
 * is the state the version 1 validator reached with 45 warnings about a correct pack.
 */
public final class Diagnostics {

    /**
     * What stands where {@code 08-errors.md} §2 puts {@code <asset>} when a codec is the one
     * reporting.
     * <p>
     * A {@link com.mojang.serialization.Codec} is handed a document, not a registry id: nothing in a
     * decode knows which asset it is decoding, and {@code DIAG.902}'s "names the asset id, and
     * additionally the source file path when one is known" is therefore only met at the loader stage
     * above it, which does know both. Until that stage exists this is what the slot holds, and it is
     * a deliberately plain phrase rather than an id-shaped placeholder that a reader might search
     * their pack for.
     */
    public static final String DECODING_LOCATION = "this palette";

    /** {@code DIAG.904}: an error refuses the world, a warning does not, and there is no third. */
    public enum Level {
        ERROR, WARN
    }

    /** One reported diagnostic: which catalogue row, at which level, and the formatted message. */
    public record Entry(Diag diag, Level level, String message) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<String> nested = new ArrayList<>();

    /** Records {@code diag} as an error, formatted with {@code args}. */
    public void error(Diag diag, Object... args) {
        entries.add(new Entry(diag, Level.ERROR, diag.message(args)));
    }

    /** Records {@code diag} as a warning, formatted with {@code args}. */
    public void warn(Diag diag, Object... args) {
        entries.add(new Entry(diag, Level.WARN, diag.message(args)));
    }

    /**
     * Records a failure that is not a catalogue row.
     * <p>
     * Two kinds reach here. One is a message a nested codec already produced, which may itself be a
     * catalogue message from a deeper level - it travels as text so that the diagnostic reported
     * three levels down is the diagnostic the author reads, rather than being replaced by a summary
     * of it. The other is a plain type error from DFU ({@code "Not a string"}), which the catalogue
     * deliberately does not cover: {@code 08-errors.md} enumerates the rejections the <em>format</em>
     * performs, and "this value is a number where a string was expected" is a rejection the codec
     * performs on any input at all.
     * <p>
     * These are not {@link Entry Entries}: an entry names the catalogue row it came from, and these
     * have none. They count as fatal, and they appear in {@link #asError()}.
     */
    public void nested(String message) {
        nested.add(message);
    }

    /** Whether anything recorded here refuses the load. */
    public boolean hasFatal() {
        return !nested.isEmpty() || entries.stream().anyMatch(entry -> entry.level() == Level.ERROR);
    }

    /** Every catalogue diagnostic recorded, in the order it was recorded. */
    public List<Entry> all() {
        return List.copyOf(entries);
    }

    /** Every non-catalogue failure recorded, in the order it was recorded; see {@link #nested}. */
    public List<String> nestedMessages() {
        return List.copyOf(nested);
    }

    /**
     * Every error as one message, or empty when nothing fatal was recorded.
     * <p>
     * The join is how {@code DIAG.903} survives the trip through a {@link
     * com.mojang.serialization.DataResult}, which carries one string: a decode reports all of its
     * errors or none of them, and the ones it drops here would be the ones an author never learns
     * about. Warnings are not included - by {@code DIAG.904} they do not refuse the load, so putting
     * one in a decode failure would turn it into one.
     */
    public Optional<String> asError() {
        if (!hasFatal()) {
            return Optional.empty();
        }
        List<String> messages = new ArrayList<>();
        entries.stream().filter(entry -> entry.level() == Level.ERROR)
                .map(Entry::message).forEach(messages::add);
        messages.addAll(nested);
        return Optional.of(String.join(" ", messages));
    }
}
