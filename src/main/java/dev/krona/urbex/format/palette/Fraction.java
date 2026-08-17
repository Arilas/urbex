package dev.krona.urbex.format.palette;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * An exact rational, for the one arithmetic {@code WEIGHT.052} says must not round.
 * <p>
 * "The distribution of a nested tree is computed as exact rational arithmetic over the whole tree, and
 * materialised into slots exactly once, at the root." Floating point cannot carry that: the shipped
 * workstation list writes {@code 0.20}, {@code 0.16}, {@code 0.05}, {@code 0.05}, {@code 0.04} and
 * {@code 0.02}, and summing those as {@code double} gives {@code 0.52000000000000002} - so a list whose
 * shares an author wrote to total exactly {@code 0.52} would be reported as totalling something else,
 * and the {@code rest} beside them would be short by a quantity that exists only in the encoding.
 * {@code RawChoice.checkList} already reached that conclusion one stage earlier, for the same sum, and
 * this is the same reasoning made a type so that the two cannot answer differently.
 * <p>
 * <b>{@link BigInteger} rather than a reduced {@code long} pair.</b> Both are exact; they differ in what
 * happens at the top of the range. A nested tree multiplies denominators at every level - a share of
 * {@code 0.001} four levels down is already {@code 10^12} - and a {@code long} pair reaching that point
 * either overflows silently, which is a wrong distribution nobody sees, or throws out of
 * {@link Math#multiplyExact}, which refuses a world with a stack trace instead of a diagnostic. Neither
 * is a behaviour any rule asks for, and this arithmetic runs once per weighted node at load, so the cost
 * of the safe choice is not measurable.
 *
 * @param numerator   the sign lives here
 * @param denominator always positive, and always reduced against the numerator
 */
public record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {

    public static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
    public static final Fraction ONE = new Fraction(BigInteger.ONE, BigInteger.ONE);

    public Fraction {
        if (denominator.signum() == 0) {
            throw new IllegalArgumentException("a fraction with denominator zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger common = numerator.gcd(denominator);
        if (!common.equals(BigInteger.ONE)) {
            numerator = numerator.divide(common);
            denominator = denominator.divide(common);
        }
    }

    /** {@code numerator / denominator}, reduced. */
    public static Fraction of(long numerator, long denominator) {
        return new Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    /** The whole number {@code value}. */
    public static Fraction of(long value) {
        return of(value, 1);
    }

    /**
     * The fraction a {@code share} states, recovered from the {@code double} the codec decoded.
     * <p>
     * {@code WEIGHT.004} says {@code share} "is a JSON number", and a JSON number arrives here as a
     * {@code double} - so the exactness {@code WEIGHT.052} requires has to start by recovering what the
     * file wrote. {@link BigDecimal#valueOf(double)} does that and {@code new BigDecimal(double)} does
     * not: the first goes through {@link Double#toString}, which produces the shortest decimal that
     * round-trips, so {@code 0.2} comes back as {@code 2/10} and then as {@code 1/5}; the second returns
     * the exact binary value, {@code 0.200000000000000011102230246251565404236316680908203125}, which is
     * a true statement about IEEE 754 and a false one about the file.
     * <p>
     * That is the same call {@code RawChoice.checkList} makes when it decides whether shares total 1, and
     * it must stay the same call: a share the decode measured as reaching 1 exactly and this measured as
     * falling short would be a list accepted by one stage and misapportioned by the next.
     */
    public static Fraction ofDecimal(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value);
        BigInteger unscaled = decimal.unscaledValue();
        int scale = decimal.scale();
        return scale >= 0
                ? new Fraction(unscaled, BigInteger.TEN.pow(scale))
                : new Fraction(unscaled.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
    }

    public Fraction plus(Fraction other) {
        return new Fraction(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Fraction minus(Fraction other) {
        return plus(new Fraction(other.numerator.negate(), other.denominator));
    }

    public Fraction times(Fraction other) {
        return new Fraction(numerator.multiply(other.numerator),
                denominator.multiply(other.denominator));
    }

    /** {@code this / other}; the caller has already established that {@code other} is not zero. */
    public Fraction dividedBy(Fraction other) {
        if (other.numerator.signum() == 0) {
            throw new IllegalArgumentException("division by zero");
        }
        return new Fraction(numerator.multiply(other.denominator),
                denominator.multiply(other.numerator));
    }

    public int signum() {
        return numerator.signum();
    }

    public boolean isZero() {
        return numerator.signum() == 0;
    }

    @Override
    public int compareTo(Fraction other) {
        // Denominators are positive by construction, so cross-multiplying cannot flip the comparison.
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    /**
     * How a diagnostic prints this ({@code DIAG.045}'s {@code <n>} slot).
     * <p>
     * As a decimal whenever the value has one, because that is how the file wrote it: an author who wrote
     * {@code 0.7} and {@code 0.4} must read "shares total 1.1" and not "shares total 11/10". A share is
     * always a terminating decimal - it is a JSON number - so the fallback is reached only by a total
     * that mixes in a weight's proportion, which no message this class serves does.
     */
    public String toPlainString() {
        try {
            return new BigDecimal(numerator).divide(new BigDecimal(denominator))
                    .stripTrailingZeros().toPlainString();
        } catch (ArithmeticException nonTerminating) {
            return numerator + "/" + denominator;
        }
    }

    @Override
    public String toString() {
        return toPlainString();
    }
}
