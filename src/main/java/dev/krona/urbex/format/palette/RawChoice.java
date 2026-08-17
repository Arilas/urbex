package dev.krona.urbex.format.palette;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One alternative of a list: a node, the size it takes, and the condition it exists under.
 * <p>
 * {@code MODEL.046}: "Each entry in {@code choices} is a node carrying additionally {@code weight} or
 * {@code rest}, and optionally {@code when}." A {@code light_socket} placement list holds the same
 * thing, by {@code MODEL.071} and {@code WEIGHT.001} - one spelling of size, wherever a list appears.
 *
 * @param node the alternative itself; its keys are siblings of the size keys in the file, not nested
 * @param size present except on a {@code $spread} element, which is not a choice yet but a request for
 *             somebody else's ({@code REF.070}) and so states no size of its own. The brief for this
 *             task specified a non-optional {@code Size}; {@code REF.070}'s and {@code WEIGHT.017}'s
 *             {@code accept} fixtures both write a bare {@code {"$spread": …}} element, which such a
 *             record cannot hold.
 * @param when the load-time condition, if any ({@code WEIGHT.020})
 */
public record RawChoice(RawNode node, Optional<Size> size, Optional<When> when) {

    /** The keys a choice adds to the node keys it shares its object with. */
    public static final Set<String> OWN_KEYS = Set.of("share", "weight", "rest", "when");

    /**
     * A list of choices, with every size rule that can be decided without resolving anything.
     * <p>
     * <b>Why one codec for the whole list rather than {@code Codec.list} over a per-element codec.</b>
     * Two of the rules need the list. {@code DIAG.040} names the choice by index - "choice 3" is the
     * only way an author finds the choice in a list of seven near-identical lines - and an element
     * codec does not know its own index. {@code WEIGHT.013} and {@code WEIGHT.014} are properties of
     * the list itself: how many choices declare {@code rest}, and what the shares total.
     * <p>
     * <b>What is deliberately not checked here.</b> {@code WEIGHT.005} evaluates every size rule "on
     * the list as it stands after {@code $spread} expansion and after exclusion, never on the choices
     * as written", so a list carrying a {@code $spread} element or any {@code when} is left alone: its
     * remaining size arrives from a file this decode has not read, or leaves after a condition this
     * decode cannot evaluate. Checking what was written would refuse a correct file, which is the same
     * class of mistake as the version 1 validator reporting 45 warnings about a pack that was right.
     */
    public static Codec<List<RawChoice>> listCodec(Codec<RawNode> node) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<List<RawChoice>, T>> decode(DynamicOps<T> ops, T input) {
                DataResult<java.util.stream.Stream<Dynamic<T>>> elements =
                        new Dynamic<>(ops, input).asStreamOpt();
                if (elements.error().isPresent()) {
                    String message = elements.error().get().message();
                    return DataResult.error(() -> message);
                }
                List<Dynamic<T>> raw = elements.result().orElseThrow().toList();

                Diagnostics diagnostics = new Diagnostics();
                List<RawChoice> choices = new ArrayList<>();
                for (int index = 0; index < raw.size(); index++) {
                    Optional<RawChoice> choice = decodeElement(ops, node, raw.get(index), index,
                            diagnostics);
                    choice.ifPresent(choices::add);
                }
                if (choices.size() == raw.size()) {
                    checkList(choices, diagnostics);
                }
                Optional<String> error = diagnostics.asError();
                if (error.isPresent()) {
                    String message = error.get();
                    return DataResult.error(() -> message);
                }
                return DataResult.success(Pair.of(List.copyOf(choices), ops.empty()));
            }

            @Override
            public <T> DataResult<T> encode(List<RawChoice> input, DynamicOps<T> ops, T prefix) {
                List<T> encoded = new ArrayList<>();
                for (RawChoice choice : input) {
                    DataResult<T> element = encodeElement(ops, node, choice);
                    if (element.error().isPresent()) {
                        String message = element.error().get().message();
                        return DataResult.error(() -> message);
                    }
                    encoded.add(element.result().orElseThrow());
                }
                return ops.mergeToList(prefix, encoded);
            }

            @Override
            public String toString() {
                return "[" + node + " with a size]";
            }
        };
    }

    /** One element, or empty when it could not be decoded - in which case {@code diagnostics} says why. */
    private static <T> Optional<RawChoice> decodeElement(DynamicOps<T> ops, Codec<RawNode> node,
                                                        Dynamic<T> element, int index,
                                                        Diagnostics diagnostics) {
        int before = reported(diagnostics);

        Optional<When> when = Optional.empty();
        Optional<Dynamic<T>> whenValue = element.get("when").result();
        if (whenValue.isPresent()) {
            DataResult<When> decoded = When.CODEC.parse(whenValue.get());
            if (decoded.error().isPresent()) {
                diagnostics.nested(decoded.error().get().message());
            } else {
                when = decoded.result();
            }
        }

        // The size and condition keys are siblings of the node's own keys, so they are taken off
        // before the node sees the object: without that, the node's own key check (MODEL.004) would
        // report 'share' as a key a node does not have, which is true and useless.
        Dynamic<T> withoutOwnKeys = element;
        for (String key : OWN_KEYS) {
            withoutOwnKeys = withoutOwnKeys.remove(key);
        }
        DataResult<RawNode> decodedNode = node.parse(withoutOwnKeys);
        if (decodedNode.error().isPresent()) {
            diagnostics.nested(decodedNode.error().get().message());
            return Optional.empty();
        }
        RawNode value = decodedNode.result().orElseThrow();
        Optional<Size> size = readSize(element, index, value.spread().isPresent(), diagnostics);
        if (reported(diagnostics) > before) {
            return Optional.empty();
        }
        return Optional.of(new RawChoice(value, size, when));
    }

    /**
     * How many failures have been reported so far - the guard that keeps a half-decoded element out of
     * the list, so that {@link #checkList} never runs over a choice whose size is not the size the file
     * asked for. Counted rather than short-circuited because {@code DIAG.903} wants every problem this
     * element has, not the first.
     */
    private static int reported(Diagnostics diagnostics) {
        return diagnostics.all().size() + diagnostics.nestedMessages().size();
    }

    /**
     * The one size this element states, or empty when it states none.
     * <p>
     * {@code "rest": false} states no size, and is read as exactly that rather than as a malformed
     * one: {@code WEIGHT.012} defines the spelling as {@code "rest": true}, so writing {@code false}
     * is a true statement that this choice does not take the remainder. Alone it leaves the choice with
     * no size at all, which is then reported as declaring none of the three - not silently accepted,
     * which is what {@code VER.012}'s doctrine forbids for a key that means nothing where it stands.
     *
     * @param isSpread whether this element is a {@code $spread} ({@code REF.070}), which states no size
     *                 because it is replaced by elements that state their own
     */
    private static <T> Optional<Size> readSize(Dynamic<T> element, int index, boolean isSpread,
                                               Diagnostics diagnostics) {
        Optional<Dynamic<T>> share = element.get("share").result();
        Optional<Dynamic<T>> weight = element.get("weight").result();
        Optional<Dynamic<T>> rest = element.get("rest").result();

        int declared = (share.isPresent() ? 1 : 0) + (weight.isPresent() ? 1 : 0)
                + (rest.isPresent() ? 1 : 0);
        if (declared > 1) {
            diagnostics.error(Diag.DIAG_040, Diagnostics.DECODING_LOCATION, index,
                    "declares both 'weight', 'share' and 'rest'");
            return Optional.empty();
        }

        Optional<Size> size = Optional.empty();
        if (rest.isPresent()) {
            DataResult<Boolean> value = rest.get().asBoolean();
            if (value.error().isPresent()) {
                diagnostics.error(Diag.DIAG_040, Diagnostics.DECODING_LOCATION, index,
                        "'rest' " + rest.get().getValue() + " is not a boolean");
                return Optional.empty();
            }
            if (value.result().orElseThrow()) {
                size = Optional.of(new Size.Rest());
            }
        } else if (weight.isPresent()) {
            DataResult<Number> value = weight.get().asNumber();
            if (value.error().isPresent() || !isPositiveInteger(value.result().orElseThrow())) {
                diagnostics.error(Diag.DIAG_040, Diagnostics.DECODING_LOCATION, index,
                        "weight " + weight.get().getValue() + " is not a positive integer");
                return Optional.empty();
            }
            size = Optional.of(new Size.Weight(value.result().orElseThrow().intValue()));
        } else if (share.isPresent()) {
            DataResult<Number> value = share.get().asNumber();
            double fraction = value.result().map(Number::doubleValue).orElse(Double.NaN);
            if (value.error().isPresent() || !(fraction > 0 && fraction < 1)) {
                diagnostics.error(Diag.DIAG_040, Diagnostics.DECODING_LOCATION, index,
                        "share " + share.get().getValue() + " is not between 0 and 1");
                return Optional.empty();
            }
            size = Optional.of(new Size.Share(fraction));
        }

        if (size.isEmpty() && !isSpread) {
            diagnostics.error(Diag.DIAG_040, Diagnostics.DECODING_LOCATION, index,
                    "declares none of 'weight', 'share' and 'rest'");
        }
        return size;
    }

    /**
     * {@code WEIGHT.004}: "{@code weight} is a positive integer", both halves.
     * <p>
     * The whole-number half is checked here rather than left to {@link Number#intValue()}, which
     * truncates. {@code "weight": 2.7} used to decode to a weight of 2 - a datapack meaning something
     * other than what it says, which is the failure this format exists to remove, and which
     * {@code Versioned} already refuses for {@code version} for the same reason. Only fractions below 1
     * were caught, because truncating those reaches 0 and 0 was already refused.
     * <p>
     * A whole number written with a decimal point ({@code 2.0}) is accepted: JSON has one number type,
     * and a value equal to its own integer part is an integer however it was spelled.
     */
    private static boolean isPositiveInteger(Number value) {
        return value.intValue() > 0 && value.doubleValue() == value.intValue();
    }

    /**
     * {@code WEIGHT.013} and {@code WEIGHT.014}, on the list as written.
     * <p>
     * Shares are summed as {@link BigDecimal#valueOf(double)}, so the comparison against 1 is exact
     * rather than within a tolerance: {@code 0.7 + 0.4} is {@code 1.1000000000000001} in binary
     * floating point, and both the message and the decision would then be about the encoding rather
     * than about the file. {@code WEIGHT.052} asks for exact rational arithmetic where the slots are
     * apportioned; this is the same reasoning one step earlier.
     */
    private static void checkList(List<RawChoice> choices, Diagnostics diagnostics) {
        if (choices.isEmpty()) {
            // MODEL.045 reports an empty list, on the node that owns it - it is that node that is
            // weighted and has no choices, and the diagnostic names the node, not the list.
            return;
        }
        boolean deferred = choices.stream().anyMatch(choice ->
                choice.node().spread().isPresent() || choice.when().isPresent());
        if (deferred) {
            return;
        }

        long rests = choices.stream().filter(choice -> choice.size()
                .filter(size -> size instanceof Size.Rest).isPresent()).count();
        long weights = choices.stream().filter(choice -> choice.size()
                .filter(size -> size instanceof Size.Weight).isPresent()).count();
        if (rests > 1) {
            diagnostics.error(Diag.DIAG_041, Diagnostics.DECODING_LOCATION,
                    rests + " choices declare 'rest'");
        } else if (rests == 1 && weights > 0) {
            diagnostics.error(Diag.DIAG_041, Diagnostics.DECODING_LOCATION,
                    "'rest' is declared beside " + weights + " weighted choices");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (RawChoice choice : choices) {
            if (choice.size().orElse(null) instanceof Size.Share share) {
                total = total.add(BigDecimal.valueOf(share.fraction()));
            }
        }
        boolean somethingTakesTheRemainder = rests + weights > 0;
        if (somethingTakesTheRemainder) {
            if (total.compareTo(BigDecimal.ONE) >= 0) {
                diagnostics.error(Diag.DIAG_045, Diagnostics.DECODING_LOCATION, plain(total),
                        "Shares must leave something for the weight choices");
            }
        } else if (total.compareTo(BigDecimal.ONE) != 0) {
            diagnostics.error(Diag.DIAG_045, Diagnostics.DECODING_LOCATION, plain(total),
                    "Shares must total exactly 1 when nothing takes the remainder");
        }
    }

    private static String plain(BigDecimal total) {
        return total.stripTrailingZeros().toPlainString();
    }

    private static <T> DataResult<T> encodeElement(DynamicOps<T> ops, Codec<RawNode> node,
                                                   RawChoice choice) {
        DataResult<T> encoded = node.encodeStart(ops, choice.node());
        if (encoded.error().isPresent()) {
            return encoded;
        }
        Dynamic<T> element = new Dynamic<>(ops, encoded.result().orElseThrow());
        if (choice.size().isPresent()) {
            element = switch (choice.size().get()) {
                case Size.Share share -> element.set("share",
                        new Dynamic<>(ops, ops.createDouble(share.fraction())));
                case Size.Weight weight -> element.set("weight",
                        new Dynamic<>(ops, ops.createInt(weight.weight())));
                case Size.Rest ignored -> element.set("rest",
                        new Dynamic<>(ops, ops.createBoolean(true)));
            };
        }
        if (choice.when().isPresent()) {
            DataResult<T> when = When.CODEC.encodeStart(ops, choice.when().get());
            if (when.error().isPresent()) {
                return when;
            }
            element = element.set("when", new Dynamic<>(ops, when.result().orElseThrow()));
        }
        return DataResult.success(element.getValue());
    }
}
