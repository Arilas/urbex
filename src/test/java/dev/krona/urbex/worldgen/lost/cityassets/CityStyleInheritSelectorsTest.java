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
 * These build a two-entry {@code extends} chain (parent, child) and let {@link CityStyle}'s
 * constructor apply it root-first, rather than reaching for a level to resolve the parent by id -
 * the merge itself needs no world.
 */
class CityStyleInheritSelectorsTest {

    private static ObjectSelector sel(String value) {
        return new ObjectSelector(1.0f, value, 0, Integer.MAX_VALUE, 0);
    }

    private static List<ObjectSelector> sels(String... values) {
        return java.util.Arrays.stream(values).map(CityStyleInheritSelectorsTest::sel).toList();
    }

    /** A registry entry declaring only {@code buildings} and {@code multibuildings}; null means undeclared. */
    private static CityStyleRE re(List<ObjectSelector> buildings, List<ObjectSelector> multiBuildings,
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
        return new CityStyleRE(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(selectors));
    }

    private static List<String> values(CityStyle style, CityStyle.Sel kind) {
        return style.selectorList(kind).stream().map(ObjectSelector::value).toList();
    }

    @Test
    void declaredListReplacesInheritedOneInsteadOfAppending() {
        CityStyleRE parent = re(sels("b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8"), null, null);
        CityStyleRE child = re(sels("b1", "b2", "b3", "b4", "b5"), null, null);

        CityStyle merged = new CityStyle(List.of(parent, child));

        assertEquals(List.of("b1", "b2", "b3", "b4", "b5"), values(merged, CityStyle.Sel.BUILDING),
                "a declared list is the whole list: no parent entries, no duplicates");
    }

    @Test
    void explicitlyEmptyListMeansEmpty() {
        CityStyleRE parent = re(null, sels("m1", "m2", "m3"), null);
        CityStyleRE child = re(null, List.of(), null);

        CityStyle merged = new CityStyle(List.of(parent, child));

        assertTrue(values(merged, CityStyle.Sel.MULTI_BUILDING).isEmpty(),
                "an explicitly empty list is a declaration of none, not an absence of one");
    }

    @Test
    void undeclaredListStillInheritsWhole() {
        CityStyleRE parent = re(null, null, sels("p1", "p2"));
        CityStyleRE child = re(sels("b1"), null, null);

        CityStyle merged = new CityStyle(List.of(parent, child));

        assertEquals(List.of("p1", "p2"), values(merged, CityStyle.Sel.PARK),
                "a list the child never mentions is inherited unchanged");
    }

    @Test
    void everySelectorKindIsCoveredByTheMerge() {
        CityStyleRE parent = re(sels("b1"), sels("m1"), sels("p1"));
        CityStyleRE child = re(null, null, null);

        CityStyle parentResolved = new CityStyle(List.of(parent));
        CityStyle merged = new CityStyle(List.of(parent, child));

        for (CityStyle.Sel kind : CityStyle.Sel.values()) {
            assertEquals(values(parentResolved, kind), values(merged, kind),
                    "an undeclared " + kind + " should inherit the parent's entries");
        }
    }
}
