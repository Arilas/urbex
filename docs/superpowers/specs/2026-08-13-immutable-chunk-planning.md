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
