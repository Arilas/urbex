# Experimental Site API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let another mod define a patch of Urbex city — its own preset, world style, ground level
and hard vertical window — and drive its generation itself, so a cave-bunker mod can carve a cavity
and ask Urbex to fill it.

**Architecture:** A *site* is a second `PlanningContext` + `CityGenerator` over the same level,
holding its own `DimensionCaches`. `PlanningContext` gains one nullable component, `SiteBinding`;
six call sites consult it and every one is inert when it is null. The vertical window is enforced
structurally at `ChunkBuffer`, the single choke point every driver write passes through. Urbex
dispatches nothing — the caller calls `fill`.

**Tech Stack:** Java 25, Fabric 26.2, Mixin, Gradle (loom). Tests are JUnit 5 under `src/test/java`.

## Global Constraints

- **No generated output may change.** Every seam is guarded by `site != null`. The five digest
  goldens (`digest.golden`, `digest-features.golden`, `digest-avoid.golden`,
  `digest-avoid-modes.golden`, `digest-rail.golden`) must reproduce exactly, at the default worker
  pool size and at `-Dmax.bg.threads=2`.
- **The public API is `dev.krona.urbex.api` and nothing else.** No other package gains public types
  for this feature. Every type in it carries an `@Experimental`-style javadoc warning that it may
  change without a deprecation cycle.
- **`SiteField` implementations are contractually pure**, thread-safe, and answer for any
  coordinate. Urbex calls them for neighbours of the chunk being filled.
- **No new dispatch.** `CarverHookMixin` and `CityFeature` are not modified.
- Package layout follows the existing tree: `worldgen/` for generation, `worldgen/lost/` for
  planning, `setup/` for configuration.

---

### Task 1: The vertical write window at `ChunkBuffer`

The hard half of the Y guarantee, built first because it is independently testable and inert by
default.

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/ChunkBuffer.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/ChunkDriver.java:235` (`setPrimer`)
- Create: `src/test/java/dev/krona/urbex/worldgen/ChunkBufferWindowTest.java`

**Interfaces:**
- Produces: `ChunkDriver.setPrimer(LevelAccessor, ChunkAccess, int writeMinY, int writeMaxY)` — the
  existing two-argument overload delegates with the level's own bounds, so no caller changes.
- Produces: `ChunkBuffer(WriteLog, WorldView, int minY, int maxY, int originX, int originZ, int writeMinY, int writeMaxY)`.

- [ ] **Step 1: Write the failing tests**

`ChunkBufferWindowTest` covers, against a buffer whose level is `[-64, 320)` and whose window is
`[10, 40]`:
- `set` at y=9 and y=41 is neither logged nor flushed; at y=10 and y=40 both happen.
- `fill(x, z, 5, 45, state)` writes exactly y=10..40 and logs exactly 31 positions.
- `fillWhere` outside the window does not consult the `WorldView` at all (a `WorldView` that throws
  proves it).
- `remember` outside the window leaves the slot null, so a section that is partly in and partly out
  flushes only its in-window blocks.
- An unbounded window (`writeMinY == minY`, `writeMaxY == maxY`) behaves exactly as today.

- [ ] **Step 2: Run and watch them fail** — `./gradlew test --tests '*ChunkBufferWindowTest*'`

- [ ] **Step 3: Implement**

Add `writeMinY` / `writeMaxY` fields. Guard `set`, `fill`, `fillWhere` and `remember`. `fill` and
`fillWhere` clamp their loop bounds rather than testing per iteration. `sectionUntouched`,
`sectionBottom` and `get` are unchanged — they are reads, and a read outside the window must keep
answering about the world.

- [ ] **Step 4: Run the tests and the full suite** — `./gradlew test`

- [ ] **Step 5: Commit** — `feat(worldgen): bound driver writes to a vertical window`

---

### Task 2: `SiteBinding` and the planning seams

The core. After this task a site can be constructed in a test and plans correctly, though nothing
public can build one yet.

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/SiteBinding.java`
- Create: `src/main/java/dev/krona/urbex/worldgen/SiteTerrain.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/PlanningContext.java` (tenth component)
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/CityField.java:34,58,80`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/ChunkPlan.java:428`
- Modify: `src/main/java/dev/krona/urbex/worldgen/CityGenerator.java:235`
- Create: `src/test/java/dev/krona/urbex/worldgen/SitePlanningTest.java`

**Interfaces:**
- Produces: `record SiteBinding(Identifier id, SiteField field, int minY, int maxY)` where
  `SiteField` is the public API interface from Task 4 — declare the interface in
  `dev.krona.urbex.api` now and let this depend on it, rather than defining a duplicate.
- Produces: `PlanningContext.site()` returning `@Nullable SiteBinding`, plus a compact convenience
  constructor keeping the existing nine-argument form (passing `null`) so no existing call site or
  test changes.
- Produces: `SiteTerrain implements TerrainSampler` — flat heightmap at `field.groundY(coord)`,
  biome and registry access delegated to a wrapped `TerrainSampler`.

- [ ] **Step 1: Write the failing tests**

`SitePlanningTest`, built on whatever headless `PlanningContext` fixture the existing planning tests
use (see `src/test/java/dev/krona/urbex/worldgen/` and `plan/`):
- With a `SiteField` that answers true on a 3×3 block of chunks and `groundY == 30`, the centre
  chunk's `ChunkPlan.isCity` is true, its `groundLevel` is 30 and its `cityLevel` is 0; a chunk
  outside the block plans `isCity == false`.
- The same `ChunkCoord` planned through a site context and through the level's context yields two
  distinct `ChunkPlan` instances with different `groundLevel` — proving cache separation.
- A `PlanningContext` with `site() == null` reaches `City.getCityFactor` exactly as before (assert
  a known-city coordinate under a stock preset still plans as city).

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement the seams**

Each is a single guarded branch:

```java
// CityField.isCityRaw
SiteBinding site = provider.site();
if (site != null) {
    return site.field().isSite(coord.chunkX(), coord.chunkZ());
}
// ... existing void check and city factor
```

```java
// CityField.getCityLevel and cityLevelUncached
if (provider.site() != null) {
    return 0;   // no surface height bands underground
}
```

```java
// ChunkPlan constructor, replacing `groundLevel = ... : profile.groundLevel();`
SiteBinding site = provider.site();
int planned = site != null ? site.field().groundY(key.chunkX(), key.chunkZ())
                           : profile.groundLevel();
groundLevel = override != null ? override.groundLevel() : planned;
```

```java
// CityGenerator.generateOrThrow, immediately before the doCity branch
if (!doCity && provider.site() != null) {
    return;   // a site is sparse: a non-site chunk is left exactly as it was found
}
```

Each guard carries a javadoc-grade comment naming *why*, in the register the surrounding code uses.

- [ ] **Step 4: Run the tests and the full suite**

- [ ] **Step 5: Commit** — `feat(worldgen): plan a chunk against a caller-supplied site`

---

### Task 3: Windowing the deferred writes

Post-todos, light todos and level tasks bypass the driver, so the `ChunkBuffer` guard does not reach
them. Bound them by anchor position.

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/ChunkGenContext.java:89,101,117`
- Create: `src/test/java/dev/krona/urbex/worldgen/SiteDeferredWriteTest.java`

**Interfaces:**
- Consumes: `PlanningContext.site()` from Task 2.
- Produces: nothing new — `addPostTodo`, `addLightTodo` and `addLevelTask` silently drop
  out-of-window anchors when a site is bound.

- [ ] **Step 1: Write the failing test** — a `ChunkGenContext` on a site with window `[10, 40]`
  accepts a post-todo at y=20 and drops one at y=50; same for a light todo and a level task. On a
  context with no site, nothing is dropped.

- [ ] **Step 2: Run and watch it fail**

- [ ] **Step 3: Implement** — capture the window on the context at construction (from
  `provider.site()`, or the level's full range when there is none) and test the anchor's Y in the
  three `add` methods. Document that this is exact for the anchored block and approximate for
  anything a callback touches around it.

- [ ] **Step 4: Run the suite**

- [ ] **Step 5: Commit** — `feat(worldgen): bound a site's deferred writes to its window`

---

### Task 4: The public API package

**Files:**
- Create: `src/main/java/dev/krona/urbex/api/SiteField.java`
- Create: `src/main/java/dev/krona/urbex/api/SiteSpec.java`
- Create: `src/main/java/dev/krona/urbex/api/UrbexSite.java`
- Create: `src/main/java/dev/krona/urbex/api/UrbexApi.java`
- Create: `src/main/java/dev/krona/urbex/api/package-info.java`
- Create: `src/main/java/dev/krona/urbex/worldgen/SiteRuntimes.java` (internal: builds and memoises)
- Create: `src/test/java/dev/krona/urbex/api/SiteSpecTest.java`
- Create: `docs/site-api.md`

**Interfaces:**
- Consumes: `SiteBinding`, `SiteTerrain` (Task 2); `GenerationSession.current()`,
  `GenerationSession.runtimeFor(level)`, `Presets.resolve(RegistryAccess, Identifier)`,
  `Presets.applyOverrides(Preset, PresetDefinition)`, `PresetDefinition.parseOverrides(JsonElement)`,
  `WorldStyleField.resolve(AssetSnapshot, long, WorldStyleMix)`,
  `GridRoadField(long, String, PresetRoadGrid)`, `LevelShape.of(LevelReader)`.
- Produces:
  - `UrbexApi.site(ServerLevel, SiteSpec) -> UrbexSite`
  - `UrbexApi.isAvailable(ServerLevel) -> boolean`
  - `UrbexSite.fill(WorldGenRegion, ChunkAccess) -> boolean`
  - `SiteSpec.builder(Identifier id, Identifier preset, SiteField field)` with
    `.worldStyles(WorldStyleMix)`, `.worldStyle(Identifier)`, `.presetOverrides(String json)`,
    `.window(int minY, int maxY)`, `.build()`.

- [ ] **Step 1: Write the failing tests** — `SiteSpecTest` covers builder validation: `maxY < minY`
  is rejected at build time with a message naming both values; a null field, id or preset is
  rejected; the default window is the full level range sentinel; `worldStyle(id)` and
  `worldStyles(mix)` produce equal specs for a single style.

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement**

`SiteRuntimes` holds a `Map<ServerLevel, Map<Identifier, Site>>` behind the `GenerationSession`
lifetime, so sites die with their world. Building one:

1. Resolve the preset, apply overrides if present (rethrowing `RetiredPresetKeyException`, logging
   anything else, exactly as `DimensionRuntime.create` does).
2. `assets` and `tagEpoch` from `GenerationSession.current()`; refuse with a named exception if the
   world has not compiled yet.
3. `LevelShape` = the level's, clamped to the spec's window.
4. `TerrainSampler` = `SiteTerrain` wrapping a `LevelTerrain` built on fresh `DimensionCaches`.
5. `PlanningContext` with the new `SiteBinding`, then a `CityGenerator` over it.

`fill` builds a `DimensionRuntime` from the level's published task queue and the world's tag epoch,
then calls `generator.generate(runtime, region, chunk)`. It does **not** touch `GeneratedChunkMark`:
the mark guards Urbex's own two dispatch routes, and a caller that calls `fill` twice for one chunk
has made its own decision.

`docs/site-api.md` is the reference: the purity contract, the "a `WorldCarver` has no
`WorldGenRegion`, hook the carver tail instead" note, the window semantics, and a complete worked
example.

- [ ] **Step 4: Run the suite**

- [ ] **Step 5: Commit** — `feat(api): experimental site API`

---

### Task 5: Prove nothing moved

**Files:** none.

- [ ] **Step 1:** `./gradlew test`
- [ ] **Step 2:** Run all six digest configurations and compare against the goldens.
- [ ] **Step 3:** Re-run with `-PurbexDigestVmArgs=-Dmax.bg.threads=2`.
- [ ] **Step 4:** Record the hashes in the branch's PR body. If any golden moves, that is a bug in
      Tasks 1-4, not a golden to regenerate.

---

### Task 6: `minecraft-mods/Urbex-Bunkers`

**Files (new project):**
- `build.gradle`, `settings.gradle`, `gradle.properties`, `gradlew` (copied from Urbex's layout)
- `src/main/resources/fabric.mod.json`, `urbexbunkers.mixins.json`
- `src/main/java/dev/krona/urbexbunkers/UrbexBunkers.java`
- `src/main/java/dev/krona/urbexbunkers/BunkerField.java`
- `src/main/java/dev/krona/urbexbunkers/BunkerCavity.java`
- `src/main/java/dev/krona/urbexbunkers/mixin/BunkerCarverHookMixin.java`
- `src/main/resources/data/urbexbunkers/...` — a world style and preset for the bunkers
- `README.md`

**Interfaces:**
- Consumes: the whole of Task 4's API.

- [ ] **Step 1:** Scaffold the Fabric mod against the local Urbex jar.
- [ ] **Step 2:** `BunkerField implements SiteField` — a region grid (default 24 chunks); each region
      hashes to a centre chunk, a radius in chunks and a depth. `isSite` is a squared-distance test
      against the region's centre and its two neighbours in each axis so a site near a region edge
      is not clipped; `groundY` is the region's depth. Pure, no allocation, no state.
- [ ] **Step 3:** `BunkerCavity` — hollows rock in the window around the site, driven off the same
      field, so carve and fill cannot disagree.
- [ ] **Step 4:** `BunkerCarverHookMixin` at `applyCarvers` TAIL: carve, then `fill`. One
      `UrbexSite` per level, fetched through `UrbexApi.site` (memoised, so per-chunk is fine).
- [ ] **Step 5:** Build it, run a client, fly to a bunker, screenshot.
- [ ] **Step 6:** Commit in the Urbex-Bunkers repo.

---

## Self-Review

**Spec coverage:** public surface → Task 4; purity contract → Tasks 2 and 4 (javadoc) and `docs/site-api.md`;
internal seam table → Tasks 1-3; cache separation → Task 2 test; disabled-dimension support → Task 4
(`fill` borrows only the task queue and tag epoch); sparse non-site chunk → Task 2; window planning
half → Task 2 (`LevelShape` clamp, built in Task 4's assembly); window writing half → Task 1;
deferred writes → Task 3; commuting property → consequence of Tasks 1 and 3, asserted nowhere and
stated in docs; example mod → Task 6; verification → Task 5 plus each task's own tests.

**Placeholders:** none — every step names the file, the assertion or the code.

**Type consistency:** `SiteField` is declared once, in `dev.krona.urbex.api`, and consumed by
`SiteBinding`; `SiteBinding.field()` is the only accessor used across Tasks 2 and 3;
`UrbexSite.fill` has one signature throughout.
