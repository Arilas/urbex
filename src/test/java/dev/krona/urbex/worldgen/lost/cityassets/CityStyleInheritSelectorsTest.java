package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.CityStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.ObjectSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Selectors;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A city style's own selector lists replace the ones it inherits, rather than being appended to
 * them.
 * <p>
 * The bundled {@code urbex:citystyle_border} is what motivated this: it lists five buildings and no
 * multibuildings, and under the old append rule generated with thirteen building entries (its own
 * five, plus all eight of {@code citystyle_common}'s, so buildings 6-8 appeared at city borders and
 * 1-5 carried double weight) and all twelve of the parent's multibuildings.
 * <p>
 * These exercise {@link CityStyle#inheritSelectors} directly rather than {@code init()}, which
 * needs a level to resolve the parent by id; the merge itself needs no world.
 */
class CityStyleInheritSelectorsTest {

    private static ObjectSelector sel(String value) {
        return new ObjectSelector(1.0f, value, 0, Integer.MAX_VALUE, 0);
    }

    private static List<ObjectSelector> sels(String... values) {
        return java.util.Arrays.stream(values).map(CityStyleInheritSelectorsTest::sel).toList();
    }

    /** A style declaring only {@code buildings} and {@code multibuildings}; null means undeclared. */
    private static CityStyle style(List<ObjectSelector> buildings, List<ObjectSelector> multiBuildings,
                                   List<ObjectSelector> parks) {
        Selectors selectors = new Selectors(
                Optional.ofNullable(buildings),
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(parks),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(multiBuildings));
        CityStyleRE re = new CityStyleRE(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(selectors));
        return new CityStyle(re);
    }

    private static List<String> values(CityStyle style, CityStyle.Sel kind) {
        return style.selectorList(kind).stream().map(ObjectSelector::value).toList();
    }

    @Test
    void declaredListReplacesInheritedOneInsteadOfAppending() {
        CityStyle parent = style(sels("b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8"), null, null);
        CityStyle child = style(sels("b1", "b2", "b3", "b4", "b5"), null, null);

        child.inheritSelectors(parent);

        assertEquals(List.of("b1", "b2", "b3", "b4", "b5"), values(child, CityStyle.Sel.BUILDING),
                "a declared list is the whole list: no parent entries, no duplicates");
    }

    @Test
    void explicitlyEmptyListMeansEmpty() {
        CityStyle parent = style(null, sels("m1", "m2", "m3"), null);
        CityStyle child = style(null, List.of(), null);

        child.inheritSelectors(parent);

        assertTrue(values(child, CityStyle.Sel.MULTI_BUILDING).isEmpty(),
                "an explicitly empty list is a declaration of none, not an absence of one");
    }

    @Test
    void undeclaredListStillInheritsWhole() {
        CityStyle parent = style(null, null, sels("p1", "p2"));
        CityStyle child = style(sels("b1"), null, null);

        child.inheritSelectors(parent);

        assertEquals(List.of("p1", "p2"), values(child, CityStyle.Sel.PARK),
                "a list the child never mentions is inherited unchanged");
    }

    @Test
    void everySelectorKindIsCoveredByTheMerge() {
        CityStyle parent = style(sels("b1"), sels("m1"), sels("p1"));
        CityStyle child = style(null, null, null);

        child.inheritSelectors(parent);

        for (CityStyle.Sel kind : CityStyle.Sel.values()) {
            assertEquals(values(parent, kind), values(child, kind),
                    "an undeclared " + kind + " should inherit the parent's entries");
        }
    }
}
