# Weighted World-Style Mixing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a world be created with several world styles balanced by weight, so each city draws its own style and one world can hold cities from several datapacks — behind an experimental config flag that is off by default.

**Architecture:** A validated `WorldStyleMix` value type replaces the single `Identifier` on `PresetChoice`. A `WorldStyleField`, owned by the dimension, resolves that mix into `WorldStyle` objects and answers *which style applies here* — `primary()` for world-spanning fields, weighted draws addressed by `Rng.Purpose.WORLD_STYLE` for per-city, per-scatter-area and per-multichunk fields. `IDimensionInfo.getWorldStyle()` is replaced by `worldStyles()` so every one of the twelve read sites has to state its scope. A single-entry mix draws no random at all, which is what keeps existing worldgen bit-identical.

**Tech Stack:** Java 25, Fabric, Minecraft 26.2, Mojang DFU codecs, JUnit 5, Gradle.

**Spec:** [`docs/superpowers/specs/2026-08-12-multi-worldstyle-mix-design.md`](../specs/2026-08-12-multi-worldstyle-mix-design.md)

## Global Constraints

- **Both worldgen digests must be unchanged by this branch.** `./gradlew digestCheck digestCheckFeatures` must pass against the existing `digest.golden` and `digest-features.golden`. This is the acceptance gate for the whole feature. If a digest moves, the single-entry fast path has been broken.
- `Rng.Purpose` constants are **appended, never inserted or reordered** — reordering reseeds every consumer from that ordinal on.
- Every datapack id reference goes through `DataTools.fromName`, which **rejects unqualified names**. `standard` is an error; `urbex:standard` is not.
- Default for `experimentalMultiWorldStyles` is `false`. With it off, the mod must behave exactly as it does today, in worldgen *and* in the GUI.
- Worldgen randomness comes only from `Rng.at` / `Rng.atPos` — never `new Random(...)`, never a shared `RandomSource` field.
- Caches in `DimensionCaches` are populated with `getOrCompute` / `putIfAbsent`, never `computeIfAbsent` (recursive population deadlocks a `ConcurrentHashMap` bin lock).
- Tests that touch Minecraft classes need `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();` in a `@BeforeAll`.
- Run the suite with `./gradlew test`. A single test class: `./gradlew test --tests 'dev.krona.urbex.setup.WorldStyleMixTest'`.

## File Structure

**New:**

| File | Responsibility |
|---|---|
| `src/main/java/dev/krona/urbex/setup/WorldStyleMix.java` | The validated `(style, weight)` list, its grammar, its codec, `primary()`, and the flag-off reduction. Pure — no registry, no level. |
| `src/main/java/dev/krona/urbex/worldgen/WorldStyleField.java` | Resolves a mix into `WorldStyle` objects and answers which one applies at a given address. Owns the single-entry fast path. |
| `src/test/java/dev/krona/urbex/setup/WorldStyleMixTest.java` | Grammar, validation, `primary()` tie-break. |
| `src/test/java/dev/krona/urbex/worldgen/WorldStyleFieldTest.java` | Fast path, draw determinism, weight distribution. |
| `src/test/java/dev/krona/urbex/config/UrbexDataMixTest.java` | Saved-data round-trip, old-save compatibility. |

**Modified:** `setup/PresetChoice.java`, `setup/Config.java`, `config/UrbexConfig.java`, `data/UrbexData.java`, `varia/Rng.java`, `worldgen/IDimensionInfo.java`, `worldgen/DefaultDimensionInfo.java`, `worldgen/DimensionCaches.java`, `worldgen/CityFeature.java`, `worldgen/CityGenerator.java`, `worldgen/lost/City.java`, `worldgen/lost/BuildingInfo.java`, `worldgen/lost/MultiChunk.java`, `worldgen/lost/Railway.java`, `worldgen/gen/Highways.java`, `worldgen/gen/Railways.java`, `gui/PresetSelection.java`, `gui/WorldStyleDialog.java`, `gui/CitiesTab.java`, `gui/NullDimensionInfo.java`, `gui/preview/CityPreview.java`, `README.md`, `docs/datapacks.md`, and the existing tests that name the changed signatures (`setup/PresetChoiceTest.java`, `gui/WorldStyleSelectionTest.java`, `gui/WorldStyleDialogTest.java`, `gui/NullDimensionInfoPlaceholderTest.java`, `config/UrbexConfigTest.java`).

---

### Task 1: `WorldStyleMix` value type and grammar

**Files:**
- Create: `src/main/java/dev/krona/urbex/setup/WorldStyleMix.java`
- Test: `src/test/java/dev/krona/urbex/setup/WorldStyleMixTest.java`

**Interfaces:**
- Consumes: `DataTools.fromName(String)` from `dev.krona.urbex.worldgen.lost.regassets.data`, which throws on an unqualified id.
- Produces:
  - `WorldStyleMix.Entry(Identifier style, float weight)` — a record.
  - `WorldStyleMix.of(Identifier)` → single-entry mix, weight `1.0f`.
  - `WorldStyleMix.of(List<Entry>)` → validating factory, throws `IllegalArgumentException`.
  - `WorldStyleMix.parse(String)` → throws `IllegalArgumentException` on anything malformed.
  - `mix.format()` → `String`, round-trips through `parse`.
  - `mix.entries()` → `List<Entry>`.
  - `mix.primary()` → `Identifier`.
  - `mix.single()` → `Optional<Identifier>`.
  - `mix.isSingle()` → `boolean`.
  - `mix.reducedToPrimary()` → `WorldStyleMix`, a single-entry mix of `primary()`.
  - `WorldStyleMix.CODEC` → `Codec<WorldStyleMix>` over the string form.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/krona/urbex/setup/WorldStyleMixTest.java`:

```java
package dev.krona.urbex.setup;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mix grammar is the one serial form for a weighted world-style selection: it is what a
 * {@code dimensionsWithPresets} entry carries after the {@code @}, and what {@code UrbexData}
 * persists. One parser rather than three, so a string that loads from a save also parses from
 * config.
 */
class WorldStyleMixTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    @Test
    void singleEntryRoundTripsWithoutAWeightSuffix() {
        WorldStyleMix mix = WorldStyleMix.of(id("urbex", "standard"));
        assertTrue(mix.isSingle());
        assertEquals(id("urbex", "standard"), mix.single().orElseThrow());
        // Weight 1 is implicit, so a single style formats exactly as it did before mixing existed.
        assertEquals("urbex:standard", mix.format());
        assertEquals(mix, WorldStyleMix.parse("urbex:standard"));
    }

    @Test
    void weightedMixRoundTrips() {
        WorldStyleMix mix = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9");
        assertEquals(2, mix.entries().size());
        assertFalse(mix.isSingle());
        assertEquals(id("urbex", "standard"), mix.entries().get(0).style());
        assertEquals(0.1f, mix.entries().get(0).weight());
        assertEquals(id("urbexmt", "moderntweaks"), mix.entries().get(1).style());
        assertEquals(0.9f, mix.entries().get(1).weight());
        assertEquals("urbex:standard*0.1+urbexmt:moderntweaks*0.9", mix.format());
    }

    @Test
    void primaryIsTheHeaviestEntry() {
        assertEquals(id("urbexmt", "moderntweaks"),
                WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9").primary());
        assertEquals(id("urbexmt", "moderntweaks"),
                WorldStyleMix.parse("urbexmt:moderntweaks*0.9+urbex:standard*0.1").primary());
    }

    @Test
    void equalWeightsBreakTheTieOnIdNotOnListOrder() {
        // Registry iteration is ConcurrentHashMap bucket order, so a positional tie-break would
        // make the primary depend on file names. Lowest id string wins, both ways round.
        assertEquals(id("urbex", "standard"),
                WorldStyleMix.parse("urbexmt:moderntweaks+urbex:standard").primary());
        assertEquals(id("urbex", "standard"),
                WorldStyleMix.parse("urbex:standard+urbexmt:moderntweaks").primary());
    }

    @Test
    void reducingToPrimaryIsWhatTheExperimentalGateApplies() {
        WorldStyleMix reduced = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9")
                .reducedToPrimary();
        assertTrue(reduced.isSingle());
        assertEquals(id("urbexmt", "moderntweaks"), reduced.single().orElseThrow());
    }

    @Test
    void malformedSpecsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse(""));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("   "));
        // Unqualified ids stay an error, exactly as they are everywhere else.
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("standard"));
        // Zero and negative weights would make the weighted draw undefined.
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard*0"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard*-1"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard*notanumber"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard*1*2"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.parse("urbex:standard+"));
        // A duplicate is an authoring error, not something to silently sum.
        assertThrows(IllegalArgumentException.class,
                () -> WorldStyleMix.parse("urbex:standard*0.1+urbex:standard*0.9"));
        assertThrows(IllegalArgumentException.class, () -> WorldStyleMix.of(List.of()));
    }

    @Test
    void codecRoundTripsTheStringForm() {
        WorldStyleMix mix = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9");
        String encoded = WorldStyleMix.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, mix).getOrThrow().getAsString();
        assertEquals(mix.format(), encoded);
        WorldStyleMix decoded = WorldStyleMix.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(encoded))
                .getOrThrow();
        assertEquals(mix, decoded);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.krona.urbex.setup.WorldStyleMixTest'`
Expected: compilation failure — `WorldStyleMix` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/dev/krona/urbex/setup/WorldStyleMix.java`:

```java
package dev.krona.urbex.setup;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A weighted set of world styles: what generates a dimension when several datapacks each bring
 * their own kind of city. Immutable, validated on construction, and pure - no registry lookup
 * happens here, so this is testable headless and safe to build on any thread.
 * <p>
 * One string grammar serves both places a mix is written down - the {@code dimensionsWithPresets}
 * config entry (after the {@code @}) and {@code UrbexData}'s saved selection:
 * <pre>urbex:standard*0.1+urbexmt:moderntweaks*0.9</pre>
 * {@code +} separates entries and {@code *} separates an id from its weight. Those two are forced:
 * {@code :} and {@code /} belong to {@link Identifier}, and {@code ,} already separates entries of
 * the {@code dimensionsWithPresets} list itself. A weight of 1 is implicit, so a single style
 * formats as the bare id it was before mixing existed - which is what lets an old save and an old
 * config line keep parsing unchanged.
 */
public record WorldStyleMix(List<Entry> entries) {

    /** One weighted style. {@code weight} is relative: only ratios matter, never the absolute value. */
    public record Entry(Identifier style, float weight) {
    }

    private static final char ENTRY_SEPARATOR = '+';
    private static final char WEIGHT_SEPARATOR = '*';

    /**
     * The serial form, as a codec, for {@code UrbexData}. Deliberately over the same string the
     * config parser reads rather than a list-of-objects encoding: two representations of one value
     * is two parsers to keep in step, and the string is what a server owner types anyway.
     */
    public static final Codec<WorldStyleMix> CODEC = Codec.STRING.comapFlatMap(
            spec -> {
                try {
                    return DataResult.success(parse(spec));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(e::getMessage);
                }
            },
            WorldStyleMix::format);

    public WorldStyleMix {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("A world style mix needs at least one style");
        }
        Set<Identifier> seen = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.style() == null) {
                throw new IllegalArgumentException("A world style mix entry has no style id");
            }
            if (!(entry.weight() > 0) || !Float.isFinite(entry.weight())) {
                throw new IllegalArgumentException("World style '" + entry.style()
                        + "' has weight " + entry.weight() + "; weights must be finite and above zero");
            }
            if (!seen.add(entry.style())) {
                throw new IllegalArgumentException("World style '" + entry.style()
                        + "' appears twice in the same mix");
            }
        }
        entries = List.copyOf(entries);
    }

    public static WorldStyleMix of(Identifier style) {
        return new WorldStyleMix(List.of(new Entry(style, 1.0f)));
    }

    public static WorldStyleMix of(List<Entry> entries) {
        return new WorldStyleMix(entries);
    }

    /**
     * Parses the grammar above. Throws rather than returning an empty optional: every caller has a
     * different thing to do with a bad spec (a config line is logged and dropped, a saved selection
     * falls back to the default), and the message names which part was wrong.
     */
    public static WorldStyleMix parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Empty world style spec");
        }
        List<Entry> parsed = new ArrayList<>();
        for (String part : spec.split("\\" + ENTRY_SEPARATOR, -1)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Empty entry in world style spec '" + spec + "'");
            }
            String[] halves = trimmed.split("\\" + WEIGHT_SEPARATOR, -1);
            if (halves.length > 2) {
                throw new IllegalArgumentException("World style entry '" + trimmed
                        + "' has more than one weight");
            }
            Identifier style;
            try {
                // fromName, not Identifier.parse: an unqualified id is an error here exactly as it
                // is in every other datapack cross-reference, and its message carries the hint.
                style = DataTools.fromName(halves[0].trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("Bad world style id in '" + trimmed + "': "
                        + e.getMessage(), e);
            }
            float weight = 1.0f;
            if (halves.length == 2) {
                try {
                    weight = Float.parseFloat(halves[1].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Bad weight in world style entry '" + trimmed + "'", e);
                }
            }
            parsed.add(new Entry(style, weight));
        }
        return new WorldStyleMix(parsed);
    }

    /**
     * The serial form. {@link Float#toString} is the shortest decimal that reads back as the same
     * float, so {@code format} round-trips through {@link #parse} exactly.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            if (!sb.isEmpty()) {
                sb.append(ENTRY_SEPARATOR);
            }
            sb.append(entry.style());
            if (entry.weight() != 1.0f) {
                sb.append(WEIGHT_SEPARATOR).append(Float.toString(entry.weight()));
            }
        }
        return sb.toString();
    }

    public boolean isSingle() {
        return entries.size() == 1;
    }

    public Optional<Identifier> single() {
        return isSingle() ? Optional.of(entries.get(0).style()) : Optional.empty();
    }

    /**
     * The style world-spanning settings come from - highway and railway parts, world settings, the
     * multichunk grid size. The heaviest entry; ties break on the id string rather than on list
     * position, because the list can arrive in registry iteration order, which is
     * {@code ConcurrentHashMap} bucket order and would make the answer depend on file names.
     */
    public Identifier primary() {
        Entry best = entries.get(0);
        for (Entry entry : entries) {
            if (entry.weight() > best.weight()) {
                best = entry;
            } else if (entry.weight() == best.weight()
                    && entry.style().toString().compareTo(best.style().toString()) < 0) {
                best = entry;
            }
        }
        return best.style();
    }

    /** What the experimental gate applies when mixing is off: keep the primary, drop the rest. */
    public WorldStyleMix reducedToPrimary() {
        return isSingle() ? this : of(primary());
    }

    /** Every style this mix names, primary first, for validation and asset preloading. */
    public List<Identifier> styles() {
        List<Identifier> ids = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            ids.add(entry.style());
        }
        return List.copyOf(ids);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.krona.urbex.setup.WorldStyleMixTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/krona/urbex/setup/WorldStyleMix.java src/test/java/dev/krona/urbex/setup/WorldStyleMixTest.java
git commit -m "feat: WorldStyleMix, the weighted world-style selection value type"
```

---

### Task 2: The experimental flag

**Files:**
- Modify: `src/main/java/dev/krona/urbex/config/UrbexConfig.java`
- Modify: `src/main/java/dev/krona/urbex/setup/Config.java`
- Test: `src/test/java/dev/krona/urbex/config/UrbexConfigTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `UrbexConfig.experimentalMultiWorldStyles()` — record component, `boolean`, default `false`, codec key `experimentalMultiWorldStyles`, positioned **last** in the record and in the codec group.
  - `Config.EXPERIMENTAL_MULTI_WORLD_STYLES` — `Supplier<Boolean>`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/dev/krona/urbex/config/UrbexConfigTest.java`:

```java
    @Test
    void multiWorldStyleMixingIsOffUnlessAskedFor() {
        assertFalse(UrbexConfig.DEFAULT.experimentalMultiWorldStyles());

        JsonObject json = new JsonObject();
        json.addProperty("experimentalMultiWorldStyles", true);
        UrbexConfig parsed = UrbexConfig.fromJson(json).orElseThrow();
        assertTrue(parsed.experimentalMultiWorldStyles());

        // Round-trips, so loadGlobal's normalized write-back keeps the opt-in.
        assertTrue(UrbexConfig.fromJson(UrbexConfig.toJson(parsed)).orElseThrow()
                .experimentalMultiWorldStyles());
    }
```

Add the imports the file does not already have: `com.google.gson.JsonObject`, `static org.junit.jupiter.api.Assertions.assertFalse`, `static org.junit.jupiter.api.Assertions.assertTrue`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.krona.urbex.config.UrbexConfigTest'`
Expected: compilation failure — `experimentalMultiWorldStyles()` is not a member.

- [ ] **Step 3: Write the implementation**

In `UrbexConfig.java`, add `boolean experimentalMultiWorldStyles` as the **last** record component (after `avoidFlattening`), add `false` as the last argument of `DEFAULT`, and add this as the last entry of the codec group (a `RecordCodecBuilder` group is positional — it must match the constructor order):

```java
            Codec.BOOL.optionalFieldOf("experimentalMultiWorldStyles", DEFAULT.experimentalMultiWorldStyles()).forGetter(UrbexConfig::experimentalMultiWorldStyles)
```

Add the doc comment above the record component:

```java
/**
 * ...existing class doc...
 *
 * @param experimentalMultiWorldStyles opts a world in to selecting several world styles at once,
 *        balanced by weight, so cities from several datapacks can share one world. Off by default
 *        and gating behaviour rather than only the UI: a save or a config line hand-edited to
 *        carry a mix is reduced to its primary style on an install that has not opted in.
 */
```

In `Config.java`, next to the other suppliers:

```java
    public static final Supplier<Boolean> EXPERIMENTAL_MULTI_WORLD_STYLES = () -> active.experimentalMultiWorldStyles();
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.krona.urbex.config.UrbexConfigTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/krona/urbex/config/UrbexConfig.java src/main/java/dev/krona/urbex/setup/Config.java src/test/java/dev/krona/urbex/config/UrbexConfigTest.java
git commit -m "feat: experimentalMultiWorldStyles config flag, off by default"
```

---

### Task 3: `PresetChoice` carries a mix, and the `dimensionsWithPresets` grammar

**Files:**
- Modify: `src/main/java/dev/krona/urbex/setup/PresetChoice.java`
- Modify: `src/main/java/dev/krona/urbex/setup/Config.java:220-258` (`parseDimensionPresetEntry`), `:279-384` (`buildPresetCache`), `:394-431` (`validateSelectedPresets`, `requireWorldStyle`)
- Test: `src/test/java/dev/krona/urbex/setup/PresetChoiceTest.java`

**Interfaces:**
- Consumes: `WorldStyleMix.parse`, `WorldStyleMix.of`, `WorldStyleMix.reducedToPrimary`, `Config.EXPERIMENTAL_MULTI_WORLD_STYLES` from Tasks 1–2.
- Produces:
  - `PresetChoice(Identifier preset, WorldStyleMix worldStyles, Optional<String> overridesJson)` — the second component is renamed from `worldStyle` and retyped.
  - `Config.DEFAULT_WORLD_STYLE_MIX` — `WorldStyleMix`, `WorldStyleMix.of(DEFAULT_WORLD_STYLE)`.
  - `Config.gateMix(WorldStyleMix, String context)` — reduces to primary and logs when the flag is off; returns the mix unchanged when on.

- [ ] **Step 1: Write the failing test**

Replace the body of `PresetChoiceTest` with this (the existing two tests are kept, retargeted at `worldStyles()`):

```java
package dev.krona.urbex.setup;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code dimensionsWithPresets} entries are {@code dimension=preset[@worldstylemix]}. The parser is
 * a small static method so it is testable headless, with no server or registry.
 */
class PresetChoiceTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    @Test
    void parsesDimensionPresetEntry() {
        Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> parsed =
                Config.parseDimensionPresetEntry("minecraft:overworld=urbex:rarecities");
        assertTrue(parsed.isPresent());
        assertEquals(dimension("minecraft:overworld"), parsed.get().getKey());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "rarecities"), parsed.get().getValue().preset());
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, parsed.get().getValue().worldStyles());
        assertTrue(parsed.get().getValue().overridesJson().isEmpty());

        Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> explicitStyle =
                Config.parseDimensionPresetEntry("minecraft:the_nether=urbex:cavern@urbex:standard");
        assertTrue(explicitStyle.isPresent());
        assertEquals(dimension("minecraft:the_nether"), explicitStyle.get().getKey());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "cavern"), explicitStyle.get().getValue().preset());
        assertEquals(WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")),
                explicitStyle.get().getValue().worldStyles());

        // A bare (unqualified) name is rejected, not defaulted to the urbex namespace: the entry
        // is logged and dropped, same as any other malformed entry.
        assertTrue(Config.parseDimensionPresetEntry("minecraft:overworld=default").isEmpty());
    }

    @Test
    void parsesAWeightedMix() {
        Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> parsed = Config.parseDimensionPresetEntry(
                "minecraft:overworld=urbex:default@urbex:standard*0.1+urbexmt:moderntweaks*0.9");
        assertTrue(parsed.isPresent());
        WorldStyleMix mix = parsed.get().getValue().worldStyles();
        assertEquals(2, mix.entries().size());
        assertEquals(0.1f, mix.entries().get(0).weight());
        assertEquals(Identifier.fromNamespaceAndPath("urbexmt", "moderntweaks"), mix.primary());
    }

    @Test
    void malformedEntryIsRejectedWithError() {
        assertTrue(Config.parseDimensionPresetEntry("junk").isEmpty());
        assertTrue(Config.parseDimensionPresetEntry("a=b=c=d").isEmpty());
        // A malformed mix takes the whole entry down, not just the mix - silently generating with
        // the default style would hide a typo in a server's config for the life of the world.
        assertTrue(Config.parseDimensionPresetEntry(
                "minecraft:overworld=urbex:default@urbex:standard*0").isEmpty());
        assertTrue(Config.parseDimensionPresetEntry(
                "minecraft:overworld=urbex:default@standard*0.5+urbex:cavern").isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.krona.urbex.setup.PresetChoiceTest'`
Expected: compilation failure — `worldStyles()` and `DEFAULT_WORLD_STYLE_MIX` do not exist.

- [ ] **Step 3: Write the implementation**

`PresetChoice.java` in full:

```java
package dev.krona.urbex.setup;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * A resolved dimension selection: which preset generates it, which world styles it draws from, and
 * an optional {@code PresetRE} JSON overlay applied on top of the resolved preset (a
 * client-published customization, or a saved-world one). {@code overridesJson}, when present, is
 * parsed with {@code PresetRE.CODEC} and applied via {@code Presets.applyOverrides}.
 * <p>
 * {@code worldStyles} is a {@link WorldStyleMix} rather than a single id: with
 * {@code experimentalMultiWorldStyles} on it can carry several weighted styles, and every other
 * path builds a single-entry mix. A single-entry mix resolves without drawing any randomness, so
 * this being a mix costs nothing for a world that only uses one.
 */
public record PresetChoice(Identifier preset, WorldStyleMix worldStyles, Optional<String> overridesJson) {
}
```

In `Config.java`, add beside `DEFAULT_WORLD_STYLE`:

```java
    /** The single-entry mix every path that does not name its own styles resolves to. */
    public static final WorldStyleMix DEFAULT_WORLD_STYLE_MIX = WorldStyleMix.of(DEFAULT_WORLD_STYLE);
```

Add the gate helper:

```java
    /**
     * Applies the {@code experimentalMultiWorldStyles} opt-in to a mix that arrived from anywhere -
     * a config line, a client publication, a saved world. With the flag off a multi-entry mix is
     * reduced to its primary style and the reduction logged.
     * <p>
     * The gate is here, on the value, rather than only on the UI: a save or a config file
     * hand-edited to carry a mix must not quietly get one on an install that never opted in.
     */
    public static WorldStyleMix gateMix(WorldStyleMix mix, String context) {
        if (mix.isSingle() || EXPERIMENTAL_MULTI_WORLD_STYLES.get()) {
            return mix;
        }
        WorldStyleMix reduced = mix.reducedToPrimary();
        Urbex.getLogger().warn("{} names {} world styles, but experimentalMultiWorldStyles is off; " +
                "generating with '{}' alone.", context, mix.entries().size(), reduced.primary());
        return reduced;
    }
```

In `parseDimensionPresetEntry`, replace the worldstyle block. The old block parsed one `Identifier`; the new one parses the whole tail as a mix:

```java
        String presetPart = split[1];
        String presetName = presetPart;
        WorldStyleMix worldStyles = DEFAULT_WORLD_STYLE_MIX;
        int at = presetPart.indexOf('@');
        if (at >= 0) {
            presetName = presetPart.substring(0, at);
            try {
                worldStyles = gateMix(WorldStyleMix.parse(presetPart.substring(at + 1)),
                        "dimensionsWithPresets entry '" + entry + "'");
            } catch (IllegalArgumentException e) {
                Urbex.getLogger().error("Bad worldstyle spec in config value: '{}'! {}", entry, e.getMessage());
                return Optional.empty();
            }
        }
```

and the return becomes `new PresetChoice(presetId, worldStyles, Optional.empty())`.

In `buildPresetCache`, change the type of the local `selectedWorldStyle` to `WorldStyleMix selectedWorldStyles` and:

- the client branch: `selectedWorldStyles = worldStyleMixFromClient != null ? worldStyleMixFromClient : DEFAULT_WORLD_STYLE_MIX;` (the field is introduced in Task 4 — for now, keep reading `worldStyleFromClient` and wrap it with `WorldStyleMix.of(...)`; Task 4 replaces the field).
- the saved-data branch: parse `data.getSelectedWorldStyle()` as before (Task 4 adds the mix field).
- the registry check: replace the single `worldStyles.get(selectedWorldStyle).isEmpty()` test with a loop over `selectedWorldStyles.styles()`, dropping unknown ids and falling back to `DEFAULT_WORLD_STYLE_MIX` if that leaves nothing:

```java
            } else {
                List<WorldStyleMix.Entry> known = new ArrayList<>();
                for (WorldStyleMix.Entry candidate : selectedWorldStyles.entries()) {
                    if (worldStyles.get(candidate.style()).isPresent()) {
                        known.add(candidate);
                    } else {
                        Urbex.getLogger().error("Unknown Urbex worldstyle '{}' selected for the overworld; " +
                                "dropping it from the mix. Valid worldstyles: {}",
                                candidate.style(), String.join(", ", sortedIds(worldStyles)));
                    }
                }
                selectedWorldStyles = known.isEmpty() ? DEFAULT_WORLD_STYLE_MIX : WorldStyleMix.of(known);
            }
```

- the nether entry: `new PresetChoice(Identifier.fromNamespaceAndPath("urbex", "cavern"), DEFAULT_WORLD_STYLE_MIX, Optional.empty())`.

In `validateSelectedPresets`, replace the single `requireWorldStyle(worldStyles, e.getValue().worldStyle(), ...)` with a loop:

```java
                for (Identifier style : e.getValue().worldStyles().styles()) {
                    requireWorldStyle(worldStyles, style, "dimensionsWithPresets entry '" + dp + "'");
                }
```

Add the imports `dev.krona.urbex.setup.WorldStyleMix` is same-package so needs none; add `java.util.ArrayList` and `java.util.List` if the wildcard `java.util.*` import is not already covering them (it is — `Config.java` imports `java.util.*`).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.krona.urbex.setup.PresetChoiceTest'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/krona/urbex/setup/PresetChoice.java src/main/java/dev/krona/urbex/setup/Config.java src/test/java/dev/krona/urbex/setup/PresetChoiceTest.java
git commit -m "feat: dimensionsWithPresets accepts a weighted world-style mix"
```

---

### Task 4: Persist a mix in `UrbexData`, and publish one from the client

**Files:**
- Modify: `src/main/java/dev/krona/urbex/data/UrbexData.java`
- Modify: `src/main/java/dev/krona/urbex/setup/Config.java` (the `worldStyleFromClient` field, `reset`, `buildPresetCache`'s two selection branches)
- Test: `src/test/java/dev/krona/urbex/config/UrbexDataMixTest.java`

**Interfaces:**
- Consumes: `WorldStyleMix`, `Config.DEFAULT_WORLD_STYLE_MIX`, `Config.gateMix` from Tasks 1 and 3.
- Produces:
  - `UrbexData.setChoice(String preset, WorldStyleMix styles, String overridesJson)`.
  - `UrbexData.getSelectedWorldStyles()` → `WorldStyleMix`; falls back through `worldStyleMix` → `worldStyle` → `Config.DEFAULT_WORLD_STYLE_MIX`.
  - `Config.worldStyleMixFromClient` — `WorldStyleMix`, nullable, replacing `worldStyleFromClient`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/krona/urbex/config/UrbexDataMixTest.java`:

```java
package dev.krona.urbex.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.data.UrbexData;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world records the styles it was created with, so it keeps generating them after the create
 * screen is gone. The new {@code worldStyleMix} key has to coexist with the old {@code worldStyle}
 * one: a world made before mixing existed carries only the latter and must keep loading, and a
 * single-style world must keep writing only the latter so its save is unchanged.
 */
class UrbexDataMixTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static UrbexData decode(String json) {
        return UrbexData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static JsonObject encode(UrbexData data) {
        return UrbexData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
    }

    @Test
    void aWorldSavedBeforeMixingExistedStillLoads() {
        UrbexData data = decode("{\"preset\":\"urbex:default\",\"worldStyle\":\"urbex:standard\"}");
        assertEquals(WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")),
                data.getSelectedWorldStyles());
    }

    @Test
    void aWorldWithNoStyleAtAllFallsBackToTheDefault() {
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, decode("{\"preset\":\"urbex:default\"}").getSelectedWorldStyles());
    }

    @Test
    void aSingleStyleWorldWritesOnlyTheOldKey() {
        UrbexData data = new UrbexData();
        data.setChoice("urbex:default",
                WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")), "");
        JsonObject json = encode(data);
        assertEquals("urbex:standard", json.get("worldStyle").getAsString());
        // Absent, not empty-and-present: a single-style save must be what it always was.
        assertFalse(json.has("worldStyleMix") && !json.get("worldStyleMix").getAsString().isEmpty());
    }

    @Test
    void aMixedWorldRoundTripsThroughTheNewKey() {
        WorldStyleMix mix = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9");
        UrbexData data = new UrbexData();
        data.setChoice("urbex:default", mix, "");
        UrbexData reloaded = decode(encode(data).toString());
        assertEquals(mix, reloaded.getSelectedWorldStyles());
        assertFalse(reloaded.getSelectedWorldStyles().isSingle());
    }

    @Test
    void aMalformedSavedMixFallsBackToTheDefaultRatherThanThrowing() {
        // Saved data can be hand-edited or corrupted; a chunk generating must not take the server
        // down over it.
        UrbexData data = decode("{\"preset\":\"urbex:default\",\"worldStyleMix\":\"nonsense*\"}");
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, data.getSelectedWorldStyles());
        assertTrue(data.getSelectedWorldStyles().isSingle());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.krona.urbex.config.UrbexDataMixTest'`
Expected: compilation failure — `getSelectedWorldStyles` and the `setChoice` overload do not exist.

- [ ] **Step 3: Write the implementation**

In `UrbexData.java`: add a `selectedWorldStyleMix` field, a fourth codec entry, and the accessor. The codec group is positional, so the new entry goes **last** and the constructor gains a fourth parameter:

```java
    public static final Codec<UrbexData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("preset", "").forGetter(d -> d.selectedPreset),
            Codec.STRING.optionalFieldOf("worldStyle", "").forGetter(d -> d.selectedWorldStyle),
            Codec.STRING.optionalFieldOf("overrides", "").forGetter(d -> d.selectedOverrides),
            Codec.STRING.optionalFieldOf("worldStyleMix", "").forGetter(d -> d.selectedWorldStyleMix)
            ).apply(instance, UrbexData::new));
```

```java
    private String selectedWorldStyleMix = "";

    public UrbexData(String preset, String worldStyle, String overrides, String worldStyleMix) {
        selectedPreset = preset;
        selectedWorldStyle = worldStyle;
        selectedOverrides = overrides;
        selectedWorldStyleMix = worldStyleMix;
    }

    /**
     * Records the world's selection. A single-style choice writes only the legacy {@code worldStyle}
     * key and leaves {@code worldStyleMix} empty, so a world that uses one style saves exactly what
     * it always did; only a genuine mix writes the new key.
     */
    public void setChoice(String preset, WorldStyleMix styles, String overridesJson) {
        selectedPreset = preset;
        selectedWorldStyle = styles.primary().toString();
        selectedWorldStyleMix = styles.isSingle() ? "" : styles.format();
        selectedOverrides = overridesJson;
        setDirty();
    }

    /**
     * The styles this world was created with. {@code worldStyleMix} wins when present, else the
     * legacy single {@code worldStyle}, else the default. Fail-soft on both: this is read on a
     * worldgen worker thread the moment a chunk generates, so a corrupted or hand-edited save must
     * degrade to the default rather than take generation down.
     */
    public WorldStyleMix getSelectedWorldStyles() {
        if (!selectedWorldStyleMix.isEmpty()) {
            try {
                return WorldStyleMix.parse(selectedWorldStyleMix);
            } catch (IllegalArgumentException e) {
                Urbex.getLogger().error("Malformed saved worldstyle mix '{}' in world data; using {}.",
                        selectedWorldStyleMix, Config.DEFAULT_WORLD_STYLE_MIX.format());
                return Config.DEFAULT_WORLD_STYLE_MIX;
            }
        }
        if (!selectedWorldStyle.isEmpty()) {
            try {
                return WorldStyleMix.parse(selectedWorldStyle);
            } catch (IllegalArgumentException e) {
                Urbex.getLogger().error("Malformed saved worldstyle id '{}' in world data; using {}.",
                        selectedWorldStyle, Config.DEFAULT_WORLD_STYLE_MIX.format());
                return Config.DEFAULT_WORLD_STYLE_MIX;
            }
        }
        return Config.DEFAULT_WORLD_STYLE_MIX;
    }
```

Keep `getSelectedWorldStyle()` — `RecreateProfileRestore` reads the raw string — and add imports for `dev.krona.urbex.Urbex`, `dev.krona.urbex.setup.Config`, `dev.krona.urbex.setup.WorldStyleMix`.

In `Config.java`: rename the field and follow it through `reset()` and `buildPresetCache`.

```java
    public static WorldStyleMix worldStyleMixFromClient = null;
```

`buildPresetCache`'s client branch becomes:

```java
        if (presetFromClient != null && !worldHasOwnChoice) {
            selectedPreset = presetFromClient;
            selectedWorldStyles = gateMix(
                    worldStyleMixFromClient != null ? worldStyleMixFromClient : DEFAULT_WORLD_STYLE_MIX,
                    "The world being created");
            selectedOverrides = overridesFromClient;
            data.setChoice(selectedPreset.toString(), selectedWorldStyles,
                    selectedOverrides == null ? "" : selectedOverrides);
        } else {
```

and the saved-data branch's whole style block collapses to:

```java
                    selectedWorldStyles = gateMix(data.getSelectedWorldStyles(), "This world's saved selection");
```

The global-config branch keeps parsing a single id: `selectedWorldStyles = globalStyle == null || globalStyle.isEmpty() ? DEFAULT_WORLD_STYLE_MIX : WorldStyleMix.of(DataTools.fromName(globalStyle));`

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.krona.urbex.config.UrbexDataMixTest' --tests 'dev.krona.urbex.setup.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/krona/urbex/data/UrbexData.java src/main/java/dev/krona/urbex/setup/Config.java src/test/java/dev/krona/urbex/config/UrbexDataMixTest.java
git commit -m "feat: persist a world-style mix in UrbexData, old saves unchanged"
```

---

### Task 5: `WorldStyleField` — the resolver

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/WorldStyleField.java`
- Modify: `src/main/java/dev/krona/urbex/varia/Rng.java` (append one enum constant)
- Modify: `src/main/java/dev/krona/urbex/worldgen/DimensionCaches.java` (one cache + its `clear()` line)
- Test: `src/test/java/dev/krona/urbex/worldgen/WorldStyleFieldTest.java`

**Interfaces:**
- Consumes: `WorldStyleMix` (Task 1); `Rng.at`, `Tools.getRandomFromList`, `ChunkCoord`, `WorldStyle`, `AssetRegistries.WORLDSTYLES`, `IDimensionInfo`, `City.isCityCenter`, `City.getCityRadius`.
- Produces:
  - `WorldStyleField.Weighted(float weight, WorldStyle style)` — record.
  - `new WorldStyleField(long seed, List<Weighted> entries)`.
  - `WorldStyleField.resolve(CommonLevelAccessor level, long seed, WorldStyleMix mix)`.
  - `field.primary()`, `field.isSingle()`, `field.styles()` → `List<WorldStyle>`.
  - `field.atCityCenter(ChunkCoord)`, `field.atScatterArea(ChunkCoord)`, `field.atMultiArea(ChunkCoord)`.
  - `field.atChunk(IDimensionInfo, ChunkCoord)`.
  - `Rng.Purpose.WORLD_STYLE`.
  - `DimensionCaches.worldStyle` — `TimedCache<ChunkCoord, WorldStyle>`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/krona/urbex/worldgen/WorldStyleFieldTest.java`. It builds `WorldStyle` objects directly from `WorldStyleRE` chains (no registry), the way `NullDimensionInfoPlaceholderTest` already does — read that file first for the exact `WorldStyleRE` constructor argument list, and mirror it.

```java
package dev.krona.urbex.worldgen;

import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The field is what makes mixing possible without making a one-style world generate differently.
 * Its most important property is negative: with one style it must never draw, so every existing
 * world and every world with the experimental flag off keeps its digests.
 */
class WorldStyleFieldTest {

    private static final long SEED = 0x5EEDL;
    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Builds a minimal resolvable world style. See NullDimensionInfoPlaceholderTest for the shape. */
    private static WorldStyle style(String path) {
        // TASK NOTE: copy the WorldStyleRE construction out of
        // src/test/java/dev/krona/urbex/gui/NullDimensionInfoPlaceholderTest.java (it builds the
        // preview placeholder the same way) and change only the registry name to `path`.
        throw new UnsupportedOperationException("replace with the NullDimensionInfoPlaceholderTest builder");
    }

    private static ChunkCoord coord(int x, int z) {
        return new ChunkCoord(OVERWORLD, x, z);
    }

    @Test
    void oneStyleAlwaysAnswersItselfWithoutDrawing() {
        WorldStyle only = style("standard");
        WorldStyleField field = new WorldStyleField(SEED, List.of(new WorldStyleField.Weighted(1.0f, only)));
        assertTrue(field.isSingle());
        assertSame(only, field.primary());
        for (int x = -50; x <= 50; x += 7) {
            for (int z = -50; z <= 50; z += 11) {
                assertSame(only, field.atCityCenter(coord(x, z)));
                assertSame(only, field.atScatterArea(coord(x, z)));
                assertSame(only, field.atMultiArea(coord(x, z)));
            }
        }
    }

    @Test
    void theSameAddressAlwaysDrawsTheSameStyle() {
        WorldStyleField field = mixed();
        for (int x = -20; x <= 20; x += 3) {
            for (int z = -20; z <= 20; z += 3) {
                assertSame(field.atCityCenter(coord(x, z)), field.atCityCenter(coord(x, z)));
            }
        }
    }

    @Test
    void drawsTrackTheWeights() {
        WorldStyleField field = mixed();
        int heavy = 0;
        int total = 0;
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                total++;
                if (field.atCityCenter(coord(x, z)).getName().endsWith(":moderntweaks")) {
                    heavy++;
                }
            }
        }
        double share = (double) heavy / total;
        // 0.9 nominal. A wide band: this asserts the weights are honoured at all, not that the
        // hash is uniform to three digits.
        assertTrue(share > 0.85 && share < 0.95, "heavy style share was " + share);
    }

    @Test
    void primaryIsTheHeaviestStyle() {
        assertEquals("urbexmt:moderntweaks", mixed().primary().getName());
    }

    private static WorldStyleField mixed() {
        return new WorldStyleField(SEED, List.of(
                new WorldStyleField.Weighted(0.1f, style("standard")),
                new WorldStyleField.Weighted(0.9f, style("moderntweaks"))));
    }
}
```

The `style(...)` helper is the one thing to fill in from the existing placeholder test — the rest is complete. Do not leave the `UnsupportedOperationException` in the committed test.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.krona.urbex.worldgen.WorldStyleFieldTest'`
Expected: compilation failure — `WorldStyleField` does not exist.

- [ ] **Step 3: Write the implementation**

Append to `Rng.Purpose`, **after** `LARGE_BRIDGE` (never before anything):

```java
        ,
        // Which world style a city, scatter area or multichunk area draws from a weighted mix.
        // Only reached when the mix has more than one entry: a single-style world never draws
        // here at all, which is what keeps its generation identical to before mixing existed.
        WORLD_STYLE
```

In `DimensionCaches`, beside `cityStyle`:

```java
    /**
     * Which world style governs a chunk, when the world was created with several. Only populated
     * for a genuine mix - {@link dev.krona.urbex.worldgen.WorldStyleField#atChunk} short-circuits
     * before reaching the cache when there is one style.
     */
    public final TimedCache<ChunkCoord, WorldStyle> worldStyle = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);
```

Add `worldStyle.clear();` to `clear()`, and the import of `dev.krona.urbex.worldgen.lost.cityassets.WorldStyle`.

Create `WorldStyleField.java`:

```java
package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.City;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CommonLevelAccessor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Which world style applies where, for a dimension created with a weighted mix of them.
 * <p>
 * A world style is not one scope. Its {@code citystyles} and {@code outsidestyle} describe a city;
 * its highway and railway {@code parts} describe a network that runs between cities for hundreds of
 * chunks. Mixing forces that distinction to become explicit, so this class offers one accessor per
 * scope and {@link IDimensionInfo} exposes the field rather than a single style - a call site has
 * to say which it means, and the compiler makes it.
 * <p>
 * <b>The single-style fast path is the point.</b> With one entry every accessor returns that style
 * without touching {@link Rng} at all, so a world that does not mix generates exactly what it
 * generated before this class existed. Both worldgen digests depend on that.
 * <p>
 * Every draw is addressed by {@code (seed, coordinate, WORLD_STYLE)} like the rest of generation,
 * so two chunks built in either order agree, and a city keeps its style across a restart.
 */
public final class WorldStyleField {

    /**
     * The grid a perlin-rarity preset ({@code cityChance < 0}, e.g. {@code urbex:largecities})
     * draws on. Such a preset has no discrete city centres to attribute a chunk to, so without a
     * coarse grid every chunk of one continuous city blob would draw its own style. 16 chunks is a
     * 256-block patch: large enough to read as one district, small enough that a mix still shows.
     */
    private static final int PERLIN_REGION_CHUNKS = 16;

    /** One resolved, weighted style. */
    public record Weighted(float weight, WorldStyle style) {
    }

    private final long seed;
    private final List<Weighted> entries;
    private final WorldStyle primary;
    private final boolean single;

    public WorldStyleField(long seed, List<Weighted> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("A world style field needs at least one style");
        }
        this.seed = seed;
        this.entries = List.copyOf(entries);
        this.single = this.entries.size() == 1;
        Weighted best = this.entries.get(0);
        for (Weighted candidate : this.entries) {
            // Same rule as WorldStyleMix.primary: heaviest, ties on the id string rather than on
            // list position, so the answer never depends on registry iteration order.
            if (candidate.weight() > best.weight()
                    || (candidate.weight() == best.weight()
                        && candidate.style().getName().compareTo(best.style().getName()) < 0)) {
                best = candidate;
            }
        }
        this.primary = best.style();
    }

    /**
     * Resolves every id in {@code mix} against the world style registry, once, at dimension
     * construction - the same point {@code DefaultDimensionInfo} used to resolve its single style.
     */
    public static WorldStyleField resolve(CommonLevelAccessor level, long seed, WorldStyleMix mix) {
        List<Weighted> resolved = new ArrayList<>(mix.entries().size());
        for (WorldStyleMix.Entry entry : mix.entries()) {
            resolved.add(new Weighted(entry.weight(),
                    AssetRegistries.WORLDSTYLES.get(level, entry.style())));
        }
        return new WorldStyleField(seed, resolved);
    }

    /** A field over one already-resolved style, for the preview and for tests. */
    public static WorldStyleField single(long seed, WorldStyle style) {
        return new WorldStyleField(seed, List.of(new Weighted(1.0f, style)));
    }

    public boolean isSingle() {
        return single;
    }

    /**
     * The style world-spanning settings come from: highway and railway {@code parts}, the world
     * {@code settings}, {@code citybiomemultipliers}, and the multichunk grid size. These cannot
     * vary by location - a highway that changed datapack partway along its run would not join up,
     * and a per-area {@code areasize} would have to be read from an area it has not defined yet.
     */
    @Nonnull
    public WorldStyle primary() {
        return primary;
    }

    /** Every style in the mix, for validation and asset preloading. */
    public List<WorldStyle> styles() {
        List<WorldStyle> all = new ArrayList<>(entries.size());
        for (Weighted entry : entries) {
            all.add(entry.style());
        }
        return List.copyOf(all);
    }

    /** The style of the city centred on this chunk. Its {@code citystyles} shape the whole city. */
    @Nonnull
    public WorldStyle atCityCenter(ChunkCoord center) {
        return draw(center.chunkX(), center.chunkZ());
    }

    /** The style a scatter area's structure is drawn from, addressed at the area's anchor chunk. */
    @Nonnull
    public WorldStyle atScatterArea(ChunkCoord anchor) {
        return draw(anchor.chunkX(), anchor.chunkZ());
    }

    /** The style a multichunk area's multi-building settings come from, at its anchor. */
    @Nonnull
    public WorldStyle atMultiArea(ChunkCoord anchor) {
        return draw(anchor.chunkX(), anchor.chunkZ());
    }

    /**
     * The style governing an ordinary chunk - its {@code outsidestyle}, its {@code rotatable} tag,
     * the palette it builds outside a city.
     * <p>
     * The dominant nearby city centre's style, so a chunk on a city's edge looks like that city
     * rather than like a coin flip. No centre in range gives {@link #primary()}. A perlin-rarity
     * preset has no centres at all and falls back to the coarse region grid; see
     * {@link #PERLIN_REGION_CHUNKS}.
     */
    @Nonnull
    public WorldStyle atChunk(IDimensionInfo provider, ChunkCoord coord) {
        if (single) {
            return primary;
        }
        // getOrCompute, not computeIfAbsent: this is reached from BuildingInfo while its
        // neighbours' characteristics are being built, and a recursive computeIfAbsent deadlocks on
        // the bin lock even for distinct keys. Same rule as every other cache in DimensionCaches.
        return provider.caches().worldStyle.getOrCompute(coord, k -> atChunkInt(provider, coord));
    }

    private WorldStyle atChunkInt(IDimensionInfo provider, ChunkCoord coord) {
        Preset profile = provider.getProfile();
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        if (profile.CITY_CHANCE < 0) {
            return draw(Math.floorDiv(chunkX, PERLIN_REGION_CHUNKS), Math.floorDiv(chunkZ, PERLIN_REGION_CHUNKS));
        }
        ChunkCoord best = null;
        float bestFactor = 0;
        int offset = (profile.CITY_MAXRADIUS + 15) / 16;
        for (int cx = chunkX - offset; cx <= chunkX + offset; cx++) {
            for (int cz = chunkZ - offset; cz <= chunkZ + offset; cz++) {
                ChunkCoord c = new ChunkCoord(provider.getType(), cx, cz);
                if (!City.isCityCenter(c, provider)) {
                    continue;
                }
                float radius = City.getCityRadius(c, provider);
                float dx = cx * 16 - (chunkX << 4);
                float dz = cz * 16 - (chunkZ << 4);
                float sqdist = dx * dx + dz * dz;
                if (sqdist >= radius * radius) {
                    continue;
                }
                float factor = (radius - (float) Math.sqrt(sqdist)) / radius;
                // Ties break on the coordinate, not on scan order, so the answer does not depend on
                // which way the loops happen to run.
                if (best == null || factor > bestFactor
                        || (factor == bestFactor
                            && (cx < best.chunkX() || (cx == best.chunkX() && cz < best.chunkZ())))) {
                    best = c;
                    bestFactor = factor;
                }
            }
        }
        return best == null ? primary : atCityCenter(best);
    }

    private WorldStyle draw(int x, int z) {
        if (single) {
            return primary;
        }
        RandomSource random = Rng.at(seed, x, z, Rng.Purpose.WORLD_STYLE);
        Weighted picked = Tools.getRandomFromList(random, entries, Weighted::weight);
        return picked == null ? primary : picked.style();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.krona.urbex.worldgen.WorldStyleFieldTest'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/krona/urbex/worldgen/WorldStyleField.java src/main/java/dev/krona/urbex/varia/Rng.java src/main/java/dev/krona/urbex/worldgen/DimensionCaches.java src/test/java/dev/krona/urbex/worldgen/WorldStyleFieldTest.java
git commit -m "feat: WorldStyleField resolves which world style applies where"
```

---

### Task 6: Wire the field through worldgen

This is the task the digest gate is really testing. Nothing here should change behaviour for a single-style world.

**Files:**
- Modify: `worldgen/IDimensionInfo.java`, `worldgen/DefaultDimensionInfo.java`, `worldgen/CityFeature.java:145`, `worldgen/CityGenerator.java:86-87,444,2060-2074,1830`, `worldgen/lost/City.java:206-283,352-358`, `worldgen/lost/BuildingInfo.java:199,690,762,793`, `worldgen/lost/MultiChunk.java:56,75,218,245,260`, `worldgen/lost/Railway.java:101`, `worldgen/gen/Highways.java:48`, `worldgen/gen/Railways.java:35`, `gui/NullDimensionInfo.java:232`

**Interfaces:**
- Consumes: `WorldStyleField` (Task 5), `PresetChoice.worldStyles()` (Task 3).
- Produces:
  - `IDimensionInfo.worldStyles()` → `WorldStyleField`, **replacing** `getWorldStyle()`.
  - `BuildingInfo.worldStyle()` → `WorldStyle`, the chunk's style, memoised on the instance.

- [ ] **Step 1: Replace the accessor and let the compiler find every call site**

In `IDimensionInfo`, delete `WorldStyle getWorldStyle();` and add:

```java
    /**
     * The world styles this dimension generates from, and which one applies where.
     * <p>
     * Replaces the old single {@code getWorldStyle()} deliberately: a world style is not one
     * scope (a city's {@code citystyles} versus a highway network's {@code parts}), and with a
     * weighted mix a call site that silently took a dimension-wide style would be wrong without
     * saying so. Ask the field for the scope you mean.
     */
    WorldStyleField worldStyles();
```

In `DefaultDimensionInfo`, replace the `style` field and its accessor:

```java
    private final WorldStyleField styles;
    ...
    public DefaultDimensionInfo(WorldGenLevel world, Preset preset, WorldStyleMix worldStyles) {
        this.world = world.getLevel();
        this.profile = preset;
        this.caches = new DimensionCaches(this.world.getSeed());
        styles = WorldStyleField.resolve(this.world, this.world.getSeed(), worldStyles);
        ...
    }

    @Override
    public WorldStyleField worldStyles() {
        return styles;
    }
```

In `CityFeature.java:145`: `new DefaultDimensionInfo(world, preset, choice.worldStyles())`.

In `NullDimensionInfo`, hold a `WorldStyleField` built with `WorldStyleField.single(seed, style)` for the placeholder path, or `WorldStyleField.resolve(...)` when a real registry is available; expose it from `worldStyles()`. Task 9 revisits this to take a mix.

- [ ] **Step 2: Run the build to enumerate the call sites**

Run: `./gradlew compileJava`
Expected: FAIL, with an error at each of the twelve `getWorldStyle()` sites. That error list is the work list for step 3.

- [ ] **Step 3: Point each call site at its scope**

Apply exactly this mapping. Nothing else changes.

**`primary()` — world-spanning:**

| File:line | New expression |
|---|---|
| `gen/Highways.java:48` | `info.provider.worldStyles().primary().getPartSelector().highwayParts()` |
| `gen/Railways.java:35` | `provider.worldStyles().primary().getPartSelector().railwayParts()` |
| `lost/Railway.java:101` | `provider.worldStyles().primary().getPartSelector().railwayParts()` |
| `lost/BuildingInfo.java:690` | `provider.worldStyles().primary().getWorldSettings().railwayAvoidance()` |
| `lost/BuildingInfo.java:762`, `:793` | `provider.worldStyles().primary().getWorldSettings().railPartHeight6()` |
| `lost/MultiChunk.java:218` | `provider.worldStyles().primary().getWorldSettings().railPartHeight6()` |
| `lost/MultiChunk.java:245` | `provider.worldStyles().primary().getWorldSettings().railwayAvoidance()` |
| `lost/MultiChunk.java:56` | `provider.worldStyles().primary().getMultiSettings().areasize()` |
| `lost/City.java:356` | `provider.worldStyles().primary().getCityChanceMultiplier(provider, coord)` |

Add this comment above `MultiChunk.java:56`:

```java
        // primary(), unlike the rest of multisettings below: areasize defines the grid that
        // getMultiCoord divides by, so it cannot come from an area that has not been identified yet.
```

and above `City.java:356`:

```java
            // primary(): this decides whether a city exists at all, so attributing it to a nearby
            // city would be circular.
```

**Per-area draws:**

`MultiChunk.java:75` (`calculateBuildings`) and `:260` (`isMultiBuildingOk`) — both already have `mc` (the divided anchor coord) or can take it from `this.mc`:

```java
        MultiSettings settings = provider.worldStyles().atMultiArea(mc).getMultiSettings();
```

`CityGenerator.java:444`:

```java
        // The scatter area's own anchor, so every chunk of one area agrees about which pack's
        // structure stands there - the same rule Scattered.generateScattered already applies to the
        // reference itself (issue #38).
        ScatteredSettings scatteredSettings = provider.worldStyles()
                .atScatterArea(Scattered.areaAnchor(provider, info.coord)).getScatteredSettings();
```

This needs the anchor calculation lifted out of `Scattered.generateScattered` into a public static, because the caller now needs it before it has the settings — and the settings' `areasize` is what the anchor depends on. Break that cycle by taking `areasize` from `primary()`:

```java
    /**
     * The anchor chunk of the scatter area {@code coord} falls in. Public because the caller has to
     * know the area before it can ask which world style governs it, and the answer must be the same
     * for every chunk of the area.
     * <p>
     * {@code areasize} comes from the primary style for the same reason {@code MultiChunk}'s does:
     * it defines the grid, so it cannot be read from a cell of a grid it has not defined.
     */
    public static ChunkCoord areaAnchor(IDimensionInfo provider, ChunkCoord coord) {
        int areasize = primaryAreasize(provider);
        int ax = (coord.chunkX() + 2000000) / areasize;
        int az = (coord.chunkZ() + 2000000) / areasize;
        return new ChunkCoord(provider.getType(), ax * areasize - 2000000, az * areasize - 2000000);
    }

    private static int primaryAreasize(IDimensionInfo provider) {
        ScatteredSettings settings = provider.worldStyles().primary().getScatteredSettings();
        return settings == null ? 1 : settings.getAreasize();
    }
```

Then `generateScattered` uses `areaAnchor(provider, info.coord)` in place of its inline `ax`/`az` computation, and keeps drawing its `Rng.Purpose.SCATTERED` stream at the **same** `(ax, az)` address it does today — divide the anchor back down, or keep the `ax`/`az` locals and build the anchor from them. **Do not change that address**: it is what the digests pin.

**Per-chunk:**

`lost/BuildingInfo.java:199` gains a memoised accessor and uses it:

```java
    /**
     * The world style governing this chunk: the dominant nearby city's, so a chunk on a city's edge
     * takes that city's look. Memoised because a BuildingInfo is per-chunk and long-lived, and the
     * lookup behind it walks the city neighbourhood.
     */
    public WorldStyle worldStyle() {
        if (chunkWorldStyle == null) {
            chunkWorldStyle = provider.worldStyles().atChunk(provider, coord);
        }
        return chunkWorldStyle;
    }

    public Style getOutsideStyle() {
        return AssetRegistries.STYLES.get(provider.getWorld(), worldStyle().getOutsideStyle());
    }
```

with `private WorldStyle chunkWorldStyle;` beside the other lazy fields.

`CityGenerator.java` — delete the `cachedRotatableTag` field and `rotatableTag()`, and change the signature so the tag comes from the chunk being generated:

```java
    /**
     * The block tag deciding what rotates with its part, from the world style governing this chunk.
     * <p>
     * Was resolved once per generator and cached. It cannot be, now that two cities in one world can
     * come from different packs with different {@code rotatable} tags - the tag has to follow the
     * chunk. {@link BuildingInfo#worldStyle()} memoises it per chunk, so this stays one field read
     * in the hot path.
     */
    private BlockState transformBlockState(BuildingInfo info, Transform transform, BlockState b) {
        if (Tools.hasTag(b.getBlock(), info.worldStyle().getRotatableTag())) {
```

and the single call site at `:1830` becomes `b = transformBlockState(info, transform, b);`.

**Per-city:**

`lost/City.java:217` (`getCityStyleForCityCenter`) — the centre's own style:

```java
        RandomSource cityStyleForCenterRandom = Rng.at(provider.getSeed(), chunkX, chunkZ, Rng.Purpose.CITY_STYLE);
        // The centre's own style, drawn at the centre: this is what makes one city coherent when a
        // world mixes several packs.
        return provider.worldStyles().atCityCenter(coord)
                .getRandomCityStyle(provider, coord, cityStyleForCenterRandom);
```

`lost/City.java:273` (the no-city-in-range fallback in `getCityStyleInt`):

```java
            cityStyleName = provider.worldStyles().atChunk(provider, coord)
                    .getRandomCityStyle(provider, coord, cityStyleRandom);
```

- [ ] **Step 4: Build and run the whole suite**

Run: `./gradlew build`
Expected: compiles clean, all existing tests pass. Fix any test that named `getWorldStyle()` (`NullDimensionInfoPlaceholderTest` does, at three lines) by renaming to `worldStyles().primary()`.

- [ ] **Step 5: Run both digest checks — the gate**

Run: `./gradlew digestCheck digestCheckFeatures`
Expected: PASS against the unchanged `digest.golden` and `digest-features.golden`.

If either fails, the single-style fast path has been broken somewhere. The usual causes, in order of likelihood: a `draw()` reached with one entry; the scattered area address changed by the `areaAnchor` refactor; a `Rng.Purpose` constant inserted rather than appended. Do **not** re-pin a golden.

- [ ] **Step 6: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: resolve world style per scope rather than per dimension"
```

---

### Task 7: Client selection state carries a mix

**Files:**
- Modify: `src/main/java/dev/krona/urbex/gui/PresetSelection.java`
- Modify: `src/main/java/dev/krona/urbex/gui/RecreateProfileRestore.java` (it passes the saved style string to `restore`)
- Test: `src/test/java/dev/krona/urbex/gui/WorldStyleSelectionTest.java`

**Interfaces:**
- Consumes: `WorldStyleMix`, `Config.worldStyleMixFromClient`, `Config.gateMix`, `Config.DEFAULT_WORLD_STYLE_MIX`.
- Produces:
  - `PresetSelection.setWorldStyles(WorldStyleMix)` — replaces `setWorldStyle(String)`.
  - `PresetSelection.effectiveWorldStyles()` → `WorldStyleMix`.
  - `PresetSelection.effectiveWorldStyle()` → `String`, kept; returns `effectiveWorldStyles().primary().toString()`.

- [ ] **Step 1: Write the failing test**

Add to `WorldStyleSelectionTest`:

```java
    @Test
    void aMixIsPublishedWholeAndSurvivesAPresetChange() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbexmt:moderntweaks"));
        selection.setAvailablePresets(List.of(entry("default")));
        selection.select(id("default"));

        WorldStyleMix mix = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9");
        selection.setWorldStyles(mix);
        selection.publish();

        assertEquals(mix, Config.worldStyleMixFromClient);
        assertEquals(mix, selection.effectiveWorldStyles());
        // The preview and the tab label still have a single id to show.
        assertEquals("urbexmt:moderntweaks", selection.effectiveWorldStyle());
    }

    @Test
    void injectingStylesThatDroppedOneOfTheMixPrunesIt() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbexmt:moderntweaks"));
        selection.setWorldStyles(WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9"));

        // The player disabled the ModernTweaks datapack on the Data Packs screen.
        selection.setAvailableWorldStyles(List.of("urbex:standard"));
        assertEquals(WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")),
                selection.effectiveWorldStyles());
    }

    @Test
    void aMixWithNothingLeftFallsBackToTheDefault() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("urbexmt:moderntweaks"));
        selection.setWorldStyles(WorldStyleMix.parse("urbexmt:moderntweaks"));
        selection.setAvailableWorldStyles(List.of("urbex:standard"));
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, selection.effectiveWorldStyles());
    }
```

Add imports for `dev.krona.urbex.setup.WorldStyleMix`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.krona.urbex.gui.WorldStyleSelectionTest'`
Expected: compilation failure — `setWorldStyles` does not exist.

- [ ] **Step 3: Write the implementation**

In `PresetSelection`, replace `@Nullable private String selectedWorldStyle` with `@Nullable private WorldStyleMix selectedWorldStyles`, and:

```java
    /**
     * Records the player's chosen styles - orthogonal to the preset (spec 1a). {@code null} means
     * "no override - use the default". Doesn't publish; the caller republishes so the change
     * reaches the server.
     */
    public void setWorldStyles(WorldStyleMix styles) {
        this.selectedWorldStyles = styles;
    }

    /** The chosen styles, or {@code null} for "use the default". */
    @Nullable
    public WorldStyleMix selectedWorldStyles() {
        return selectedWorldStyles;
    }

    /** The styles that will actually generate: the chosen override, or the default. */
    public WorldStyleMix effectiveWorldStyles() {
        return selectedWorldStyles != null ? selectedWorldStyles : Config.DEFAULT_WORLD_STYLE_MIX;
    }

    /**
     * One representative style id, for the preview's cache key and the tab's single-style label.
     * The mix's primary, so a single selection reads exactly as it did before mixing existed.
     */
    public String effectiveWorldStyle() {
        return effectiveWorldStyles().primary().toString();
    }
```

`setAvailableWorldStyles` prunes rather than clearing wholesale, so dropping one datapack does not silently discard the rest of a mix:

```java
    public void setAvailableWorldStyles(List<String> ids) {
        this.availableWorldStyles = ids == null ? List.of() : List.copyOf(ids);
        if (selectedWorldStyles == null) {
            return;
        }
        List<WorldStyleMix.Entry> kept = new ArrayList<>();
        for (WorldStyleMix.Entry entry : selectedWorldStyles.entries()) {
            if (availableWorldStyles.contains(entry.style().toString())) {
                kept.add(entry);
            }
        }
        // Nothing left means every chosen style went away with its datapack: fall back to "use the
        // default" rather than to an empty mix, which cannot be constructed anyway.
        selectedWorldStyles = kept.isEmpty() ? null : WorldStyleMix.of(kept);
    }
```

`publish()` sets `Config.worldStyleMixFromClient = Config.gateMix(effectiveWorldStyles(), "The world being created")` in place of the `worldStyleFromClient` assignment, and nulls that same field on the disabled branch. `discardPublication()` follows the rename.

`restore(String preset, String worldStyle, String overridesJson)` keeps its signature — `RecreateProfileRestore` reads `UrbexData`'s raw strings — but parses the style through `WorldStyleMix.parse` with a fallback:

```java
        WorldStyleMix styles;
        try {
            styles = (worldStyle == null || worldStyle.isEmpty())
                    ? Config.DEFAULT_WORLD_STYLE_MIX : WorldStyleMix.parse(worldStyle);
        } catch (IllegalArgumentException e) {
            Urbex.getLogger().warn("Re-created world used a malformed Urbex worldstyle spec '{}'; using {}.",
                    worldStyle, Config.DEFAULT_WORLD_STYLE_MIX.format());
            styles = Config.DEFAULT_WORLD_STYLE_MIX;
        }
```

Because `WorldStyleMix.parse` accepts a bare qualified id, a world saved with only `worldStyle` restores unchanged. Update `RecreateProfileRestore` to pass `data.getSelectedWorldStyles().format()` where it currently passes the raw `worldStyle` string, so a re-created mixed world restores its whole mix.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.krona.urbex.gui.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/krona/urbex/gui/PresetSelection.java src/main/java/dev/krona/urbex/gui/RecreateProfileRestore.java src/test/java/dev/krona/urbex/gui/WorldStyleSelectionTest.java
git commit -m "feat: client selection carries a world-style mix"
```

---

### Task 8: The picker UI

**Files:**
- Modify: `src/main/java/dev/krona/urbex/gui/WorldStyleDialog.java`
- Modify: `src/main/java/dev/krona/urbex/gui/CitiesTab.java:166-174,286-346`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json`
- Test: `src/test/java/dev/krona/urbex/gui/WorldStyleDialogTest.java`

**Interfaces:**
- Consumes: `WorldStyleMix`, `PresetSelection.effectiveWorldStyles`, `PresetSelection.setWorldStyles`, `Config.EXPERIMENTAL_MULTI_WORLD_STYLES`.
- Produces:
  - `WorldStyleDialog.MixRow(String style, boolean enabled, float weight)` — record, the dialog's editable row model.
  - `WorldStyleDialog.normalize(List<MixRow>)` → `List<Integer>` percentages, `-1` for a disabled row.
  - `WorldStyleDialog.toMix(List<MixRow>)` → `WorldStyleMix`.
  - `WorldStyleDialog.rowsFor(List<String> choices, WorldStyleMix current)` → `List<MixRow>`.
  - `WorldStyleDialog.canDisable(List<MixRow>, int index)` → `boolean`.
  - `new WorldStyleDialog(Screen parent, List<String> styles, WorldStyleMix current, boolean allowMixing, Consumer<WorldStyleMix> onSelect)`.

The four statics above are the whole of the dialog's logic; they are pure and get the tests. The widget code around them is exercised manually, exactly as the existing dialog is.

- [ ] **Step 1: Write the failing test**

Add to `WorldStyleDialogTest`:

```java
    private static WorldStyleDialog.MixRow row(String style, boolean enabled, float weight) {
        return new WorldStyleDialog.MixRow(style, enabled, weight);
    }

    @Test
    void percentagesAreNormalizedOverTheEnabledRowsOnly() {
        List<WorldStyleDialog.MixRow> rows = List.of(
                row("urbex:standard", true, 0.1f),
                row("urbexmt:moderntweaks", true, 0.9f),
                row("urbex:cavern", false, 1.0f));
        // The player types the balance they mean; the dialog does the dividing.
        assertEquals(List.of(10, 90, -1), WorldStyleDialog.normalize(rows));
    }

    @Test
    void weightsThatDoNotSumToOneStillReadAsPercentages() {
        List<WorldStyleDialog.MixRow> rows = List.of(
                row("urbex:standard", true, 1.0f),
                row("urbexmt:moderntweaks", true, 3.0f));
        assertEquals(List.of(25, 75), WorldStyleDialog.normalize(rows));
    }

    @Test
    void onlyEnabledRowsReachTheMix() {
        List<WorldStyleDialog.MixRow> rows = List.of(
                row("urbex:standard", true, 0.1f),
                row("urbexmt:moderntweaks", true, 0.9f),
                row("urbex:cavern", false, 1.0f));
        assertEquals(WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9"),
                WorldStyleDialog.toMix(rows));
    }

    @Test
    void theLastEnabledRowCannotBeDisabled() {
        List<WorldStyleDialog.MixRow> rows = List.of(
                row("urbex:standard", true, 1.0f),
                row("urbexmt:moderntweaks", false, 1.0f));
        // No sequence of clicks can produce an empty mix, so Done never has to be disabled.
        assertFalse(WorldStyleDialog.canDisable(rows, 0));
        assertTrue(WorldStyleDialog.canDisable(
                List.of(row("urbex:standard", true, 1.0f), row("urbexmt:moderntweaks", true, 1.0f)), 0));
    }

    @Test
    void rowsOpenShowingWhatCurrentlyGenerates() {
        List<WorldStyleDialog.MixRow> rows = WorldStyleDialog.rowsFor(
                List.of("urbex:standard", "urbexmt:moderntweaks", "urbex:cavern"),
                WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9"));
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).enabled());
        assertEquals(0.1f, rows.get(0).weight());
        assertTrue(rows.get(1).enabled());
        assertEquals(0.9f, rows.get(1).weight());
        // A registered style the mix does not name opens disabled, at a neutral weight.
        assertFalse(rows.get(2).enabled());
        assertEquals(1.0f, rows.get(2).weight());
    }
```

Add imports: `dev.krona.urbex.setup.WorldStyleMix`, `java.util.List`, `static org.junit.jupiter.api.Assertions.assertFalse`, `assertTrue`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.krona.urbex.gui.WorldStyleDialogTest'`
Expected: compilation failure — `MixRow` does not exist.

- [ ] **Step 3: Write the pure logic**

Add to `WorldStyleDialog`:

```java
    /** Minimum and maximum a weight stepper allows, and the step it moves by. */
    static final float MIN_WEIGHT = 0.05f;
    static final float MAX_WEIGHT = 10.0f;
    static final float WEIGHT_STEP = 0.05f;

    /**
     * One editable row of the mix editor: a registered style, whether it is in the mix, and its
     * relative weight. Weights are raw rather than percentages so a player can type the balance
     * they mean - 0.1 and 0.9 - without being made to produce numbers that sum to anything; the
     * dialog does the dividing for the display.
     */
    public record MixRow(String style, boolean enabled, float weight) {
    }

    /** The rows a dialog opens with: every registered style, those in {@code current} enabled. */
    public static List<MixRow> rowsFor(List<String> choices, WorldStyleMix current) {
        Map<String, Float> chosen = new LinkedHashMap<>();
        for (WorldStyleMix.Entry entry : current.entries()) {
            chosen.put(entry.style().toString(), entry.weight());
        }
        List<MixRow> rows = new ArrayList<>(choices.size());
        for (String choice : choices) {
            Float weight = chosen.get(choice);
            rows.add(new MixRow(choice, weight != null, weight != null ? weight : 1.0f));
        }
        return rows;
    }

    /**
     * The percentage beside each row: its share of the enabled rows' total weight, rounded to a
     * whole number, or {@code -1} for a disabled row (which shows a dash).
     */
    public static List<Integer> normalize(List<MixRow> rows) {
        float total = 0;
        for (MixRow row : rows) {
            if (row.enabled()) {
                total += row.weight();
            }
        }
        List<Integer> percentages = new ArrayList<>(rows.size());
        for (MixRow row : rows) {
            percentages.add(row.enabled() && total > 0 ? Math.round(row.weight() / total * 100f) : -1);
        }
        return percentages;
    }

    /** The mix the enabled rows describe. Never empty: {@link #canDisable} keeps one row on. */
    public static WorldStyleMix toMix(List<MixRow> rows) {
        List<WorldStyleMix.Entry> entries = new ArrayList<>();
        for (MixRow row : rows) {
            if (row.enabled()) {
                entries.add(new WorldStyleMix.Entry(DataTools.fromName(row.style()), row.weight()));
            }
        }
        return WorldStyleMix.of(entries);
    }

    /**
     * Whether the row at {@code index} may be switched off. The last enabled row may not: there is
     * no such thing as a world with no world style, so the toggle is simply inert rather than
     * letting the player reach a state Done would have to refuse.
     */
    public static boolean canDisable(List<MixRow> rows, int index) {
        if (!rows.get(index).enabled()) {
            return true;
        }
        int enabled = 0;
        for (MixRow row : rows) {
            if (row.enabled()) {
                enabled++;
            }
        }
        return enabled > 1;
    }
```

Imports to add: `dev.krona.urbex.setup.WorldStyleMix`, `dev.krona.urbex.worldgen.lost.regassets.data.DataTools`, `java.util.LinkedHashMap`, `java.util.Map`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.krona.urbex.gui.WorldStyleDialogTest'`
Expected: PASS.

- [ ] **Step 5: Build the widget layer**

Change the constructor to `WorldStyleDialog(Screen parent, List<String> styles, WorldStyleMix current, boolean allowMixing, Consumer<WorldStyleMix> onSelect)` and keep a `List<MixRow> rows` field seeded from `rowsFor(styles, current)`.

Two modes, one dialog:

- **`allowMixing == false`, or `styles.size() <= 1`** — render exactly today's `StyleList`: a row click calls `onSelect.accept(WorldStyleMix.of(DataTools.fromName(style)))` and closes. `preselectIndex(styles, current.primary().toString())` keeps its current job and its existing test. **No new widget renders in this mode.**
- **`allowMixing == true`** — render the mix editor. Each row draws, left to right: a `Checkbox` bound to `rows.get(i).enabled()` and gated on `canDisable(rows, i)`; the style id; a `[-]` and `[+]` `Button` pair stepping the weight by `WEIGHT_STEP` clamped to `[MIN_WEIGHT, MAX_WEIGHT]`; the weight to two decimals; and the percentage from `normalize(rows)` right-aligned, or `-` when `-1`. A `Done` button commits `onSelect.accept(toMix(rows))` and closes; `Cancel` closes without calling back. Widen `MAX_WIDTH` to `320` for this mode so the four controls fit; keep `220` for the single-pick mode.

A `[x] Mix` `Checkbox` in the header switches between the two, present only when `allowMixing` and `styles.size() > 1`. Switching to single mode keeps only the heaviest row enabled.

Every size stays derived from the row count and the screen, as the class doc already promises — no fixed screen coordinates.

- [ ] **Step 6: Wire the Cities tab**

In `CitiesTab`, the button opens the dialog with the flag:

```java
    private void openWorldStyleDropdown() {
        requestReopenOnCitiesTab();
        List<String> choices = PresetSelection.CLIENT.styleChoices();
        Minecraft.getInstance().gui.setScreen(new WorldStyleDialog(screen, choices,
                PresetSelection.CLIENT.effectiveWorldStyles(),
                Config.EXPERIMENTAL_MULTI_WORLD_STYLES.get(),
                this::onWorldStylesChanged));
    }

    private void onWorldStylesChanged(WorldStyleMix styles) {
        PresetSelection.CLIENT.setWorldStyles(styles);
        PresetSelection.CLIENT.publish();
        if (worldStyleButton != null) {
            worldStyleButton.setMessage(worldStyleLabel(styles));
            worldStyleButton.setTooltip(worldStyleTooltip(styles));
        }
    }

    /**
     * The selector's label: the style id for a single choice - unchanged from before mixing
     * existed - and a count for a mix, whose per-style percentages are in the tooltip rather than
     * squeezed onto a button that has to fit at GUI scale 4.
     */
    private static Component worldStyleLabel(WorldStyleMix styles) {
        Component value = styles.isSingle()
                ? Component.literal(styles.primary().toString())
                : Component.translatable("urbex.tab.worldstyle.mixed", styles.entries().size());
        return Component.translatable("urbex.tab.worldstyle").append(": ").append(value);
    }

    @Nullable
    private static Tooltip worldStyleTooltip(WorldStyleMix styles) {
        if (styles.isSingle()) {
            return null;
        }
        float total = 0;
        for (WorldStyleMix.Entry entry : styles.entries()) {
            total += entry.weight();
        }
        MutableComponent lines = Component.empty();
        boolean first = true;
        for (WorldStyleMix.Entry entry : styles.entries()) {
            if (!first) {
                lines.append(CommonComponents.NEW_LINE);
            }
            first = false;
            lines.append(Component.literal(entry.style() + "  " + Math.round(entry.weight() / total * 100f) + "%"));
        }
        return Tooltip.create(lines);
    }
```

`refreshDetail()`'s style block becomes `worldStyleButton.setMessage(worldStyleLabel(PresetSelection.CLIENT.effectiveWorldStyles()))` plus the tooltip, and the constructor's initial label the same. The `worldStyles.size() > 1` guard that decides whether the button exists at all is unchanged — a one-style install still shows nothing new.

- [ ] **Step 7: Add the lang keys**

In `src/main/resources/assets/urbex/lang/en_us.json`:

```json
  "urbex.tab.worldstyle.mixed": "%s mixed",
  "urbex.screen.worldstyle.mix": "Mix",
  "urbex.screen.worldstyle.done": "Done"
```

- [ ] **Step 8: Build and test**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/dev/krona/urbex/gui src/main/resources/assets/urbex/lang/en_us.json src/test/java/dev/krona/urbex/gui
git commit -m "feat: weighted world-style picker on the Cities tab"
```

---

### Task 9: The preview shows the mix

**Files:**
- Modify: `src/main/java/dev/krona/urbex/gui/NullDimensionInfo.java`
- Modify: `src/main/java/dev/krona/urbex/gui/preview/CityPreview.java:103,122-136,156-157,220,253-255`
- Modify: `src/main/java/dev/krona/urbex/gui/CitiesTab.java` (`PreviewWidget.extractWidgetRenderState`)
- Modify: `src/main/java/dev/krona/urbex/gui/CustomizeScreen.java` if it calls `preview.update`
- Test: `src/test/java/dev/krona/urbex/gui/NullDimensionInfoPlaceholderTest.java`

**Interfaces:**
- Consumes: `WorldStyleMix`, `WorldStyleField`.
- Produces: `NullDimensionInfo(Preset, WorldStyleMix, long, RegistryAccess)`; `CityPreview.update(Preset, WorldStyleMix, long, Mode)`.

- [ ] **Step 1: Update the existing placeholder test**

In `NullDimensionInfoPlaceholderTest`, change the three `getWorldStyle()` calls to `worldStyles().primary()` and the constructor calls to pass `WorldStyleMix.of(id)`. Add one test:

```java
    @Test
    void aMixedPreviewResolvesEveryStyleItWasGiven() {
        NullDimensionInfo diminfo = new NullDimensionInfo(new Preset(Identifier.parse("urbex:default")),
                WorldStyleMix.parse("urbex:standard*0.1+urbex:standard_everywhere*0.9"), 1234L, null);
        // Both are resolvable without a registry only via the placeholder path; what matters is
        // that the field carries as many styles as the mix named.
        assertEquals(2, diminfo.worldStyles().styles().size());
    }
```

If the placeholder path cannot resolve a second id without a registry, assert instead that `worldStyles().isSingle()` is false and that `primary()` is non-null — the point is that the preview does not silently collapse a mix.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.krona.urbex.gui.NullDimensionInfoPlaceholderTest'`
Expected: compilation failure.

- [ ] **Step 3: Write the implementation**

`NullDimensionInfo`'s constructor takes a `WorldStyleMix` and builds a `WorldStyleField`. Where the registry is present it uses `WorldStyleField.resolve(...)`; where it is not, it keeps building the hand-made placeholder style and wraps it with `WorldStyleField.single(seed, placeholder)`. The placeholder path is unchanged apart from the wrapper.

`CityPreview.Key`'s `String worldStyle` becomes the mix's `format()` string, so a weight change re-renders. `update` and `recompute` take a `WorldStyleMix` and pass it straight to `NullDimensionInfo`; the `DataTools.fromName(worldStyle)` call at `:255` goes away.

`CitiesTab.PreviewWidget` passes `PresetSelection.CLIENT.effectiveWorldStyles()`.

- [ ] **Step 4: Build and test**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/krona/urbex/gui src/test/java/dev/krona/urbex/gui
git commit -m "feat: the world-creation preview renders a mixed selection"
```

---

### Task 10: Docs, and verification against a real second datapack

**Files:**
- Modify: `README.md`, `docs/datapacks.md`

- [ ] **Step 1: Document the feature**

In `README.md`, under **Usage**, after the `dimensionsWithPresets` bullet:

```markdown
- **Mixing world styles (experimental).** Set `experimentalMultiWorldStyles: true` in
  `config/urbex/urbex.json` and the **World Style** picker on the Cities tab gains a **Mix** mode:
  tick several styles and give each a weight. Weights are relative, so `0.1` and `0.9` mean one
  city in ten comes from the first style. Each city draws its own style, so a world can hold cities
  from several datapacks at once. Server owners get the same thing in `dimensionsWithPresets`:
  `minecraft:overworld=urbex:default@urbex:standard*0.1+urbexmt:moderntweaks*0.9`. Highways,
  railways and the world settings come from the heaviest style, so a highway never changes pack
  partway along its run.
```

In `docs/datapacks.md`, add a short section to the world-style chapter explaining which fields are per-city and which are world-wide under mixing, pointing at the spec's §3 table.

- [ ] **Step 2: Run the whole suite and both digests**

Run: `./gradlew build digestCheck digestCheckFeatures`
Expected: PASS, with both goldens unchanged.

- [ ] **Step 3: Build the ModernTweaks pack**

The pack lives at `../../../Urbex-ModernTweaks` relative to this worktree (`/Volumes/Dev/Projects/krona/minecraft-mods/Urbex-ModernTweaks`). It has a `build.sh` that produces a zip in `dist/`. It registers `urbexmt:moderntweaks` at `pack/data/urbexmt/urbex/worldstyles/moderntweaks.json`, with its own `citystyles` (standard, desert, jungle, snowy), its own `outsidestyle`, its own highway and railway `parts`, its own `rotatable` tag, and a `scattered` block naming `urbexmt:cabin` alongside `urbex:radiotower` and `urbex:oilrig`.

Run: `bash /Volumes/Dev/Projects/krona/minecraft-mods/Urbex-ModernTweaks/build.sh`

- [ ] **Step 4: Verify a mixed world generates**

This is requirements 3 and 4 of the goal — that the styles can actually be used, and that scattered works.

1. `./gradlew runClient` (or install the built jar plus the ModernTweaks zip into a dev instance).
2. Set `experimentalMultiWorldStyles: true` in `config/urbex/urbex.json` and restart.
3. Create a world with the ModernTweaks datapack enabled, pick a preset on the **Cities** tab, open **World Style**, tick **Mix**, and set `urbex:standard` to `0.1` and `urbexmt:moderntweaks` to `0.9`. Confirm the readout says `10%` / `90%` and the tab button says `World Style: 2 mixed`.
4. In the world, fly out and confirm:
   - cities of both flavours appear, roughly nine ModernTweaks to one Urbex;
   - each individual city is internally one flavour — no half-and-half city;
   - **scattered structures mix**: both `urbex:radiotower` and `urbexmt:cabin` appear;
   - a highway or rail line keeps one pack's parts for its whole run.
5. `/urbex savepreset` still works, and reloading the world keeps the mix (check `worldStyleMix` in the world's `data/urbex/data.dat`).
6. Set `experimentalMultiWorldStyles: false`, restart, and confirm the Cities tab shows the plain single-pick dialog again and the existing world generates with `urbexmt:moderntweaks` alone (the primary), with the reduction logged.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/datapacks.md
git commit -m "docs: how to mix world styles"
```

---

## Self-Review

**Spec coverage.** §4.1 → Task 1. §4.2 and §4.3 → Task 5. §5 → Task 6's mapping table. §6.1 → Task 2 and `Config.gateMix` in Task 3. §6.2 → Task 3. §7 → Task 4. §8 → Tasks 7–9. §9 → Task 5 (`WORLD_STYLE` appended, tie-break on id). §10 → the test in each task, plus Task 10 steps 2 and 4. §11 → the File Structure table.

**Type consistency.** `WorldStyleMix.Entry(Identifier, float)` is used with that argument order in Tasks 1, 3, 7 and 8. `WorldStyleField.Weighted(float, WorldStyle)` is `(weight, style)` in Task 5's implementation and its test. `PresetChoice.worldStyles()` is used in Tasks 3, 4 and 6. `IDimensionInfo.worldStyles()` is used in Task 6's mapping table and Task 9. `PresetSelection.effectiveWorldStyles()` is used in Tasks 7, 8 and 9; `effectiveWorldStyle()` survives as the primary's string, used by Task 8's single-style label path.

**Known gap, deliberate.** Task 5's `WorldStyleFieldTest.style(...)` helper is left to be lifted from `NullDimensionInfoPlaceholderTest` rather than transcribed here, because the `WorldStyleRE` constructor takes nine `Optional` arguments whose exact order must be read from the file rather than trusted from memory. The step says so, and says not to commit the placeholder exception.
