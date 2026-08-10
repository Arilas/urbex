package dev.krona.urbex.gui.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.config.UrbexProfile;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry is the metadata backbone every later editor task builds on, so this test pins the two properties
 * that make it trustworthy: every user-editable {@link UrbexProfile} field is described exactly once, and every
 * descriptor points at a field (and lang keys) that actually exist. Reflection over the profile is what keeps the
 * registry honest when someone adds a field later — a new field with no descriptor fails here.
 */
class SettingsCompletenessTest {

    /**
     * Public {@code UrbexProfile} fields that are deliberately NOT surfaced as editable settings. Each needs a
     * one-line justification; keep this list tiny — the point of the test is that almost everything is covered.
     */
    private static final Set<String> EXCLUDED = Set.of(
            // Internal world-edit flag toggled by the edit-mode tooling, not a world-generation knob.
            "EDITMODE"
    );

    private static List<Field> editableFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : UrbexProfile.class.getFields()) { // getFields() = public members only
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

    private static List<SettingDescriptor> homeDescriptors() {
        List<SettingDescriptor> home = new ArrayList<>();
        for (SettingDescriptor d : Settings.ALL) {
            if (!d.general()) {
                home.add(d);
            }
        }
        return home;
    }

    @Test
    void everyEditableFieldHasExactlyOneHomeDescriptor() {
        Map<String, Integer> counts = new HashMap<>();
        for (SettingDescriptor d : homeDescriptors()) {
            counts.merge(d.key(), 1, Integer::sum);
        }

        Set<String> fieldNames = new TreeSet<>();
        for (Field f : editableFields()) {
            fieldNames.add(f.getName());
        }

        // Every editable field is described exactly once by a non-general descriptor.
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
        assertTrue(missing.isEmpty(), "UrbexProfile fields with no home descriptor: " + missing);
        assertTrue(duplicated.isEmpty(), "UrbexProfile fields with more than one home descriptor: " + duplicated);
    }

    @Test
    void everyDescriptorKeyIsARealEditableField() {
        Set<String> editable = new TreeSet<>();
        for (Field f : editableFields()) {
            editable.add(f.getName());
        }
        Set<String> allFieldNames = new HashSet<>();
        for (Field f : UrbexProfile.class.getFields()) {
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
        assertTrue(unknown.isEmpty(), "Descriptor keys that are not public UrbexProfile fields: " + unknown);
        assertTrue(pointsAtExcluded.isEmpty(), "Descriptor keys that point at an EXCLUDED field: " + pointsAtExcluded);
    }

    @Test
    void generalDuplicatesReuseAnExistingHomeKey() {
        Set<String> homeKeys = new HashSet<>();
        for (SettingDescriptor d : homeDescriptors()) {
            homeKeys.add(d.key());
        }
        for (SettingDescriptor d : Settings.ALL) {
            if (d.general()) {
                assertEquals(SettingCategory.GENERAL, d.category(),
                        "general descriptor must sit under the GENERAL category: " + d.key());
                assertTrue(homeKeys.contains(d.key()),
                        "general descriptor has no matching home descriptor: " + d.key());
            } else {
                assertFalse(d.category() == SettingCategory.GENERAL,
                        "GENERAL category is reserved for general=true duplicates: " + d.key());
            }
        }
    }

    @Test
    void sliderBoundsAreSane() {
        for (SettingDescriptor d : Settings.ALL) {
            if (d.kind() == ControlKind.SLIDER) {
                assertTrue(d.min() < d.max(),
                        "slider " + d.key() + " must have min < max (was " + d.min() + ".." + d.max() + ")");
                if (d.logScale()) {
                    assertTrue(d.min() > 0,
                            "log-scale slider " + d.key() + " must have min > 0 (was " + d.min() + ")");
                }
            }
        }
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
     * {@code slider("CITY_CHANCE", ..., p -> p.CITY_MINRADIUS, ...)} would pass every other test while silently
     * orphaning one field and double-exposing another. So for every descriptor, drive a sentinel through the
     * getter (write the raw field, read it back through the lambda) and the setter (write through the lambda,
     * read the raw field) and prove both observe the field named by {@link SettingDescriptor#key()}.
     */
    @Test
    void getterAndSetterObserveTheKeyedField() throws Exception {
        for (SettingDescriptor d : Settings.ALL) {
            Field f = UrbexProfile.class.getField(d.key());
            f.setAccessible(true);
            Class<?> t = f.getType();
            String where = d.key() + (d.general() ? " (general)" : "");

            if (t == int.class) {
                int sentinel = 4242;
                UrbexProfile getP = fresh();
                f.setInt(getP, sentinel);
                assertEquals((double) sentinel, ((Number) d.getter().apply(getP)).doubleValue(), 0.0,
                        "getter does not read field " + where);
                UrbexProfile setP = fresh();
                d.setter().accept(setP, (double) sentinel);
                assertEquals(sentinel, f.getInt(setP), "setter does not write field " + where);
            } else if (t == float.class) {
                float sentinel = 137.5f; // exactly representable; survives Double<->float round-trip
                UrbexProfile getP = fresh();
                f.setFloat(getP, sentinel);
                assertEquals((double) sentinel, ((Number) d.getter().apply(getP)).doubleValue(), 1e-4,
                        "getter does not read field " + where);
                UrbexProfile setP = fresh();
                d.setter().accept(setP, (double) sentinel);
                assertEquals(sentinel, f.getFloat(setP), 1e-4f, "setter does not write field " + where);
            } else if (t == double.class) {
                double sentinel = 4242.25;
                UrbexProfile getP = fresh();
                f.setDouble(getP, sentinel);
                assertEquals(sentinel, ((Number) d.getter().apply(getP)).doubleValue(), 0.0,
                        "getter does not read field " + where);
                UrbexProfile setP = fresh();
                d.setter().accept(setP, sentinel);
                assertEquals(sentinel, f.getDouble(setP), 0.0, "setter does not write field " + where);
            } else if (t == boolean.class) {
                boolean sentinel = !f.getBoolean(fresh()); // flip the default so the value is distinctive
                UrbexProfile getP = fresh();
                f.setBoolean(getP, sentinel);
                assertEquals(sentinel, d.getter().apply(getP), "getter does not read field " + where);
                UrbexProfile setP = fresh();
                d.setter().accept(setP, sentinel);
                assertEquals(sentinel, f.getBoolean(setP), "setter does not write field " + where);
            } else if (t == String.class) {
                String sentinel = "sentinel-" + d.key();
                UrbexProfile getP = fresh();
                f.set(getP, sentinel);
                assertEquals(sentinel, d.getter().apply(getP), "getter does not read field " + where);
                UrbexProfile setP = fresh();
                d.setter().accept(setP, sentinel);
                assertEquals(sentinel, f.get(setP), "setter does not write field " + where);
            } else if (t == String[].class) {
                String[] sentinel = {"sentinel-" + d.key(), "b"};
                UrbexProfile getP = fresh();
                f.set(getP, sentinel);
                assertArrayEquals(sentinel, (String[]) d.getter().apply(getP), "getter does not read field " + where);
                UrbexProfile setP = fresh();
                d.setter().accept(setP, sentinel);
                assertArrayEquals(sentinel, (String[]) f.get(setP), "setter does not write field " + where);
            } else if (t.isEnum()) {
                Object def = f.get(fresh());
                Object sentinel = null;
                for (Object c : t.getEnumConstants()) {
                    if (!c.equals(def)) {
                        sentinel = c;
                        break;
                    }
                }
                assertTrue(sentinel != null, "enum field " + where + " has only one constant; no distinctive sentinel");
                UrbexProfile getP = fresh();
                f.set(getP, sentinel);
                assertEquals(sentinel, d.getter().apply(getP), "getter does not read field " + where);
                UrbexProfile setP = fresh();
                d.setter().accept(setP, sentinel);
                assertEquals(sentinel, f.get(setP), "setter does not write field " + where);
            } else {
                throw new AssertionError("unhandled field type " + t + " for " + where
                        + " — add a sentinel branch");
            }
        }
    }

    private static UrbexProfile fresh() {
        return new UrbexProfile("test", true);
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
