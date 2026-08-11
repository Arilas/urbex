# Datapack-Driven Presets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the runtime-generated profile system with a codec-backed dynamic registry `urbex:presets`, defaults shipped as datapack JSON with delta-inheritance, worldStyle extracted into first-class selection plumbing, `useAvgHeightmap` on by default, explicit non-zero lighting density, plus a JSON Schema kept honest by tests.

**Spec:** `docs/superpowers/specs/2026-08-11-datapack-presets-design.md`

**Architecture:** A new `PresetRE` codec record (all-optional, sectioned) registers as the 13th dynamic registry in `CustomRegistries`, exactly like `WorldStyleRE`. A resolver walks the `parent` chain onto code defaults, producing a runtime `Preset` class that keeps `UrbexProfile`'s public field names verbatim — so the ~40 worldgen consumers change only by type rename. Selection (preset id + worldStyle id + optional customization overrides) flows through `Config`/`UrbexData` as three first-class values instead of smuggled JSON.

**Tech Stack:** Java 21 / Fabric, Mojang DFU codecs (`RecordCodecBuilder`, `JsonOps`), Gson, JUnit 5 (`./gradlew test`), Gradle run configs `runDigestCheck` / `runDigestCheckFeatures` for worldgen goldens.

## Global Constraints

- Clean break: no legacy parsing, no migration of old saved data, configs, or profile files (spec §2). Old `Configuration`-format anything is deleted, not deprecated.
- User-facing and internal naming is "preset", not "profile" (spec §1). Exception: the runtime class keeps `UrbexProfile`'s UPPER_SNAKE public field names (e.g. `RUIN_CHANCE`) to make the worldgen migration a pure type rename.
- Preset ids are namespaced `Identifier`s; bare names default to the `urbex` namespace via `DataTools.fromName` (spec §3).
- `useAvgHeightmap` code default flips to `true`; every shipped preset carries an explicit `lightingDensity` (spec §6).
- Unknown JSON keys never fail a load: they log one WARN naming key and preset; keys starting with `_` are silently allowed as pack metadata (spec §4).
- Commit style: conventional commits (`feat:`, `fix:`, `docs:`, `test:`) as in recent history.
- Test command: `./gradlew test`. Full build: `./gradlew build`.
- The digest goldens (`digest.golden`, `digest-features.golden`) are regenerated ONCE, in Task 6, after all worldgen-affecting changes have landed. Tasks 1–5 must NOT regenerate them; the digest run configs are expected to fail during the transition.

## File Structure Overview

```
src/main/java/dev/krona/urbex/
  worldgen/lost/regassets/PresetRE.java              (new — codec record)
  worldgen/lost/regassets/data/preset/               (new package — 11 section records + UnknownKeys)
  config/Preset.java                                 (new — resolved runtime class; replaces UrbexProfile)
  config/Presets.java                                (new — resolver + cache + tag listing)
  config/UrbexProfile.java, Configuration.java,
         ProfileSetup.java                           (deleted in Task 5)
  setup/CustomRegistries.java                        (+1 registry)
  setup/Config.java                                  (selection rewrite)
  data/UrbexData.java                                (new saved-data shape)
  worldgen/* , gen/*, plan/*                         (type rename UrbexProfile -> Preset)
  gui/PresetSelection.java, CitiesTab.java,
      CustomizeScreen.java, RecreateProfileRestore.java,
      settings/Settings.java                         (registry-driven UI)
  network/PacketRequestProfile.java,
          PacketReturnProfileToClient.java           (deleted — handle() is an empty stub, no consumers)
  commands/CommandSaveProfile.java                   (becomes CommandSavePreset)
src/main/resources/data/urbex/
  urbex/presets/*.json                               (new — 12 shipped presets)
  tags/urbex/presets/presets.json                    (new — #urbex:presets UI tag)
docs/schema/preset.schema.json                       (new)
docs/presets.md                                      (new — authoring + IDE guide)
```

## Field → Section Mapping (authoritative)

JSON key names are unchanged from the old format (camelCase). Every key below is
`optionalFieldOf` in its section codec. `worldStyle`, `public`, `basedOn`,
`generateLighting`, `generateLoot`, `buildingWithoutLootChance`,
`chestWithoutLootChance` do NOT exist in the new format.

| Section | Keys |
|---|---|
| *(top level)* | `parent` (preset id), `description`, `extraDescription`, `warning`, `icon` |
| `terrain` | landscapeType, groundLevel, seaLevel, liquidBlock, baseBlock, bedrockLayer, terrainFixLowerMinOffset, terrainFixLowerMaxOffset, terrainFixUpperMinOffset, terrainFixUpperMaxOffset, oceanCorrectionBorder, avoidWater, useAvgHeightmap |
| `cities` | cityChance, cityMinRadius, cityMaxRadius, cityPerlinScale, cityPerlinOffset, cityPerlinInnerScale, cityThreshold, citySpawnDistance1, citySpawnDistance2, citySpawnMultiplier1, citySpawnMultiplier2, cityStyleThreshold, cityStyleAlternative, cityAvoidVoid, cityLevel0Height…cityLevel7Height, cityMinHeight, cityMaxHeight, scatteredChanceMultiplier |
| `buildings` | buildingChance, buildingMinFloors, buildingMaxFloors, buildingMinFloorsChance, buildingMaxFloorsChance, buildingMinCellars, buildingMaxCellars, buildingDoorwayChance, buildingFrontChance, multiUseCorner, multiBuildingStreetConflict, generateSpawners |
| `roads` | primaryRoadSpacingX, primaryRoadSpacingZ, primaryRoadOptionalChance, primaryRoadForceEvery, secondaryRoadMinCountX, secondaryRoadMaxCountX, secondaryRoadMinCountZ, secondaryRoadMaxCountZ, minimumRoadSeparation, minimumRoadEdgeDistance, tertiaryRoadChance, tertiaryRoadMinLength, tertiaryRoadMaxLength, plannedPrimaryBridgeChance, plannedPrimaryBridgeMaxLength, openLotParkChance, parkElevation, parkBorder, parkStreetThreshold, fountainChance, corridorChance, bridgeChance, bridgeSupports |
| `highways` | highwayRequiresTwoCities, highwayLevelFromCities, highwayDistanceMask, highwayMainPerlinScale, highwaySecondaryPerlinScale, highwayPerlinFactor, highwaySupports |
| `railways` | railwaysEnabled, railwayStationsEnabled, railwaySurfaceStationsEnabled, railwaysCanEnd, railwayDungeonChance |
| `destruction` | ruinChance, ruinMinlevelPercent, ruinMaxlevelPercent, rubbleLayer, rubbleDirtScale, rubbleLeaveScale, explosionChance, explosionMinRadius, explosionMaxRadius, explosionMinHeight, explosionMaxHeight, miniExplosionChance, miniExplosionMinRadius, miniExplosionMaxRadius, miniExplosionMinHeight, miniExplosionMaxHeight, explosionsInCitiesOnly, debrisToNearbyChunkFactor |
| `decoration` | randomLeafBlockChance, randomLeafBlockThickness, avoidFoliage, lightingDensity, lootDensity |
| `spawn` | spawnBiome, spawnCity, spawnNotInBuilding, forceSpawnInBuilding, forceSpawnBuildings, forceSpawnParts, spawnCheckRadius, spawnRadiusIncrease, spawnCheckAttempts |
| `atmosphere` | horizon, fogRed, fogGreen, fogBlue, fogDensity |
| `misc` | editMode, generateNether |

Old-field-to-key mapping, value types, ranges, and comments: mine them from
`UrbexProfile.init*()` (`src/main/java/dev/krona/urbex/config/UrbexProfile.java:238-525`)
before it is deleted — every `cfg.getX(key, category, default, min, max, comment)`
call is one row: key, Java field, type, default, bounds, doc string. Reuse the
comments as JSON Schema `description`s in Task 7.

---

### Task 1: Preset format — codec, resolved class, resolver

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/PresetRE.java`
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/preset/` — `TerrainSettings.java`, `CitySettings.java`, `BuildingSettings.java`, `RoadSettings.java`, `HighwaySettings.java`, `RailwaySettings.java`, `DestructionSettings.java`, `DecorationSettings.java`, `SpawnSettings.java`, `AtmosphereSettings.java`, `MiscSettings.java`, `UnknownKeys.java`
- Create: `src/main/java/dev/krona/urbex/config/Preset.java`
- Create: `src/main/java/dev/krona/urbex/config/Presets.java`
- Modify: `src/main/java/dev/krona/urbex/setup/CustomRegistries.java`
- Test: `src/test/java/dev/krona/urbex/config/PresetCodecTest.java`, `PresetResolutionTest.java`, `PresetRoundTripTest.java`

**Interfaces (later tasks rely on these exact signatures):**
- `PresetRE` — immutable record-style class, `public static final Codec<PresetRE> CODEC`, `Optional<Identifier> parent()`, metadata getters, one getter per section (`Optional<TerrainSettings> terrain()` etc.), implements `IAsset<PresetRE>` (setRegistryName/getRegistryName like `WorldStyleRE`).
- Each section record: all-`Optional` fields + `void apply(Preset p)` writing only present values.
- `Preset` — resolved runtime class. Public UPPER_SNAKE fields copied verbatim from `UrbexProfile` (same names, same types) EXCEPT: no `worldStyle` field/getters, no `isPublic`, no `Configuration` anything. `USE_AVG_HEIGHTMAP` defaults `true`. Constructor `Preset(Identifier id)` (code defaults). Keeps helpers `getLiquidBlock()`, `getBaseBlock()`, `isDefault()`, `isFloating()`, `isCavern()`, `getIcon()`, metadata getters, plus `Identifier getId()` and `Preset copy()` (field-by-field clone, for the GUI editor). New: `PresetRE toRE()` — encodes EVERY field as present (fully-populated sections), used for round-trip tests, saved-data overrides, and the export command.
- `Presets` — static:
  - `Preset resolve(Identifier id, Function<Identifier, PresetRE> lookup)` — pure core, testable headless. Walks the parent chain (cycle → `IllegalStateException` naming the chain; missing parent → `IllegalStateException` naming the id), applies root-first onto `new Preset(id)`.
  - `Preset resolve(RegistryAccess access, Identifier id)` — registry-backed wrapper with a `ConcurrentHashMap<Identifier, Preset>` cache (get / construct-outside / putIfAbsent, mirroring `RegistryAssetRegistry`).
  - `Preset applyOverrides(Preset base, PresetRE overrides)` — clone base, apply the RE's sections on top (this is how the Customize screen's saved deltas land).
  - `List<Identifier> listBrowsable(RegistryAccess access)` — members of tag `#urbex:presets` in registry order; if the tag is missing or empty, all registry entries. Sort: `urbex:default` first, then lexicographic.
  - `void reset()` — clears the cache; wire into `AssetRegistries.reset()`.
- `CustomRegistries.PRESET_REGISTRY_KEY` — `ResourceKey<Registry<PresetRE>>` for `urbex:presets`, registered via `DynamicRegistries.register(PRESET_REGISTRY_KEY, PresetRE.CODEC)`.
- `Presets.TAG_BROWSABLE = TagKey.create(CustomRegistries.PRESET_REGISTRY_KEY, Identifier.fromNamespaceAndPath("urbex", "presets"))`.

- [ ] **Step 1: Write failing codec + resolution tests.** Headless JUnit, `JsonOps.INSTANCE`, no MC bootstrap needed (sections hold only primitives/strings/lists). Cover at minimum:

```java
// PresetCodecTest
@Test void minimalFileParses() {
    // {"description":"x","cities":{"cityChance":0.001}} -> PresetRE with only those present
}
@Test void unknownTopLevelKeyParsesButWarns() {
    // {"citiez":{}} -> parses fine; UnknownKeys.check returns ["citiez"]
}
@Test void unknownSectionKeyParsesButWarns() {
    // {"cities":{"cityChanse":0.1}} -> parses; check returns ["cities.cityChanse"]
}
@Test void underscoreKeysAreSilentlyAllowed() {
    // {"_comment":"x","cities":{"_note":"y"}} -> parses; check returns []
}
@Test void enumValuesParse() {
    // terrain.landscapeType "cavern" -> LandscapeType.CAVERN;
    // buildings.multiBuildingStreetConflict parses by name
}

// PresetResolutionTest — lookup = Map<Identifier,PresetRE>::get
@Test void parentlessPresetGetsCodeDefaults() {
    // empty PresetRE resolves; assert USE_AVG_HEIGHTMAP == true, LIGHTING_DENSITY == 0.15f,
    // GROUNDLEVEL == 71 — i.e. defaults survive
}
@Test void childOverridesOnlyItsOwnFields() {
    // parent sets cities.cityChance=0.5; child sets destruction.ruinChance=0.9;
    // child resolves with BOTH, parent's other fields = defaults
}
@Test void grandparentChainAppliesRootFirst() { /* 3 levels, middle overrides root, leaf wins */ }
@Test void cycleIsError() { /* a->b->a throws IllegalStateException naming both ids */ }
@Test void danglingParentIsError() { /* parent id absent from lookup */ }

// PresetRoundTripTest
@Test void toReEncodesEveryKey() {
    // new Preset(id).toRE() encoded via CODEC: every section present, key set per section
    // EXACTLY equals the section's UnknownKeys allowed set (this is the drift guard's engine)
}
@Test void roundTripPreservesValues() {
    // mutate a Preset (one field per section), toRE() -> encode -> decode -> resolve
    // with empty lookup -> field-by-field equals
}
```

- [ ] **Step 2: Run tests, verify they fail** (classes don't exist): `./gradlew test --tests 'dev.krona.urbex.config.Preset*'`

- [ ] **Step 3: Implement.** Key mechanics:

*Unknown-key warning* — DFU codecs silently ignore unknown keys; we keep that leniency (loads never fail, forward-compatible) but surface typos with one WARN. Two pieces so the check is pure and testable:

```java
// UnknownKeys.java
/** Pure: unknown top-level keys of a map-shaped Dynamic. Keys starting with "_" are pack metadata and never reported. */
public static List<String> check(Dynamic<?> dyn, Set<String> allowed) {
    return dyn.asMapOpt().result().stream().flatMap(s -> s)
            .map(p -> p.getFirst().asString(""))
            .filter(k -> !k.startsWith("_") && !allowed.contains(k))
            .toList();
}

/** Wraps a codec: decode is unchanged, but unknown keys log one WARN naming them and the context. */
public static <A> Codec<A> warning(Codec<A> base, Set<String> allowed, String context) {
    return Codec.PASSTHROUGH.comapFlatMap(dyn -> {
        List<String> unknown = check(dyn, allowed);
        if (!unknown.isEmpty()) {
            Urbex.getLogger().warn("Ignoring unknown key(s) in preset {}: {}", context, unknown);
        }
        return base.parse(dyn);
    }, a -> new Dynamic<>(JsonOps.INSTANCE, base.encodeStart(JsonOps.INSTANCE, a).getOrThrow()));
}
```

Each section record declares `public static final Set<String> KEYS = Set.of(...)` next to its codec and exposes `CODEC = UnknownKeys.warning(RAW_CODEC, KEYS, "cities")`. `PresetRE.CODEC` likewise wraps with its top-level key set (`parent`, metadata, 11 section names). The `toReEncodesEveryKey` test pins KEYS == actual codec fields. (The registry loader doesn't hand the codec the file's id, so the warning carries the section name and keys — enough to grep a pack for.)

*>16 fields* — `RecordCodecBuilder.instance.group(...)` caps at 16; `cities` (25), `roads` (23), `destruction` (18) split as `instance.group(f1..f14).and(instance.group(f15..f25)).apply(instance, Ctor)`.

*Section shape* — one complete example; the other ten follow it mechanically using the mapping table and the mined defaults/bounds:

```java
public record RailwaySettings(Optional<Boolean> railwaysEnabled,
                              Optional<Boolean> railwayStationsEnabled,
                              Optional<Boolean> railwaySurfaceStationsEnabled,
                              Optional<Boolean> railwaysCanEnd,
                              Optional<Float> railwayDungeonChance) {
    public static final Set<String> KEYS = Set.of("railwaysEnabled", "railwayStationsEnabled",
            "railwaySurfaceStationsEnabled", "railwaysCanEnd", "railwayDungeonChance");
    private static final Codec<RailwaySettings> RAW = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("railwaysEnabled").forGetter(RailwaySettings::railwaysEnabled),
            Codec.BOOL.optionalFieldOf("railwayStationsEnabled").forGetter(RailwaySettings::railwayStationsEnabled),
            Codec.BOOL.optionalFieldOf("railwaySurfaceStationsEnabled").forGetter(RailwaySettings::railwaySurfaceStationsEnabled),
            Codec.BOOL.optionalFieldOf("railwaysCanEnd").forGetter(RailwaySettings::railwaysCanEnd),
            Codec.floatRange(0f, 1f).optionalFieldOf("railwayDungeonChance").forGetter(RailwaySettings::railwayDungeonChance)
    ).apply(i, RailwaySettings::new));
    public static final Codec<RailwaySettings> CODEC = UnknownKeys.warning(RAW, KEYS, "railways");

    public void apply(Preset p) {
        railwaysEnabled.ifPresent(v -> p.RAILWAYS_ENABLED = v);
        railwayStationsEnabled.ifPresent(v -> p.RAILWAY_STATIONS_ENABLED = v);
        railwaySurfaceStationsEnabled.ifPresent(v -> p.RAILWAY_SURFACE_STATIONS_ENABLED = v);
        railwaysCanEnd.ifPresent(v -> p.RAILWAYS_CAN_END = v);
        railwayDungeonChance.ifPresent(v -> p.RAILWAY_DUNGEON_CHANCE = v);
    }
}
```

Use ranged codecs (`Codec.floatRange`, `Codec.intRange`) with the bounds mined from `UrbexProfile.init*()` so out-of-range values fail at load. Enums (`LandscapeType`, `MultiBuildingStreetConflict`) parse by lowercase name via `Codec.STRING.comapFlatMap`. `forceSpawnBuildings`/`forceSpawnParts` are `Codec.STRING.listOf()` (change the `Preset` fields to `List<String>`, and update the two consumers found by grepping `FORCE_SPAWN_BUILDINGS`/`FORCE_SPAWN_PARTS`).

*Resolution core*:

```java
public static Preset resolve(Identifier id, Function<Identifier, PresetRE> lookup) {
    List<PresetRE> chain = new ArrayList<>();   // leaf..root
    Set<Identifier> seen = new LinkedHashSet<>();
    Identifier cur = id;
    while (cur != null) {
        if (!seen.add(cur)) throw new IllegalStateException("Preset parent cycle: " + seen + " -> " + cur);
        PresetRE re = lookup.apply(cur);
        if (re == null) throw new IllegalStateException("Unknown preset '" + cur + "' (referenced from '" + id + "')");
        chain.add(re);
        cur = re.parent().orElse(null);
    }
    Preset p = new Preset(id);
    for (int i = chain.size() - 1; i >= 0; i--) chain.get(i).applyTo(p);  // root first
    return p;
}
```

`PresetRE.applyTo(Preset)` applies metadata (description/extraDescription/warning/icon if present) then each present section's `apply`.

Register the registry key in `CustomRegistries` (13th entry, same pattern as line 49) and add `Presets.reset()` to `AssetRegistries.reset()` (`worldgen/lost/cityassets/AssetRegistries.java:35`).

- [ ] **Step 4: Run tests to green:** `./gradlew test --tests 'dev.krona.urbex.config.Preset*'`, then full `./gradlew test` (nothing else should break yet — old system untouched).

- [ ] **Step 5: Commit:** `feat(presets): codec-backed preset format with delta inheritance`

---

### Task 2: Bundled datapack — 12 shipped presets + UI tag

**Files:**
- Create: `src/main/resources/data/urbex/urbex/presets/{default,cavern,nodamage,floating,rarecities,onlycities,tallbuildings,safe,ancient,wasteland,atlantis,largecities}.json`
- Create: `src/main/resources/data/urbex/tags/urbex/presets/presets.json`
- Test: `src/test/java/dev/krona/urbex/config/ShippedPresetsTest.java`

**Interfaces:**
- Consumes: `PresetRE.CODEC`, `Presets.resolve(Identifier, Function)` from Task 1.
- Produces: the 12 preset resource files + tag that everything later loads in-game.

- [ ] **Step 1: Write failing test.** It loads every `*.json` under `src/main/resources/data/urbex/urbex/presets/` from the filesystem (walk the path relative to the repo root, same technique as any existing resource-reading test — if none exists, use `Path.of("src/main/resources/data/urbex/urbex/presets")`), decodes with `PresetRE.CODEC` + `JsonOps`, builds a lookup map keyed by `urbex:<filename>`, and asserts:

```java
@Test void allShippedPresetsParseAndResolve() { /* no decode errors, resolve() succeeds for all */ }
@Test void everyShippedPresetHasExplicitLightingDensity() {
    // spec §6: each FILE (not resolution result) carries decoration.lightingDensity present
}
@Test void avgHeightmapOnEverywhere() { /* resolved USE_AVG_HEIGHTMAP true for all 12 */ }
@Test void nonDefaultPresetsParentDefault() { /* all except default have parent urbex:default */ }
@Test void tagListsExactlyTheShippedPresets() {
    // parse tags/urbex/presets/presets.json, values == 12 ids
}
```

- [ ] **Step 2: Run, verify fails** (no files yet).

- [ ] **Step 3: Write the 12 preset files.** Values translate `ProfileSetup.initStandardProfiles()` (`config/ProfileSetup.java:37-228`) exactly; that method stays in-tree until Task 5, so diff against it. Two complete examples, then the delta table:

`default.json`:
```json
{
  "description": "Default generation, common cities, explosions",
  "icon": "textures/gui/icon_default.png",
  "decoration": { "lightingDensity": 0.15, "lootDensity": 0.65 }
}
```

`rarecities.json`:
```json
{
  "parent": "urbex:default",
  "description": "Cities are rare",
  "icon": "textures/gui/icon_rarecities.png",
  "cities": { "cityChance": 0.001 },
  "destruction": { "ruinChance": 0.0 },
  "highways": { "highwayRequiresTwoCities": false },
  "railways": { "railwaysCanEnd": true },
  "decoration": { "lightingDensity": 0.15 }
}
```

Remaining ten (every file also gets its `description`/`extraDescription`/`warning`/`icon` from ProfileSetup, `"parent": "urbex:default"`, and an explicit `decoration.lightingDensity` — value from ProfileSetup where set, else 0.15):

| Preset | Section deltas (beyond metadata + lightingDensity) |
|---|---|
| cavern | terrain{landscapeType:"cavern", groundLevel:40, seaLevel:32}; atmosphere{horizon:128, fogRed:0, fogGreen:0, fogBlue:0, fogDensity:0.02}; destruction{explosionChance:0, miniExplosionChance:0}; railways{railwaysEnabled:false}; cities{cityLevel0Height:44, cityLevel1Height:52, cityLevel2Height:60, cityLevel3Height:68, cityLevel4Height:76, cityLevel5Height:82, cityLevel6Height:90, cityLevel7Height:98}; lightingDensity 0.65 |
| nodamage | destruction{explosionChance:0, miniExplosionChance:0, ruinChance:0, rubbleLayer:false} |
| floating | terrain{landscapeType:"floating", groundLevel:50}; cities{cityChance:0.03, cityLevel0Height:50, cityLevel1Height:56, cityLevel2Height:62, cityLevel3Height:68, cityLevel4Height:76, cityLevel5Height:84, cityLevel6Height:92, cityLevel7Height:100}; atmosphere{horizon:0}; highways{highwaySupports:false, highwayDistanceMask:15}; buildings{buildingMaxCellars:1}; railways{railwaysCanEnd:true, railwaysEnabled:false, railwayStationsEnabled:false} |
| onlycities | cities{cityChance:0.2, cityMaxRadius:256} |
| tallbuildings | buildings{buildingMinFloors:4, buildingMinFloorsChance:8, buildingMaxFloorsChance:15, buildingMaxFloors:19}; destruction{debrisToNearbyChunkFactor:175, explosionChance:0.006, explosionMaxHeight:256, explosionMaxRadius:60, explosionMinHeight:130, miniExplosionChance:0.09, miniExplosionMaxHeight:256, miniExplosionMaxRadius:14, miniExplosionMinRadius:3, ruinChance:0.01} |
| safe | buildings{generateSpawners:false}; decoration{lightingDensity:1.0, lootDensity:0.0} |
| ancient | decoration{randomLeafBlockThickness:6, randomLeafBlockChance:0.05, lightingDensity:0.05, lootDensity:0.4}; destruction{explosionChance:0, miniExplosionChance:0, rubbleLayer:true, rubbleDirtScale:2.0, rubbleLeaveScale:2.0, ruinChance:0.9, ruinMinlevelPercent:0.0, ruinMaxlevelPercent:0.9} |
| wasteland | decoration{randomLeafBlockChance:0.01, avoidFoliage:true, lightingDensity:0.05, lootDensity:0.4}; destruction{rubbleLayer:true, rubbleDirtScale:2.0, rubbleLeaveScale:0.0, ruinChance:0.5, ruinMinlevelPercent:0.5, ruinMaxlevelPercent:0.9}; terrain{avoidWater:true} |
| atlantis | terrain{seaLevel:89}; destruction{ruinChance:0.1} |
| largecities | cities{cityChance:-1, cityPerlinScale:7.0, cityPerlinOffset:0.2, cityPerlinInnerScale:0.1, cityThreshold:0.1, cityStyleThreshold:0.4, cityStyleAlternative:"citystyle_border"}; decoration{lightingDensity:0.35}; buildings{buildingMaxFloors:9, buildingMaxFloorsChance:7, buildingChance:0.4} |

Tag file `src/main/resources/data/urbex/tags/urbex/presets/presets.json`:
```json
{ "values": ["urbex:default", "urbex:cavern", "urbex:nodamage", "urbex:floating",
             "urbex:rarecities", "urbex:onlycities", "urbex:tallbuildings", "urbex:safe",
             "urbex:ancient", "urbex:wasteland", "urbex:atlantis", "urbex:largecities"] }
```

- [ ] **Step 4: Run tests to green**, full `./gradlew test`.
- [ ] **Step 5: Commit:** `feat(presets): ship the 12 built-in presets as datapack files`

---

### Task 3: Selection plumbing — Config, UrbexData, worldgen type swap

**Files:**
- Modify: `src/main/java/dev/krona/urbex/data/UrbexData.java`
- Modify: `src/main/java/dev/krona/urbex/config/UrbexConfig.java` (fields at :23-25, codec at :55-70)
- Modify: `src/main/java/dev/krona/urbex/setup/Config.java` (selection surface, cache, validation)
- Modify: `src/main/java/dev/krona/urbex/worldgen/CityFeature.java:92-116`
- Modify: `src/main/java/dev/krona/urbex/worldgen/DefaultDimensionInfo.java:41-50`, `worldgen/lost/City.java:345`, `gui/NullDimensionInfo.java`
- Modify: `src/main/java/dev/krona/urbex/setup/DigestCheck.java` (pins the digest preset — switch to namespaced id)
- Modify (mechanical type rename `UrbexProfile` → `Preset`): every file matching `grep -rl 'UrbexProfile' src/main src/test` not otherwise listed — worldgen, gen, plan (`GridSettings.fromProfile` → `fromPreset(Preset)`), varia, commands.
- Test: `src/test/java/dev/krona/urbex/setup/PresetChoiceTest.java` (new); update existing tests that construct `UrbexProfile`.

**Interfaces:**
- Consumes: `Presets.resolve(RegistryAccess, Identifier)`, `Presets.applyOverrides`, `PresetRE.CODEC` (Task 1).
- Produces:
  - `record PresetChoice(Identifier preset, Identifier worldStyle, Optional<String> overridesJson)` (in `setup/Config.java` or its own file in `setup/`).
  - `Config.presetFromClient` (`Identifier`, nullable), `Config.worldStyleFromClient` (`Identifier`, nullable), `Config.overridesFromClient` (`String` JSON of a `PresetRE`, nullable) — replacing `profileFromClient`/`jsonFromClient`.
  - `Config.getPresetChoiceForDimension(ServerLevel, ResourceKey<Level>)` → `PresetChoice` or null — replacing `getProfileForDimension`.
  - `UrbexData.setChoice(String preset, String worldStyle, String overridesJson)` / `getSelectedPreset()` / `getSelectedWorldStyle()` / `getSelectedOverrides()`.
  - `Config.DEFAULT_WORLD_STYLE = Identifier.fromNamespaceAndPath("urbex", "standard")`.
  - `DefaultDimensionInfo(WorldGenLevel world, Preset preset, Identifier worldStyle)`.

- [ ] **Step 1: Failing unit test for the config-entry parser.** `dimensionsWithPresets` entries use `dimension=preset[@worldstyle]`:

```java
@Test void parsesDimensionPresetEntry() {
    // "minecraft:overworld=urbex:rarecities" -> (overworld, urbex:rarecities, urbex:standard)
    // "minecraft:the_nether=urbex:cavern@urbex:standard" -> explicit style
    // bare names namespace to urbex: "minecraft:overworld=default" -> urbex:default
}
@Test void malformedEntryIsRejectedWithError() { /* "junk" and "a=b=c=d" -> empty + logged */ }
```
Put the parser in a small static method (`Config.parseDimensionPresetEntry(String)` returning `Optional<Map.Entry<ResourceKey<Level>, PresetChoice>>`) so it tests headless.

- [ ] **Step 2: Run, verify fails.**

- [ ] **Step 3: Implement the plumbing.**

*UrbexData* — codec becomes three optional strings (empty string = unset, matching current style):
```java
Codec.STRING.optionalFieldOf("preset", "").forGetter(d -> d.selectedPreset),
Codec.STRING.optionalFieldOf("worldStyle", "").forGetter(d -> d.selectedWorldStyle),
Codec.STRING.optionalFieldOf("overrides", "").forGetter(d -> d.selectedOverrides)
```
Old worlds stored `{profile, json}`; with `optionalFieldOf` those keys are simply ignored → clean break with no crash (the world regenerates its selection as unset; changelog covers it).

*UrbexConfig* — rename `dimensionsWithProfiles` → `dimensionsWithPresets`, `selectedProfile` → `selectedPreset` (default `""`), add `selectedWorldStyle` (default `""`), delete `selectedCustomJson`. Update `DEFAULT` and codec accordingly.

*Config* — `buildPresetCache(ServerLevel)` mirrors the old flow with the three-value record: config entries first, then client-published values (persist into `UrbexData.setChoice`), then saved data, then global config for the overworld. Overrides JSON, when present, rides in `PresetChoice.overridesJson` — no more mutating a shared "customized" profile. `GENERATE_NETHER` on the resolved overworld preset maps the nether to `new PresetChoice(Identifier.fromNamespaceAndPath("urbex","cavern"), DEFAULT_WORLD_STYLE, Optional.empty())`. **Note:** resolving the overworld preset inside cache-building needs `RegistryAccess` — `level.registryAccess()` is available; resolve via `Presets.resolve` and read `GENERATE_NETHER` off the result. `validateSelectedPresets(MinecraftServer)` (wired where `validateSelectedProfiles` was, `ModSetup.init`) checks every referenced preset id exists in `server.registryAccess().lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY)` and every style id in the worldstyles registry, failing with the sorted list of valid ids.

*CityFeature.getDimensionInfo* —
```java
PresetChoice choice = Config.getPresetChoiceForDimension(world.getLevel(), type);
if (choice == null) return null;
Preset preset = Presets.resolve(world.registryAccess(), choice.preset());
if (choice.overridesJson().isPresent()) {
    PresetRE re = PresetRE.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString(choice.overridesJson().get())).getOrThrow();
    preset = Presets.applyOverrides(preset, re);
}
IDimensionInfo diminfo = new DefaultDimensionInfo(world, preset, choice.worldStyle());
```

*DefaultDimensionInfo* — `style = AssetRegistries.WORLDSTYLES.get(this.world, worldStyle)` (the `Identifier` overload). `City.java:345`'s `profile.getWorldStyle()` read switches to `provider.getWorldStyle()` (the compiled style is already on `IDimensionInfo`; if the call site needs the multiplier off the RE, pass through the dimension info — inspect the call site and keep semantics identical). `NullDimensionInfo` (preview) takes `(Preset, Identifier worldStyle)` the same way.

*Mechanical rename* — `UrbexProfile` → `Preset` everywhere else; field names are unchanged so only imports/type names move. `getName()` call sites (log lines like `CityFeature.java:85`) switch to `getId()`. Delete `Preset`-unrelated leftovers only in Task 5.

- [ ] **Step 4: Green:** `./gradlew test`; also `./gradlew compileJava` early and often — this touches ~40 files.
- [ ] **Step 5: Commit:** `feat(presets): first-class preset+worldstyle selection plumbing`

---

### Task 4: GUI — registry-driven selection, customize as overrides

**Files:**
- Modify: `src/main/java/dev/krona/urbex/gui/PresetSelection.java` (rewrite), `CitiesTab.java`, `CustomizeScreen.java`, `RecreateProfileRestore.java`, `PresetListWidget.java`, `WorldStyleDialog.java` (touch-ups)
- Delete: `src/main/java/dev/krona/urbex/gui/SaveAsDialog.java` (spec §9 — save-to-config is gone)
- Modify: `src/main/java/dev/krona/urbex/gui/settings/Settings.java`, `SettingDescriptor.java` (retarget `UrbexProfile` → `Preset` in the generics; lambdas unchanged)
- Modify: `src/main/resources/assets/urbex/lang/en_us.json` (drop save-as keys; preset wording)
- Test: update `src/test/java/dev/krona/urbex/gui/` (`WorldStyleDialogTest`, `WorldStyleSelectionTest`, PresetSelection tests) and `settings/SettingsCompletenessTest` (reflect over `Preset`; excluded set stays `EDITMODE`, now also minus the removed `worldStyle`).

**Interfaces:**
- Consumes: `Presets.listBrowsable`, `Presets.resolve`, `Preset.copy()`, `Preset.toRE()`, `Config.presetFromClient`/`worldStyleFromClient`/`overridesFromClient` (Task 3).
- Produces:
  - `PresetSelection.Entry(Identifier id, Component name, Preset preset)` — no more `custom`/`basedOn`/`Optional`; the transient customized entry keeps a sentinel id `urbex:customized`.
  - `PresetSelection.setAvailablePresets(List<Entry>)` — injected by `CitiesTab` from the world-creation `RegistryAccess` (same source as `registeredWorldStyles(screen)` at `CitiesTab.java:357-370`), built via `Presets.listBrowsable` + `resolve`.
  - `publish()` — sets the three `Config.*FromClient` values: preset id (null for disabled), chosen worldStyle id (never null once a preset is selected; defaults to `Config.DEFAULT_WORLD_STYLE`), overrides JSON only for the customized entry (`Preset.toRE()` encoded via `PresetRE.CODEC` + Gson) — the worldStyle-smuggling branch (`PresetSelection.java:256-291`) is deleted.
  - `restore(String preset, String worldStyle, String overridesJson)` for the Re-Create flow.

- [ ] **Step 1: Update/extend the headless PresetSelection tests first** (they exist and run without MC): entries come only from `setAvailablePresets` (no `ProfileSetup` statics), disabled row first, `urbex:default` sorts first, publish sets the three Config fields, customized publish emits parseable `PresetRE` JSON, worldStyle no longer round-trips through profile JSON. Run: fails.

- [ ] **Step 2: Implement.**
  - `PresetSelection`: drop `ProfileSetup`/`USER_PROFILES` imports entirely; state = injected entries + selected + worldStyle + optional customized `Preset`. `applyCustomized(Preset copy)` keeps working for the editor.
  - `CitiesTab`: alongside `registeredWorldStyles`, add `registeredPresets(screen)` reading the preset registry + tag; inject both. The preview path (`NullDimensionInfo`) gets the resolved `Preset` + effective style id.
  - `CustomizeScreen`: operates on `selection.selected().preset().copy()`; Done → `applyCustomized(copy)` + republish. Remove `performSave`/`basedOn` (the whole file-writing path at `CustomizeScreen.java:418` area) and the Save-as button.
  - `RecreateProfileRestore`: read the three new `UrbexData` fields; call `restore`.
- [ ] **Step 3: Green:** `./gradlew test`.
- [ ] **Step 4: Commit:** `feat(presets): registry-driven create-world UI; customize publishes overrides`

---

### Task 5: Cleanup — delete the old system, dead packets, export command

**Files:**
- Delete: `src/main/java/dev/krona/urbex/config/UrbexProfile.java`, `Configuration.java`, `ProfileSetup.java`
- Delete: `src/main/java/dev/krona/urbex/network/PacketRequestProfile.java`, `PacketReturnProfileToClient.java` (+ registrations in `Urbex.java`, `setup/ClientSetup.java`) — `handle()` is an empty stub since 1.14; nothing consumes them.
- Delete: `src/test/java/dev/krona/urbex/config/UrbexProfileDensityTest.java`, `ProfileSetupDensityTest.java` (superseded by Task 1/2 tests)
- Modify: `src/main/java/dev/krona/urbex/setup/ModSetup.java` (drop `ProfileSetup.setupProfiles()` from `preInit`)
- Modify: `src/main/java/dev/krona/urbex/commands/CommandSaveProfile.java` → rename `CommandSavePreset`, command `urbex savepreset`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json` (sweep leftover profile strings)

**Interfaces:**
- Consumes: `Preset.toRE()`, `PresetRE.CODEC`.
- Produces: `urbex savepreset` — dumps the sender dimension's **resolved** preset (post-inheritance, post-overrides, from the live `IDimensionInfo`) as pretty JSON to `<gamedir>/urbex-export/<id path>.json` and prints the path (spec §11):

```java
JsonElement json = PresetRE.CODEC.encodeStart(JsonOps.INSTANCE, preset.toRE()).getOrThrow();
Path out = FabricLoader.getInstance().getGameDir().resolve("urbex-export");
Files.createDirectories(out);
Files.writeString(out.resolve(preset.getId().getPath() + ".json"),
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json));
```

- [ ] **Step 1: Delete + rewire**, then sweep: `grep -rn 'UrbexProfile\|ProfileSetup\|STANDARD_PROFILES\|profileFromClient\|jsonFromClient\|Configuration\b\|lostcity' src/main src/test` must return zero hits (the `lostcity` category string dies with the format — spec §11).
- [ ] **Step 2: Green:** `./gradlew build` (full build, not just test — catches resource/lang issues).
- [ ] **Step 3: Commit:** `feat(presets)!: delete the runtime-generated profile system`

---

### Task 6: Playable checkpoint — in-game validation + digest goldens

**Files:**
- Modify: `digest.golden`, `digest-features.golden`, `CHANGELOG.md`

**Interfaces:** none new — this is verification.

- [ ] **Step 1: Run the client** (`./gradlew runClient` or the IDE run config) and verify in-game, in a NEW world (old dev worlds are unsupported — clean break):
  - Create-world Cities tab lists the 12 presets (from the datapack registry/tag).
  - Select `rarecities` + the standard style; world generates; no `config/urbex/profiles/` directory appears anywhere under `runs/`.
  - City buildings contain lights (lightingDensity 0.15 path exercising `CityGenerator.handleLightMarker`).
  - `urbex savepreset` writes the resolved JSON and it shows `useAvgHeightmap: true`.
  - Customize a value, create another world, verify the override applies and survives relaunch (saved data).
- [ ] **Step 2: Regenerate goldens** (both defaults changes shift worldgen output — spec §6). Per the procedure in `build.gradle:70-105`: delete `digest.golden`, run `runDigestCheck` twice (outputs must match), commit the new value; same for `digest-features.golden` with `runDigestCheckFeatures`, and confirm the features window still reports bridge chunks at (87..89, 92) and a slope at (74, 87) — if either count is zero, relocate the window per the build.gradle comment instead of accepting the golden.
- [ ] **Step 3: Also wipe stale digest run dirs first** (`runs/digestcheck`, `runs/digestcheckfeatures`) — this run IS the #112 scenario; regenerating from wiped dirs closes it honestly. Comment on and close issue #112: root cause removed (no config-dir profiles exist), goldens regenerated from clean runs.
- [ ] **Step 4: Changelog entry:** presets are datapack-driven; old worlds/configs unsupported; testers recreate worlds; avg heightmap now default-on; lighting density fixed.
- [ ] **Step 5: Commit:** `chore(presets): regenerate worldgen goldens for avg-heightmap + preset defaults`

---

### Task 7: JSON Schema + docs

**Files:**
- Create: `docs/schema/preset.schema.json`
- Create: `docs/presets.md`
- Modify: `build.gradle` (add `testImplementation 'com.networknt:json-schema-validator:1.5.6'`)
- Test: `src/test/java/dev/krona/urbex/config/PresetSchemaTest.java`

**Interfaces:**
- Consumes: `Preset.toRE()` full-encode (Task 1), shipped preset files (Task 2), section `KEYS` sets.

- [ ] **Step 1: Failing tests:**

```java
@Test void everyShippedPresetValidatesAgainstSchema() {
    // networknt JsonSchemaFactory (draft 2020-12), validate all 12 files: no messages
}
@Test void schemaCoversExactlyTheCodecKeys() {
    // for each section: schema properties key set == section KEYS constant;
    // top level: properties == {parent, description, extraDescription, warning, icon, ...11 sections};
    // every object node has "additionalProperties": false
}
@Test void schemaRejectsUnknownKey() { /* {"cities":{"cityChanse":1}} -> validation error */ }
```

- [ ] **Step 2: Write the schema.** Draft 2020-12, `$id` pointing at the repo raw URL, `additionalProperties: false` on the root and every section PLUS `"patternProperties": {"^_": {}}` so `_comment`-style pack metadata isn't flagged (mirroring the codec's warning exemption), types/ranges/enums mirroring the codecs (reuse the min/max mined from `UrbexProfile.init*()` in Task 1 as `minimum`/`maximum`, and the old config comments as `description` strings — that documentation should not die with the class). `parent` is a string with pattern `^([a-z0-9_.-]+:)?[a-z0-9_./-]+$`.
- [ ] **Step 3: Write `docs/presets.md`:** what a preset is; a complete minimal example datapack (`pack.mcmeta`, one delta preset with `parent`, a tag file adding it to `#urbex:presets`); the resolution rules (parent chain, code defaults); `urbex savepreset` as the "show me everything" tool; IDE wiring — VS Code `settings.json`:

```json
"json.schemas": [{
  "fileMatch": ["**/data/*/urbex/presets/*.json"],
  "url": "./docs/schema/preset.schema.json"
}]
```
and the IntelliJ equivalent (Settings → JSON Schema Mappings, same file pattern).
- [ ] **Step 4: Green:** `./gradlew test`, then full `./gradlew build`.
- [ ] **Step 5: Commit:** `docs(presets): JSON schema with drift tests and authoring guide`

---

## Self-Review Notes

- Spec coverage: §1/§3→Tasks 1–2, §2→Tasks 3/5 (clean break, ignored old saved-data keys), §4/§5→Task 1, §6→Tasks 1 (default flip) + 2 (explicit densities) + 6 (goldens), §7→Task 3, §8→Tasks 1 (listBrowsable) + 2 (tag file) + 4 (UI), §9→Task 4, §10→Task 7, §11→Tasks 3 (DigestCheck), 4 (Settings/completeness), 5 (packets/command/`lostcity` string), §12 sequencing→Tasks 1–5 then 6 checkpoint then 7.
- The dead-packet deletion (Task 5) exceeds the spec's "move packets to the new format" — reconnaissance showed `handle()` has been an empty stub since 1.14 and the fog/horizon fields have no runtime consumers. Deleting beats porting dead code; the atmosphere section stays in the format for a future re-wire.
- Known risk, called out for the executor: `Config.buildPresetCache` resolving `GENERATE_NETHER` needs registry access during worldgen init — `level.registryAccess()` is safe there because `getDimensionInfo` already runs with a live `ServerLevel`.
