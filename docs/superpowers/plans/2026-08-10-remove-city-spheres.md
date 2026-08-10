# City Sphere Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove city spheres and their sphere-bound monorails, leaving one profile per dimension and no sphere-specific runtime, configuration, UI, registry, or datapack surface.

**Architecture:** Delete the sphere generation entry point first and simplify every runtime consumer to the ordinary single-profile city path. Then remove the now-unused landscape/profile/UI and asset-schema surface, preserving unrelated RNG addresses with neutral reserved slots. Finish by deleting bundled resources, updating generic integrity coverage and documentation, and verifying the complete project.

**Tech Stack:** Java 25, Minecraft 26.2, Fabric Loader/API, Mojang codecs, JUnit 5, Gradle/Loom.

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-10-remove-city-spheres-design.md`.
- Add no migration, fallback, deprecation, legacy parsing, or old-world compatibility behavior.
- Add no test that asserts spheres/monorails are absent or that locks `LandscapeType` to an exact permanent set.
- Keep generic completeness/integrity tests and update them only for the current model.
- Preserve the three removed RNG ordinal positions as neutral unused slots so unrelated generation does not change.
- Do not rewrite preserved historical design, plan, or upstream history documents.
- Do not design replacement domes, habitats, monorails, or other transport networks in this change.

---

### Task 1: Remove the sphere and monorail runtime

**Files:**

- Delete: `src/main/java/dev/krona/urbex/worldgen/SphereFeature.java`
- Delete: `src/main/java/dev/krona/urbex/worldgen/gen/Spheres.java`
- Delete: `src/main/java/dev/krona/urbex/worldgen/gen/Monorails.java`
- Delete: `src/main/java/dev/krona/urbex/worldgen/lost/CitySphere.java`
- Modify: `src/main/java/dev/krona/urbex/Urbex.java`
- Modify: `src/main/java/dev/krona/urbex/commands/CommandDebug.java`
- Modify: `src/main/java/dev/krona/urbex/setup/Registration.java`
- Modify: `src/main/java/dev/krona/urbex/setup/SpawnPlacement.java`
- Modify: `src/main/java/dev/krona/urbex/gui/NullDimensionInfo.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/IDimensionInfo.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/DefaultDimensionInfo.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/CityFeature.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/CityGenerator.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/DimensionCaches.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/gen/Scattered.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/BuildingInfo.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/ChunkContentResolver.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/City.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/Highway.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/PrimaryBridgePlanner.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/Railway.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/ConditionContext.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/ConditionTest.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/ConditionPart.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/PartRef.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/ChunkContentResolverTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/BelowPartConditionTest.java`

**Interfaces:**

- Produces: `DefaultDimensionInfo(WorldGenLevel world, UrbexProfile profile)` with no outside-profile state.
- Produces: `IDimensionInfo` without `getOutsideProfile()`.
- Produces: `ChunkContentResolver.ChunkFacts` ending at `Supplier<Railway.RailChunkInfo> railInfo`.
- Produces: `ChunkContentResolver.resolve(UrbexProfile, long, RandomSource, boolean, boolean, RoadType, MultiPos, ChunkCoord, PrefersLonely, @Nullable String)` with no sphere-center argument.
- Produces: `ConditionContext` without `isSphere()` and condition records/codecs without `issphere`.
- Task 2 consumes these simplified interfaces when deleting the remaining schemas and settings.

- [ ] **Step 1: Record a green runtime baseline**

Run:

```bash
./gradlew test \
  --tests dev.krona.urbex.worldgen.lost.ChunkContentResolverTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.BelowPartConditionTest
```

Expected: both existing suites pass. This is a deletion, so do not invent a failing “feature is absent” test.

- [ ] **Step 2: Delete the generation entry point and collapse dimension state**

Remove the four runtime classes listed above. In `Urbex`, delete the `TOP_LAYER_MODIFICATION` biome feature injection and its sphere-specific imports/comment. In `Registration`, retain only the `urbex:city` feature:

```java
private static CityFeature cityFeature;

public static CityFeature cityFeature() {
    return cityFeature;
}

public static void init() {
    cityFeature = Registry.register(BuiltInRegistries.FEATURE,
            Identifier.fromNamespaceAndPath(Urbex.MODID, "city"), new CityFeature());
}
```

Remove `getOutsideProfile()` from `IDimensionInfo` and both implementations. Change `DefaultDimensionInfo` to:

```java
private final UrbexProfile profile;

public DefaultDimensionInfo(WorldGenLevel world, UrbexProfile profile) {
    this.world = world.getLevel();
    this.profile = profile;
    this.caches = new DimensionCaches(this.world.getSeed());
    this.style = AssetRegistries.WORLDSTYLES.get(this.world, profile.getWorldStyle());
    this.feature = new CityGenerator(this, profile);
    this.biomeRegistry = this.world.registryAccess().lookupOrThrow(Registries.BIOME);
    this.roadField = new GridRoadField(this.world.getSeed(), getType().identifier().toString(),
            GridSettings.fromProfile(profile));
}
```

In `CityFeature`, remove the outside-profile lookup and construct `new DefaultDimensionInfo(world, profile)`. Remove `citySphere` from `DimensionCaches`, its import, documentation, and `clear()`.

- [ ] **Step 3: Simplify all runtime consumers to the single-profile path**

Make these concrete transformations:

- `City.isCityCenter` always uses the selected profile’s ordinary city-chance path; `City.getCityRadius` always uses its ordinary radius path.
- `BuildingInfo.profile` is always `provider.getProfile()`; remove `outsideChunk`, `getProfile(ChunkCoord, IDimensionInfo)`, `getCityLevelSpace`, sphere-center overrides, sphere distance facts, monorail fields/methods, and `getSphereInt` overloads.
- `BuildingInfo.getCityLevel` and `getCityLevelGui` retain only floating, cavern, and normal branches.
- `BuildingInfo.effectiveRoadType` tests connected neighbours with the same selected profile directly, without the former inside/outside identity comparison.
- `CityGenerator.generate` starts with `boolean doCity = info.isCity;`; remove sphere center-part placement, sphere landscape switch arms, space-only clearing/support behavior, sphere condition callbacks, and dead commented monorail code.
- Remove sphere border exclusions from `Highway` and `Railway` and the sphere callback from `Scattered`.
- Remove sphere diagnostics from `CommandDebug`; update the stale sphere comment in `PrimaryBridgePlanner`.
- Remove all sphere-spawn branches from `SpawnPlacement`. Replace `CitySphere.squaredDistance` in the remaining predefined-city branch with a local overflow-safe helper:

```java
private static double squaredHorizontalDistance(int x1, int z1, int x2, int z2) {
    double dx = x1 - (double) x2;
    double dz = z1 - (double) z2;
    return dx * dx + dz * dz;
}
```

Simplify the resolver contracts to:

```java
public record ChunkFacts(BooleanSupplier hasPredefinedBuilding,
                         BooleanSupplier hasPredefinedStreet,
                         Supplier<CityStyle> cityStyle,
                         Supplier<RoadType> effectiveRoad,
                         BooleanSupplier hasHighway,
                         IntSupplier maxHighwayLevel,
                         BooleanSupplier hasRailway,
                         Supplier<Railway.RailChunkInfo> railInfo) {
}

public static ChunkContent resolve(
        UrbexProfile profile, long seed, RandomSource rand,
        boolean isCity, boolean couldHaveBuilding, RoadType effectiveRoad,
        MultiPos section, ChunkCoord coord, PrefersLonely prefersLonely,
        @Nullable String candidateBuildingName)
```

This is a signature excerpt, not a replacement declaration: retain the existing lonely veto, open-lot decision, park roll, street selection, and `ChunkContent` return. Remove only the sphere-edge clamp and sphere-center override from implementation and Javadoc.

- [ ] **Step 4: Remove `issphere` from the condition model and adapt existing tests**

Delete the `issphere` field/getter/constructor argument from `ConditionTest`, and remove the corresponding optional codec field and constructor argument from `ConditionPart` and `PartRef`. Delete `ConditionContext.isSphere()` and every override in `BuildingInfo`, `CityGenerator`, `Scattered`, and `BelowPartConditionTest`.

In `ChunkContentResolverTest`:

- remove the `LandscapeType`, `CitySphereSettings`, and sphere-related imports;
- remove `sphereDistance` from `Facts` and its supplier from `build()`;
- remove the two sphere-edge tests and the sphere-center override test;
- remove the obsolete `null` sphere-center argument from every `resolve` helper/call;
- rewrite the Pass Two class comment to describe only the lonely veto, open-lot park roll, and street selection.

Update `BelowPartConditionTest` constructor calls to the shorter `ConditionTest` signature while keeping its three below-part/in-part assertions unchanged.

- [ ] **Step 5: Compile and run the affected tests**

Run:

```bash
./gradlew compileJava test \
  --tests dev.krona.urbex.worldgen.lost.ChunkContentResolverTest \
  --tests dev.krona.urbex.worldgen.lost.cityassets.BelowPartConditionTest \
  --tests dev.krona.urbex.worldgen.lost.BuildingInfoTest
```

Expected: `compileJava` succeeds and all selected tests pass.

- [ ] **Step 6: Commit the runtime removal**

```bash
git add -A src/main/java/dev/krona/urbex \
  src/test/java/dev/krona/urbex/worldgen/lost/ChunkContentResolverTest.java \
  src/test/java/dev/krona/urbex/worldgen/lost/cityassets/BelowPartConditionTest.java
git commit -m "refactor(worldgen): remove sphere and monorail runtime"
```

---

### Task 2: Remove sphere profiles, UI, registries, and asset schemas

**Files:**

- Delete: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/PredefinedSphere.java`
- Delete: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/PredefinedSphereRE.java`
- Delete: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/CitySphereSettings.java`
- Delete: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/SphereSettings.java`
- Delete: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/MonorailParts.java`
- Modify: `src/main/java/dev/krona/urbex/config/LandscapeType.java`
- Modify: `src/main/java/dev/krona/urbex/config/ProfileSetup.java`
- Modify: `src/main/java/dev/krona/urbex/config/UrbexProfile.java`
- Modify: `src/main/java/dev/krona/urbex/gui/CustomizeScreen.java`
- Modify: `src/main/java/dev/krona/urbex/gui/settings/SettingCategory.java`
- Modify: `src/main/java/dev/krona/urbex/gui/settings/SettingControls.java`
- Modify: `src/main/java/dev/krona/urbex/gui/settings/Settings.java`
- Modify: `src/main/java/dev/krona/urbex/setup/CustomRegistries.java`
- Modify: `src/main/java/dev/krona/urbex/varia/Rng.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/ChunkHeightmap.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetRegistries.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/CityStyle.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/WorldStyle.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/CityStyleRE.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/WorldStyleRE.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/PartSelector.java`
- Modify: `src/test/java/dev/krona/urbex/config/LegacyTomlTest.java`
- Modify: `src/test/java/dev/krona/urbex/config/ProfileSetupDensityTest.java`
- Modify: `src/test/java/dev/krona/urbex/config/UrbexConfigTest.java`
- Modify: `src/test/java/dev/krona/urbex/varia/RngTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/CityPredefinedCacheLatchTest.java`

**Interfaces:**

- Consumes: the sphere-free runtime and single-profile dimension interface from Task 1.
- Produces: `LandscapeType` with the currently supported values, while callers discover values dynamically.
- Produces: `PartSelector(HighwayParts highwayParts, RailwayParts railwayParts)`.
- Produces: city/world style codecs with no sphere or monorail fields.
- Task 3 consumes these schemas when cleaning the bundled JSON resources.

- [ ] **Step 1: Record a green configuration and schema baseline**

Run:

```bash
./gradlew test \
  --tests dev.krona.urbex.config.ProfileSetupDensityTest \
  --tests dev.krona.urbex.gui.settings.SettingsCompletenessTest \
  --tests dev.krona.urbex.varia.RngTest \
  --tests dev.krona.urbex.worldgen.lost.CityPredefinedCacheLatchTest
```

Expected: all selected suites pass before the model is reduced.

- [ ] **Step 2: Remove sphere landscapes, presets, settings, and GUI controls**

Reduce `LandscapeType` to the currently supported enum values without adding a count/set assertion:

```java
public enum LandscapeType {
    DEFAULT("default"),
    FLOATING("floating"),
    CAVERN("cavern");
    // existing name map and accessors remain
}
```

In `UrbexProfile`, delete `CATEGORY_CITY_SPHERES`, every `CITYSPHERE_*` field, `SPAWN_SPHERE`, `initCitySpheres`, `getCategoryCitySpheres`, and the `isSpace`/`isSpheres`/`isVoidSpheres` helpers. Remove the sphere-category initialization call/comment. Make the `landscapeType` validation list dynamic:

```java
String[] landscapeNames = Arrays.stream(LandscapeType.values())
        .map(LandscapeType::getName)
        .toArray(String[]::new);
String type = cfg.getString("landscapeType", UrbexProfile.CATEGORY_CITY_ID,
        LANDSCAPE_TYPE.getName(), "Type of landscape", landscapeNames);
```

In `ProfileSetup`, delete the complete `space`, `biosphere_caves`, `biosphere`, `bio_wasteland`, and `void_outside` blocks, plus the commented water-bubble profiles. Remove `SPHERES` from `SettingCategory`, the whole sphere descriptor block and advanced sphere fields from `Settings`, and stale sphere/monorail comments in `Settings`, `SettingControls`, and `CustomizeScreen`.

Remove obsolete sphere switch arms from `ChunkHeightmap` and any switch sites intentionally left compiling in Task 1.

- [ ] **Step 3: Remove sphere and monorail registry/schema objects**

Delete the five classes listed above. Remove `PREDEFINEDSPHERES_REGISTRY_KEY` and its registration from `CustomRegistries`; remove `PREDEFINED_SPHERES` from `AssetRegistries.reset()` and `loadPredefinedStuff()`, and update that method’s comments to refer only to predefined cities.

Remove sphere fields, initialization, getters and inheritance from `CityStyle`/`CityStyleRE`. Remove city-sphere settings from `WorldStyle`/`WorldStyleRE`.

Reduce `PartSelector` to:

```java
public record PartSelector(HighwayParts highwayParts, RailwayParts railwayParts) {
    public static final Codec<PartSelector> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    HighwayParts.CODEC.optionalFieldOf("highways").forGetter(l -> l.highwayParts.get()),
                    RailwayParts.CODEC.optionalFieldOf("railways").forGetter(l -> l.railwayParts.get())
            ).apply(instance, (highways, railways) -> new PartSelector(
                    highways.orElse(HighwayParts.DEFAULT),
                    railways.orElse(RailwayParts.DEFAULT))));

    public static final PartSelector DEFAULT =
            new PartSelector(HighwayParts.DEFAULT, RailwayParts.DEFAULT);

    public Optional<PartSelector> get() {
        if (this == DEFAULT) {
            return Optional.empty();
        }
        return Optional.of(this);
    }
}
```

- [ ] **Step 4: Preserve RNG addresses under neutral names**

Rename without reordering: `SPHERE` to `RESERVED_19`, `SPHERE_BLOCKS` to `RESERVED_30`, and `SPHERE_CITY_LEVEL` to `RESERVED_31`.

Update the enum comments to explain that the unused ordinals preserve existing addresses for unrelated consumers. Keep `PURPOSE_COUNT` and golden values unchanged in `RngTest`; update only `PURPOSE_ORDER` to the neutral names.

- [ ] **Step 5: Update existing profile and registry tests**

In `ProfileSetupDensityTest`, remove the five deleted profile entries while retaining the exact density expectations for all profiles that remain. In `LegacyTomlTest` and `UrbexConfigTest`, replace `biosphere` sample values with `cavern` so the parser tests use a current profile name.

Update `CityPredefinedCacheLatchTest` comments from “PredefinedCity/PredefinedSphere” to predefined-city-only wording; its behavior and assertions remain unchanged. Do not add an enum-size test or any sphere-absence test. `SettingsCompletenessTest` remains generic and should require no sphere-specific assertion.

- [ ] **Step 6: Compile and run the affected model tests**

Run:

```bash
./gradlew compileJava test \
  --tests dev.krona.urbex.config.LegacyTomlTest \
  --tests dev.krona.urbex.config.ProfileSetupDensityTest \
  --tests dev.krona.urbex.config.UrbexConfigTest \
  --tests dev.krona.urbex.gui.settings.SettingsCompletenessTest \
  --tests dev.krona.urbex.varia.RngTest \
  --tests dev.krona.urbex.worldgen.lost.CityPredefinedCacheLatchTest
```

Expected: compilation succeeds; remaining profile count/densities, generic settings completeness, RNG golden values/order, and predefined-city cache behavior all pass.

- [ ] **Step 7: Commit the public/model removal**

```bash
git add -A src/main/java/dev/krona/urbex src/test/java/dev/krona/urbex
git commit -m "refactor(config): remove sphere profiles and schemas"
```

---

### Task 3: Remove bundled resources, document the break, and verify

**Files:**

- Delete: `src/main/resources/data/urbex/worldgen/configured_feature/spheres.json`
- Delete: `src/main/resources/data/urbex/worldgen/placed_feature/spheres.json`
- Delete: `src/main/resources/data/urbex/urbex/parts/monorails_both.json`
- Delete: `src/main/resources/data/urbex/urbex/parts/monorails_station.json`
- Delete: `src/main/resources/data/urbex/urbex/parts/monorails_vertical.json`
- Delete: `src/main/resources/assets/urbex/textures/gui/icon_space.png`
- Delete: `src/main/resources/assets/urbex/textures/gui/icon_biosphere.png`
- Delete: `src/main/resources/assets/urbex/textures/gui/icon_cavespheres.png`
- Delete: `src/main/resources/assets/urbex/textures/gui/icon_bubbles.png`
- Modify: `src/main/resources/data/urbex/urbex/citystyles/citystyle_common.json`
- Modify: `src/main/resources/data/urbex/urbex/citystyles/citystyle_border.json`
- Modify: `src/main/resources/data/urbex/urbex/worldstyles/standard.json`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json`
- Modify: `src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**

- Consumes: the sphere-free codecs and `PartSelector` produced by Task 2.
- Produces: a bundled datapack containing only references understood by the current Java schemas.
- Produces: the final user-facing statement of this intentional breaking removal.

- [ ] **Step 1: Remove sphere/monorail resources and localization**

Delete the nine files listed above. Remove `sphereblocks` objects from `citystyle_common.json` and `citystyle_border.json`; remove the `parts.monorails` object from `worldstyles/standard.json` while retaining highway and railway wiring.

From `en_us.json`, remove:

- the Spheres category and its four section label/description pairs;
- every `CITYSPHERE_*` label and tooltip;
- `SPAWN_SPHERE` label and tooltip;
- `landscapetype.space`, `landscapetype.spheres`, and `landscapetype.cavernspheres` labels.

Keep the `CITY_THRESHOLD` tooltip: its use of the geometric word “sphere” describes the city influence function, not the removed CitySphere feature.

- [ ] **Step 2: Keep datapack integrity generic**

In `DatapackReferenceIntegrityTest`, remove only the block that traverses `parts.monorails`. Keep traversal and resolution for all currently supported asset references. Do not add a test that bans monorail or sphere names.

Run:

```bash
./gradlew test --tests dev.krona.urbex.data.DatapackReferenceIntegrityTest
```

Expected: the generic integrity suite passes against the reduced datapack.

- [ ] **Step 3: Document the breaking removal**

Add one entry near the top of `CHANGELOG.md`’s Unreleased section stating all of the following:

- city spheres and their three landscape types were removed;
- the `space`, `biosphere`, and `biosphere_caves` presets were removed;
- predefined spheres, sphere profile settings, sphere asset fields, and sphere spawn targeting were removed;
- the old monorail implementation was removed because it only connected spheres;
- old test worlds/config directories may need to be recreated and no migration is provided.

Do not edit earlier changelog entries or preserved design/history documents that accurately describe older revisions.

- [ ] **Step 4: Run the one-time dangling-reference audit**

Run:

```bash
rg -n 'CitySphere|CITYSPHERE_|SPAWN_SPHERE|PredefinedSphere|SphereFeature|SphereSettings|Monorail|monorail|isSpace\(|isSpheres\(|isVoidSpheres\(|LandscapeType\.(SPACE|SPHERES|CAVERNSPHERES)|SettingCategory\.SPHERES|landscapetype\.(space|spheres|cavernspheres)|sphereblocks|cityspheres' \
  src/main/java src/test/java src/main/resources

rg --files src/main/resources | \
  rg '(configured_feature/spheres|placed_feature/spheres|monorails_|icon_(space|biosphere|cavespheres|bubbles))'
```

Expected: both commands produce no output. This is a one-time implementation check, not a committed test; the geometric `CITY_THRESHOLD` tooltip does not match these feature identifiers.

- [ ] **Step 5: Run the complete verification suite**

Run:

```bash
./gradlew test
./gradlew build
git diff --check
git status --short
```

Expected: tests and build report `BUILD SUCCESSFUL`; `git diff --check` prints nothing; status lists only the intended Task 3 changes plus any explicitly preserved pre-existing work.

- [ ] **Step 6: Commit resources and documentation**

```bash
git add -A CHANGELOG.md src/main/resources \
  src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java
git commit -m "chore: remove city sphere resources"
```
