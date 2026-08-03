# Urbex — P0 (repo & rename) + P1 (generation context) design

Date: 2026-08-02
Status: approved, ready for planning
Supersedes: nothing. First spec of the fork.

## 1. Context

`LostCities` by McJty (MIT) has been ported from NeoForge/MC 1.21.11 to Fabric/MC 26.2 on the
`fabric/26.2` branch. The port works: worlds generate, the config GUI renders, the dedicated
server boots clean. See `PORTING-NOTES.md` for what the port touched.

Investigation of the ported codebase (188 Java files, ~23k LOC, ~280 JSON assets) found that the
mod's limits are structural rather than incidental. The fork exists to change the structure, which
means a new mod rather than a maintained branch.

**Urbex** is that mod: a hard fork, Fabric-only, breaking changes permitted, MIT lineage preserved.

### 1.1 What the fork is about

Four tracks, chosen deliberately:

1. **Correctness & performance** — the mod's output is not reproducible from the world seed, and
   generation is serialized behind a per-dimension lock.
2. **Generator rewrite** — cities are radial blobs of chunk-aligned 16x16 buildings with no road
   network and no districts. That is the ceiling, and it is a design ceiling.
3. **Data-driven platform** — profiles are code-defined and stored in global config; the asset
   format cannot express a building that is not exactly one chunk.
4. **Ecosystem & compat** — a real API, real structure integration, cooperation with other
   worldgen mods.

**Deliberately out of scope:** content and atmosphere. New block palettes, landmark buildings,
themed loot, narrative decay and biome-native city styles are not what this fork is for. Others
can build that on the platform.

### 1.2 Decisions taken before this spec

| Decision | Choice | Rationale |
|---|---|---|
| Asset format | New format v2 + legacy importer | The 16x16 char-slice format cannot express arbitrary footprints. An importer keeps the 186 existing parts usable without shackling the new generator. |
| Loaders | Fabric only, no platform abstraction | The hard problem is the generator, not the loader. Abstraction is cost paid against a port that may never happen. |
| World integration | Lots as bounded vanilla `Structure`s, city plan as a pure seed-derived function | Inherits `TerrainAdjustment` (the cut-and-fill the mod hand-rolls today), `/locate`, explorer maps, structure-reference negotiation and correct heightmaps. The engine caps how far one structure reaches, so a city cannot be one structure — lots can. |
| First spec | P0 + P1 | Prerequisite for everything, and independently shippable. |
| Name | Urbex | `urbex` slug is free on Modrinth (search returns 0 hits, slug 404s). CurseForge unverified — check manually before publishing. |

### 1.3 Full decomposition

This spec covers P0 and P1 only. The rest get their own spec → plan → build cycles.

| | Sub-project | Depends on |
|---|---|---|
| **P0** | Repo, name, rename, license, CI, labels, docs | — |
| **P1** | Generation context: per-invocation state, seed-derived RNG, lock removal, god-class split | P0 |
| **P2** | City plan: seed → districts → road graph → lot subdivision, pure logic + debug renderer | P0 |
| **P3** | Asset model v2 (footprints, floor heights, NBT templates) + legacy importer | P0 |
| **P4** | Structure-based placement: lots as `Structure`s, terrain adaptation, `/locate` | P1, P2, P3 |
| **P5** | Profiles as world-stored datapack registries; drop Forge Config API Port | P1 |
| **P6** | Public API + ecosystem hooks + docs | P4 |

P2 is worth prototyping early regardless of order: it has zero Minecraft coupling, so it can be
iterated against a PNG renderer with unit tests, and it answers the question the whole fork rests
on — whether lot-based cities actually look better than chunk-grid cities.

## 2. Findings this spec addresses

Verified against the ported source. Line numbers are as of commit `0bffd84`.

### Fixed in P1

- **A1 — output is not reproducible from the seed.** `LostCityTerrainFeature.rand` is one
  `RandomSource` shared across every chunk in a dimension. It is reseeded at
  `LostCityTerrainFeature.java:343`, *after* `doCityChunk()` already ran at line 301. So
  `generateRuins()` (lines 1227, 1230) and `getRandomPart()` (line 577) consume whatever state the
  previous chunk left behind. Same seed plus a different exploration order yields a different world.
- **A2 — a global LCG drives world content.** `fastrand128()` (line 245) mutates a
  `private static int gSeed` that is never seeded from the world seed. Every rubble and leaf block
  in every world in the JVM comes off it.
- **A3 — unsynchronized static caches.** `Highway.X_HIGHWAY_LEVEL_CACHE` / `Z_...`
  (`Highway.java:16-17`) are plain `HashMap`s, unlike `Railway.RAIL_INFO` which is synchronized.
- **A4 — standard profiles cannot be edited.** `ProfileSetup.setupProfiles()`
  (`ProfileSetup.java:445`) writes all standard profiles to `config/lostcities/profiles/*.json` on
  every launch and *then* reads the directory, so user edits are overwritten before being read.
- **A5 — world height is still 0..255.** `isVoid()` starts at y=255 (line 251); `fixAfterExplosion`
  clears to a literal `256` with its own `// @todo hardcoded height` (line 1091); `DamageArea`
  builds its AABB as `0..256` (`DamageArea.java:38`, with acknowledgements at lines 165 and 176);
  `BuildingInfo.java:1759` and `:1813` compare against `256`. The entire -64..320 range is invisible.
- **A7 — city placement ignores the world seed.** `City.isCityCenter()` seeds from coordinates
  alone (`City.java:157`), as do `getCityRadius()` (`:185`) and `getCityStyleForCityCenter()`
  (`:213`) — unlike `getCityStyleInt()` at `:226`, which does mix in `provider.getSeed()`.
  `MultiChunk.java:72` has the same shape. Every world on a given profile therefore places its
  cities in identical chunks at identical radii. Found during the P1a RNG audit, after §1.2 was
  settled.
- **B2 — one shared mutable `ChunkDriver`, `rand` and `street` per dimension**, which is why
  generation is serialized (`LostCityFeature.place`, `LostCitySphereFeature.place`,
  `StructureSuppressor.suppressedByCity`).
- **B4 — two god classes**, `LostCityTerrainFeature` (2392 lines) and `BuildingInfo` (2000 lines).
- **Pre-existing NPE**: an invalid `selectedProfile` crashes world init at
  `Config.getProfileForDimension`.

### Deferred, recorded here so they are not rediscovered

- **A6 — worldgen settings live in global config, not the world** (`config/lostcities-server.toml`).
  Two singleplayer worlds share one setting and a world is not self-describing. → P5.
- **B1 — cities are a `Feature`, not a `Structure`.** Root cause of the structure conflicts, the
  `StructureSuppressor` workaround, the `avoidStructures` options and cities being invisible to
  `/locate`. → P4.
- **B3 — `LostCityProfile` is 659 lines of public mutable fields**, code-defined, and the only part
  of the mod that is not a datapack registry. → P5.
- **C1 — a building is exactly one chunk.** All 186 parts are `xsize:16, zsize:16`; multibuildings
  are N×M *chunks*. No lots, no setbacks, no alleys. → P3/P4.
- **C2 — there is no road network.** A street is "a city chunk with no building"
  (`LostCityTerrainFeature.java:993-997`); street type is re-rolled from a positional `Random` at
  line 1290. → P2.
- **C3 — a city is a radial blob with one style.** `City.getCityFactor()` sums distance falloff from
  random centers; building selection is a flat weighted list. → P2.
- **C4 — terrain adaptation is 8 discrete steps** (`CITY_LEVEL0_HEIGHT`..`CITY_LEVEL7_HEIGHT`), then
  the chunk is flattened. Hence cities hanging in air and the `fillSupportBelow` workaround. → P4,
  via vanilla `TerrainAdjustment`.
- **C5 — `FLOORHEIGHT = 6` is hardcoded** (line 62, with `// We currently only support 6 here` at
  line 2225). → P3.
- **C6 — decay is per-chunk noise, not a history.** → out of scope (content track).
- **C7 — palettes are pre-1.13-era blocks.** → out of scope (content track).

## 3. P0 — repo, name, rename

### 3.1 Repository

New repository seeded from `fabric/26.2` **with full git history**, not a squashed import. Blame
stays useful, McJty's authorship stays visible, and the port commits keep their context.

`LICENSE` keeps the original MIT notice verbatim and adds the fork's copyright line beneath it. MIT
requires the former. `README.md` states the lineage in its first paragraph.

Upstream `changelog.txt` is preserved as `docs/history/CHANGELOG-lostcities.txt`; a fresh
`CHANGELOG.md` starts at the fork. `PORTING-NOTES.md` and `PORTING-PLAN.md` move to `docs/history/`.

### 3.2 Rename

| From | To |
|---|---|
| package `mcjty.lostcities` | `dev.krona.urbex` |
| mod id `lostcities` | `urbex` |
| data/asset namespace `lostcities:` | `urbex:` |
| `META-INF/lostcities.accesswidener` | `META-INF/urbex.accesswidener` |
| `lostcities.mixins.json` | `urbex.mixins.json` |
| command root `/lostcities` | `/urbex` |
| dimension `lostcities:lostcity` | `urbex:city` |
| saved data `lostcities:lostcities_data`, `lostcities:lostcity_editdata` | `urbex:data`, `urbex:editdata` |
| config dir `config/lostcities/` | `config/urbex/` |
| version `26.2-9.4.2-fabric` | `26.2-0.1.0` |

`gradle.properties` drops `projectId`, `projectSlug`, `modrinthId` and repoints `github_project`.

The old `lostcities:` namespace stays meaningful in exactly one place: the P3 legacy importer reads
it. Nothing else should refer to it.

This is mechanical but wide (188 Java files, ~280 data files). It happens first because doing it
later means rebasing it across every subsequent change.

### 3.3 Deletions

- `src/api/java/ivorius` — vestigial `reccomplex` stub, already uncompiled.
- The `mcjty.lostcities.api` package, **removed rather than renamed**. Its five `LostCityEvents`
  hooks are a NeoForge-event-bus shape wearing a Fabric coat, nothing on Fabric consumes them
  today, and P6 designs the real API against the new generator's shapes. The `LostCityEvents.post`
  call sites in `LostCityTerrainFeature` and `BuildingInfo` are deleted with it; internal extension
  points become plain method calls until P6.

### 3.4 Infrastructure

- GitHub Actions: build on push and PR, upload the jar as an artifact.
- Label taxonomy (§6) created.
- The findings in §2 filed as labelled issues, so the backlog exists before code moves.
- `docs/` skeleton.

### 3.5 P0 acceptance

`./gradlew build` green; the jar contains the renamed `fabric.mod.json`, accesswidener, mixin
config and data; a dedicated server boots and generates a world with cities present. P0 predates
the digest harness, so its acceptance is build-green plus a manual worldgen smoke test.

## 4. P1 — the generation context

### 4.1 The core move

A1, A2, A3 and B2 are one problem wearing four hats: `LostCityTerrainFeature` is a per-dimension
singleton that holds the `ChunkDriver`, the `RandomSource`, the `street` char and four noise buffers
as mutable fields, then mutates them per chunk.

Replace with a `ChunkGenContext` constructed per generation call:

```java
final class ChunkGenContext {
    final ChunkDriver driver;
    final WorldGenRegion region;
    final ChunkAccess chunk;
    final ChunkCoord coord;
    final DimensionInfo dim;
    final Profile profile;
    final BuildingInfo info;
    final CompiledPalette palette;
    final char street;
    final NoiseBuffers buffers;

    RandomSource rng(Purpose purpose);   // dim.seed + coord + purpose
}
```

Constructed by the orchestrator's `generate()`, passed explicitly down the call chain, never stored
on a field. With no shared mutable state the `synchronized` blocks in `LostCityFeature`,
`LostCitySphereFeature` and `StructureSuppressor` all delete, and parallel worldgen returns.

### 4.2 RNG becomes addressed, not sequential

One factory:

```java
enum Purpose { RUINS, RUBBLE, LEAVES, DEBRIS, PARTS, STUFF, SPAWNERS, LOOT,
               VEGETATION, DAMAGE, BUILDING, STREET, MULTI }

RandomSource Rng.at(long worldSeed, int chunkX, int chunkZ, Purpose purpose);
```

The enum above is the set implied by today's call sites; implementation derives the exhaustive list
by replacing every existing random consumer, and a consumer without a purpose is a review failure.

Coordinates and purpose are mixed through a splitmix64-style hash into an
`XoroshiroRandomSource`. Each purpose gets an independent stream at the same coordinates, so adding
a consumer never perturbs an existing one — a property that matters as much for P2's plan layer as
it does here.

`fastrand128()` and its static `gSeed` are deleted. The shared `rand` field is deleted. The
existing positional randoms (`BuildingInfo.getBuildingRandom`, `VEGETATION_RAND`,
`RANDOMIZED_OFFSET*`, the ad-hoc `new Random(chunkZ * ... + chunkX * ...)` in `City` and
`LostCityTerrainFeature:1290`) are unified under the same factory, so there is exactly one
mechanism.

Unifying them changes output. That is intended and happens once — see §5.

### 4.3 Lazy shared state must be audited

Removing the lock exposes latent races in code this refactor does not otherwise touch. Every
lazily-initialized field reachable from generation must be either moved onto the context, made an
eagerly-computed immutable static, or made safely publishable:

- `LostCityTerrainFeature.randomLeafs`, `randomDirt`, `randomDirtSet`, `railStates`,
  `statesNeedingTodo`, `statesNeedingLightingUpdate`, `statesNeedingPoiUpdate`, `base`, `liquid`,
  `typeCache` — all lazily built instance fields on the shared feature.
- `BuildingPart.vslices` — lazily built and cached on a shared, registry-held asset
  (`BuildingPart.java:108-130`). This is a real data race once generation is parallel.
- `CompiledPalette`'s internal caches.

This audit is a required deliverable of P1, not a follow-up.

### 4.4 Cache ownership

The static caches move onto the per-dimension info object and become concurrent:

`BuildingInfo.BUILDING_INFO_MAP`, `CITY_INFO_MAP`, `CITY_LEVEL_CACHE`; `City.CITY_STYLE_CACHE`,
`CITY_RARITY_MAP`, the predefined-city/building/street maps and the two occupied-chunk maps;
`Railway.RAIL_INFO`; `Highway.X_HIGHWAY_LEVEL_CACHE` and `Z_HIGHWAY_LEVEL_CACHE`; the heightmap
cache. `cleanCache()` becomes a dimension lifecycle event instead of a call someone must remember.

**Reentrancy hazard, and it is not theoretical.** These caches are mutually recursive: building
`BuildingInfo` for a chunk reads its neighbours' characteristics, which read their city styles.
`ConcurrentHashMap.computeIfAbsent` must therefore never be used here — recursive population
deadlocks even for distinct keys that hash to the same bin. `City.getCityStyle`
(`City.java:219`) uses `computeIfAbsent` today and must be converted. The safe shape everywhere is
get → compute outside the map → `putIfAbsent`, which `BuildingInfo.getChunkCharacteristics`
already happens to use. Recomputing on a race is harmless because the computation is now a pure
function of the seed.

### 4.5 God-class split

`LostCityTerrainFeature` (2392) becomes a thin orchestrator plus:

| Class | Responsibility |
|---|---|
| `TerrainShaper` | bedrock, water fill, city surface leveling, void detection, clearing, fill-to-ground, support fill |
| `BuildingGenerator` | `generateBuilding`, `makeRoomForBuilding`, floors, cellars, doors, part2 |
| `StreetGenerator` | streets, borders, parks, front parts, street decorations |
| `DecayPass` | explosions, `fixAfterExplosion`, ruins, rubble, debris |
| `PartPlacer` | `generatePart`, palette application, spawner and loot handling |

`BuildingInfo` (2000) becomes:

| Class | Responsibility |
|---|---|
| `ChunkCharacteristicsResolver` | city-ness, multibuilding sectioning, city level, city style |
| `BuildingLayout` | floors, cellars, part selection, connections, ruin height |
| `ChunkNeighborhood` | the xmin/xmax/zmin/zmax accessors |
| `InfoCache` | the caching layer, owned by `DimensionInfo` |

### 4.6 Height fixes (A5)

Every hardcoded `255`/`256` becomes `level.getMinY()` / `level.getMaxY()`:
`LostCityTerrainFeature:251`, `:1091`; `DamageArea:38`, `:165`, `:176`; `BuildingInfo:1759`, `:1813`.

### 4.7 Profile handling (A4 stopgap)

`setupProfiles()` writes the standard set to `config/urbex/profiles/defaults/` — regenerated every
launch, documented as read-only reference — and creates `config/urbex/profiles/<name>.json` only
when absent. User files are never overwritten. The real fix, world-stored profile registries, is P5.

### 4.8 Error handling

`place()` keeps its catch-log-continue: one bad chunk must not kill a world. The context makes it
cheap to enrich the report with chunk coordinates, profile and city style, so `ErrorLogger` gains
that.

Separately, an invalid `selectedProfile` becomes a clear failure at server start listing the valid
profile names, rather than an NPE during world init.

### 4.9 Not in P1

No new asset format, no structures, no city plan, no visual redesign.

## 5. Testing & acceptance

**Ordering matters more than the tests.** P1 changes output once, deliberately (the RNG fix), then
must never change it again (the refactor). Splitting the god classes before fixing determinism
would mean refactoring against a target that shifts underneath, with no way to tell a refactoring
bug from RNG noise.

1. Fix determinism — `Rng`, `ChunkGenContext`, buffers, cache ownership, lazy-state audit. Output
   moves once.
2. Snapshot digests for a handful of seeds.
3. Split the god classes incrementally, asserting the digest does not move after each extraction.

### 5.1 The harness

> **Amended 2026-08-03 during P1a execution.** As first specified, the harness hashed *whole
> chunks*. That was wrong: a whole-chunk hash includes vanilla terrain, and vanilla terrain is not
> run-stable under forced concurrent generation. Proven by control — the plain overworld, with no
> Urbex profile and this mod generating nothing, gave two different digests for the same seed in
> the same order (`5750acb1ce8e44a4` vs `1b9a1c68bdac96d6`). The acceptance signal is instead a
> digest that `ChunkDriver` accumulates over exactly the positions and states **Urbex wrote**:
> final-state semantics, canonically sorted, off by default. The whole-chunk hash survives as a
> loose tripwire only.
>
> Two consequences for anyone reading this later. Digests must be taken as
> `execute in urbex:city run urbex digest ...` — the overworld has no Urbex profile by default, so
> a bare invocation measures vanilla. And exact equality across all three chunk orders is not
> reachable in P1a: ~11 positions still vary because Urbex reads neighbouring vanilla state during
> the decoration step. That is precisely what §4's structure-based placement (P4) fixes, and it is
> tracked as `Arilas/urbex#18`.

A `/urbex digest <radius> <order> <offset>` command force-generates a square of chunks and emits a
stable hash. Three assertions on it are the acceptance criteria for P1:

- **Order independence** — same seed, chunks walked row-major versus shuffled, identical digest.
  This is the test for A1 and A2, and the one that would have caught them.
- **Concurrency** — same seed, parallel workers, lock removed: identical digest, no exceptions.
  This is what licenses deleting the `synchronized` blocks.
- **Refactor invariance** — digest unchanged across each class extraction.

Automating this in CI means booting a headless dedicated server twice and diffing digests. That is a
real gradle task and worth having, but the command is the required deliverable and the CI job is a
stretch goal — the command alone gives the signal in about two minutes of manual work.

### 5.2 Unit tests (no Minecraft)

The `Rng` factory: same inputs produce the same stream; different `Purpose` values produce
independent streams; results are stable across JVM runs. Small, fast, and the primitive P2 builds on.

### 5.3 Performance

Measured, not gated. `Statistics` already tracks generation time. Record throughput before and
after; expect parity single-threaded and a real gain multi-threaded. If lock removal does not
speed anything up that is worth knowing, but it is not a reason to keep shared mutable state.

### 5.4 Explicit non-goal

Matching current output. Today's output is not self-consistent, so there is nothing to match.

## 6. Label taxonomy

Three axes; a typical issue carries one from each of the first two.

| Axis | Labels |
|---|---|
| **Type** | `bug` · `perf` · `refactor` · `feature` · `content` · `docs` |
| **Area** | `area:terrain` · `area:city-plan` · `area:buildings` · `area:assets` · `area:profiles` · `area:api` · `area:gui` · `area:commands` · `area:compat` |
| **Flags** | `breaking` · `determinism` · `upstream-fix` · `epic` · `needs-design` · `needs-screenshots` · `good-first-issue` |

`upstream-fix` marks work worth offering back to McJty's LostCities — the A-series correctness
fixes largely qualify, since they are present in the NeoForge original too.

## 7. Build sequence

Three chunks of work, deliberately large.

1. **P0 — repo and rename.** New repo with preserved history, full rename, deletions, CI, labels,
   issues, docs skeleton. Acceptance: build green, server generates a world with cities.
2. **P1a — determinism and concurrency.** `Rng` factory, `ChunkGenContext`, noise buffers, cache
   ownership with the reentrancy-safe shape, lazy-shared-state audit, lock removal, height fixes,
   profile stopgap, startup validation, `/urbex digest`. Acceptance: order-independence and
   concurrency assertions pass; `Rng` unit tests pass.
3. **P1b — decomposition.** Split both god classes. Acceptance: digest invariant across every
   extraction; build green.

## 8. Risks

| Risk | Mitigation |
|---|---|
| The rename touches ~470 files and lands before any digest harness exists. | Script it, then verify with build-green plus a manual worldgen smoke test. Do it first, when there is least to rebase across. |
| Removing the lock exposes races in untouched code. | §4.3 makes the lazy-shared-state audit a required deliverable, and the concurrency digest assertion is the gate. |
| `ConcurrentHashMap.computeIfAbsent` deadlocks on the mutually recursive caches. | §4.4: get → compute outside → `putIfAbsent`, everywhere. Recomputation on a race is harmless because the computation is a pure function of the seed. |
| Splitting 4400 lines of generation code silently changes behaviour. | Digest invariance asserted after every extraction, which is why determinism must land first. |
| P1 produces no visible improvement, so its value is hard to feel. | It is the prerequisite for P2 and P4, and it makes worlds reproducible — a property the mod has never had. Accepted knowingly. |

## 9. Open questions

None blocking. Two to resolve before publishing rather than before building: whether `urbex` is
free on CurseForge, and whether the fork announces itself to McJty before or after P1 lands (the
`upstream-fix` label presumes some contact).
