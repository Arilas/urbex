package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.ConditionRE;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class Condition {

    private final Identifier name;

    private final List<Pair<Predicate<ConditionContext>, Pair<Float, String>>> valueSelector = new ArrayList<>();

    public Condition(ConditionRE object) {
        name = object.getRegistryName();
        for (ConditionPart cp : object.getValues()) {
            float factor = cp.getFactor();
            String value = cp.getValue();
            Predicate<ConditionContext> test = ConditionContext.parseTest(cp);
            valueSelector.add(Pair.of(test, Pair.of(factor, value)));
        }
    }

    public String getName() {
        return DataTools.toName(name);
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
