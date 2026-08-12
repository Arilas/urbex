package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.ConditionDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class Condition {

    private final Identifier name;

    private final List<Pair<Predicate<ConditionContext>, Pair<Float, String>>> valueSelector = new ArrayList<>();
    /**
     * The entries this was folded from, kept beside the compiled predicates rather than instead of
     * them.
     * <p>
     * {@link ConditionContext#parseTest} turns each entry's matchers into one closure chain, tested
     * once per draw - that is the right shape and it stays. But a closure cannot be asked what it
     * matches on, so a condition naming a part no datapack registers used to be invisible: the
     * predicate simply never fired, and nothing said why. Keeping the entries costs one reference and
     * lets {@link AssetGraph} report it (issue #56).
     */
    private final List<ConditionPart> entries;

    /**
     * Builds a fully resolved condition from its {@code extends} chain, root first: a declared
     * {@code values} replaces the inherited list unless it opts into appending, and an absent one
     * inherits it unchanged. A chain where nothing declares {@code values} is a load error, since
     * the condition would silently hand back null for every draw.
     */
    public Condition(Identifier id, List<ConditionDefinition> chainRootFirst) {
        name = id;
        List<ConditionPart> values = new ArrayList<>();
        boolean anyValues = false;
        for (ConditionDefinition object : chainRootFirst) {
            if (object.getValues() != null) {
                Mergeable.apply(values, object.getValues());
                anyValues = true;
            }
        }
        Resolved.require(anyValues ? values : null, name, "values");
        entries = List.copyOf(values);
        for (ConditionPart cp : values) {
            float factor = cp.getFactor();
            String value = cp.getValue();
            Predicate<ConditionContext> test = ConditionContext.parseTest(cp);
            valueSelector.add(Pair.of(test, Pair.of(factor, value)));
        }
    }

    /** What each entry matched on and hands back, for the load-time walk. */
    List<ConditionPart> entries() {
        return entries;
    }

    /** The fully-qualified id, e.g. {@code "urbex:chestloot"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    public String getRandomValue(RandomSource random, ConditionContext info) {
        List<Pair<Float, String>> values = new ArrayList<>();
        for (Pair<Predicate<ConditionContext>, Pair<Float, String>> pair : valueSelector) {
            if (pair.getLeft().test(info)) {
                values.add(pair.getRight());
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        Pair<Float, String> randomFromList = Tools.getRandomFromList(random, values, Pair::getLeft);
        if (randomFromList == null) {
            return null;
        } else {
            return randomFromList.getRight();
        }
    }
}
