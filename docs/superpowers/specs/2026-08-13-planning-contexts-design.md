# Explicit planning, preview and runtime contexts (#129)

Phase 3, item 8 of epic #134. This is the design for replacing `IDimensionInfo` — and with it
`NullDimensionInfo`, the preview's impersonation of a server dimension — with values that say what
they carry.

## What is wrong today

`IDimensionInfo` is thirteen methods wide and answers questions from five unrelated scopes:

| Scope | Methods |
| --- | --- |
| World identity | `getSeed`, `getType`, `dimension` |
| Level access | `getWorld`, `registryAccess` |
| Compiled inputs | `assets`, `getProfile`, `worldStyles`, `roadField` |
| Per-dimension caches | `caches` |
| Terrain sampling | `getHeightmap` ×2, `getBiome` |
| The generator itself | `getFeature` |

Two implementations exist. `DefaultDimensionInfo` wraps a `ServerLevel`. `NullDimensionInfo`
impersonates one for the world-creation preview: it returns `null` from `getWorld()`, constructs a
`CityGenerator` nothing drives, and hand-builds a placeholder `WorldStyleDefinition` complete enough
to survive post-resolution requiredness checks — the only place in `src/main` that builds a world
style by hand rather than decoding one.

The consequences are concrete, not stylistic:

- **`getWorld() != null` is load-bearing in planning code.** `City` guards five datapack-derived
  static maps on it, `ChunkPlan.getCityLevel` skips its cache on it, and `City.getCityFactor` gates
  two rules on `registryAccess() != null` for the same reason. Each is a planning decision keyed on
  "am I the preview?".
- **The generator/dimension cycle.** `DefaultDimensionInfo`'s constructor builds a `CityGenerator`
  passing `this`, and `CityGenerator` reads `provider.getWorld()` back out. Neither can be
  constructed in a test without the other.
- **Narrow tests need dynamic proxies.** `CityPredefinedCacheLatchTest` fabricates an
  `IDimensionInfo` through `java.lang.reflect.Proxy` and throws from every method it did not
  anticipate, because there is no smaller thing to hand `City` than the whole bag.
- **The preview cannot be trusted to agree with generation.** It reaches the same planner, but the
  planner takes different branches for it.

## Target shape

```java
record PlanningContext(
        long seed,
        ResourceKey<Level> dimension,
        Preset preset,
        AssetSnapshot assets,
        WorldStyleField worldStyles,
        RoadField roadField,
        DimensionCaches caches,
        LevelShape shape,
        TerrainSampler terrain) {}

record PreviewContext(PlanningContext planning, PreviewTerrain terrain) {}

record DimensionRuntime(ServerLevel level, PlanningContext planning, CityGenerator generator,
                        LevelTaskQueue tasks, TagEpoch tagEpoch) {}
```

`DimensionRuntime` is not introduced here — #125 created it in phase 1 with an `IDimensionInfo` in
the planning slot, and this issue narrows that same record. There is one definition, not two.

The two new ports are what `getWorld()` decomposes into:

- **`LevelShape`** — `minY`, `maxY`, `seaLevel`. Every `provider.getWorld().getMinY()`,
  `.getMaxY()` and `Tools.getSeaLevel(provider.getWorld())` in planning and generation reads one of
  these three numbers. They are fixed for the life of a dimension, so they are values, not calls.
- **`TerrainSampler`** — the chunk heightmap at a coordinate, the biome at a position, and the
  registry-backed biome lookup planning rules need. Backed by the level in generation and by the
  preview's own biome bitmap in the preview, so both answer rather than one answering `null`.

`PlanningContext` is a composition-root value. Planners and renderers keep taking the collaborators
they need; nothing gains a `PlanningContext` parameter it does not read.

## Order of work

Each step is its own PR, in this order. The ordering is a dependency chain: every step removes one
reason the next step's deletion would be unsafe.

1. **`PredefinedIndex` on the asset snapshot.** `City`'s five static latched maps become one index
   computed by `AssetCompiler`, so the `getWorld() != null` guards that protect the latches from the
   preview disappear with the latches. *(This PR.)*
2. **`LevelShape`.** Resolve `minY`/`maxY`/`seaLevel` once per runtime; replace the
   `getWorld()`-for-a-number call sites. The preview gets a real shape instead of a null world.
3. **`TerrainSampler`.** Extract heightmap/biome sampling behind a port; `DefaultDimensionInfo` and
   the preview each implement it. After this, `getWorld()` has no planning-path callers left.
4. **`PlanningContext`.** Introduce the record and migrate `ChunkPlan`, `City`, `Highway`, `Railway`,
   `DamageArea`, `MultiChunk`, `PrimaryBridgePlanner`, `Scattered`, `Railways`, `BiomeInfo`,
   `WorldStyle`, `Style` and `ChunkContentResolver` off `IDimensionInfo`.
5. **`PreviewContext`.** The preview builds a `PlanningContext` directly; `NullDimensionInfo` and its
   hand-built placeholder world style are deleted.
6. **Narrow `DimensionRuntime`.** `planning` becomes a `PlanningContext` and `generator` becomes a
   real component; `IDimensionInfo` and `DefaultDimensionInfo` are deleted.
7. **Runtime lookup for commands and mixins.** `Registration.cityFeature()` stops being how code
   reaches generation.
8. **Planning-package purity.** `Preset -> GridSettings` moves out of `dev.krona.urbex.plan`, and the
   package dependency test is tightened so pure planning may depend only on `java.*` and
   `dev.krona.urbex.plan.*`.

## Verification

Steps 1–4, 6 and 7 must not move any digest golden: they relocate ownership without changing what is
computed. Step 5 changes what the *preview* draws only where it was previously drawing the
placeholder fallback; it touches no server generation path. Step 8 is mechanical.

Every PR states `runDigestCheck`, `runDigestCheckFeatures`, `runDigestCheckAvoid`,
`runDigestCheckAvoidModes` and `runDigestCheckRail` and says whether they moved.

## Step 1 in detail: `PredefinedIndex`

`City` holds five `static final` maps and five `static volatile boolean` latches:

```java
PREDEFINED_CITY_MAP        // ChunkCoord -> PredefinedCity
PREDEFINED_BUILDING_MAP    // ChunkCoord -> PredefinedBuilding   (declared position)
PREDEFINED_STREET_MAP      // ChunkCoord -> PredefinedStreet     (declared position)
OCCUPIED_CHUNKS_BUILDING   // ChunkCoord -> PreDefBuildingOffset (every chunk a multi covers)
OCCUPIED_CHUNKS_STREET     // ChunkCoord -> PredefinedStreet     (every chunk a street covers)
```

All five are a pure function of two things the snapshot already holds: `predefinedCities()` and
`multiBuildings()`. Nothing about them is per-level, per-seed or per-preset. They are filled lazily
from worker threads, latched, and cleared by a `cleanPredefinedCache()` that three call sites have to
remember to call — one of them the preview, which clears maps shared with live worldgen.

They become one immutable `PredefinedIndex`, built by `AssetCompiler` alongside the twelve asset
indexes and carried as a snapshot component. `City`'s five accessors become lookups on
`context.assets().predefined()`, and:

- the `getWorld() != null` guards go, because there is no latch left to protect;
- `cleanPredefinedCache()` and its three call sites go, because a snapshot is not cleared;
- `CityPredefinedCacheLatchTest`'s proxy-based latch coverage is replaced by direct tests of the
  index, which needs no level, no registry access and no `IDimensionInfo` at all.

**Conflict resolution is deliberately unchanged.** Two predefined cities claiming one chunk still
resolve last-writer-wins in `AssetIndex.all()` order, because the index is built by the same walk in
the same order. That is a datapack authoring error either way, and picking a rule for it (first-wins,
or a load error naming both) is an asset-validation decision that belongs with #56, not a determinism
fix smuggled into an ownership move.
