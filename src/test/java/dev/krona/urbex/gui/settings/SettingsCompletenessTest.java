package dev.krona.urbex.gui.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry is the metadata backbone every later editor task builds on, so this test pins the two properties
 * that make it trustworthy: every user-editable {@link Preset} field is described exactly once, and every
 * descriptor points at a field (and lang keys) that actually exist. Reflection over the preset is what keeps the
 * registry honest when someone adds a field later — a new field with no descriptor fails here.
 */
class SettingsCompletenessTest {

    /**
     * Public {@code Preset} fields that are deliberately NOT surfaced as editable settings. Each needs a
     * one-line justification; keep this list tiny — the point of the test is that almost everything is covered.
     * {@code worldStyle} needs no entry here (unlike the old runtime-generated profile format): Task 4
     * removed it from {@link Preset} entirely - worldStyle is a first-class value orthogonal to the
     * preset, not one of its fields.
     */
    private static final Set<String> EXCLUDED = Set.of(
            // Internal world-edit flag toggled by the edit-mode tooling, not a world-generation knob.
            "EDITMODE"
    );

    private static List<Field> editableFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : PresetDraft.class.getFields()) { // getFields() = public members only
            if (Modifier.isStatic(f.getModifiers())) {
                continue; // category-id constants etc.
            }
            if (EXCLUDED.contains(f.getName())) {
                continue;
            }
            fields.add(f);
        }
        return fields;
    }

    /**
     * The curated settings whose sole home is {@link SettingCategory#GENERAL} (spec §3): the highest-impact
     * knobs, removed from their former categories so nothing renders twice. Pinned here so the "no duplication"
     * model is a checked property, not a comment.
     */
    private static final Set<String> GENERAL_KEYS = Set.of(
            "CITY_CHANCE", "CITY_MINRADIUS", "CITY_MAXRADIUS",
            "BUILDING_MINFLOORS", "BUILDING_MAXFLOORS", "RUIN_CHANCE",
            "EXPLOSION_CHANCE", "MINI_EXPLOSION_CHANCE",
            "LOOT_DENSITY", "LIGHTING_DENSITY", "LANDSCAPE_TYPE"
    );

    @Test
    void everyEditableFieldHasExactlyOneDescriptor() {
        Map<String, Integer> counts = new HashMap<>();
        for (SettingDescriptor d : Settings.ALL) {
            counts.merge(d.key(), 1, Integer::sum);
        }

        Set<String> fieldNames = new TreeSet<>();
        for (Field f : editableFields()) {
            fieldNames.add(f.getName());
        }

        // Every editable field is described exactly once - no duplicates (spec §3).
        Set<String> missing = new TreeSet<>();
        Set<String> duplicated = new TreeSet<>();
        for (String name : fieldNames) {
            int c = counts.getOrDefault(name, 0);
            if (c == 0) {
                missing.add(name);
            } else if (c > 1) {
                duplicated.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "Preset fields with no descriptor: " + missing);
        assertTrue(duplicated.isEmpty(), "Preset fields with more than one descriptor: " + duplicated);
    }

    @Test
    void everyDescriptorKeyIsARealEditableField() {
        Set<String> editable = new TreeSet<>();
        for (Field f : editableFields()) {
            editable.add(f.getName());
        }
        Set<String> allFieldNames = new HashSet<>();
        for (Field f : PresetDraft.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                allFieldNames.add(f.getName());
            }
        }

        Set<String> unknown = new TreeSet<>();
        Set<String> pointsAtExcluded = new TreeSet<>();
        for (SettingDescriptor d : Settings.ALL) {
            if (!allFieldNames.contains(d.key())) {
                unknown.add(d.key());
            } else if (!editable.contains(d.key())) {
                pointsAtExcluded.add(d.key());
            }
        }
        assertTrue(unknown.isEmpty(), "Descriptor keys that are not public PresetDraft fields: " + unknown);
        assertTrue(pointsAtExcluded.isEmpty(), "Descriptor keys that point at an EXCLUDED field: " + pointsAtExcluded);
    }

    @Test
    void generalCategoryHoldsExactlyTheCuratedSet() {
        Set<String> generalKeys = new TreeSet<>();
        for (SettingDescriptor d : Settings.byCategory(SettingCategory.GENERAL)) {
            generalKeys.add(d.key());
        }
        assertEquals(new TreeSet<>(GENERAL_KEYS), generalKeys,
                "GENERAL category must hold exactly the curated set (no more, no less)");
    }

    @Test
    void sliderBoundsAreSane() {
        for (SettingDescriptor d : Settings.ALL) {
            if (isSliderLike(d.kind())) {
                assertTrue(d.min() < d.max(),
                        "slider " + d.key() + " must have min < max (was " + d.min() + ".." + d.max() + ")");
                if (d.logScale()) {
                    assertTrue(d.min() > 0,
                            "log-scale slider " + d.key() + " must have min > 0 (was " + d.min() + ")");
                }
            }
        }
    }

    /** {@link ControlKind#SLIDER} and the {@link ControlKind#CHANCE_PERLIN} composite both carry slider bounds. */
    private static boolean isSliderLike(ControlKind kind) {
        return kind == ControlKind.SLIDER || kind == ControlKind.CHANCE_PERLIN;
    }

    /**
     * {@link SettingControls} builds a {@link LogValueMapper} from every {@code logScale} slider's
     * {@code (min, max)} at widget-creation time; that class needs GL to instantiate, so it cannot be
     * exercised directly here. This pins the piece of the data that a widget test would otherwise catch:
     * every log-scale slider's default value actually lands inside its own {@code (min, max)} and round-trips
     * cleanly through the mapping the widget will use. {@code sliderBoundsAreSane()} above already covers
     * {@code min > 0} for log-scale sliders, so it is not repeated here.
     */
    @Test
    void logScaleSlidersRoundTripTheirDefaultValueThroughLogValueMapper() {
        PresetDraft preset = fresh();
        for (SettingDescriptor d : Settings.ALL) {
            if (isSliderLike(d.kind()) && d.logScale()) {
                LogValueMapper mapper = new LogValueMapper(d.min(), d.max());
                double defaultValue = (Double) d.getter().apply(preset);
                double roundTripped = mapper.fromSlider(mapper.toSlider(defaultValue));
                double relativeError = Math.abs((roundTripped - defaultValue) / defaultValue);
                assertTrue(relativeError < 1e-9,
                        "log-scale slider " + d.key() + " default " + defaultValue
                                + " does not round-trip through LogValueMapper(" + d.min() + ", " + d.max()
                                + "): got " + roundTripped + " (relative error " + relativeError + ")");
            }
        }
    }

    /**
     * {@link SettingControls} reads a {@code CYCLE} descriptor's enum type off a live value via
     * {@code getDeclaringClass()} rather than a type token the descriptor doesn't carry (see Task 4's boxing
     * convention) - {@code getDeclaringClass()} rather than {@code getClass()} because a constant with a
     * class body would make {@code getClass()} return an anonymous subclass instead of the enum itself. A
     * cycle button is meaningless with fewer than two options, so this pins that every such descriptor's
     * field type actually has at least two.
     */
    @Test
    void cycleDescriptorsHaveAtLeastTwoEnumConstants() {
        PresetDraft preset = fresh();
        for (SettingDescriptor d : Settings.ALL) {
            if (d.kind() == ControlKind.CYCLE) {
                Object value = d.getter().apply(preset);
                assertTrue(value instanceof Enum<?>, "CYCLE descriptor " + d.key() + " getter did not return an enum value");
                Class<?> enumType = ((Enum<?>) value).getDeclaringClass();
                Object[] constants = enumType.getEnumConstants();
                assertTrue(constants != null && constants.length >= 2,
                        "CYCLE descriptor " + d.key() + " enum " + enumType.getSimpleName()
                                + " has fewer than two constants; a cycle button needs at least two");
            }
        }
    }

    /**
     * {@link SettingControls#enumValueLabel} looks up a per-constant lang key
     * ({@link SettingControls#enumLangKey}) for every CYCLE descriptor's enum value and only falls back to a
     * title-cased name when that key is absent. The fallback means a missing translation degrades silently at
     * runtime instead of erroring, so this test is what actually catches it: every enum constant behind a
     * CYCLE descriptor must have a real lang entry.
     */
    @Test
    void cycleDescriptorEnumConstantsHaveLangLabels() {
        JsonObject lang = loadLang();
        PresetDraft preset = fresh();
        Set<String> missing = new TreeSet<>();
        for (SettingDescriptor d : Settings.ALL) {
            if (d.kind() == ControlKind.CYCLE) {
                Enum<?> value = (Enum<?>) d.getter().apply(preset);
                Class<?> enumType = value.getDeclaringClass();
                for (Object constant : enumType.getEnumConstants()) {
                    String key = SettingControls.enumLangKey((Enum<?>) constant);
                    if (!lang.has(key)) {
                        missing.add(key);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "Missing per-value lang entries for CYCLE descriptors in en_us.json: " + missing);
    }

    @Test
    void everyDescriptorHasNameAndTooltipLang() {
        JsonObject lang = loadLang();
        Set<String> missing = new TreeSet<>();
        for (SettingDescriptor d : Settings.ALL) {
            if (!lang.has(d.nameKey())) {
                missing.add(d.nameKey());
            }
            if (!lang.has(d.tooltipKey())) {
                missing.add(d.tooltipKey());
            }
        }
        assertTrue(missing.isEmpty(), "Missing lang entries in en_us.json: " + missing);
    }

    /**
     * Every descriptor must be stamped with a non-empty sub-section id: it is what the editor groups by, and an
     * unstamped descriptor would render loose above the first header (or crash the header lookup). The registry's
     * {@code Reg} builder already refuses to add a descriptor before a section is opened, so this pins the property
     * at the data level too.
     */
    @Test
    void everyDescriptorHasANonEmptySection() {
        Set<String> offenders = new TreeSet<>();
        for (SettingDescriptor d : Settings.ALL) {
            if (d.section() == null || d.section().isBlank()) {
                offenders.add(d.key());
            }
        }
        assertTrue(offenders.isEmpty(), "Descriptors with a null/blank section: " + offenders);
    }

    /**
     * Each sub-section renders a header from two lang keys ({@code urbex.section.<category>.<section>} and its
     * {@code .desc}). A missing entry would show the raw key as the header, so - exactly like the per-setting name
     * and tooltip check - this asserts both keys exist in en_us.json for every distinct section a descriptor names.
     */
    @Test
    void everySectionHasNameAndDescLang() {
        JsonObject lang = loadLang();
        Set<String> missing = new TreeSet<>();
        for (SettingDescriptor d : Settings.ALL) {
            if (!lang.has(d.sectionNameKey())) {
                missing.add(d.sectionNameKey());
            }
            if (!lang.has(d.sectionDescKey())) {
                missing.add(d.sectionDescKey());
            }
        }
        assertTrue(missing.isEmpty(), "Missing section name/desc lang entries in en_us.json: " + missing);
    }

    @Test
    void everyCategoryHasALabel() {
        JsonObject lang = loadLang();
        Set<String> missing = new TreeSet<>();
        for (SettingCategory c : SettingCategory.values()) {
            if (!lang.has(c.labelKey())) {
                missing.add(c.labelKey());
            }
        }
        assertTrue(missing.isEmpty(), "Missing category labels in en_us.json: " + missing);
    }

    /**
     * A key naming the right field is not enough: a copy-paste slip like
     * {@code slider("CITY_CHANCE", ..., p -> p.cityMinRadius(), ...)} would pass every other test while silently
     * orphaning one field and double-exposing another.
     *
     * <p>A single sentinel is also not enough: comparing one flipped value against the field's own default lets a
     * getter that reads a <em>different</em> field slip through whenever that other field's default happens to equal
     * the sentinel (e.g. a boolean getter mistakenly reading a field whose default is the opposite of this one's).
     * So this drives TWO distinct sentinels {@code s1 != s2} through each accessor. A lambda bound to the wrong
     * field returns/holds a value that is constant across both probes, so it cannot equal both distinct sentinels —
     * catching the mismatch independently of any field's default.</p>
     */
    @Test
    void getterAndSetterObserveTheKeyedField() throws Exception {
        for (SettingDescriptor d : Settings.ALL) {
            Field f = PresetDraft.class.getField(d.key());
            f.setAccessible(true);
            Class<?> t = f.getType();
            String where = d.key();

            if (t == int.class) {
                checkNumeric(d, f, where, 4242, 1313);
            } else if (t == float.class) {
                // exactly representable; survive the Double<->float round-trip
                checkNumeric(d, f, where, 137.5, 88.25);
            } else if (t == double.class) {
                checkNumeric(d, f, where, 4242.25, 1313.75);
            } else if (t == boolean.class) {
                checkObject(d, f, where, Boolean.TRUE, Boolean.FALSE);
            } else if (t == String.class) {
                checkObject(d, f, where, "sentinel-a-" + d.key(), "sentinel-b-" + d.key());
            } else if (t == List.class) {
                // FORCE_SPAWN_BUILDINGS / FORCE_SPAWN_PARTS: List<String> on Preset, but the TEXT
                // control's boxing convention (SettingDescriptor's doc) is String[] for list-valued
                // fields, same as it was for the old profile format's String[]-backed fields.
                checkList(d, f, where,
                        new String[]{"sentinel-a-" + d.key()},
                        new String[]{"sentinel-b-" + d.key(), "c"});
            } else if (t.isEnum()) {
                Object[] constants = t.getEnumConstants();
                assertTrue(constants.length >= 2,
                        "enum field " + where + " has fewer than two constants; cannot two-probe");
                checkObject(d, f, where, constants[0], constants[1]);
            } else {
                throw new AssertionError("unhandled field type " + t + " for " + where
                        + " — add a sentinel branch");
            }
        }
    }

    /** Two-probe check for numeric fields, honoring the Double-boxed slider convention. */
    private static void checkNumeric(SettingDescriptor d, Field f, String where, double s1, double s2) throws Exception {
        PresetDraft getP = fresh();
        f.set(getP, coerce(f.getType(), s1));
        assertEquals(s1, ((Number) d.getter().apply(getP)).doubleValue(), 1e-4, "getter does not read field " + where);
        f.set(getP, coerce(f.getType(), s2));
        assertEquals(s2, ((Number) d.getter().apply(getP)).doubleValue(), 1e-4, "getter does not read field " + where);

        PresetDraft setP = fresh();
        d.setter().accept(setP, s1);
        assertEquals(s1, ((Number) f.get(setP)).doubleValue(), 1e-4, "setter does not write field " + where);
        d.setter().accept(setP, s2);
        assertEquals(s2, ((Number) f.get(setP)).doubleValue(), 1e-4, "setter does not write field " + where);
    }

    private static Object coerce(Class<?> t, double v) {
        if (t == int.class) {
            return (int) Math.round(v);
        }
        if (t == float.class) {
            return (float) v;
        }
        return v;
    }

    /** Two-probe check for reference-valued fields compared with {@link Object#equals}. */
    private static void checkObject(SettingDescriptor d, Field f, String where, Object s1, Object s2) throws Exception {
        PresetDraft getP = fresh();
        f.set(getP, s1);
        assertEquals(s1, d.getter().apply(getP), "getter does not read field " + where);
        f.set(getP, s2);
        assertEquals(s2, d.getter().apply(getP), "getter does not read field " + where);

        PresetDraft setP = fresh();
        d.setter().accept(setP, s1);
        assertEquals(s1, f.get(setP), "setter does not write field " + where);
        d.setter().accept(setP, s2);
        assertEquals(s2, f.get(setP), "setter does not write field " + where);
    }

    /** Two-probe check for {@code List<String>} fields, boxed as {@code String[]} across the descriptor. */
    private static void checkList(SettingDescriptor d, Field f, String where, String[] s1, String[] s2) throws Exception {
        PresetDraft getP = fresh();
        f.set(getP, List.of(s1));
        assertArrayEquals(s1, (String[]) d.getter().apply(getP), "getter does not read field " + where);
        f.set(getP, List.of(s2));
        assertArrayEquals(s2, (String[]) d.getter().apply(getP), "getter does not read field " + where);

        PresetDraft setP = fresh();
        d.setter().accept(setP, s1);
        assertEquals(List.of(s1), f.get(setP), "setter does not write field " + where);
        d.setter().accept(setP, s2);
        assertEquals(List.of(s2), f.get(setP), "setter does not write field " + where);
    }

    private static PresetDraft fresh() {
        return new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "test"));
    }

    private static JsonObject loadLang() {
        try (InputStream in = SettingsCompletenessTest.class.getResourceAsStream("/assets/urbex/lang/en_us.json")) {
            assertTrue(in != null, "en_us.json not found on the test classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read en_us.json", e);
        }
    }
}
