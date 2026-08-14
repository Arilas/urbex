# Per-City-Style Edge Variants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move city-edge appearance out of presets and into an optional, atomic `edge` on each `worldStyle.citystyles[]` selector, while keeping one stable base/edge family per centred city or Perlin region.

**Architecture:** A selector decodes to a weighted, biome-filtered `CityStyleSelection` containing its base city-style id and optional validated `CityStyleEdge`. `WorldStyle` chooses that family once at the existing spatial scope; `City` maps each contributing city factor to the family’s base or edge member before preserving the current overlap weighting. The obsolete preset fields are deleted and guarded as retired keys, and both members of every family enter the same eager asset-validation graph.

**Tech Stack:** Java 25, Mojang DataFixerUpper codecs, Fabric/Minecraft world generation, JUnit 5, NetworkNT JSON Schema, Gradle

**Spec:** [`docs/superpowers/specs/2026-08-14-per-citystyle-edge-variants-design.md`](../specs/2026-08-14-per-citystyle-edge-variants-design.md)

## Global Constraints

- `citystyles[].factor` remains only the weighted selector-entry choice; `edge.threshold` is only the spatial city-factor boundary.
- `edge` is optional per selector entry. If present, both `citystyle` and `threshold` are required, and `threshold` must be finite with `0 < threshold <= 1`.
- Resolve the edge only when `cityFactor < threshold`; equality and larger factors use the base.
- Select one family per centred city or one family per 16-by-16-chunk Perlin region. Never reroll the selector at an observing edge chunk.
- Preserve the existing factor-weighted overlap draw after resolving every contributing centre to its own base or edge id.
- An explicit predefined-city style is base-only. A predefined city without an explicit style follows the ordinary world-style family.
- Delete `cities.cityStyleThreshold` and `cities.cityStyleAlternative` without compatibility decoding, precedence, fallback, or automatic migration.
- Specifically rethrow retired-key failures at every per-world override boundary; malformed overrides unrelated to these retired keys may retain their existing fail-soft behaviour.
- Do not create a world-style JSON schema: this repository has none. Update the datapack guide and remove the retired properties from the preset schema.
- Preserve unrelated worktree changes. Do not update digest goldens until targeted semantic and asset-validation tests pass.
- Each task below is a medium-to-large review unit and ends in one focused commit.

---

### Task 1: Introduce city-style families and validate both family members

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/CityStyleEdge.java`
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/CityStyleSelection.java`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/regassets/data/CityStyleSelectorEdgeTest.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/DataTools.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/CityStyleSelector.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/WorldStyle.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetCompiler.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetGraph.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/City.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/AssetCompilerTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/AssetGraphTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RequiredAfterResolutionTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RegistryChainResolutionTest.java`

**Interfaces:**
- `CityStyleEdge(String citystyle, float threshold)` owns the nested codec and threshold validation.
- `CityStyleSelection(String citystyle, Optional<CityStyleEdge> edge)` owns `styleAt(float cityFactor)` and `baseOnly(String citystyle)`.
- `CityStyleSelector(..., Optional<CityStyleEdge> edge)` owns `selection()` and retains a three-argument constructor for source compatibility inside this repository.
- `WorldStyle.getRandomCityStyle(...)` returns a nullable `CityStyleSelection`, not a raw id.
- Asset compilation and graph traversal treat `selection.citystyle` and `selection.edge.citystyle` as equal city-style roots.

- [ ] **Step 1: Write the failing selector and threshold tests**

Add `CityStyleSelectorEdgeTest` cases that decode with `JsonOps.INSTANCE`, encode back to JSON, and inspect the `DataResult` error text:

```java
@Test
void selectorWithoutEdgeIsBaseOnly() {
    CityStyleSelector selector = decode("""
            {"factor": 0.5, "citystyle": "test:base"}
            """);

    assertEquals("test:base", selector.selection().styleAt(0.1f));
    assertTrue(selector.edge().isEmpty());
}

@Test
void completeEdgeRoundTripsAndUsesStrictBoundary() {
    CityStyleSelector selector = decode("""
            {
              "factor": 0.5,
              "citystyle": "test:base",
              "edge": {"citystyle": "test:edge", "threshold": 0.4}
            }
            """);

    assertEquals("test:edge", selector.selection().styleAt(0.399f));
    assertEquals("test:base", selector.selection().styleAt(0.4f));
    assertEquals("test:base", selector.selection().styleAt(0.8f));
    assertTrue(encode(selector).has("edge"));
}
```

Cover these failures separately: missing `edge.citystyle`, missing `edge.threshold`, blank or unqualified edge id, `NaN`, positive infinity, zero, a negative threshold, and a threshold above one. Add a two-entry case proving one selection cannot borrow the other entry’s edge.

Run:

```bash
./gradlew test --tests dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelectorEdgeTest
```

Expected: compilation or assertions fail because the new records and `edge` codec do not exist.

- [ ] **Step 2: Implement the atomic edge codec and family value**

Add a strict string-reference codec next to `STRICT_IDENTIFIER_CODEC` so edge ids preserve the surrounding string-based model without accepting an implicit `minecraft` namespace:

```java
public static final Codec<String> STRICT_REFERENCE_CODEC =
        STRICT_IDENTIFIER_CODEC.xmap(Identifier::toString, DataTools::fromName);
```

Implement the new records with the exact boundary rule:

```java
public record CityStyleEdge(String citystyle, float threshold) {
    private static final Codec<Float> THRESHOLD = Codec.FLOAT.validate(value ->
            Float.isFinite(value) && value > 0.0f && value <= 1.0f
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Edge threshold must be finite and satisfy 0 < threshold <= 1"));

    public static final Codec<CityStyleEdge> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_REFERENCE_CODEC.fieldOf("citystyle").forGetter(CityStyleEdge::citystyle),
                    THRESHOLD.fieldOf("threshold").forGetter(CityStyleEdge::threshold)
            ).apply(instance, CityStyleEdge::new));
}

public record CityStyleSelection(String citystyle, Optional<CityStyleEdge> edge) {
    public static CityStyleSelection baseOnly(String citystyle) {
        return new CityStyleSelection(citystyle, Optional.empty());
    }

    public String styleAt(float cityFactor) {
        return edge.filter(value -> cityFactor < value.threshold())
                .map(CityStyleEdge::citystyle)
                .orElse(citystyle);
    }
}
```

Keep the value types immutable and free of registry lookups or random selection.

- [ ] **Step 3: Extend the selector codec and change the WorldStyle draw result**

Change the selector record and preserve the convenient existing constructor used by tests:

```java
public record CityStyleSelector(float factor, String citystyle,
                                BiomeMatcher biomeMatcher,
                                Optional<CityStyleEdge> edge) {
    public CityStyleSelector(float factor, String citystyle, BiomeMatcher biomeMatcher) {
        this(factor, citystyle, biomeMatcher, Optional.empty());
    }

    public CityStyleSelection selection() {
        return new CityStyleSelection(citystyle, edge);
    }
}
```

Decode `CityStyleEdge.CODEC.optionalFieldOf("edge")` in the same record codec. In `WorldStyle`, change the resolved list element from `Pair<Float, String>` to `Pair<Float, CityStyleSelection>`, copy `selector.selection()` into it, and return the selected family from `getRandomCityStyle`.

Temporarily adapt `City` call sites to use `.citystyle()` so this task compiles without activating spatial edge behaviour before Task 2. Preserve null handling when no biome-eligible selector exists.

- [ ] **Step 4: Make asset compilation and graph validation traverse the edge**

Replace every world-style-selector access that assumes `pair.getRight().getRight()` is a string. `AssetCompiler.reachableCityStyles` must enqueue both roots:

```java
CityStyleSelection selection = weighted.getRight();
reachable.add(selection.citystyle());
selection.edge().ifPresent(edge -> reachable.add(edge.citystyle()));
```

Do the same when `AssetGraph` discovers city-style palettes and dependencies. Do not make an edge recursively select another family: resolve it as an ordinary direct `CityStyle` id.

Add asset tests for:

- a valid base plus valid edge entering the reachable set;
- a missing edge id producing the same load diagnostic class as a missing base id;
- an edge city style with incomplete required wiring refusing compilation;
- no-edge selectors preserving the prior base-only graph.

- [ ] **Step 5: Update typed selector assertions and run the focused suite**

Update chain-resolution tests to assert through `CityStyleSelection.citystyle()` and `edge()` instead of nested string pairs. Keep assertions for selector-list replace/append semantics so an edge remains atomic with its entry.

Run:

```bash
./gradlew test \
  --tests dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelectorEdgeTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.AssetCompilerTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.AssetGraphTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.RequiredAfterResolutionTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.RegistryChainResolutionTest
```

Expected: all focused tests pass.

- [ ] **Step 6: Commit the family model and eager validation**

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/DataTools.java \
  src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/CityStyleEdge.java \
  src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/CityStyleSelection.java \
  src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/CityStyleSelector.java \
  src/main/java/dev/krona/urbex/worldgen/lost/cityassets/WorldStyle.java \
  src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetCompiler.java \
  src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetGraph.java \
  src/main/java/dev/krona/urbex/worldgen/lost/City.java \
  src/test/java/dev/krona/urbex/worldgen/lost/regassets/data/CityStyleSelectorEdgeTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/cityassets/AssetCompilerTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/cityassets/AssetGraphTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RequiredAfterResolutionTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RegistryChainResolutionTest.java
git commit -m 'feat: model per-city-style edge families'
```

---

### Task 2: Apply one stable family at each city scope

**Files:**
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/CityStyleScopeTest.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/City.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/WorldStyleField.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/PredefinedCity.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/TestWorldStyles.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/WorldStyleFieldTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RequiredAfterResolutionTest.java`

**Interfaces:**
- `WorldStyleField.perlinRegionAnchor(ChunkCoord coord)` is public because `City` lives in the `worldgen.lost` subpackage; it returns the minimum chunk coordinate of the containing 16-by-16 region using `floorDiv`.
- `City.getCityStyleSelectionForCityCenter(...)` returns a stable family; explicit predefined styles become `CityStyleSelection.baseOnly(...)`.
- A package-private Perlin-scope helper draws its family from the canonical region anchor’s biome and `CITY_STYLE` RNG address.
- `PredefinedCity.getCityStyle()` may return `null` when no style is declared.

- [ ] **Step 1: Write failing spatial-scope tests**

Create fixtures in `TestWorldStyles` that accept a selector list, then add `CityStyleScopeTest` and `WorldStyleFieldTest` coverage for:

```java
assertEquals(anchor(dimension, 0, 0), perlinAnchor(dimension, 15, 15));
assertEquals(anchor(dimension, 16, 0), perlinAnchor(dimension, 16, 15));
assertEquals(anchor(dimension, -16, -16), perlinAnchor(dimension, -1, -1));
assertEquals(anchor(dimension, -32, 0), perlinAnchor(dimension, -17, 3));
```

Also prove:

- two chunks in one Perlin region use the same selected family even when their local factors choose different members;
- adjacent regions may deterministically choose different families;
- centred-city observers resolve the same family selected at the centre;
- different biome-filtered selector entries are evaluated at the centre/region anchor, not at the observer;
- two overlapping centres retain their factor weights after each resolves base versus edge;
- an explicit predefined style remains base-only at low factor;
- a predefined city omitting `citystyle` resolves successfully and follows the ordinary family.

Run:

```bash
./gradlew test \
  --tests dev.krona.urbex.worldgen.WorldStyleFieldTest \
  --tests dev.krona.urbex.worldgen.lost.CityStyleScopeTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.RequiredAfterResolutionTest
```

Expected: new assertions fail against per-chunk selection and required predefined style behaviour.

- [ ] **Step 2: Add the canonical Perlin region anchor**

Expose the existing grid size only through one helper and reuse it wherever Perlin family scope is needed:

```java
public static ChunkCoord perlinRegionAnchor(ChunkCoord coord) {
    int x = Math.floorDiv(coord.chunkX(), PERLIN_REGION_CHUNKS) * PERLIN_REGION_CHUNKS;
    int z = Math.floorDiv(coord.chunkZ(), PERLIN_REGION_CHUNKS) * PERLIN_REGION_CHUNKS;
    return new ChunkCoord(coord.dimension(), x, z);
}
```

Keep `WorldStyleField.atChunk`’s mixed-style draw semantics unchanged. The helper supplies the canonical biome and city-family RNG coordinates; the field still selects the region’s world style on its existing coarse grid.

- [ ] **Step 3: Return stable selections for centred and predefined cities**

Change the centre helper from a string result to a `CityStyleSelection`:

```java
static CityStyleSelection getCityStyleSelectionForCityCenter(
        ChunkCoord center, PlanningContext provider) {
    PredefinedCity predefined = getPredefinedCity(provider, center);
    if (predefined != null && predefined.getCityStyle() != null) {
        return CityStyleSelection.baseOnly(predefined.getCityStyle());
    }
    RandomSource random = Rng.at(provider.seed(), center.chunkX(), center.chunkZ(),
            Rng.Purpose.CITY_STYLE);
    return provider.worldStyles().atCityCenter(center)
            .getRandomCityStyle(provider, center, random);
}
```

In `PredefinedCity`, stop requiring `citystyle` after chain resolution and retain the final declared value, which may be null. Keep dimension, coordinates, and radius required.

- [ ] **Step 4: Resolve each centred-city candidate before overlap blending**

For every centre inside its radius:

```java
float factor = (radius - dist) / radius;
CityStyleSelection selection = getCityStyleSelectionForCityCenter(center, provider);
if (selection != null) {
    styles.add(Pair.of(factor, selection.styleAt(factor)));
}
```

Do not change the final `Tools.getRandomFromList(..., Pair::getLeft)` call or its `CITY_STYLE_LOCAL` RNG. That final draw is the overlap behaviour being preserved.

When the candidate list is empty, draw a family from `worldStyles().atChunk(...)` and use its base id; there is no contributing city factor with which to choose an edge.

- [ ] **Step 5: Resolve one Perlin family at the region anchor**

Replace the per-chunk centre-helper call in the `cityChance < 0` branch with:

```java
ChunkCoord anchor = WorldStyleField.perlinRegionAnchor(coord);
RandomSource familyRandom = Rng.at(provider.seed(), anchor.chunkX(), anchor.chunkZ(),
        Rng.Purpose.CITY_STYLE);
CityStyleSelection selection = provider.worldStyles().atChunk(provider, anchor)
        .getRandomCityStyle(provider, anchor, familyRandom);
if (selection != null) {
    styles.add(Pair.of(factor, selection.styleAt(factor)));
}
```

The local chunk factor only decides base versus edge. It must not enter the family RNG or biome lookup.

- [ ] **Step 6: Run scope tests and commit the runtime semantics**

Run:

```bash
./gradlew test \
  --tests dev.krona.urbex.worldgen.WorldStyleFieldTest \
  --tests dev.krona.urbex.worldgen.lost.CityStyleScopeTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.RequiredAfterResolutionTest
```

Expected: all scope, anchor, threshold, overlap, and predefined-city tests pass.

Commit:

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/City.java \
  src/main/java/dev/krona/urbex/worldgen/WorldStyleField.java \
  src/main/java/dev/krona/urbex/worldgen/lost/cityassets/PredefinedCity.java \
  src/test/java/dev/krona/urbex/worldgen/TestWorldStyles.java \
  src/test/java/dev/krona/urbex/worldgen/WorldStyleFieldTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/CityStyleScopeTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RequiredAfterResolutionTest.java
git commit -m 'feat: scope city-style families to cities and regions'
```

---

### Task 3: Delete the preset alternative and reject stale overrides

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/RetiredPresetKeyException.java`
- Create: `src/test/java/dev/krona/urbex/data/PresetOverrideDecodeSitesTest.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/RetiredKeys.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/preset/CitySettings.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/PresetDefinition.java`
- Modify: `src/main/java/dev/krona/urbex/config/PresetDraft.java`
- Modify: `src/main/java/dev/krona/urbex/config/Preset.java`
- Modify: `src/main/java/dev/krona/urbex/gui/settings/Settings.java`
- Modify: `src/main/java/dev/krona/urbex/gui/PresetSelection.java`
- Modify: `src/main/java/dev/krona/urbex/setup/Config.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/DimensionRuntime.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetCompiler.java`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json`
- Modify: `src/main/resources/data/urbex/urbex/presets/largecities.json`
- Modify: `docs/schema/preset.schema.json`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/regassets/data/RetiredKeysRejectedTest.java`
- Modify: `src/test/java/dev/krona/urbex/config/PresetSchemaTest.java`
- Modify: `src/test/java/dev/krona/urbex/gui/settings/SettingsCompletenessTest.java`
- Modify: `src/test/java/dev/krona/urbex/data/CityStyleLookupSitesTest.java`

**Interfaces:**
- `RetiredKeys.problem/reject` gain overloads accepting an ordered key-to-error-message map while the existing two-argument inheritance guard delegates unchanged.
- `PresetDefinition.parseOverrides(JsonElement)` performs the nested `cities` retired-key precheck and then the normal codec parse.
- `RetiredPresetKeyException` marks the one parse failure that no override boundary may catch and downgrade.
- `CitySettings.RETIRED_KEY_ERRORS` is one public immutable ordered map reused by registry decoding and override prechecks, so both routes emit identical migration text.
- `Preset`, `PresetDraft`, `CitySettings`, and GUI settings no longer expose either alternative field.

- [ ] **Step 1: Write failing retirement and schema tests**

In `RetiredKeysRejectedTest`, test each old key independently and both together under the nested `cities` object. Require a deterministic message that names the full path and the replacement:

```text
Preset key 'cities.cityStyleAlternative' was removed; declare the selected world style's
'citystyles[].edge' with 'citystyle' and 'threshold' instead.
```

Add tests for both registry decoding and `PresetDefinition.parseOverrides(...)`. Add `PresetOverrideDecodeSitesTest`, following the repository’s source-audit test style, to prove `PresetSelection`, `Config`, and `DimensionRuntime` call the central parser and explicitly rethrow `RetiredPresetKeyException` rather than turning it into a plain-preset fallback.

In `PresetSchemaTest`, assert that valid current `cities` content passes and each retired property is rejected by `additionalProperties: false`.

Run:

```bash
./gradlew test \
  --tests dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeysRejectedTest \
  --tests dev.krona.urbex.config.PresetSchemaTest \
  --tests dev.krona.urbex.gui.settings.SettingsCompletenessTest \
  --tests dev.krona.urbex.data.PresetOverrideDecodeSitesTest \
  --tests dev.krona.urbex.data.CityStyleLookupSitesTest
```

Expected: tests fail because the fields and fail-soft paths still exist.

- [ ] **Step 2: Generalize retired-key rejection for nested preset settings**

Add ordered-map overloads without changing existing inheritance messages. The custom map contains the final message rather than only a replacement token, which lets the preset guard name `cities.<key>` precisely:

```java
public static Optional<String> problem(
        Dynamic<?> dyn, Map<String, String> errorsByKey) { ... }

public static <A> Codec<A> reject(
        Codec<A> base, Map<String, String> errorsByKey) { ... }
```

Make the current `problem(dyn, context)` and `reject(base, context)` retain their existing inheritance messages while delegating key-presence detection to the new overload. Define `CitySettings.RETIRED_KEY_ERRORS` as an unmodifiable `LinkedHashMap` whose values are the complete migration errors, then wrap `CitySettings.RAW` before `UnknownKeys.warning`, so old fields fail rather than becoming ignorable unknown-key warnings.

- [ ] **Step 3: Add one strict override parser and preserve unrelated fail-soft behaviour**

Add `RetiredPresetKeyException` and centralize override parsing in `PresetDefinition`:

```java
public static PresetDefinition parseOverrides(JsonElement json) {
    Dynamic<JsonElement> root = new Dynamic<>(JsonOps.INSTANCE, json);
    root.get("cities").result().ifPresent(cities ->
            RetiredKeys.problem(cities, CitySettings.RETIRED_KEY_ERRORS)
                    .ifPresent(message -> {
                        throw new RetiredPresetKeyException(message);
                    }));
    return CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
}
```

Use the shared public immutable error map so `CitySettings.CODEC` and `parseOverrides` emit the same migration text across their different Java packages.

Replace direct `PresetDefinition.CODEC.parse(...)` calls for override JSON in `PresetSelection`, `Config`, and `DimensionRuntime`. At each existing fail-soft catch, rethrow `RetiredPresetKeyException` before handling other malformed input:

```java
} catch (RetiredPresetKeyException e) {
    throw e;
} catch (Exception e) {
    // Existing malformed-override fallback and log.
}
```

This is the deliberate breaking-change boundary: stale keys refuse the operation, while corrupted JSON and unrelated invalid values keep the current recovery policy.

- [ ] **Step 4: Remove the fields from every model, GUI, runtime, and data path**

Delete both properties from:

- `CitySettings` record components, `Part1`, codec, key set, mapping, and `apply`;
- `PresetDraft` constants/state/copy/accessors;
- `Preset` constructor/state/copy/serialization/accessors;
- the Cities/Advanced settings descriptors in `Settings`;
- `en_us.json` labels and tooltips;
- `DimensionRuntime.requireCityStyle` and its obsolete runtime-route comments/helper;
- `AssetCompiler`’s preset-alternative reachability route if any remains after Task 1;
- `largecities.json`;
- `docs/schema/preset.schema.json`.

Update `CityStyleLookupSitesTest` so only legitimate runtime city-style lookups remain. Let `SettingsCompletenessTest` prove no descriptor, getter, translation, or preset key is dangling.

- [ ] **Step 5: Prove complete removal and strict failure**

Run:

```bash
rg -n 'cityStyleThreshold|cityStyleAlternative|CITY_STYLE_THRESHOLD|CITY_STYLE_ALTERNATIVE' \
  src/main src/test docs/schema src/main/resources/data/urbex/urbex/presets
./gradlew test \
  --tests dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeysRejectedTest \
  --tests dev.krona.urbex.config.PresetSchemaTest \
  --tests dev.krona.urbex.gui.settings.SettingsCompletenessTest \
  --tests dev.krona.urbex.data.PresetOverrideDecodeSitesTest \
  --tests dev.krona.urbex.data.CityStyleLookupSitesTest
```

Expected: `rg` finds the spellings only in `CitySettings.RETIRED_KEY_ERRORS`, retirement/schema/source-audit tests, and no live preset field, accessor, GUI descriptor, runtime branch, bundled preset, or schema property. All focused tests pass.

- [ ] **Step 6: Commit the breaking preset cleanup**

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/regassets/RetiredPresetKeyException.java \
  src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/RetiredKeys.java \
  src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/preset/CitySettings.java \
  src/main/java/dev/krona/urbex/worldgen/lost/regassets/PresetDefinition.java \
  src/main/java/dev/krona/urbex/config/PresetDraft.java \
  src/main/java/dev/krona/urbex/config/Preset.java \
  src/main/java/dev/krona/urbex/gui/settings/Settings.java \
  src/main/java/dev/krona/urbex/gui/PresetSelection.java \
  src/main/java/dev/krona/urbex/setup/Config.java \
  src/main/java/dev/krona/urbex/worldgen/DimensionRuntime.java \
  src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetCompiler.java \
  src/main/resources/assets/urbex/lang/en_us.json \
  src/main/resources/data/urbex/urbex/presets/largecities.json \
  docs/schema/preset.schema.json \
  src/test/java/dev/krona/urbex/worldgen/lost/regassets/data/RetiredKeysRejectedTest.java \
  src/test/java/dev/krona/urbex/config/PresetSchemaTest.java \
  src/test/java/dev/krona/urbex/gui/settings/SettingsCompletenessTest.java \
  src/test/java/dev/krona/urbex/data/PresetOverrideDecodeSitesTest.java \
  src/test/java/dev/krona/urbex/data/CityStyleLookupSitesTest.java
git commit -m 'refactor!: remove preset city-style alternatives'
```

---

### Task 4: Migrate bundled families and document the new ownership

**Files:**
- Modify: `src/main/resources/data/urbex/urbex/worldstyles/standard.json`
- Modify: `src/test/java/dev/krona/urbex/data/WorldStyleCompletenessTest.java`
- Modify: `src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/AssetCompilerTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/AssetGraphTest.java`
- Modify: `docs/datapacks.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Both bundled standard-world-style selector entries point to `urbex:citystyle_border` with threshold `0.4`.
- Static datapack traversals visit `citystyles[].citystyle` and optional `citystyles[].edge.citystyle`, with no preset route.
- The guide presents edge ownership, threshold semantics, anchor-biome behaviour, and the breaking preset migration.

- [ ] **Step 1: Write failing bundled-data and static-reference tests**

Update `WorldStyleCompletenessTest` and `DatapackReferenceIntegrityTest` to traverse:

```java
JsonObject selector = entry.getAsJsonObject();
references.add(selector.get("citystyle").getAsString());
if (selector.has("edge")) {
    references.add(selector.getAsJsonObject("edge").get("citystyle").getAsString());
}
```

Remove the old preset-alternative branch. Add explicit assertions that:

- `urbex:standard` has two selector entries;
- both declare `urbex:citystyle_border` at `0.4`;
- the border style is reachable when no preset contributes city-style ids;
- a missing nested edge reference fails the same integrity check as a missing base.

Run:

```bash
./gradlew test \
  --tests dev.krona.urbex.data.WorldStyleCompletenessTest \
  --tests dev.krona.urbex.data.DatapackReferenceIntegrityTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.AssetCompilerTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.AssetGraphTest
```

Expected: bundled-family assertions fail until `standard.json` is migrated.

- [ ] **Step 2: Attach the bundled edge per selector entry**

Add this object to both the standard and desert entries in `worldstyles/standard.json`:

```json
"edge": {
  "citystyle": "urbex:citystyle_border",
  "threshold": 0.4
}
```

Do not factor it into a world-style-wide property. Keep each nested declaration independently authored even though their current values match.

- [ ] **Step 3: Rewrite the datapack guide examples and migration notes**

In `docs/datapacks.md`:

- show one base-only selector and one selector with a complete `edge`;
- state that selector `factor` is a weighted-choice weight while `edge.threshold` is a spatial city factor;
- document strict `<` and base-at-equality behaviour;
- explain centre anchoring and minimum-coordinate Perlin-region anchoring, including anchor biome matching;
- state that an edge applies under every preset and omission means base-only;
- remove all preset examples, required-field lists, reachability routes, and troubleshooting text for the old alternative fields;
- make predefined-city `citystyle` optional in the required-field table and explain explicit style as base-only;
- add a breaking migration example from the two removed preset keys to per-entry world-style edges;
- mention future typed districts only as a possible later sibling field, not as supported syntax.

Keep all annotated JSON examples parseable so `DatapackGuideExamplesTest` continues to execute them.

- [ ] **Step 4: Record the breaking data-format change**

Add an Unreleased changelog item covering:

- optional per-entry `citystyles[].edge`;
- stable centred-city and 16-by-16 Perlin-region family selection;
- eager edge asset validation;
- deletion of both preset alternative fields;
- bundled `urbex:citystyle_border` applying through the standard world style under every preset.

Do not edit `docs/history/CHANGELOG-lostcities.txt`; it is historical upstream material.

- [ ] **Step 5: Run all data, schema, guide, and unit tests**

Run:

```bash
./gradlew test
```

Expected: every unit, codec, schema, guide-example, static-reference, and asset-compilation test passes. Digest tasks are intentionally deferred to Task 5.

- [ ] **Step 6: Commit bundled data and documentation**

```bash
git add src/main/resources/data/urbex/urbex/worldstyles/standard.json \
  src/test/java/dev/krona/urbex/data/WorldStyleCompletenessTest.java \
  src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/cityassets/AssetCompilerTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/cityassets/AssetGraphTest.java \
  docs/datapacks.md CHANGELOG.md
git commit -m 'docs: migrate city edges to world styles'
```

---

### Task 5: Review intentional generation changes and complete acceptance verification

**Files:**
- Modify after review: `digest.golden`
- Modify after review: `digest-features.golden`
- Modify after review: `digest-avoid.golden`
- Modify after review: `digest-avoid-modes.golden`
- Modify after review: `digest-rail.golden`
- Modify only if verification exposes a defect: files from Tasks 1–4 and their closest tests

**Interfaces:**
- The five digest fixtures record the reviewed intentional generation output after bundled edges become active.
- Shuffled/threaded/expiry variants must agree with their primary digest, proving order and cache stability.
- Manual inspection must cover one radius city and one Large Cities Perlin boundary before accepting hashes.

- [ ] **Step 1: Re-run semantic tests before touching goldens**

Run:

```bash
./gradlew test \
  --tests dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelectorEdgeTest \
  --tests dev.krona.urbex.worldgen.lost.CityStyleScopeTest \
  --tests dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeysRejectedTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.AssetCompilerTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.AssetGraphTest \
  --tests dev.krona.urbex.data.WorldStyleCompletenessTest \
  --tests dev.krona.urbex.data.DatapackReferenceIntegrityTest
```

Expected: all semantic tests pass. Stop and fix the implementation if any fail; do not use a digest update to mask a semantic failure.

- [ ] **Step 2: Capture and review each new primary digest**

Run each primary check against its old golden and capture the reported actual digest:

```bash
./gradlew runDigestCheck
./gradlew runDigestCheckFeatures
./gradlew runDigestCheckAvoid
./gradlew runDigestCheckAvoidModes
./gradlew runDigestCheckRail
```

Expected: any scenario whose generated cities now contain an edge reports a deterministic mismatch. Record old and new values in the implementation notes. Investigate an unchanged digest if the fixture demonstrably contains an eligible edge, and investigate unrelated structural changes before accepting any new value.

- [ ] **Step 3: Visually inspect radius and Perlin boundaries**

Run:

```bash
./gradlew runClient
```

Using a fixed test seed (record it in the implementation notes; `1337` is acceptable), inspect:

1. a radius-based city under Default with `urbex:standard`;
2. a Perlin city under Large Cities with `urbex:standard`;
3. at least one selector-family boundary if a mixed world style is available in the test setup.

Confirm that the border appears only where the local city factor is below `0.4`, the centre/interior stays on the selected base, standard/desert entries do not borrow each other’s base, and a Perlin region does not alternate family chunk by chunk. Use `/urbex debug` or the existing debug overlay/commands to identify city factor and scope where practical.

- [ ] **Step 4: Update only reviewed golden values**

Use `apply_patch` to replace each old digest with the reviewed actual value. Do not delete golden files and do not regenerate them through an opaque bulk rewrite.

Then rerun the five primary tasks:

```bash
./gradlew runDigestCheck
./gradlew runDigestCheckFeatures
./gradlew runDigestCheckAvoid
./gradlew runDigestCheckAvoidModes
./gradlew runDigestCheckRail
```

Expected: all five pass with their reviewed goldens.

- [ ] **Step 5: Prove deterministic order, cache, mode, and rail behaviour**

Run:

```bash
./gradlew runDigestCheckShuffled
./gradlew runDigestCheckAvoidShuffled
./gradlew runDigestCheckAvoidThreads
./gradlew runDigestCheckAvoidExpire
./gradlew runDigestCheckAvoidModes
./gradlew runDigestCheckRailShuffled
```

Expected: every variant passes against its corresponding primary golden. In particular, shuffled and threaded checks must not expose a family reroll tied to observation order.

- [ ] **Step 6: Run the complete build and structural audit**

Run:

```bash
./gradlew clean test build
! rg -n 'cityStyleThreshold|cityStyleAlternative|CITY_STYLE_THRESHOLD|CITY_STYLE_ALTERNATIVE' \
  src/main/java src/main/resources docs/schema \
  -g '!**/CitySettings.java'
git diff --check
git status --short
```

Expected: Gradle exits 0; the structural search has no live-model, runtime, GUI, schema, or data references outside the deliberate retired-key error map in `CitySettings`; the diff is whitespace-clean; status contains only the reviewed digest changes or an intentional verification fix.

- [ ] **Step 7: Commit the reviewed generation fixtures**

```bash
git add digest.golden digest-features.golden digest-avoid.golden \
  digest-avoid-modes.golden digest-rail.golden
git commit -m 'test: accept per-city-style edge generation'
```

If verification required a production fix, commit it with its regression test before this golden-only commit.

- [ ] **Step 8: Perform the final acceptance audit**

Run:

```bash
git log --oneline -7
git status --short --branch
./gradlew test runDigestCheck runDigestCheckFeatures runDigestCheckShuffled \
  runDigestCheckAvoid runDigestCheckAvoidShuffled runDigestCheckAvoidThreads \
  runDigestCheckAvoidExpire runDigestCheckAvoidModes \
  runDigestCheckRail runDigestCheckRailShuffled
```

Expected: five focused implementation commits follow the design/plan commits, the worktree is clean, all unit and digest checks pass, and all ten acceptance criteria in the approved design are evidenced by tests, data, docs, or the recorded manual inspection.
