package dev.krona.urbex.format.palette;

/**
 * How large a share of its list one alternative takes.
 * <p>
 * {@code WEIGHT.001}: a choice's size is stated by {@code share}, {@code weight} or {@code rest} -
 * "Each has one spelling, one meaning, and one reading wherever it appears". Version 1 had two
 * spellings on two scales read by two algorithms: {@code random} in {@code blocks} and
 * {@code unlitBlocks}, distributed over 128 slots; {@code weight} on light candidates, drawn by a
 * ticket walk. They appeared in adjacent entries of the same file, and {@code random: 0} was legal
 * where {@code weight: 0} was refused.
 * <p>
 * Sealed, and with no fourth alternative, because {@code WEIGHT.003} says a choice carries exactly one
 * of the three. A {@code $spread} element carries none - it is not a choice yet, only a request for
 * somebody else's - which is why {@link RawChoice} holds this as an {@code Optional} rather than
 * modelling absence as a fourth case here.
 */
public sealed interface Size permits Size.Share, Size.Weight, Size.Rest {

    /**
     * {@code WEIGHT.010}: an exact fraction of the node, strictly between 0 and 1.
     *
     * @param fraction the share, as written; no palette file states a denominator ({@code WEIGHT.004})
     */
    record Share(double fraction) implements Size {
    }

    /**
     * {@code WEIGHT.011}: a relative part of whatever the shares leave.
     *
     * @param weight a positive integer; zero and negative are refused by {@code WEIGHT.002}, because
     *               a choice an author wrote and weighted is a choice they want to see
     */
    record Weight(int weight) implements Size {
    }

    /**
     * {@code WEIGHT.012}: the sole {@code weight} choice written without a number - it takes
     * everything the shares leave.
     */
    record Rest() implements Size {
    }
}
