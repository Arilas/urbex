package dev.krona.urbex.format;

import com.mojang.serialization.DataResult;

import java.util.Optional;
import java.util.function.Function;

/**
 * A value, or the catalogue diagnostic that refused it.
 * <p>
 * <b>Why not {@link DataResult}.</b> A {@code DataResult}'s failure is a string, and a string has lost
 * which catalogue row produced it. That was survivable while every rejection happened inside a codec -
 * a codec must hand its failure back as a string anyway - and it stopped being survivable when
 * resolution began reporting into a {@link Diagnostics}: five rows ({@code DIAG.030}, {@code DIAG.034},
 * {@code DIAG.036}, {@code DIAG.037}, {@code DIAG.039}) reached the collector through
 * {@link Diagnostics#nested}, whose whole meaning is "a failure that is not a catalogue row", and
 * {@link Diagnostics#all()} stopped being the list of every catalogue diagnostic recorded. A test that
 * has to add {@code all()} and {@code nestedMessages()} together to state its claim is the symptom.
 * <p>
 * So a step that can fail with a <em>named</em> diagnostic returns one of these, and the caller decides
 * where it goes: {@link #reportInto} for a stage that collects ({@code DIAG.903}), {@link #asDataResult}
 * for one that must hand it back through a codec. The row survives either way.
 *
 * @param <T> what the step produces when it succeeds
 */
public sealed interface Outcome<T> permits Outcome.Ok, Outcome.Failed {

    /** The step produced a value. */
    record Ok<T>(T value) implements Outcome<T> {
    }

    /**
     * The step failed, with the row that says so and the message already formatted.
     * <p>
     * Formatted here rather than at the reporting site because the arguments - which half of a pointer
     * failed, which tier was searched - are known here and nowhere else.
     */
    record Failed<T>(Diag diag, String message) implements Outcome<T> {
    }

    static <T> Outcome<T> ok(T value) {
        return new Ok<>(value);
    }

    /** Fails with {@code diag}, formatted with {@code args} in the order the row declares them. */
    static <T> Outcome<T> failed(Diag diag, Object... args) {
        return new Failed<>(diag, diag.message(args));
    }

    /** Re-fails with the same row and message, at a different value type. */
    default <R> Outcome<R> refail() {
        return switch (this) {
            case Ok<T> ignored -> throw new IllegalStateException("refail on a successful outcome");
            case Failed<T> failed -> new Failed<>(failed.diag(), failed.message());
        };
    }

    /** The value, or empty - for a caller that has already dealt with the failure, or does not care. */
    default Optional<T> result() {
        return this instanceof Ok<T> ok ? Optional.of(ok.value()) : Optional.empty();
    }

    /**
     * Records the failure into {@code diagnostics} and returns the value.
     * <p>
     * The whole point of this type: the diagnostic arrives at the collector with its row attached, so
     * {@link Diagnostics#all()} is what its javadoc says it is.
     */
    default Optional<T> reportInto(Diagnostics diagnostics) {
        if (this instanceof Failed<T> failed) {
            diagnostics.errorAlreadyFormatted(failed.diag(), failed.message());
            return Optional.empty();
        }
        return result();
    }

    /** For a caller inside a codec, which can only hand a failure back as a string. */
    default DataResult<T> asDataResult() {
        return switch (this) {
            case Ok<T> ok -> DataResult.success(ok.value());
            case Failed<T> failed -> DataResult.error(failed::message);
        };
    }

    default <R> Outcome<R> map(Function<T, R> mapping) {
        return this instanceof Ok<T> ok ? new Ok<>(mapping.apply(ok.value())) : refail();
    }
}
