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

## What has to be decided before code

**The structure-avoidance semantic.** The issue offers two, and they are not equivalent:

1. A deterministic **`StructureMask`** computed before the plan is published, if suppressed chunks
   should affect their neighbours (a village stays surrounded by non-city, as today's *intent*).
2. A local immutable **`GenerationOverride`**, if suppression should only affect what is rendered in
   that chunk (neighbours keep planning as if the city were there).

(1) preserves the current intent and is the one the acceptance criteria imply ("suppressed chunks
should affect neighbors"). It also needs a source of structure positions that does not read
neighbouring chunks.

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
