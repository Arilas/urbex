# Independent Lighting and Loot Density Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the profile lighting and loot booleans with independent per-marker and per-container density controls, add typed multi-source light pools with survival-aware placement, keep redstone torches excluded, expose both densities as percentage sliders, and seed intentional values in every bundled profile.

**Architecture:** Profiles own two normalized floats and migrate legacy JSON at load time. Position-addressed RNG purposes make lighting admission, lighting variant selection, and loot admission independent. Palette entries may compile a typed light pool; generation turns both typed and legacy torch characters into deferred light markers, then a survival-aware placer selects a valid floor, wall, ceiling, or free light from the driver cache. Loot admission happens once per container before the existing loot-table stream is used.

**Tech Stack:** Java 25, Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.155.2+26.2, Mojang codecs, Gson, JUnit 5, Gradle/Loom 1.17.17.

## Global Constraints

- Treat [`docs/superpowers/specs/2026-08-09-lighting-loot-density-design.md`](../specs/2026-08-09-lighting-loot-density-design.md) as the source of truth.
- Keep `lightingDensity` and `lootDensity` in `[0.0, 1.0]`; `0.0` must admit nothing and `1.0` must admit everything without consuming an RNG draw.
- Make one lighting decision per marker and one loot decision per container. Never use building-wide admission.
- Append RNG purposes only. Never insert, reorder, rename, or remove an existing `Rng.Purpose` constant.
- Keep `Rng.Purpose.LOOT` exclusively for loot-table/content selection.
- Keep legacy palette entries with `"torch": true` functional, but do not emit that field in the bundled common palette after it moves to typed pools.
- Leave the `g` redstone-torch palette entry and its behavior unchanged. Redstone torches are functional blocks, not optional lighting.
- Keep the `urbex:lights` block tag unchanged; it remains update classification, not source selection.
- Preserve user-owned `config/urbex/profiles/*.json` files. Regenerate only `config/urbex/profiles/defaults/` and seed absent user files.
- Preserve the retired building-no-loot random draw so removing `BuildingInfo.noLoot` does not shift the existing sequential building stream and alter ruin generation.
- Run the focused test named in each task before its implementation and confirm that it fails for the intended reason.
- Make one commit per task using the commit message shown in that task.

## File Structure Map

### New production files

- `src/main/java/dev/krona/urbex/varia/DensitySelector.java`: endpoint-safe, position-addressed lighting and loot admission.
- `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/LightSettings.java`: codec-facing weighted floor, wall, ceiling, and free lists.
- `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/LightPool.java`: validated immutable compiled light states and weighted fallback order.
- `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/OptionalLightPlacer.java`: opportunity order, state orientation, and survival callback.
- `src/main/java/dev/krona/urbex/worldgen/DriverLevelReader.java`: `LevelReader` overlay that reads pending `ChunkDriver` states.
- `src/main/java/dev/krona/urbex/gui/elements/PercentageSliderElement.java`: 0–100 percent slider backed by a normalized profile float.

### Modified production files

- `src/main/java/dev/krona/urbex/config/Configuration.java:232`: make legacy-key probing safe for absent categories.
- `src/main/java/dev/krona/urbex/config/LostCityProfile.java:80-83,162-163,290-432`: add densities, migrate old keys, and retire old serialization.
- `src/main/java/dev/krona/urbex/config/ProfileSetup.java:22-463`: assign all bundled defaults and isolate profile-file seeding for testing.
- `src/main/java/dev/krona/urbex/varia/Rng.java:23-91`: append three independent purposes.
- `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/PaletteEntry.java:18-45,135-149`: add the `light` codec field.
- `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/Palette.java:62-122`: compile typed light pools and carry them in `Palette.Info`.
- `src/main/java/dev/krona/urbex/commands/CommandExportPart.java:137-142`: supply the new optional constructor argument.
- `src/main/java/dev/krona/urbex/worldgen/ChunkDriver.java:220-249`: expose cache-aware absolute block reads.
- `src/main/java/dev/krona/urbex/worldgen/lost/BuildingInfo.java:68,118-164,779-883`: replace torch todos, remove building-wide loot state, and preserve the retired draw.
- `src/main/java/dev/krona/urbex/worldgen/LostCityTerrainFeature.java:328,424-450,1763-1967,2051-2096`: queue and place lights, admit loot per container, and decouple spawners.
- `src/main/java/dev/krona/urbex/worldgen/gen/Bridges.java:33-56`: use the same marker admission path for bridge parts.
- `src/main/java/dev/krona/urbex/gui/GuiLCConfig.java:121-164`: register the two percentage sliders on Customize → Various.
- `src/main/resources/data/urbex/urbex/palettes/common.json:14-25`: replace `T` and `h` with typed light pools and leave `g` unchanged.

### New and modified tests

- `src/test/java/dev/krona/urbex/config/LostCityProfileDensityTest.java`
- `src/test/java/dev/krona/urbex/config/ProfileSetupDensityTest.java`
- `src/test/java/dev/krona/urbex/varia/DensitySelectorTest.java`
- `src/test/java/dev/krona/urbex/varia/RngTest.java`
- `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/LightPoolTest.java`
- `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/OptionalLightPlacerTest.java`
- `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/CommonPaletteLightingTest.java`
- `src/test/java/dev/krona/urbex/gui/elements/PercentageSliderElementTest.java`

---

## Task 1: Add the profile schema, legacy migration, and standard defaults

**Interfaces**

- **Consumes:** `Configuration.fromJson`, the old four loot/lighting keys, the approved default-profile matrix, and current profile-file ownership rules.
- **Produces:** `LostCityProfile.LIGHTING_DENSITY`, `LostCityProfile.LOOT_DENSITY`, new-only serialization, legacy migration, deterministic standard defaults, and a package-visible profile seeding seam.

**Files:**

- Modify: `src/main/java/dev/krona/urbex/config/Configuration.java:232-234`
- Modify: `src/main/java/dev/krona/urbex/config/LostCityProfile.java:80-83,162-163,208-220,290-432`
- Modify: `src/main/java/dev/krona/urbex/config/ProfileSetup.java:22-463`
- Create: `src/test/java/dev/krona/urbex/config/LostCityProfileDensityTest.java`
- Create: `src/test/java/dev/krona/urbex/config/ProfileSetupDensityTest.java`

- [ ] **Step 1: Write failing migration and serialization tests**

Cover these cases in `LostCityProfileDensityTest`:

```java
@Test
void migratesLegacyLightingBoolean() {
    LostCityProfile on = profile("""
            {"lostcity":{"generateLighting":true}}
            """);
    LostCityProfile off = profile("""
            {"lostcity":{"generateLighting":false}}
            """);
    assertEquals(1.0f, on.LIGHTING_DENSITY);
    assertEquals(0.0f, off.LIGHTING_DENSITY);
}

@Test
void migratesLegacyLootProbability() {
    LostCityProfile profile = profile("""
            {"lostcity":{
              "generateLoot":true,
              "buildingWithoutLootChance":0.25,
              "chestWithoutLootChance":0.40
            }}
            """);
    assertEquals(0.45f, profile.LOOT_DENSITY, 0.00001f);
}

@Test
void migratesDisabledLegacyLootToZero() {
    LostCityProfile profile = profile("""
            {"lostcity":{
              "generateLoot":false,
              "buildingWithoutLootChance":0.0,
              "chestWithoutLootChance":0.0
            }}
            """);
    assertEquals(0.0f, profile.LOOT_DENSITY);
}

@Test
void legacyLootDefaultsMigrateToPoint64() {
    LostCityProfile profile = profile("""
            {"lostcity":{"generateLoot":true}}
            """);
    assertEquals(0.64f, profile.LOOT_DENSITY, 0.00001f);
}

@Test
void newKeysWinIndependentlyOverLegacyKeys() {
    LostCityProfile newLighting = profile("""
            {"lostcity":{
              "lightingDensity":0.35,
              "generateLighting":false,
              "generateLoot":false,
              "buildingWithoutLootChance":0.0,
              "chestWithoutLootChance":0.0
            }}
            """);
    assertEquals(0.35f, newLighting.LIGHTING_DENSITY);
    assertEquals(0.0f, newLighting.LOOT_DENSITY);

    LostCityProfile newLoot = profile("""
            {"lostcity":{
              "generateLighting":true,
              "lootDensity":0.70,
              "generateLoot":false,
              "buildingWithoutLootChance":1.0,
              "chestWithoutLootChance":1.0
            }}
            """);
    assertEquals(1.0f, newLoot.LIGHTING_DENSITY);
    assertEquals(0.70f, newLoot.LOOT_DENSITY);
}

@Test
void serializesOnlyDensityKeys() {
    JsonObject lostcity = new LostCityProfile("test", true).toJson(false).getAsJsonObject("lostcity");
    assertTrue(lostcity.has("lightingDensity"));
    assertTrue(lostcity.has("lootDensity"));
    assertFalse(lostcity.has("generateLighting"));
    assertFalse(lostcity.has("generateLoot"));
    assertFalse(lostcity.has("buildingWithoutLootChance"));
    assertFalse(lostcity.has("chestWithoutLootChance"));
}

@Test
void clampsAndRoundTripsDensities() {
    LostCityProfile clamped = profile("""
            {"lostcity":{"lightingDensity":-0.25,"lootDensity":1.25}}
            """);
    assertEquals(0.0f, clamped.LIGHTING_DENSITY);
    assertEquals(1.0f, clamped.LOOT_DENSITY);

    clamped.LIGHTING_DENSITY = 0.37f;
    clamped.LOOT_DENSITY = 0.82f;
    LostCityProfile roundTripped = profile(clamped.toJson(false).toString());
    assertEquals(0.37f, roundTripped.LIGHTING_DENSITY);
    assertEquals(0.82f, roundTripped.LOOT_DENSITY);
}

private static LostCityProfile profile(String json) {
    return new LostCityProfile("legacy", json);
}
```

- [ ] **Step 2: Run the focused test and confirm the missing density fields fail compilation**

Run: `./gradlew test --tests dev.krona.urbex.config.LostCityProfileDensityTest`

Expected: test compilation fails because `LIGHTING_DENSITY` and `LOOT_DENSITY` do not exist.

- [ ] **Step 3: Make key probing null-safe and implement migration**

Change `Configuration.hasKey` to:

```java
public boolean hasKey(String category, String name) {
    Category values = categoryMap.get(category);
    return values != null && values.valueMap.containsKey(name);
}
```

Add these profile fields with the ordinary-profile defaults:

```java
public float LIGHTING_DENSITY = 0.15f;
public float LOOT_DENSITY = 0.65f;
```

Keep the old Java fields temporarily, mark them `@Deprecated(forRemoval = true)`, and stop registering them with `Configuration`. They are a build bridge until Tasks 5 and 6 remove all old generator references. At the point where the old loot-chance reads currently occur, migrate only when the new key is absent:

```java
private void initDensities(Configuration cfg) {
    if (!cfg.hasKey(CATEGORY_LOSTCITY, "lightingDensity")
            && cfg.hasKey(CATEGORY_LOSTCITY, "generateLighting")) {
        LIGHTING_DENSITY = cfg.getBoolean("generateLighting", CATEGORY_LOSTCITY, false, "Legacy lighting switch")
                ? 1.0f : 0.0f;
    }

    if (!cfg.hasKey(CATEGORY_LOSTCITY, "lootDensity")
            && cfg.hasKey(CATEGORY_LOSTCITY, "generateLoot")) {
        boolean enabled = cfg.getBoolean("generateLoot", CATEGORY_LOSTCITY, true, "Legacy loot switch");
        float buildingWithout = cfg.hasKey(CATEGORY_LOSTCITY, "buildingWithoutLootChance")
                ? cfg.getFloat("buildingWithoutLootChance", CATEGORY_LOSTCITY, 0.2f, 0.0f, 1.0f, "Legacy building exclusion")
                : 0.2f;
        float chestWithout = cfg.hasKey(CATEGORY_LOSTCITY, "chestWithoutLootChance")
                ? cfg.getFloat("chestWithoutLootChance", CATEGORY_LOSTCITY, 0.2f, 0.0f, 1.0f, "Legacy chest exclusion")
                : 0.2f;
        LOOT_DENSITY = enabled ? (1.0f - buildingWithout) * (1.0f - chestWithout) : 0.0f;
    }

    LIGHTING_DENSITY = clampDensity(cfg.getFloat("lightingDensity", CATEGORY_LOSTCITY, LIGHTING_DENSITY,
            0.0f, 1.0f, "Independent chance that a light marker places a light"));
    LOOT_DENSITY = clampDensity(cfg.getFloat("lootDensity", CATEGORY_LOSTCITY, LOOT_DENSITY,
            0.0f, 1.0f, "Independent chance that a loot container receives a loot table"));

    GENERATE_LIGHTING = LIGHTING_DENSITY > 0.0f;
    GENERATE_LOOT = LOOT_DENSITY > 0.0f;
    BUILDING_WITHOUT_LOOT_CHANCE = 0.0f;
    CHEST_WITHOUT_LOOT_CHANCE = 1.0f - LOOT_DENSITY;
}

private static float clampDensity(float value) {
    return Math.max(0.0f, Math.min(1.0f, value));
}
```

Call `initDensities(cfg)` once from `initLostcity` after the category exists. Delete the four old `cfg.getBoolean` and `cfg.getFloat` registrations so subsequent `toJson` calls emit only the new keys.

- [ ] **Step 4: Add standard-profile and file-ownership tests**

In `ProfileSetupDensityTest`, assert this exact matrix after `ProfileSetup.initStandardProfiles()`:

```java
private static final Map<String, float[]> EXPECTED = Map.ofEntries(
        Map.entry("default", new float[]{0.15f, 0.65f}),
        Map.entry("nodamage", new float[]{0.15f, 0.65f}),
        Map.entry("floating", new float[]{0.15f, 0.65f}),
        Map.entry("rarecities", new float[]{0.15f, 0.65f}),
        Map.entry("onlycities", new float[]{0.15f, 0.65f}),
        Map.entry("tallbuildings", new float[]{0.15f, 0.65f}),
        Map.entry("atlantis", new float[]{0.15f, 0.65f}),
        Map.entry("cavern", new float[]{0.65f, 0.65f}),
        Map.entry("biosphere_caves", new float[]{0.65f, 0.65f}),
        Map.entry("space", new float[]{0.50f, 0.65f}),
        Map.entry("biosphere", new float[]{0.50f, 0.65f}),
        Map.entry("largecities", new float[]{0.35f, 0.65f}),
        Map.entry("ancient", new float[]{0.05f, 0.40f}),
        Map.entry("wasteland", new float[]{0.05f, 0.40f}),
        Map.entry("bio_wasteland", new float[]{0.05f, 0.40f}),
        Map.entry("safe", new float[]{1.00f, 0.00f}),
        Map.entry("void_outside", new float[]{0.00f, 0.00f})
);
```

Also use `@TempDir` to pre-create `profiles/default.json` with sentinel text, call the new package-visible `ProfileSetup.writeProfileFiles(profileDir)`, and assert that the sentinel is unchanged while `profiles/defaults/default.json` contains `lightingDensity` and `lootDensity`.

- [ ] **Step 5: Refactor and seed the profile matrix**

Make `initStandardProfiles()` package-visible, clear `STANDARD_PROFILES` at its start, and replace the old boolean assignments with the approved density values. Extract the existing two write loops from `setupProfiles()`:

```java
static void writeProfileFiles(Path profileDir) {
    Path defaultsDir = profileDir.resolve("defaults");
    defaultsDir.toFile().mkdirs();
    for (Map.Entry<String, LostCityProfile> entry : STANDARD_PROFILES.entrySet()) {
        writeProfile(defaultsDir, entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, LostCityProfile> entry : STANDARD_PROFILES.entrySet()) {
        File target = profileDir.resolve(entry.getKey() + ".json").toFile();
        if (!target.exists()) {
            writeProfile(profileDir, entry.getKey(), entry.getValue());
        }
    }
}
```

Have `setupProfiles()` call `initStandardProfiles()`, `writeProfileFiles(profileDir)`, and then `readProfiles(profileDir)`.

- [ ] **Step 6: Run profile tests**

Run: `./gradlew test --tests 'dev.krona.urbex.config.*DensityTest'`

Expected: all migration, serialization, default matrix, and no-overwrite tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/krona/urbex/config/Configuration.java src/main/java/dev/krona/urbex/config/LostCityProfile.java src/main/java/dev/krona/urbex/config/ProfileSetup.java src/test/java/dev/krona/urbex/config/LostCityProfileDensityTest.java src/test/java/dev/krona/urbex/config/ProfileSetupDensityTest.java
git commit -m "feat: add independent profile densities"
```

---

## Task 2: Add append-only RNG purposes and endpoint-safe density selection

**Interfaces**

- **Consumes:** world seed, block position, normalized profile density, and the append-only `Rng.Purpose` contract.
- **Produces:** independent `LIGHTING_DENSITY`, `LIGHTING_VARIANT`, and `LOOT_DENSITY` streams plus reusable admission methods.

**Files:**

- Modify: `src/main/java/dev/krona/urbex/varia/Rng.java:23-91`
- Modify: `src/test/java/dev/krona/urbex/varia/RngTest.java:60-91,139-157`
- Create: `src/main/java/dev/krona/urbex/varia/DensitySelector.java`
- Create: `src/test/java/dev/krona/urbex/varia/DensitySelectorTest.java`

- [ ] **Step 1: Pin the new enum tail in a failing test**

Append these names to `PURPOSE_ORDER`, set `PURPOSE_COUNT` to `50`, set `LAST_PURPOSE` to `Rng.Purpose.LOOT_DENSITY`, and replace `GOLDEN_LAST` with:

```java
private static final long[] GOLDEN_LAST = {
        -8452086439569127134L,
        5133209234212060231L,
        -2993523536662716498L,
        -3335748431786222750L
};
```

Run: `./gradlew test --tests dev.krona.urbex.varia.RngTest.theEnumIsAppendOnly`

Expected: compilation fails because the new purpose names do not exist.

- [ ] **Step 2: Append the purposes and verify both golden vectors**

Append, without changing any existing constant:

```java
VINES_SOUTH,
LIGHTING_DENSITY,
LIGHTING_VARIANT,
LOOT_DENSITY
```

Run: `./gradlew test --tests dev.krona.urbex.varia.RngTest`

Expected: all RNG tests pass, including the old `RUINS` vector and new tail vector.

- [ ] **Step 3: Write failing density-selector tests**

```java
@Test
void endpointsAreExact() {
    BlockPos pos = new BlockPos(10, 64, -10);
    assertFalse(DensitySelector.lighting(7L, pos, 0.0f));
    assertTrue(DensitySelector.lighting(7L, pos, 1.0f));
    assertFalse(DensitySelector.loot(7L, pos, 0.0f));
    assertTrue(DensitySelector.loot(7L, pos, 1.0f));
}

@Test
void lightingAndLootAreIndependentAtTheSameMarkers() {
    boolean foundDifferentDecision = false;
    for (int y = 0; y < 256; y++) {
        BlockPos pos = new BlockPos(3, y, -7);
        if (DensitySelector.lighting(9L, pos, 0.5f) != DensitySelector.loot(9L, pos, 0.5f)) {
            foundDifferentDecision = true;
            break;
        }
    }
    assertTrue(foundDifferentDecision);
}

@Test
void admissionIsMonotonicAndIterationOrderIndependent() {
    List<BlockPos> positions = IntStream.range(0, 512)
            .mapToObj(y -> new BlockPos(3, y, -7))
            .toList();
    Set<BlockPos> lightingLow = admitted(positions, pos -> DensitySelector.lighting(9L, pos, 0.25f));
    Set<BlockPos> lightingHigh = admitted(positions, pos -> DensitySelector.lighting(9L, pos, 0.75f));
    Set<BlockPos> lootLow = admitted(positions, pos -> DensitySelector.loot(9L, pos, 0.25f));
    Set<BlockPos> lootHigh = admitted(positions, pos -> DensitySelector.loot(9L, pos, 0.75f));
    assertTrue(lightingHigh.containsAll(lightingLow));
    assertTrue(lootHigh.containsAll(lootLow));

    List<BlockPos> reversed = new ArrayList<>(positions);
    Collections.reverse(reversed);
    assertEquals(lightingHigh, admitted(reversed, pos -> DensitySelector.lighting(9L, pos, 0.75f)));
    assertEquals(lootHigh, admitted(reversed, pos -> DensitySelector.loot(9L, pos, 0.75f)));
}

private static Set<BlockPos> admitted(List<BlockPos> positions, Predicate<BlockPos> predicate) {
    return positions.stream().filter(predicate).collect(Collectors.toSet());
}

@Test
void densityCallsCannotPerturbVariantOrLootContentStreams() {
    BlockPos pos = new BlockPos(3, 64, -7);
    long variant = Rng.atPos(9L, pos.getX(), pos.getY(), pos.getZ(),
            Rng.Purpose.LIGHTING_VARIANT).nextLong();
    long content = Rng.atPos(9L, pos.getX(), pos.getY(), pos.getZ(),
            Rng.Purpose.LOOT).nextLong();

    DensitySelector.lighting(9L, pos, 0.25f);
    DensitySelector.lighting(9L, pos, 0.75f);
    DensitySelector.loot(9L, pos, 0.25f);
    DensitySelector.loot(9L, pos, 0.75f);

    assertEquals(variant, Rng.atPos(9L, pos.getX(), pos.getY(), pos.getZ(),
            Rng.Purpose.LIGHTING_VARIANT).nextLong());
    assertEquals(content, Rng.atPos(9L, pos.getX(), pos.getY(), pos.getZ(),
            Rng.Purpose.LOOT).nextLong());
}
```

Run: `./gradlew test --tests dev.krona.urbex.varia.DensitySelectorTest`

Expected: test compilation fails because `DensitySelector` does not exist.

- [ ] **Step 4: Implement the selector**

```java
package dev.krona.urbex.varia;

import net.minecraft.core.BlockPos;

public final class DensitySelector {
    private DensitySelector() {
    }

    public static boolean lighting(long worldSeed, BlockPos pos, float density) {
        return admit(worldSeed, pos, density, Rng.Purpose.LIGHTING_DENSITY);
    }

    public static boolean loot(long worldSeed, BlockPos pos, float density) {
        return admit(worldSeed, pos, density, Rng.Purpose.LOOT_DENSITY);
    }

    private static boolean admit(long worldSeed, BlockPos pos, float density, Rng.Purpose purpose) {
        if (density <= 0.0f) {
            return false;
        }
        if (density >= 1.0f) {
            return true;
        }
        return Rng.floatAtPos(worldSeed, pos.getX(), pos.getY(), pos.getZ(), purpose) < density;
    }
}
```

- [ ] **Step 5: Run and commit**

Run: `./gradlew test --tests dev.krona.urbex.varia.RngTest --tests dev.krona.urbex.varia.DensitySelectorTest`

```bash
git add src/main/java/dev/krona/urbex/varia/Rng.java src/main/java/dev/krona/urbex/varia/DensitySelector.java src/test/java/dev/krona/urbex/varia/RngTest.java src/test/java/dev/krona/urbex/varia/DensitySelectorTest.java
git commit -m "feat: add addressed density decisions"
```

---

## Task 3: Add typed palette light pools and validation

**Interfaces**

- **Consumes:** palette JSON, Mojang codecs, `Tools.stringToState`, and Minecraft block-state light emission.
- **Produces:** decoded `LightSettings`, validated compiled `LightPool`, `Palette.Info.light`, and a representative state for editor/export consumers.

**Files:**

- Create: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/LightSettings.java`
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/LightPool.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/PaletteEntry.java:18-45,100-149`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/Palette.java:62-122`
- Modify: `src/main/java/dev/krona/urbex/commands/CommandExportPart.java:137-142`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/LightPoolTest.java`

- [ ] **Step 1: Write failing codec and validation tests**

Initialize vanilla registries once in the test class:

```java
@BeforeAll
static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
}
```

Test all of the following:

- a pool containing floor, wall, ceiling, and free candidates decodes and preserves all four groups;
- an object with four empty or absent lists fails decoding;
- weight `0` fails decoding;
- `minecraft:stone` fails compilation because it emits no light;
- `minecraft:redstone_torch[lit=true]` is accepted for a custom datapack because weak nonzero sources remain allowed;
- a malformed block-state string fails with palette id, marker character, placement group, and block string in the exception message;
- `representative()` returns the first state from the first non-empty group in floor, wall, ceiling, free order.
- a complete legacy palette entry with `"block":"minecraft:wall_torch[facing=north]"` and `"torch":true` decodes, receives a registry name through `PaletteRE.setRegistryName`, compiles through `Palette`, and produces `Info.isTorch() == true` with `Info.light() == null`.

Decode with:

```java
DataResult<LightSettings> result = LightSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
assertTrue(result.result().isPresent());
```

- [ ] **Step 2: Run the focused test and confirm missing types fail compilation**

Run: `./gradlew test --tests dev.krona.urbex.worldgen.lost.cityassets.LightPoolTest`

Expected: test compilation fails because `LightSettings` and `LightPool` do not exist.

- [ ] **Step 3: Implement the codec-facing records**

Use positive integer weights and default each absent group to an immutable empty list:

```java
public record LightSettings(List<Entry> floor, List<Entry> wall,
                            List<Entry> ceiling, List<Entry> free) {
    public static final Codec<LightSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(Entry.CODEC).optionalFieldOf("floor", List.of()).forGetter(LightSettings::floor),
            Codec.list(Entry.CODEC).optionalFieldOf("wall", List.of()).forGetter(LightSettings::wall),
            Codec.list(Entry.CODEC).optionalFieldOf("ceiling", List.of()).forGetter(LightSettings::ceiling),
            Codec.list(Entry.CODEC).optionalFieldOf("free", List.of()).forGetter(LightSettings::free)
    ).apply(instance, LightSettings::new)).flatXmap(
            value -> value.isEmpty()
                    ? DataResult.error(() -> "A light pool must define at least one candidate")
                    : DataResult.success(value),
            DataResult::success);

    public LightSettings {
        floor = List.copyOf(floor);
        wall = List.copyOf(wall);
        ceiling = List.copyOf(ceiling);
        free = List.copyOf(free);
    }

    public boolean isEmpty() {
        return floor.isEmpty() && wall.isEmpty() && ceiling.isEmpty() && free.isEmpty();
    }

    public record Entry(int weight, String block) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(Entry::weight),
                Codec.STRING.fieldOf("block").forGetter(Entry::block)
        ).apply(instance, Entry::new));
    }
}
```

- [ ] **Step 4: Implement immutable compiled pools**

`LightPool` must define:

```java
public enum Placement { FLOOR, WALL, CEILING, FREE }
public record Candidate(int weight, BlockState state) { }

public static LightPool compile(Identifier paletteId, char marker, LightSettings settings)
public static LightPool legacyTorch()
public List<Candidate> weightedOrder(Placement placement, RandomSource random)
public BlockState representative()
public Collection<Candidate> allCandidates()
```

Compile each string with `Tools.stringToState`, wrap every parse failure with full context, reject `state.getLightEmission() <= 0`, and detect integer overflow while summing weights. `weightedOrder` must draw one ticket, put the weighted winner first, then append every other candidate in JSON order starting after the winner and wrapping at the end. `legacyTorch()` must contain only a floor torch and a wall torch; it must not contain ceiling or free candidates.

- [ ] **Step 5: Wire the optional palette field**

Add this codec field immediately before `tag` in `PaletteEntry.CODEC`:

```java
LightSettings.CODEC.optionalFieldOf("light").forGetter(entry -> Optional.ofNullable(entry.getLight()))
```

Add the field, getter, constructor argument, and `toString` output. Update `CommandExportPart` to pass one additional `Optional.empty()`.

In `Palette.parsePaletteArray`, compile first and carry the result in `Info`:

```java
LightPool light = entry.getLight() == null ? null : LightPool.compile(name, c, entry.getLight());
Info info = new Info(entry.getMob(), entry.getLoot(),
        entry.getTorch() != null && entry.getTorch(), light, entry.getTag());
```

If an entry has no `block`, `variant`, `frompalette`, or `blocks` but has a light pool, store `light.representative()` as its `PE` state. Change the record to:

```java
public record Info(String mobId, String loot, boolean isTorch, LightPool light, CompoundTag tag) {
    public boolean isSpecial() {
        return mobId != null || loot != null || isTorch || light != null || tag != null;
    }
}
```

- [ ] **Step 6: Run and commit**

Run: `./gradlew test --tests dev.krona.urbex.worldgen.lost.cityassets.LightPoolTest`

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/LightSettings.java src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/PaletteEntry.java src/main/java/dev/krona/urbex/worldgen/lost/cityassets/LightPool.java src/main/java/dev/krona/urbex/worldgen/lost/cityassets/Palette.java src/main/java/dev/krona/urbex/commands/CommandExportPart.java src/test/java/dev/krona/urbex/worldgen/lost/cityassets/LightPoolTest.java
git commit -m "feat: add typed palette light pools"
```

---

## Task 4: Build deterministic survival-aware light placement

**Interfaces**

- **Consumes:** a compiled `LightPool`, a `LIGHTING_VARIANT` stream, marker position, pending driver states, and Minecraft `canSurvive` rules.
- **Produces:** the first valid oriented placement attempt in floor, west, east, north, south, ceiling, free order.

**Files:**

- Create: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/OptionalLightPlacer.java`
- Create: `src/main/java/dev/krona/urbex/worldgen/DriverLevelReader.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/ChunkDriver.java:220-249`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/OptionalLightPlacerTest.java`

- [ ] **Step 1: Write failing placer tests**

Use small compiled pools and a survival callback to assert:

- floor is attempted before walls;
- wall support order is west, east, north, south;
- a west-supported wall torch faces east;
- an east-supported end rod faces west;
- a ceiling lantern has `LanternBlock.HANGING == true`;
- a floor lantern has `LanternBlock.HANGING == false`;
- a rejected weighted winner falls through the remaining JSON-order candidates before the next opportunity;
- free candidates are tried only after supported opportunities;
- all rejected attempts return `Optional.empty()`.
- `LightPool.legacyTorch()` can place on a floor or any of the four wall directions, but never attempts ceiling or free placement.

Run: `./gradlew test --tests dev.krona.urbex.worldgen.lost.cityassets.OptionalLightPlacerTest`

Expected: test compilation fails because `OptionalLightPlacer` does not exist.

- [ ] **Step 2: Implement the pure selection engine**

Use these public interfaces:

```java
public record Attempt(BlockState state, LightPool.Placement placement,
                      @Nullable Direction supportDirection) { }

@FunctionalInterface
public interface Survival {
    boolean canPlace(Attempt attempt);
}

public static Optional<Attempt> select(LightPool pool, RandomSource random, Survival survival)
```

The fixed opportunity list is:

```java
private static final List<Opportunity> OPPORTUNITIES = List.of(
        new Opportunity(LightPool.Placement.FLOOR, Direction.DOWN),
        new Opportunity(LightPool.Placement.WALL, Direction.WEST),
        new Opportunity(LightPool.Placement.WALL, Direction.EAST),
        new Opportunity(LightPool.Placement.WALL, Direction.NORTH),
        new Opportunity(LightPool.Placement.WALL, Direction.SOUTH),
        new Opportunity(LightPool.Placement.CEILING, Direction.UP),
        new Opportunity(LightPool.Placement.FREE, null)
);
```

Orient a `BlockState` before invoking `Survival`:

```java
private static BlockState orient(BlockState state, @Nullable Direction supportDirection) {
    if (supportDirection == null) {
        return state;
    }
    Direction facing = supportDirection.getOpposite();
    if (state.hasProperty(BlockStateProperties.HANGING)) {
        state = state.setValue(BlockStateProperties.HANGING, supportDirection == Direction.UP);
    }
    if (state.hasProperty(BlockStateProperties.FACING)) {
        state = state.setValue(BlockStateProperties.FACING, facing);
    } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) {
        state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }
    return state;
}
```

- [ ] **Step 3: Expose cache-aware reads**

Add to `ChunkDriver`:

```java
public BlockState getBlockAt(BlockPos pos) {
    return getBlockSafe(pos);
}
```

This is the only read the placement overlay may use; reading the region directly would miss not-yet-flushed building support blocks.

- [ ] **Step 4: Implement the `LevelReader` overlay**

Use a dynamic proxy so every Minecraft query except `getBlockState(BlockPos)` delegates to the real generation region:

```java
public static LevelReader overlay(LevelReader delegate, Function<BlockPos, BlockState> stateAt) {
    return (LevelReader) Proxy.newProxyInstance(
            LevelReader.class.getClassLoader(),
            new Class<?>[]{LevelReader.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getBlockState")
                        && args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                    return stateAt.apply(pos);
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
}
```

- [ ] **Step 5: Run and commit**

Run: `./gradlew test --tests dev.krona.urbex.worldgen.lost.cityassets.OptionalLightPlacerTest`

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/cityassets/OptionalLightPlacer.java src/main/java/dev/krona/urbex/worldgen/DriverLevelReader.java src/main/java/dev/krona/urbex/worldgen/ChunkDriver.java src/test/java/dev/krona/urbex/worldgen/lost/cityassets/OptionalLightPlacerTest.java
git commit -m "feat: add survival-aware light placement"
```

---

## Task 5: Integrate per-marker lighting and update bundled sources

**Interfaces**

- **Consumes:** `LIGHTING_DENSITY`, `LIGHTING_VARIANT`, `Palette.Info.light`, legacy `isTorch`, the driver cache overlay, and common palette markers `T`, `h`, and `g`.
- **Produces:** one admission roll per light marker, deferred typed or legacy placement, exact bundled pools, and no optional treatment of redstone torches.

**Files:**

- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/BuildingInfo.java:118-164`
- Modify: `src/main/java/dev/krona/urbex/worldgen/LostCityTerrainFeature.java:328,424-450,1763-1856`
- Modify: `src/main/java/dev/krona/urbex/worldgen/gen/Bridges.java:33-56`
- Modify: `src/main/java/dev/krona/urbex/config/LostCityProfile.java:80-83,426-429`
- Modify: `src/main/resources/data/urbex/urbex/palettes/common.json:14-25`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/CommonPaletteLightingTest.java`

- [ ] **Step 1: Write a failing bundled-palette regression test**

Load `data/urbex/urbex/palettes/common.json` from the test classpath and assert:

- `T` has `light.floor`, `light.wall`, and `light.ceiling`, but no `torch`;
- `h` has only `light.free`;
- every bundled candidate compiles and emits at least `14`;
- `T` uses exact weights `6/3/1`, `8/2`, and `8/2` for floor, wall, and ceiling;
- `h` uses exact weights `6/2/1/1`;
- `g` is still `minecraft:redstone_torch[lit=true]` and has neither `light` nor `torch`;
- neither compiled bundled light pool contains `Blocks.REDSTONE_TORCH` or `Blocks.REDSTONE_WALL_TORCH`.
- every JSON file under `src/main/resources/data/urbex/urbex/palettes` decodes through `PaletteRE.CODEC`, so the new optional field does not break unrelated bundled palettes.

Run: `./gradlew test --tests dev.krona.urbex.worldgen.lost.cityassets.CommonPaletteLightingTest`

Expected: assertions fail because `common.json` still has legacy `T` and unconditional `h` entries.

- [ ] **Step 2: Replace torch todos with typed light todos**

In `BuildingInfo`, replace `List<BlockPos> torchTodo` with:

```java
public record LightTodo(BlockPos pos, @Nullable LightPool pool) { }

private final List<LightTodo> lightTodo = Collections.synchronizedList(new ArrayList<>());

public void addLightTodo(BlockPos pos, @Nullable LightPool pool) {
    lightTodo.add(new LightTodo(pos, pool));
}

public List<LightTodo> getLightTodo() {
    return lightTodo;
}

public void clearLightTodo() {
    lightTodo.clear();
}
```

`pool == null` means a legacy `"torch": true` marker and selects `LightPool.legacyTorch()` during placement.

- [ ] **Step 3: Admit every marker independently in parts and bridges**

Add a shared method to `LostCityTerrainFeature`:

```java
public BlockState handleLightMarker(BuildingInfo info, Palette.Info marker, BlockPos pos) {
    if (DensitySelector.lighting(provider.getSeed(), pos, info.profile.LIGHTING_DENSITY)) {
        info.addLightTodo(pos, marker.light());
    }
    return air;
}
```

In `generatePart`, replace the boolean torch branch with:

```java
if (inf.light() != null || inf.isTorch()) {
    b = handleLightMarker(info, inf, driver.getCurrentCopy());
}
```

Use the same path from `Bridges.generateBridge`, qualified through its feature argument:

```java
if (inf.light() != null || inf.isTorch()) {
    b = feature.handleLightMarker(info, inf, driver.getCurrentCopy());
}
```

The marker is always written as air immediately. A failed density roll, unsupported marker, or exhausted candidate pool must never leave the representative block behind.

- [ ] **Step 4: Replace `fixTorches` with survival-aware `fixLights`**

At the existing generation phase, create one overlay and one variant stream per admitted marker:

```java
private void fixLights(ChunkGenContext ctx, BuildingInfo info) {
    List<BuildingInfo.LightTodo> lights = info.getLightTodo();
    if (lights.isEmpty()) {
        return;
    }
    ChunkDriver driver = ctx.driver;
    LevelReader level = DriverLevelReader.overlay((LevelReader) driver.getRegion(), driver::getBlockAt);
    for (BuildingInfo.LightTodo todo : lights) {
        BlockPos pos = todo.pos();
        LightPool pool = todo.pool() == null ? LightPool.legacyTorch() : todo.pool();
        RandomSource random = Rng.atPos(provider.getSeed(), pos.getX(), pos.getY(), pos.getZ(),
                Rng.Purpose.LIGHTING_VARIANT);
        OptionalLightPlacer.select(pool, random, attempt -> canPlaceLight(driver, level, pos, attempt))
                .ifPresent(attempt -> {
                    driver.currentAbsolute(pos).block(attempt.state());
                    updateNeeded(info, pos, Block.UPDATE_CLIENTS);
                });
    }
    info.clearLightTodo();
}
```

Use actual support and survival checks:

```java
private static boolean canPlaceLight(ChunkDriver driver, LevelReader level, BlockPos marker,
                                     OptionalLightPlacer.Attempt attempt) {
    Direction supportDirection = attempt.supportDirection();
    if (supportDirection != null) {
        BlockPos supportPos = marker.relative(supportDirection);
        Direction exposedFace = supportDirection.getOpposite();
        if (!driver.getBlockAt(supportPos).isFaceSturdy(level, supportPos, exposedFace)) {
            return false;
        }
    }
    return attempt.state().canSurvive(level, marker);
}
```

Rename the call at line 328 to `fixLights(ctx, info)` and remove all old literal torch-placement code. Delete the temporary `GENERATE_LIGHTING` bridge field after all references are gone.

- [ ] **Step 5: Update the common palette exactly**

Use these definitions:

```json
{
  "char": "T",
  "light": {
    "floor": [
      { "weight": 6, "block": "minecraft:lantern[hanging=false]" },
      { "weight": 3, "block": "minecraft:torch" },
      { "weight": 1, "block": "minecraft:end_rod[facing=up]" }
    ],
    "wall": [
      { "weight": 8, "block": "minecraft:wall_torch[facing=north]" },
      { "weight": 2, "block": "minecraft:end_rod[facing=north]" }
    ],
    "ceiling": [
      { "weight": 8, "block": "minecraft:lantern[hanging=true]" },
      { "weight": 2, "block": "minecraft:end_rod[facing=down]" }
    ]
  }
},
{
  "char": "h",
  "light": {
    "free": [
      { "weight": 6, "block": "minecraft:glowstone" },
      { "weight": 2, "block": "minecraft:sea_lantern" },
      { "weight": 1, "block": "minecraft:shroomlight" },
      { "weight": 1, "block": "minecraft:ochre_froglight" }
    ]
  }
},
{
  "char": "g",
  "block": "minecraft:redstone_torch[lit=true]"
}
```

- [ ] **Step 6: Run lighting tests and compile production integration**

Run:

```bash
./gradlew test --tests 'dev.krona.urbex.worldgen.lost.cityassets.*Light*Test'
./gradlew compileJava
```

Expected: tests and compilation pass; `rg -n 'GENERATE_LIGHTING|torchTodo|fixTorches' src/main/java` returns no matches.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/BuildingInfo.java src/main/java/dev/krona/urbex/worldgen/LostCityTerrainFeature.java src/main/java/dev/krona/urbex/worldgen/gen/Bridges.java src/main/java/dev/krona/urbex/config/LostCityProfile.java src/main/resources/data/urbex/urbex/palettes/common.json src/test/java/dev/krona/urbex/worldgen/lost/cityassets/CommonPaletteLightingTest.java
git commit -m "feat: generate varied lights per marker"
```

---

## Task 6: Admit loot per container and decouple spawners

**Interfaces**

- **Consumes:** `LOOT_DENSITY`, container position, world seed, and the existing `LOOT` content stream.
- **Produces:** one independent admission decision per loot marker, unchanged loot-table selection after admission, and spawners controlled only by `GENERATE_SPAWNERS`.

**Files:**

- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/BuildingInfo.java:68,779-883`
- Modify: `src/main/java/dev/krona/urbex/worldgen/LostCityTerrainFeature.java:1931-1967,2051-2096`
- Modify: `src/main/java/dev/krona/urbex/config/LostCityProfile.java:80-83,162-163`
- Extend: `src/test/java/dev/krona/urbex/varia/DensitySelectorTest.java`

- [ ] **Step 1: Add deterministic per-container behavior tests**

Extend `DensitySelectorTest` to assert that the same position is stable, nearby positions are not forced to share a result, and raising density is monotonic for a fixed position:

```java
@Test
void lootAdmissionIsPositionAddressedAndMonotonic() {
    BlockPos pos = new BlockPos(20, 70, 30);
    assertEquals(DensitySelector.loot(123L, pos, 0.4f), DensitySelector.loot(123L, pos, 0.4f));

    boolean low = DensitySelector.loot(123L, pos, 0.25f);
    boolean high = DensitySelector.loot(123L, pos, 0.75f);
    assertFalse(low && !high);

    boolean foundDifferentContainer = false;
    for (int x = 21; x < 277; x++) {
        if (DensitySelector.loot(123L, pos, 0.5f)
                != DensitySelector.loot(123L, new BlockPos(x, 70, 30), 0.5f)) {
            foundDifferentContainer = true;
            break;
        }
    }
    assertTrue(foundDifferentContainer);
}
```

Run: `./gradlew test --tests dev.krona.urbex.varia.DensitySelectorTest`

Expected: the selector tests pass; they pin the admission contract before production wiring changes.

- [ ] **Step 2: Remove building-wide loot state without shifting ruins**

Delete `BuildingInfo.noLoot`, its multibuilding copy, and its assignment. Preserve the retired draw at the old assignment site:

```java
rand.nextFloat();
float r = rand.nextFloat();
if (rand.nextFloat() < profile.RUIN_CHANCE
        && (predefinedBuilding == null || !predefinedBuilding.preventRuins())) {
    ruinHeight = profile.RUIN_MINLEVEL_PERCENT
            + (profile.RUIN_MAXLEVEL_PERCENT - profile.RUIN_MINLEVEL_PERCENT) * r;
} else {
    ruinHeight = -1;
}
```

Add a comment that the discarded first draw preserves the legacy building-stream slot formerly used by `buildingWithoutLootChance`.

- [ ] **Step 3: Make spawners independent**

Change the spawner condition to:

```java
if (info.profile.GENERATE_SPAWNERS) {
```

No loot density or loot admission call may appear in `handleSpawner`.

- [ ] **Step 4: Move loot admission to `handleLoot`**

```java
private void handleLoot(ChunkGenContext ctx, BuildingInfo info, IBuildingPart part,
                        BlockState block, Palette.Info marker) {
    BlockPos pos = ctx.driver.getCurrentCopy();
    if (!DensitySelector.loot(provider.getSeed(), pos, info.profile.LOOT_DENSITY)) {
        return;
    }
    info.addPostTodo(pos, inWorld -> {
        if (!inWorld.getBlockState(pos).isAir()) {
            inWorld.setBlock(pos, block, Block.UPDATE_CLIENTS);
            generateLoot(info, inWorld, pos,
                    new BuildingInfo.ConditionTodo(marker.loot(), part.getName(), info));
        }
    });
}
```

Remove the `GENERATE_LOOT` check from `generateLoot` and the `CHEST_WITHOUT_LOOT_CHANCE` draw from `createLoot`. Keep `Rng.Purpose.LOOT` exactly where it is so admitted containers retain deterministic content selection.

- [ ] **Step 5: Delete legacy fields and verify structural independence**

Delete `GENERATE_LOOT`, `CHEST_WITHOUT_LOOT_CHANCE`, and `BUILDING_WITHOUT_LOOT_CHANCE` from `LostCityProfile`, including the temporary synchronization statements introduced in Task 1.

Run:

```bash
./gradlew test --tests dev.krona.urbex.varia.DensitySelectorTest --tests dev.krona.urbex.config.LostCityProfileDensityTest
./gradlew compileJava
```

Then run: `rg -n 'GENERATE_LOOT|CHEST_WITHOUT_LOOT_CHANCE|BUILDING_WITHOUT_LOOT_CHANCE|noLoot' src/main/java src/test/java`

Expected: no matches. `Rng.Purpose.LOOT` remains in the content-selection code and `Rng.Purpose.LOOT_DENSITY` appears only in `DensitySelector` and its tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/BuildingInfo.java src/main/java/dev/krona/urbex/worldgen/LostCityTerrainFeature.java src/main/java/dev/krona/urbex/config/LostCityProfile.java src/test/java/dev/krona/urbex/varia/DensitySelectorTest.java
git commit -m "feat: generate loot per container density"
```

---

## Task 7: Add percentage sliders to Customize → Various

**Interfaces**

- **Consumes:** a normalized float in profile configuration, Minecraft 26.2 `AbstractSliderButton`, and the existing `GuiElement` lifecycle.
- **Produces:** 0–100 percent controls in one-percent increments for `lightingDensity` and `lootDensity`.

**Files:**

- Create: `src/main/java/dev/krona/urbex/gui/elements/PercentageSliderElement.java`
- Modify: `src/main/java/dev/krona/urbex/gui/GuiLCConfig.java:121-164`
- Create: `src/test/java/dev/krona/urbex/gui/elements/PercentageSliderElementTest.java`

- [ ] **Step 1: Write failing conversion tests**

```java
@Test
void snapsToWholePercentAndClamps() {
    assertEquals(0.00f, PercentageSliderElement.snap(-0.4));
    assertEquals(0.37f, PercentageSliderElement.snap(0.374));
    assertEquals(0.38f, PercentageSliderElement.snap(0.376));
    assertEquals(1.00f, PercentageSliderElement.snap(1.4));
}

@Test
void formatsNormalizedValuesAsPercent() {
    assertEquals(0, PercentageSliderElement.percent(0.0f));
    assertEquals(65, PercentageSliderElement.percent(0.65f));
    assertEquals(100, PercentageSliderElement.percent(1.0f));
}
```

Run: `./gradlew test --tests dev.krona.urbex.gui.elements.PercentageSliderElementTest`

Expected: test compilation fails because `PercentageSliderElement` does not exist.

- [ ] **Step 2: Implement the slider element**

Mirror `BooleanElement` for label rendering, page visibility, and enablement. Hold an inner `AbstractSliderButton` created with the Minecraft 26.2 constructor `(x, y, width, height, Component, double)`. Use package-visible conversion helpers:

```java
static float snap(double value) {
    double clamped = Math.max(0.0, Math.min(1.0, value));
    return Math.round(clamped * 100.0) / 100.0f;
}

static int percent(float value) {
    return Math.round(snap(value) * 100.0f);
}
```

The inner widget must guard programmatic `update()` synchronization so `setValue` does not rewrite the profile or refresh the preview. User `applyValue()` must snap, store the normalized float through `Configuration.Value`, call `profile.copyFromConfiguration(configuration)`, update the message to `N%`, and refresh the preview once.

- [ ] **Step 3: Register it in the Various page**

Add:

```java
private PercentageSliderElement addPercentage(int left, String attribute) {
    PercentageSliderElement element = new PercentageSliderElement(this, curpage, left, y, attribute);
    add(element);
    return element;
}
```

Replace only the two retired booleans in `initVarious`:

```java
addBool(left, "lostcity.generateSpawners").label("Spawners:"); nl();
addPercentage(left, "lostcity.lightingDensity").label("Lighting:"); nl();
addPercentage(left, "lostcity.lootDensity").label("Loot:"); nl();
```

- [ ] **Step 4: Run and commit**

Run:

```bash
./gradlew test --tests dev.krona.urbex.gui.elements.PercentageSliderElementTest
./gradlew compileJava
```

```bash
git add src/main/java/dev/krona/urbex/gui/elements/PercentageSliderElement.java src/main/java/dev/krona/urbex/gui/GuiLCConfig.java src/test/java/dev/krona/urbex/gui/elements/PercentageSliderElementTest.java
git commit -m "feat: add lighting and loot density sliders"
```

---

## Task 8: Run the full regression and acceptance pass

**Interfaces**

- **Consumes:** all implementation tasks and the acceptance criteria in the approved design.
- **Produces:** a clean build, resource-validation evidence, removal of retired identifiers, and a manual smoke record for the UI and worldgen endpoints.

**Files:**

- Modify only files required by failures found during this task.

- [ ] **Step 1: Run the complete automated suite**

Run:

```bash
./gradlew clean test build
```

Expected: all tests pass and the remapped mod jar builds.

- [ ] **Step 2: Scan for retired implementation paths**

Run:

```bash
rg -n 'GENERATE_LIGHTING|GENERATE_LOOT|CHEST_WITHOUT_LOOT_CHANCE|BUILDING_WITHOUT_LOOT_CHANCE|noLoot|torchTodo|fixTorches' src/main/java src/test/java
rg -n 'LIGHTING_DENSITY|LIGHTING_VARIANT|LOOT_DENSITY' src/main/java/dev/krona/urbex/varia/Rng.java
rg -n 'redstone_(wall_)?torch' src/main/resources/data/urbex/urbex/palettes/common.json
```

Expected: the first command has no matches; the second shows the three append-only purposes in order; the third shows only the unchanged `g` functional redstone torch entry.

- [ ] **Step 3: Inspect serialized bundled profiles in tests or a temporary config directory**

Confirm every generated default profile contains `lightingDensity` and `lootDensity`, contains none of the four retired JSON keys, and matches the exact matrix from Task 1. Confirm a pre-existing user profile remains byte-for-byte unchanged.

- [ ] **Step 4: Run an in-game smoke pass**

Run `./gradlew runClient` and verify:

- Customize → Various shows independent Lighting and Loot percentage sliders;
- each slider snaps to whole percentages and persists through page/profile refresh;
- a `0%` lighting profile leaves all `T` and `h` markers empty;
- a `100%` lighting profile uses lanterns, torches, end rods, and free luminous blocks where valid;
- unsupported typed and legacy markers remain air;
- `g` redstone torches remain present at every lighting density;
- a `0%` loot profile leaves containers without loot while spawners still follow `generateSpawners`;
- a `100%` loot profile admits every loot marker;
- regenerating the same seed and profile produces the same accepted markers and variants.
- an unmodified legacy profile loads with its migrated density values;
- a small legacy datapack containing `"torch": true` loads and places its marker through the legacy floor/wall pool.

- [ ] **Step 5: Fix only acceptance failures and rerun verification**

For every correction, first add or tighten the smallest relevant automated test, confirm it fails, implement the correction, and rerun `./gradlew clean test build`.

- [ ] **Step 6: Commit final verification corrections if any**

If Task 8 changed files:

```bash
git add src/main/java src/main/resources src/test/java
git commit -m "test: verify independent density generation"
```

If Task 8 required no corrections, do not create an empty commit.

## Completion Criteria

- Both new profile values serialize, migrate, copy, and clamp correctly.
- New density keys win independently over legacy keys.
- The approved bundled profile matrix is exact and user profile files are never overwritten.
- Lighting admission is per marker and loot admission is per container.
- Density admission and variant/content RNG streams are separate and append-only.
- Typed palette pools support floor, wall, ceiling, and free candidates with weighted deterministic fallback.
- Actual placement reads pending driver states, checks support faces, and calls `BlockState.canSurvive`.
- Legacy `"torch": true` entries still use floor/wall placement under the new density.
- Bundled light variants all emit at least 14; custom nonzero weak sources remain legal.
- Redstone torches remain excluded from optional lighting.
- Loot density never suppresses spawners and never changes the existing loot-content stream.
- Customize → Various exposes whole-percent sliders backed by normalized floats.
- `./gradlew clean test build` passes.
