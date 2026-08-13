# Immutable, order-independent chunk planning (issue #126)

Phase 2, step 7 of epic #134 — and the last of that phase. Written before any code because this one
changes what generates, not only where state lives, and the digest consequences need settling before
the diff exists.

## The two defects, from the code

### 1. Published planning values are mutated afterwards

`CityGenerator.generate`, on the generation path:

```java
avoidChunk = hasBlacklistedStructure(region, chunkX, chunkZ);
if (avoidChunk != AvoidChunk.NO) {
    doCity = false;
    info.isCity = false;                              // the cached BuildingInfo
    BuildingInfo.setCityRaw(coord, provider, false);  // and the cached ChunkCharacteristics
}
```

Both are `DimensionCaches` entries other chunks have already read. `ChunkCharacteristics.isCity` is
`volatile` with a comment saying exactly this — the field was made visible across threads rather than
the write being removed. Everything a neighbour derived from `isCity == true` before the flip
(`couldHaveBuilding`, city level, multi-building membership, the highway and railway decisions in
`BuildingInfo`) stays derived from a value that is no longer there.

Building construction mutates further state during consumption (`floors`, `cellars` are assigned in
the constructor from other cached lookups; the top-left of a multi-building copies them back out).
`TimedCache` eviction can then rebuild either object independently, so a plan can be recreated from a
different starting point than its neighbours saw.

### 2. Structure avoidance depends on what happens to be loaded

`hasBlacklistedStructure` probes a 3×3 neighbourhood:

```java
if (level.hasChunk(chunkX + dx, chunkZ + dz)) {
    ChunkAccess ch = level.getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.STRUCTURE_REFERENCES);
    ...
}
// "Chunks that are not part of this region are unknown and assumed to be ok."
```

The comment states the defect plainly: the answer is *whatever the region happened to contain*. Two
worlds with the same seed, generated in a different order, can disagree about whether a chunk is a
city — and that disagreement is then written back into the shared cache by the block above.

`avoidVillages` **defaults to true**, so this is not a corner configuration — but see the digest
section: default-on turned out not to mean the sampled windows contain a village, and they do not.

## `StructureSuppressor` is the precedent, and it reframes the choice

There is already a deterministic answer to the mirror of this problem, shipping. `StructureSuppressor`
+ `StructureStartMixin` cancel *structure* placement inside a city, and its javadoc states the
relationship plainly: "the 'avoidStructures' options solve this the other way around, by moving the
city out of the way; this one lets the city win instead."

They are two policies for one conflict, both config-gated, and the defaults pick *city yields*
(`avoidVillages=true`, `structuresYieldToCities=false`). So the avoid path cannot simply be deleted.

What matters for this issue is **how the suppressor gets a deterministic answer**: it asks
`BuildingInfo.isCity(coord, diminfo)` - pure seed and coordinate, no neighbour reads - at structure
placement time. It queries *planning* from *generation*, which is cheap and order-independent.

The avoid path needs the reverse - "is there a structure here?" asked at planning time - and that is
the hard direction, because structure placement is not seed-pure: candidate chunks are seed-derived
but pass a biome/terrain check at generation. That asymmetry is the whole difficulty of option 1
below, and the suppressor does not share it.

It also means option 2 has a working precedent in this codebase rather than being the compromise: the
suppressor already cancels one side of the conflict locally, at generation, without touching planning.
Cancelling the city's *rendering* in a structure chunk is its exact mirror.

## What has to be decided before code

**The structure-avoidance semantic.** The issue offers two, and they are not equivalent:

1. A deterministic **`StructureMask`** computed before the plan is published, if suppressed chunks
   should affect their neighbours (a village stays surrounded by non-city, as today's *intent*).
2. A local immutable **`GenerationOverride`**, if suppression should only affect what is rendered in
   that chunk (neighbours keep planning as if the city were there).

(1) preserves the current intent and is the one the acceptance criteria imply ("suppressed chunks
should affect neighbors"). It also needs a source of structure positions that does not read
neighbouring chunks - and see the section above: that source is not seed-pure, which is the cost
option 2 does not pay.

**Unverified, and step one of the implementation:** whether this Minecraft version exposes structure
placement as a pure function of seed and settings that Urbex can call at plan time - the
`ChunkGeneratorStructureState` / `StructurePlacement` family reached through the chunk source. The
whole of option (1) rests on it. If it is not reachable, the fallback is to derive the mask from the
same `STRUCTURE_REFERENCES` data but only for the chunk being planned, and to accept that adjacency
becomes a two-pass property rather than a probe - which changes what "adjacent" can mean and should
be re-decided rather than fudged.

## Target shape

```java
record ChunkCandidate(...)  // raw city, road, style and building choices - seed and coordinate only
record ChunkPlan(...)       // settled occupancy, topology, transport, layout
```

Published once, never written again. Structure suppression enters as an *input* to the candidate →
plan step, not as a later edit of a published plan.

## Digest expectations — measured, and not what this spec first assumed

The first draft of this section predicted both goldens would move, reasoning that `avoidVillages`
defaults to true so the digest worlds must exercise avoidance. **Measured, they do not.** The
`avoidedChunks` counter added in 126a reports:

```
digestCheck          radius 3, offset 100   avoidedChunks=0
digestCheckFeatures  radius 9, offset  83   avoidedChunks=0
```

Neither window contains a structure avoidance suppresses. So:

- **Today's goldens do not cover this code path at all.** The config being on by default is not the
  same as the sampled chunks containing a village.
- **A shuffled-order run over the primary window agrees with the row-major one** — but that agreement
  says nothing about #126, because the order-dependent branch never executes in it.
- **126c will not move either golden**, which is the opposite of the risk this section was written
  about. The real risk is the mirror image: the behaviour change ships with **no** digest coverage and
  CI stays green through it.

So the work 126a has to do is not "capture today's behaviour before changing it" — the primary and
features windows already do that faithfully for everything except this. It is **to give avoidance a
window at all**:

1. probe for an offset/radius whose sample contains an avoided structure, the way the features window
   was sited on a bridge and a slope;
2. add it as a third configuration with a `requireAvoided` gate, so a window that stops containing one
   fails rather than passes silently;
3. capture its golden under today's behaviour;
4. only then land 126b and 126c, and expect *that* golden to move while the other two hold.

The shuffled-order run and the `avoidedChunks` counter are worth keeping regardless: the counter is
what makes the coverage gap visible instead of assumed, and the shuffled run is the standing version
of an experiment that had only ever been done by hand (see the note in `build.gradle` about
15dba5f2).

## The third defect, found by the window this spec asked for

Siting the avoidance window turned up an instability the spec had not predicted: the same window
returned two different digests with every count identical, including `blocks` and `avoidedChunks`.
It was recorded here as a coin flip and left unpinned. It is not a coin flip.

**Measured.** The digest is a deterministic function of the worker thread count:

```
max.bg.threads=1   995b19892c47e848        max.bg.threads=2   89d790a9182cff3f
max.bg.threads=8   995b19892c47e848        max.bg.threads=3   89d790a9182cff3f
default (16 cpu)   995b19892c47e848
```

Eight consecutive runs at the default pool size agree. Nothing wobbles run to run; the two recorded
hashes were taken under two different pool sizes.

**It predates local suppression.** The pre-suppression build is unstable in the same window and
worse - `drivenChunks` itself moves (575 / 576 / 575 across 1, 2, 3 threads). Local suppression
narrowed the divergence to a single block; it did not introduce it.

**Root cause, and it is not structure avoidance.** The two digests differ at exactly one block,
`(-80, 65, -1)` - `stone_bricks` against `stone`, the corner of chunk (-5, -1). That chunk's border
column starts at `getMinHeightAt`, which is `min` of its own sampled height and the diagonal
neighbour's, and the neighbour is chunk (-6, **0**). Its published height is 66 on one pool size and
67 on the other.

`CityGenerator.getHeightmap` tiles the chunk grid into `heightSampleSize` squares that share one
sampled height, and fills the cache a whole block at a time under `putIfAbsent`. The tiling was not
a partition: the anchor was `(c / size) * size`, which truncates towards zero, and the block was
then laid out away from the origin with a `-1` step for negative coordinates. Row 0 and column 0
belonged to two blocks at once, sampling terrain at two different coordinates, and whichever chunk
reached the cache first published its answer for the whole overlap.

So the honest summary is that avoidance was a red herring twice over. Turning `avoidVillages` off
made the window stable, which looked like evidence and was coincidence - it changes which chunks are
cities and so which of them consult that diagonal. The defect is in planning's shared heightmap and
would have been reachable by any world generated across the origin, with or without a village in it.

**Why four windows missed it.** `floorDiv` and `/` agree for non-negative coordinates and the step
was already `+1` there, so the tiling only ever overlapped at the origin row and column. `digestCheck`
and `digestCheckShuffled` sample chunks 97..103; `digestCheckFeatures` samples 74..92. The avoidance
window is the first one centred on the origin, and it found this on its first run.

**What now stands guard.** `digestCheckAvoid` pins the window (`b37050817cd94b93`), and two more runs
share that golden: `digestCheckAvoidShuffled` for the request order and `digestCheckAvoidThreads`,
pinned to two workers, for the scheduling. The third is the one with teeth - measured on the pre-fix
build, the shuffled run returned `995b19892c47e848` exactly as the row-major one did, so shuffling
the request order would not have caught this. All three are in CI. `HeightSampleGridTest` asserts the
partition property directly, in milliseconds rather than 25 seconds a run.

**What it says about the shuffled run generally.** Order-independence checks that vary only the order
chunks are *asked for* leave the pool free to resolve races the same way every time. A decision made
by whichever worker arrives first produces one stable answer per pool size and looks reproducible
until the pool size changes - which is not something a single machine rerunning a check will ever do.

## Conflict precedence, as landed

Three conflicts, one rule: **the losing side is cancelled where it is drawn, and no published plan is
edited.** Neighbours keep planning as though the loser were there. That is the cost, it is the same
cost in all three, and it is what makes the answer a function of the seed and the coordinate rather
than of which chunk generated first.

| Conflict | Winner | Config | Where |
|---|---|---|---|
| Structure vs city | structure; the city is not drawn in that chunk | `avoidVillages`, `avoidStructures`, `avoidSurfaceStructures` (avoidance on) | `CityGenerator.hasBlacklistedStructure` → `doCity = false` |
| City vs structure | city; the structure start is cancelled | `structuresYieldToCities` | `StructureSuppressor` + `StructureStartMixin` |
| Building vs railway | building; the rails are not drawn in that chunk | `railwayavoidance: block_railway` | `Railway.buildingBlocksRail`, asked from `CityGenerator.generate` |

The first two are mutually exclusive policies for one conflict and the defaults pick *city yields*
(`avoidVillages=true`, `structuresYieldToCities=false`).

The third is the one this issue changed. It used to be resolved by `BuildingInfo`'s constructor
calling `Railway.removeRailChunkType`, which wrote `NOTHING` over the published `railInfo` entry -
a planner constructor editing another planning cache, which criterion 3 names directly. Rail
planning reads its neighbours' entries and `MultiChunk` reads them when accepting a multi-building,
so what either saw depended on whether that chunk's `BuildingInfo` had been constructed yet.

**Measured, the two agree.** In the one window that generates the collision at all - 110 rail chunks,
6 of them cancelled by a building - the old resolution and the new one produce
`3fff027c14eea4a9` alike, at one, two and three worker threads and under forced cache expiry. So this
is a structural fix, not a bug fix with an observed symptom: the mutation was a real order-dependence
hazard and a real criterion-3 violation, and it is now demonstrably not one, but nobody was looking
at a world it visibly damaged.

## Coverage, measured rather than assumed

`railwayavoidance` ships as `ignore` in the only world style this mod has, so **no check in the
repository had ever generated the railway collision**. `digestCheckRail` brings its own datapack -
a world style that extends the shipped one with that single setting changed - which is the only
configuration here under which the conflict exists.

The avoid* modes needed the same treatment. Measured on the avoidance window: all 108 suppressed
chunks come from the village *tag* branch, and turning `avoidVillages` off drops the count to zero,
so the named-blacklist branch and the surface-step catch-all matched nothing. `digestCheckAvoidModes`
turns the tag off, names the village in `avoidStructures` instead - the same 108 chunks, reached
through the blacklist branch - and turns on `avoidSurfaceStructures`, which suppresses exactly one
more.

| Mode | Covered by | Chunks |
|---|---|---|
| `avoidVillages` (tag) | `digestCheckAvoid` | 108 |
| `avoidStructures` (named list) | `digestCheckAvoidModes` | 108 |
| `avoidSurfaceStructures` | `digestCheckAvoidModes` | +1 |
| `avoidFlattening` | both, since it is what a suppressed chunk does with its terrain | 108 / 109 |
| railway collision | `digestCheckRail`, `digestCheckRailShuffled` | 6 |

Suppression demonstrably changes the world rather than only a counter: with `avoidVillages` off the
avoidance window drives 658 chunks and 5942711 blocks against 580 and 5359701 with it on.

## Immutability, and what was done instead of the record split

The Direction section proposes publishing `record ChunkCandidate` / `record ChunkPlan` in place of
`BuildingInfo` + `ChunkCharacteristics`. That was **not** done, and the criterion it serves -
"cached candidate/final plan values are immutable after publication" - was met by making the values
that are already published immutable instead:

- `BuildingInfo.isCity` and `streetType` are `final`, and `isCity` is no longer `volatile`: the
  keyword existed because structure avoidance flipped it after publication.
- `setCityRaw` is deleted (its last caller went with local suppression).
- `setBuildingType`, the last writer of a published plan, now throws unless it is handed an instance
  from `detachedForEditing` - which `/urbex createbuilding` uses. That command used to rewrite the
  cached plan for a chunk in place.
- `Railway.removeRailChunkType` is deleted; see the precedence section.

Audited afterwards: every remaining write into a planning cache is a `putIfAbsent` at first
publication - `characteristics`, `buildingInfo`, `cityLevel`, `biomeInfo`, `heightmap`, `railInfo` -
and the only `clear()` is `DimensionCaches.clear()`, which drops everything at once. Nothing writes
into a second planning cache from a constructor or a query.

Two things still mutate after publication, both memoization of pure functions rather than planning
decisions: `BuildingInfo`'s lazily computed direction fields (`streetSlopeDirection`,
`stairDirection`, `actualStairDirection`, the bridge fields), and `Highway`'s level cache, which uses
`put` rather than `putIfAbsent` because every chunk of one highway run writes the run's level. The
forced-expiry window exercises both - it re-derives everything from different starting points - and
holds its golden.

The record split remains available as a follow-up. It would be a large mechanical refactor of a
~40-field class reached from most of the generator, and on this evidence it would not change
behaviour: it makes the immutability structural rather than enforced, which is worth doing on its own
schedule rather than inside the change that also moved a golden.

*(Noted while auditing, not part of this issue: `Highway.getHighwayLevel` bounds its extent scan at
`MAX_HIGHWAY_SCAN = 10_000` chunks, and two chunks of one run could only disagree about that run if
it were near that long - which is the degenerate every-chunk-is-a-highway case the method already
bails out of.)*

## Suggested PR split

**126a — order-independence and avoidance coverage made observable.** *Landed.* The shuffled-order
run and the `avoidedChunks` counter, no production change. It passes — and the counter is what
established that the reason it passes is that neither window contains an avoided structure. Siting a
window that does is the remainder of 126a.

**126b — immutable candidate/plan.** The record split and the removal of post-publication mutation,
with suppression still computed the way it is now. Must not move a golden: it changes when values are
fixed, not what they are.

**126c — deterministic structure avoidance.** The mask, replacing the neighbourhood probe. This is the
digest-affecting one and it lands alone.

126b is the larger diff; 126c is the one that needs the conversation.
