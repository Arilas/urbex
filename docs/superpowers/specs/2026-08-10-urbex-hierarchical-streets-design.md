# Hierarchical street system — backport of upstream Lost Cities 1.20-7.5.0

Date: 2026-08-10
Status: approved, ready for planning
Supersedes: the legacy per-chunk street/park decision, `PARK_CHANCE`, the city-style `parkchance`
override, `StreetType.FULL` and `StreetType.randomNonPark()`

## 1. Context

Upstream Lost Cities released `1.20-7.5.0` on its `1.20` branch (single squashed commit
`58b4ca1b`, 2026-07-22). It is a substantial generation feature drop that **never reached the
1.21/NeoForge line**. Urbex forked from 9.4.2, which is feature-equivalent to `1.20-7.4.13`, so
all of 7.5.0 is new to us.

Two facts shape everything below:

- **The 1.20 branch shares no history with ours.** `git merge-base HEAD lostcities-upstream/1.20`
  is empty. Nothing can be cherry-picked. Every item is a reimplementation guided by upstream's
  source.
- **Upstream's planners are dependency-free.** `HierarchicalStreetPlanner`,
  `EffectiveStreetResolver` and `IntercityHighwayPlanner` import nothing but `java.util`. They are
  pure functions of seed, dimension id, settings and coordinates, with world access injected as a
  callback. That matches Urbex's reproducible-worldgen invariant unusually well.

### 1.1 Full decomposition of the backport

This spec covers **Spec 1 only**. The other two get their own spec → plan → build cycles.

| | Sub-project | Depends on |
|---|---|---|
| Spec 1 | **Hierarchical street system** — road field, assets, renderer, bridges, slopes, open lots, multibuilding conflict | nothing |
| Spec 2 | Inter-city highway network — `IntercityHighwayPlanner`, `ApproximateCityPotential`, hub persistence; replaces the legacy Perlin highway algorithm | nothing (independent of Spec 1) |
| Spec 3 | Structure avoidance — extraction from `CityGenerator`, atomic multibuilding footprints, two blacklist bug fixes | nothing |

### 1.2 Explicitly out of scope for the whole backport

| Upstream 7.5.0 item | Why dropped |
|---|---|
| `GenerationContext` + striped 3x3 locks | We solved generation concurrency independently and better: `ChunkGenContext` per invocation, concurrent `DimensionCaches`, per-dimension lock removed entirely. |
| Deterministic scattered-area generation | Already covered. `Scattered` caches the whole-area scan on the area anchor and reconstructs the area RNG by address, so no first-generated chunk decides for the others. |
| `LEGACY` modes, `StreetGenerationMode`, `HighwayGenerationMode`, `LostCityWorldGenData` | ~500 LOC existing solely so pre-7.5.0 worlds keep generating the old way. Urbex makes no such compatibility promise. See §2.1. |
| `CommandTestFill` | A scratch dev command built on `new Random()`, which our RNG policy forbids. |
| Forge/NeoForge platform churn, `playerdata/*`, `network/PacketHandler` | Loader noise. Fabric-only fork. |

### 1.3 Decisions taken before this spec

| Decision | Choice | Rationale |
|---|---|---|
| Legacy generation modes | **Hard replace** | The mode machinery exists only for old-world compatibility Urbex has explicitly not promised. Dropping it removes ~500 LOC and keeps one code path permanently. |
| P3 planner (`dev.krona.urbex.plan.*`) | **Park it, port behind a seam** | `feat/p3-road-system` stays unmerged and untouched. The port binds to a narrow `RoadField` interface so a terrain-aware planner can replace the grid later without touching the renderer. |
| Addressed randomness | **Lift P3's `Hash` + a purpose enum** | Upholds the single-addressing-mechanism invariant documented in `Rng.java`, makes upstream's 14 magic salts reviewable named constants, and rescues good work from the parked branch. Accepted cost: our layout is not bit-identical to upstream's for a given seed. |
| `BuildingInfo` integration | **Extract a content-decision class** | `BuildingInfo` is already 1,978 lines; upstream's equivalent change added 483 to theirs. Extraction shrinks it instead. |
| Validation bar | **Property tests + preview + digest**, sequenced playable-first | The planner's purity makes invariant tests cheap, and this is the discipline P3 lacked. But the bulk of the suite is written after in-game validation rather than before it — see §8.0. |
| `multiBuildingStreetConflict` control | **Ship as a first-class CYCLE setting** | `ControlKind.CYCLE` already exists end-to-end (registrar, rendering, lang-key convention, completeness assertion). Cost is one registrar line and three lang keys, not new GUI infrastructure. |

## 2. Goal

Replace Urbex's inherited per-chunk street/park coin-flip with a deterministic global road field of
primary, secondary and tertiary roads, rendered with a real wide-road asset family, planned bridges
over water, and sloped connections across one-level height differences.

### 2.1 What "hard replace" means concretely

The hierarchical grid is the only street implementation. There is no mode enum, no per-dimension
persisted mode, no `SavedData`, and no `LEGACY` branch anywhere. Consequences, stated plainly
because they are user-visible:

- Existing Urbex alpha worlds generate differently past the already-generated chunk border. This is
  permitted: `0.1.0` shipped with "worlds created with it are not guaranteed to generate
  identically on a later version".
- The legacy park nomination path (stage A "nominate a park", stage B "require a sufficiently open
  neighborhood") is deleted. Open lots replace it.
- `PARK_CHANCE` and the city-style `parkchance` override become dead and are removed. The
  city-style `parkchance` removal is a datapack-breaking change for third-party packs.
- `StreetType.FULL` and `StreetType.randomNonPark()` become unreachable — planned roads are always
  `NORMAL` — and are deleted along with `StreetTypeTest`. `StreetType.PARK` survives to render open
  lots.

## 3. Architecture

### 3.1 The seam

One interface, in a package with no Minecraft imports:

```java
public interface RoadField {
    RoadCell at(int chunkX, int chunkZ);
}
```

`RoadCell` carries the raw road class, its four connections, and the primary-block data upstream
retains for diagnostics:

```java
public record RoadCell(
        RoadType type,                    // NONE | PRIMARY | SECONDARY | TERTIARY
        boolean north, boolean south, boolean west, boolean east,
        int blockX, int blockZ,           // primary-block identity (active candidate indices)
        int westX, int northZ, int eastX, int southZ,   // primary-block bounds, chunk coords
        float density,
        List<Integer> secondaryX, List<Integer> secondaryZ,
        @Nullable TertiarySegment tertiary) {}
```

Generation consumes only `type` and the four connection flags. Everything from `blockX` onward is
diagnostic — it exists for `/urbex debug` and the preview, and no rendering decision may read it.
A future `RoadField` that has no notion of primary blocks supplies empty diagnostics.

That is the entire contract. The renderer, bridge planner, multibuilding conflict check, preview
and `/urbex debug` all bind to `RoadField`, never to the grid implementation. There is deliberately
**no registry and no mode enum**: hard replace means exactly one implementation is wired, and a
future terrain-aware planner replaces it by construction rather than by configuration.

> **Amended 2026-08-10, streets follow-up review (#106).** Two things above are now stale.
> `RoadCell.density` is `double` in the shipped record, not `float` as written above. And "that is
> the entire contract" undersold `RoadField`: the interface gained a second method since this
> section was written, `typeAt(int chunkX, int chunkZ)` — a default that returns
> `at(chunkX, chunkZ).type()` for the many callers that need only the road class, which
> implementations may override for a cheaper answer as long as it agrees with `at(...).type()`. The
> contract is `at` plus `typeAt`, not `at` alone.

### 3.2 Package layout

The pure module reuses `dev.krona.urbex.plan`, the root P3 established for dependency-free planning
code, with its stated rule that dependencies point *into* it and never out.

| Class | Origin | Notes |
|---|---|---|
| `plan.Hash` | lifted verbatim from `feat/p3-road-system` | splitmix64 mixing, extracted out of `Rng`, which then delegates to it |
| `plan.RoadField`, `plan.RoadCell`, `plan.RoadType`, `plan.TertiarySegment` | new | the seam |
| `plan.EffectiveRoad` | upstream `EffectiveStreetResolver` | pure four-input clipping rule |
| `plan.grid.GridRoadField` | upstream `HierarchicalStreetPlanner` (~350 LOC) | implements `RoadField` |
| `plan.grid.GridSettings` | upstream `StreetPlannerSettings` | validating record, see §6 |
| `plan.grid.GridPurpose` | upstream's 14 salt constants | named enum, in its own key band (see below) |

Extracting `Hash` out of `Rng` must be bit-for-bit behaviour-preserving, and `RngTest`'s existing
`GOLDEN` / `GOLDEN_LAST` vectors prove it: they pin `Rng.at(42L, 100, -100, …)` output, so any
drift in the mixing fails the build.

`GridPurpose` keys occupy their own band, distinct from `Rng.Purpose` (ordinals from 0) and from
the `PlanPurpose` band P3 defined at offset 1000, so a parked branch returning later cannot collide
with a shipped stream. Two logically independent decisions must never share an address and a key.

If P3 ever returns, it returns as a second `RoadField` implementation in that same package root.
That is what the seam is for.

### 3.3 Minecraft-coupled pieces

| Class | Role |
|---|---|
| `worldgen.lost.ChunkContentResolver` (new) | owns the precedence order; returns an immutable `ChunkContent` |
| `worldgen.lost.ChunkContent` (new) | record: road type, has-building, street type, open-lot flag, park part |
| `worldgen.lost.PrimaryBridgePlanner` | upstream `HierarchicalBridgePlanner`; needs biome + base-height sampling |
| `worldgen.CityGenerator` | large-part, connector and stair rendering |
| `worldgen.IDimensionInfo` | holds the per-dimension `RoadField` beside the existing caches |

### 3.4 The extracted content decision

`ChunkContentResolver` has a single entry point implementing upstream's precedence order as one
ordered, readable method:

1. hard exclusions and sphere/infrastructure constraints
2. predefined buildings and predefined multibuildings
3. predefined streets
4. accepted random multibuildings
5. effective planned road
6. ordinary single-building chance + the existing lonely-building veto
7. bounded non-road fallback → open lot

`BuildingInfo` consumes the result instead of computing it. Scope is strictly the content decision;
nothing else in `BuildingInfo` moves.

## 4. Data flow

One `GridRoadField` is built per dimension from (world seed, dimension id, validated
`GridSettings`) and held on `IDimensionInfo`. It is immutable and pure, so it needs no cache and no
lock — matching upstream, which notes its planner uses none.

Per chunk:

1. `raw = roadField.at(cx, cz)` — pure, reads no world state.
2. `effective = EffectiveRoad.resolve(raw, isCityRaw(here), anyConnectedNeighbourIsCityRaw, overriddenByHigherPrecedence)`.
   The neighbour test removes isolated one-chunk stubs at city protrusions and consults only
   lower-level raw city membership, so height, biome, rarity, sphere, void and city-factor clipping
   are all preserved without depending on final building decisions. At its sole call site
   (`BuildingInfo.effectiveRoadType`), `overriddenByHigherPrecedence` is hardcoded `false` — a
   multi-building's precedence over a road never reaches `EffectiveRoad` this way. That precedence
   is enforced downstream instead, by `ChunkContentResolver`'s `!hasBuilding` check on the chunk.
3. `ChunkContentResolver` walks the precedence order and returns `ChunkContent`.
4. `BuildingInfo` consumes it. `MultiChunk.canPlaceBuilding()` queries the **raw** field.

### 4.1 Cycle-freedom invariant

The planner reads nothing. `EffectiveRoad` reads only `isCityRaw`, which does not depend on
buildings. `MultiChunk` reads raw only. Therefore querying roads first or multibuildings first
cannot change either answer. This is pinned by a test, not just documented.

## 5. Rendering

**Part family by road class.** `PRIMARY` → `largeparts`, `SECONDARY` → `parts`, `TERTIARY` →
`tertiaryparts`. The tertiary fallback to `parts` happens in the city-style getter, not the codec —
upstream's `CityStyle.getTertiaryStreetParts()` returns `tertiaryStreetParts == StreetParts.DEFAULT
? streetParts : tertiaryStreetParts`. (Upstream's changelog describes this as a default; the code
achieves it at the getter. Port from the code.)

**Topology.** Selected from final neighbour classifications. For primaries, **only neighbouring
primaries participate**: a touching secondary or tertiary must not turn a primary end or straight
into a bend or junction, so the quartz centre marking never aims down a minor street.

**Connectors.** After the main part is placed, the rotated `connector` part overlays every edge with
a connected minor road, filling only the centred 8 blocks of the primary's otherwise untouched
outer row. Both widths are centred in the chunk, so 8-wide minor streets meet 14-wide primaries
without a gap.

**Slopes.** A lower minor-road chunk becomes a full-chunk `stair` part only when it has exactly one
minor road one level higher, same-level minor roads continuing directly behind and beyond the
transition, and no same-level side branches at either end. This keeps bends and intersections flat.
The upper road includes the slope in its topology so it continues to the chunk edge; its retaining
wall opens only across the stair part's `z1`/`z2` bounds. Fountains, park parts, random vegetation,
building-front overlays and the older narrow stair decoration are all suppressed on a sloped chunk.
Primary roads never slope.

**Open lots.** A non-road city chunk that fails its building roll or a later veto becomes a grass
open lot rendered through the existing park surface. `openLotParkChance` (0.8) decides whether a
weighted park part is placed in it. It never turns the lot into a road.

**Bridges.** A non-city chunk on a raw primary line is a candidate when water-like (a water biome,
or a deterministic base height below sea level, which catches inland lakes whose biome is still
plains). `PrimaryBridgePlanner` scans both directions along that same primary line up to
`plannedPrimaryBridgeMaxLength` and accepts the span only when every intervening chunk is non-city,
water-like and on the raw primary line; both ends are effective primary-road city chunks at city
level zero; and one deterministic roll for the whole span passes. The roll hashes seed, dimension,
orientation, both endpoints and a dedicated bridge purpose, so every chunk in a span reconstructs
the same answer with no shared state. At a crossing of horizontal and vertical spans a
seed/dimension-stable orientation wins, preventing two bridges from claiming one chunk. Rendering
reuses the existing bridge renderer but selects from the city style's optional `largebridges`.
Higher-level bridges need ramp assets and are out of scope.

## 6. Assets and schema

### 6.1 New datapack assets

Ten part JSONs — `street_large_{straight,bend,t,all,end,none,full,connector}`, `street_stair`,
`bridge_large_open` — plus the `street_large` palette. Vanilla blocks only
(`minecraft:smooth_stone_slab[type=double]`, `minecraft:smooth_quartz`), so no 26.2 block-name
work. The built-in large pieces form a 14-block-wide full-height smooth-stone-slab surface that
retains the normal outermost block on each side, with two centred rows of smooth quartz.
`bridge_large_open` carries the same deck and marking.

All assets are written `urbex:`-namespaced with wiring declared explicitly in
`worldstyles/standard` and `citystyles/citystyle_common`, per the convention established in
`5fdab7cf`. `DatapackReferenceIntegrityTest` enforces this automatically.

### 6.2 Schema additions

| Type | Field | Default |
|---|---|---|
| `StreetParts` | `connector` | `urbex:street_large_connector` |
| `StreetParts` | `stair` | `urbex:street_stair` |
| `StreetSettings` | `largeparts` | absent → narrow `parts` used for primaries |
| `StreetSettings` | `tertiaryparts` | absent → falls back to `parts` at the getter |
| `Selectors` | `largebridges` | absent → ordinary bridge part |

### 6.3 Profile settings

Seventeen new fields (sixteen numeric, one enum); one removed. Ranges are upstream's.

| Setting | Field | Default | Range |
|---|---|---:|---|
| `primaryRoadSpacingX` | `PRIMARY_ROAD_SPACING_X` | 8 | 8–128 |
| `primaryRoadSpacingZ` | `PRIMARY_ROAD_SPACING_Z` | 8 | 8–128 |
| `primaryRoadOptionalChance` | `PRIMARY_ROAD_OPTIONAL_CHANCE` | 0.45 | 0–1 |
| `primaryRoadForceEvery` | `PRIMARY_ROAD_FORCE_EVERY` | 4 | 1–16 |
| `secondaryRoadMinCountX` | `SECONDARY_ROAD_MIN_COUNT_X` | 0 | 0–128 |
| `secondaryRoadMaxCountX` | `SECONDARY_ROAD_MAX_COUNT_X` | 2 | 0–128 |
| `secondaryRoadMinCountZ` | `SECONDARY_ROAD_MIN_COUNT_Z` | 0 | 0–128 |
| `secondaryRoadMaxCountZ` | `SECONDARY_ROAD_MAX_COUNT_Z` | 2 | 0–128 |
| `minimumRoadSeparation` | `MINIMUM_ROAD_SEPARATION` | 4 | 2–32 |
| `minimumRoadEdgeDistance` | `MINIMUM_ROAD_EDGE_DISTANCE` | 3 | 2–32 |
| `tertiaryRoadChance` | `TERTIARY_ROAD_CHANCE` | 0.40 | 0–1 |
| `tertiaryRoadMinLength` | `TERTIARY_ROAD_MIN_LENGTH` | 2 | 1–32 |
| `tertiaryRoadMaxLength` | `TERTIARY_ROAD_MAX_LENGTH` | 5 | 1–32 |
| `plannedPrimaryBridgeChance` | `PLANNED_PRIMARY_BRIDGE_CHANCE` | 1.0 | 0–1 |
| `plannedPrimaryBridgeMaxLength` | `PLANNED_PRIMARY_BRIDGE_MAX_LENGTH` | 12 | 1–64 |
| `openLotParkChance` | `OPEN_LOT_PARK_CHANCE` | 0.8 | 0–1 |
| `multiBuildingStreetConflict` | `MULTI_BUILDING_STREET_CONFLICT` | `OVERRIDE_MINOR` | enum |

Removed: `parkChance` / `PARK_CHANCE`, and the city-style `parkchance` override in `ParkSettings`.

**GUI placement.** The sixteen road settings and `MULTI_BUILDING_STREET_CONFLICT` go under a new
`SettingCategory.ROADS`, in sub-sections `roads_primary`, `roads_secondary`, `roads_tertiary` and
`roads_bridges`, each needing `urbex.section.roads.<id>` and `.desc` lang keys.
`OPEN_LOT_PARK_CHANCE` takes `PARK_CHANCE`'s place under `BUILDINGS`.
`MULTI_BUILDING_STREET_CONFLICT` registers via the existing `ControlKind.CYCLE` path with
`urbex.enum.multibuildingstreetconflict.<value>` keys. `SettingsCompletenessTest` enforces that
every new public profile field has a descriptor and lang keys.

> **Amended 2026-08-10, after Task 6.** This paragraph originally placed the road settings under
> `SettingCategory.TRANSPORT`. That was written before the `ROADS` preview mode existed. Since each
> editor category drives both a tab and the preview shown beside it, leaving the settings on
> `TRANSPORT` would have produced an empty `ROADS` tab whose only content was a picture of settings
> living on another tab. `TRANSPORT` keeps highways, railways and monorails — the things its own
> overlay draws — and `ROADS` owns the street grid it previews.

### 6.4 Multibuilding conflict policy

`MultiChunk.canPlaceBuilding()` queries the raw road field before accepting a random candidate:

- `BLOCK_ALL` — primary, secondary and tertiary intersections all reject the candidate
- `OVERRIDE_MINOR` (default) — only primary intersections reject; accepted complexes suppress
  secondary and tertiary roads under their footprint
- `OVERRIDE_ALL` — no road intersection rejects; every covered road is suppressed after acceptance

Predefined multibuildings bypass the policy and always override automatic roads.

## 7. Failure handling

**Settings validation at profile load.** `GridSettings`'s compact constructor rejects spacing below
8, `forceEvery` outside 1–16, chances outside [0,1], inverted secondary min/max, separation or edge
distance below 2, and inverted tertiary lengths. Errors name the offending field, following the
existing idiom where an unknown `selectedProfile` fails server start with the valid names listed.
An invalid `multiBuildingStreetConflict` name is a profile error listing the valid values.

**Asset gaps degrade, never crash.** A city style without `largeparts` renders primaries with
narrow parts. Without `largebridges` it falls back to its ordinary bridge part. An empty
`connector` list disables connector overlays for that style — upstream supports this deliberately
and so do we.

## 8. Testing

### 8.0 Test sequencing: playable first

The full suite below is the destination, not the starting point. Generation design changes once
you see it in a real world, and tests written against a design that then changes get rewritten.
So the tests split in two:

**Written before the first playable build** — only the invariants whose failure would waste
in-game time by producing garbage that is hard to diagnose from screenshots:

- determinism: same (seed, dimension id, settings) reproduces the field exactly
- order independence: row-major, reversed and shuffled queries agree
- primary continuity: an active vertical corridor is `PRIMARY` for every z (and symmetrically)
- no seam at coordinate zero

**Written after in-game validation** — everything else in §8.1 and §8.2. These pin details that
tuning may legitimately move, so writing them earlier risks pinning the wrong thing.

This reorders test effort; it does not reduce it. The full suite is a required chunk of the
implementation plan, gated on in-game feedback rather than dropped.

### 8.1 Property tests on `GridRoadField`

Pure Java, no Minecraft, milliseconds each. Invariants are taken from upstream's documented
guarantees:

- consecutive active primary gaps are a multiple of the candidate spacing and never exceed
  `spacing × forceEvery`; every `forceEvery`th candidate is active
- a vertical corridor's activation depends only on its X candidate index, so an active primary is
  straight and continuous for all z (and symmetrically for horizontal corridors)
- no seam at coordinate zero: structurally identical results across symmetric negative and positive
  ranges, proving `floorDiv`/`floorMod` rather than truncating division
- every secondary spans its whole primary block and touches both bounding primaries
- accepted secondaries respect `minimumRoadSeparation` and `minimumRoadEdgeDistance`; requesting
  more roads than physically fit yields fewer rather than throwing
- tertiary segments are contiguous, originate on an existing road at least two chunks from its
  intersections, and always leave at least one non-road chunk before the opposite road
- a cell whose chosen side cannot fit falls through to the other sides in deterministic order
  rather than silently losing its access road
- **order independence**: querying a window row-major, reversed and shuffled yields identical
  output — the same property `DigestCheck` exercises with its `rowmajor`/`shuffled` orders
- same (seed, dimension id, settings) reproduces the field exactly; changing the seed changes it,
  and changing the dimension id changes it

### 8.2 Other tests

| Target | Test |
|---|---|
| `EffectiveRoad` | four-input truth table |
| `ChunkContentResolver` | precedence-order tests against fakes, including the cycle-freedom invariant of §4.1 |
| `PrimaryBridgePlanner` | span acceptance rules; crossing case where both chunks must independently agree which orientation wins |
| `GridSettings` | every validation branch produces a clear, field-naming error |
| Road preview mode | colour-mapping test alongside the existing `PreviewModeMappingTest` / `CityPreviewKeyTest` |
| New datapack assets | covered automatically by `DatapackReferenceIntegrityTest` |
| New profile settings | covered automatically by `SettingsCompletenessTest` |

### 8.3 Digest

`digest.golden` is regenerated exactly once, in phase D. Phases A, B, C and E must leave it
byte-identical.

## 9. Sequencing

Ordered to reach a playable build as early as possible, per §8.0.

| Phase | Work | Digest |
|---|---|---|
| A | Assets, schema (`StreetParts`, `StreetSettings`, `Selectors`, `CityStyle`), `MultiBuildingStreetConflict` enum + CYCLE registration | unchanged |
| B | Extract `ChunkContentResolver` from `BuildingInfo` as a behaviour-preserving refactor | **unchanged** |
| C | Extract `plan.Hash` out of `Rng` (delegating, `RngTest` golden vectors unchanged); seam types, `GridRoadField`, `GridSettings`, `GridPurpose`, `EffectiveRoad` + the four smoke invariants of §8.0 | unchanged |
| D | Wire roads: profile settings + GUI, road field into the content decision, part-family selection and connectors, open lots replacing the legacy park path, multibuilding conflict; delete `PARK_CHANCE`, `parkchance` and `StreetType.FULL`. **First playable build.** | changes once |
| E | Planned bridges and sloped minor roads. Second in-game look. | changes |
| F | Road preview mode, `/urbex debug` street diagnostics, containing-multibuilding name | unchanged |
| G | Full property suite of §8.1 and §8.2, gated on in-game feedback from D and E | unchanged |

Phase B comes before D deliberately: D inserts "effective planned road" into the precedence order,
so extracting that order first means the insertion happens once rather than twice. B is also the
one phase that touches working code without adding behaviour, and precisely the phase where an
unchanged digest proves nothing moved.

Phase E is separated from D so the first playable build arrives without waiting on bridge and slope
geometry, which are the fiddliest rendering rules in the spec and the most likely to need tuning
against what the world actually looks like.

## 10. Known limitations accepted

- **The grid is not terrain-aware.** Outside planned bridge spans, roads run over hills and lakes.
  Upstream lists this as a V1 limitation. The `RoadField` seam is the way back to a terrain-aware
  planner — that is what P3 was attempting.
- **Layout is not bit-identical to upstream's** for a given seed, because we express the salts
  through `plan.Hash` rather than upstream's constants. Structure and statistics match; exact chunk
  positions do not. We therefore cannot validate by diffing against an upstream world.
- **Density is stable block randomness only.** It does not sample buildings, terrain or generated
  chunks, matching upstream V1.
- **Higher-level bridges are unsupported** — they need ramp assets.
- **Upstream's doc and code disagree in places.** Port from the source and treat the doc as intent;
  §5 records the one instance found so far.
