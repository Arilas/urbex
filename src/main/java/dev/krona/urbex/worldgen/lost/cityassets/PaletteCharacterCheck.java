package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Whether the characters a part's slices use will resolve where the part is used.
 *
 * <p>A missing character is {@code "Could not find entry 'x' in the palette for part 'y'!"} thrown
 * from a worldgen worker, on the first chunk that places the part - which for a rarely-selected
 * building can be a long way into a world (issue #56).</p>
 *
 * <h2>The palette is built, not guessed</h2>
 *
 * <p>Every candidate merge is assembled with {@link CompiledPalette}'s own constructor, in the same
 * order {@code CityGenerator.computePalette} uses: style palette, then the building's, then the
 * part's. Reimplementing the merge would mean two definitions of what a character resolves to, and
 * the validator's would be the one that drifts - which is precisely the "guesses at the merge"
 * failure the sequencing note on #56 warned about.</p>
 *
 * <h2>Two answers, because there are two defects</h2>
 *
 * <p>A style's palette is <em>one choice per {@code randompalettes} group</em>, so a part has as many
 * possible palettes as the product of the group sizes. Checking one arbitrary selection would pass
 * characters that only some worlds get; enumerating the product is exponential and unnecessary:</p>
 *
 * <ul>
 *   <li><strong>Undefined everywhere</strong> - not present even when every choice of every group is
 *       merged in at once. No selection can place it, so this is a load error.</li>
 *   <li><strong>Undefined in some selections</strong> - present in that everything-merge, but not
 *       guaranteed: some worlds get it and some do not. Reported as a warning rather than a refusal,
 *       because packs that ship this generate correctly most of the time today and refusing them
 *       would be this check inventing a rule rather than reporting a break.</li>
 * </ul>
 *
 * <p>The second answer is found by <strong>constructing a witness</strong> rather than by reasoning
 * about which palette defines what. For each group and each choice in it, the character is looked up
 * in the merge of that one choice with <em>every</em> choice of every other group. If it is missing
 * even there, then it is missing for every selection that picks that choice - the other groups were
 * given more than any real selection would - so a failing selection provably exists. Nothing is
 * reported without one, so the warning cannot fire on a pack that always works.</p>
 *
 * <p>That construction is not an optimisation, it is the correctness fix. Testing a choice on its own
 * instead reports every {@code frompalette} that points at a character another group supplies -
 * {@code urbex:glass_side_variant_glass} maps {@code '@'} to {@code 'a'} and nothing else - which is
 * the shipped pack's own idiom, and made this check produce 45 warnings about a pack that is
 * correct.</p>
 */
final class PaletteCharacterCheck {

    private PaletteCharacterCheck() {
    }

    static void check(BuildingPart part, PartUsage usage, AssetDiagnostics diagnostics) {
        if (usage.style() == null) {
            return;
        }
        check("urbex:parts", part.getId(), charactersUsedBy(part), usage.style(),
                usage.building() == null ? null : usage.building().getLocalPalette(),
                part.getLocalPalette(), describe(usage) + " uses", diagnostics);
    }

    /**
     * A city style's own character fields - {@code streetblock}, {@code grassblock} and the rest.
     * <p>
     * They are the same question as a part's characters and were the same crash, from a different
     * line: the generator resolves them against the chunk's palette too. There is no part or building
     * layer here, because these are placed on the street rather than inside anything.
     */
    static void checkCityStyle(CityStyle cityStyle, Style style, AssetDiagnostics diagnostics) {
        Map<Character, List<String>> fields = new LinkedHashMap<>();
        declare(fields, "streetblock", cityStyle.getStreetBlock());
        declare(fields, "streetbaseblock", cityStyle.getStreetBaseBlock());
        declare(fields, "streetvariantblock", cityStyle.getStreetVariantBlock());
        declare(fields, "borderblock", cityStyle.getBorderBlock());
        declare(fields, "wallblock", cityStyle.getWallBlock());
        declare(fields, "railmainblock", cityStyle.getRailMainBlock());
        declare(fields, "grassblock", cityStyle.getGrassBlock());
        declare(fields, "ironbarsblock", cityStyle.getIronbarsBlock());
        declare(fields, "glowstoneblock", cityStyle.getGlowstoneBlock());
        declare(fields, "leavesblock", cityStyle.getLeavesBlock());
        declare(fields, "rubbledirtblock", cityStyle.getRubbleDirtBlock());
        declare(fields, "parkelevationblock", cityStyle.getParkElevationBlock());
        declare(fields, "corridorroofblock", cityStyle.getCorridorRoofBlock());
        declare(fields, "corridorglassblock", cityStyle.getCorridorGlassBlock());
        if (fields.isEmpty()) {
            return;
        }
        namedFields = fields;
        try {
            check("urbex:citystyles", cityStyle.getId(), new TreeSet<>(fields.keySet()), style,
                    null, null, "declares", diagnostics);
        } finally {
            namedFields = null;
        }
    }

    private static void declare(Map<Character, List<String>> fields, String name, @Nullable Character c) {
        if (c != null) {
            fields.computeIfAbsent(c, key -> new ArrayList<>()).add(name);
        }
    }

    /**
     * Which field each character came from, for the message, while a city style is being checked.
     * A thread local is not needed - compilation is single-threaded - and threading it through every
     * signature would put a parameter on the part path that only the city-style path ever reads.
     */
    @Nullable
    private static Map<Character, List<String>> namedFields;

    private static void check(String registry, Identifier asset, SortedSet<Character> used, Style style,
                              @Nullable Palette building, @Nullable Palette local, String verb,
                              AssetDiagnostics diagnostics) {
        if (used.isEmpty()) {
            return;
        }
        List<List<Pair<Float, Palette>>> groups = style.paletteChoices();
        CompiledPalette everything = merge(groups.stream().flatMap(List::stream)
                .map(Pair::getRight).toList(), building, local);

        SortedSet<Character> never = new TreeSet<>();
        SortedSet<Character> sometimes = new TreeSet<>();
        for (char c : used) {
            if (!everything.isDefined(c)) {
                never.add(c);
            } else if (hasFailingSelection(c, groups, building, local)) {
                sometimes.add(c);
            }
        }

        if (!never.isEmpty()) {
            diagnostics.record(registry, asset, verb + " character(s) " + quote(never)
                    + " that no palette there defines, so generating would fail on the first chunk "
                    + "that needs one");
        }
        if (!sometimes.isEmpty()) {
            diagnostics.warn(registry, asset, verb + " character(s) " + quote(sometimes)
                    + " that only some 'randompalettes' choices of '" + style.getName()
                    + "' define, so whether it generates depends on the draw");
        }
    }

    /**
     * The merge the generator would build, in the generator's order: the style's palettes first, then
     * the building's, then the part's, so a part's own entry wins over what it is placed into.
     */
    private static CompiledPalette merge(List<Palette> stylePalettes, @Nullable Palette building,
                                         @Nullable Palette part) {
        List<Palette> all = new ArrayList<>(stylePalettes);
        if (building != null) {
            all.add(building);
        }
        if (part != null) {
            all.add(part);
        }
        return new CompiledPalette(all.toArray(new Palette[0]));
    }

    /**
     * Whether some selection provably fails to define {@code c}.
     *
     * <p>The witness is one choice plus everything every other group offers. That is more than any
     * real selection gets, so a character missing from it is missing from every selection containing
     * that choice - which makes this sound in the only direction that matters: nothing is reported
     * without a selection that really breaks.</p>
     */
    private static boolean hasFailingSelection(char c, List<List<Pair<Float, Palette>>> groups,
                                               @Nullable Palette building, @Nullable Palette part) {
        for (int g = 0; g < groups.size(); g++) {
            for (Pair<Float, Palette> choice : groups.get(g)) {
                List<Palette> witness = new ArrayList<>();
                for (int other = 0; other < groups.size(); other++) {
                    if (other == g) {
                        witness.add(choice.getRight());
                    } else {
                        groups.get(other).forEach(alternative -> witness.add(alternative.getRight()));
                    }
                }
                if (!merge(witness, building, part).isDefined(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SortedSet<Character> charactersUsedBy(BuildingPart part) {
        SortedSet<Character> used = new TreeSet<>();
        for (char[] slice : part.getVslices()) {
            if (slice == null) {
                continue;
            }
            for (char c : slice) {
                used.add(c);
            }
        }
        return used;
    }

    private static String describe(PartUsage usage) {
        return "as used by '" + usage.owner() + "' (" + usage.field() + ")"
                + (usage.building() == null ? "" : " inside building '" + usage.building().getName() + "'")
                + " under style '" + usage.style().getName() + "', this part";
    }

    /** Quotes each character, naming the field it was written in when there is one. */
    private static String quote(SortedSet<Character> characters) {
        Map<Character, List<String>> fields = namedFields;
        List<String> quoted = new ArrayList<>(characters.size());
        for (char c : characters) {
            List<String> names = fields == null ? null : fields.get(c);
            quoted.add("'" + c + "'" + (names == null ? "" : " (" + String.join(", ", names) + ")"));
        }
        return String.join(", ", quoted);
    }
}
