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

`avoidVillages` **defaults to true**, so this is not a corner configuration. Both digest runs exercise
it today.

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

## Digest expectations — the part to settle first

**Both goldens will almost certainly move**, and unlike every other change in phases 1 and 2 that is
not a defect in the change:

- avoidance is on by default, so the digest worlds run it;
- today's answer depends on region contents, so today's golden encodes *one particular load order*;
- a deterministic mask gives one answer for every order, which is the point, and it will not always
  be the answer the current golden happens to hold.

That makes this the first change in the epic where "the golden moved" is the expected outcome rather
than the alarm. It therefore needs, in order:

1. the semantic decided and written down (above);
2. the new digest configurations the acceptance criteria ask for — parallel and shuffled-order runs
   covering villages, blacklisted structures, every `avoid*` mode, and railway collisions — added
   **before** the behaviour changes, so the old behaviour is captured first;
3. explicit approval of the golden change, with the before/after hashes and an explanation of which
   chunks moved and why;
4. regeneration the sanctioned way: delete the file, run twice, require agreement.

Step 2 is what makes step 3 reviewable. Landing the behaviour first would leave nothing to compare
against.

## Suggested PR split

**126a — order-independence made observable.** The shuffled-order and `avoid*` digest configurations,
against today's behaviour. No production change; it should pass, and if it does not, that failure is
the bug this issue exists to fix, captured before touching anything.

**126b — immutable candidate/plan.** The record split and the removal of post-publication mutation,
with suppression still computed the way it is now. Must not move a golden: it changes when values are
fixed, not what they are.

**126c — deterministic structure avoidance.** The mask, replacing the neighbourhood probe. This is the
digest-affecting one and it lands alone.

126b is the larger diff; 126c is the one that needs the conversation.
