# Removing cross-chunk access from carver-stage city generation

Date: 2026-08-10
Status: approved, ready for planning
Supersedes: the vine subsystem (`VINE_CHANCE`, the world-style `vinewest`/`vineeast`/`vinenorth`/`vinesouth`
settings, and `ChunkFixer`'s vine generation)
Closes: [#101](https://github.com/Arilas/urbex/issues/101)

## 1. Context

Minecraft 26.2 logs this during world generation, in volume:

```
[Worker-Main-19/ERROR] (Minecraft) Detected unsafe terrain read during worldgen:
reading from chunk [-124, -48] while generating chunk [-124, -49]
(distance: 1, write radius: 0), step: minecraft:carvers
```

The message comes from vanilla — `net.minecraft.server.level.WorldGenRegion`, which holds
`centerChunkX`, `centerChunkZ` and `writeRadius` and checks every chunk resolution against them.

### 1.1 Why we are in this position

`CarverHookMixin` runs city generation at the tail of `applyCarvers`. That was deliberate, and it
was right: a chunk's decoration step may run before or after its neighbours' passes, so a feature
reading live terrain saw neighbouring ore blobs and border-crossing trees depending on worker
scheduling, and output was not run-to-run reproducible (issue #18). Moving to the carver stage
fixed that.

But MC 26.2 gives the carvers step a **write radius of 0** — a chunk may touch only itself — while
Lost Cities' inherited design reads and writes neighbours. The determinism fix and the engine's
safety contract are in direct tension, and this spec resolves it by removing the cross-chunk access
rather than making it safe.

### 1.2 What the investigation established

Measured, not assumed. A temporary mixin on `WorldGenRegion.getChunk` dumped one deduplicated stack
trace per unsafe read during a digest run.

**Exactly two call sites in our code:**

| Site | Path |
|---|---|
| `ChunkFixer.generateVines` (lines 41, 55, 70, 84) | ← `ChunkFixer.fix` ← `CityGenerator.generate:337` ← `CityFeature.generateFromPipeline:82` |
| `ChunkDriver.updateAdjacent:411` | ← `ChunkDriver.correct:501-504` ← `correctionsPass` ← `actuallyGenerate` ← `CityGenerator.generate:336` |

One further trace carried no Urbex frames (`writeRadius=-1`) and is vanilla's own.

**The vine code is dead.** `generateVines` guards its cross-border work on the neighbour having
reached `ChunkStatus.FEATURES`. We now run at the carver stage, before features. Instrumented over
the digest window: **179 guard evaluations, 0 passes.** Every unsafe read there resolves a
neighbouring chunk purely to evaluate a condition that cannot be true, and border vines have been
silently absent since `15dba5f2` — a feature lost with no test or digest able to notice, because a
feature that never fires leaves no trace.

**Generation is not actually diverging.** The same window generated `rowmajor`, `reverse` and
`shuffled` produces byte-identical output (`1be5211b0009554d`), and CI has exercised the parallel
path since `15dba5f2`. So these reads are a contract violation producing log noise and latent risk,
not an active correctness bug. That lowers the urgency but does not make it acceptable: the
contract exists because the engine reserves the right to schedule those chunks differently.

### 1.3 Decisions taken before this spec

| Decision | Choice | Rationale |
|---|---|---|
| Border vines | **Remove the whole vine subsystem** | Most buildings are chunk-sized, so most vine surface is at chunk borders — precisely the part that has been broken for months. A datapack can paint vines into a building asset, including a reserved margin, which also allows per-building variety the single global chance never could. |
| Scope | **Both sites in one piece of work** | Both fixes change generation; doing them together means one digest regeneration and one in-game check. |
| Dead `Rng.Purpose` constants | **Delete them, do not reserve them** | The reserve-don't-delete rule protects existing worlds. The mod is unreleased and `CHANGELOG.md` already states worlds are not guaranteed to generate identically across versions, so the constraint does not apply and the constants are cruft. |

## 2. Goal

No `dev.krona.urbex` frame appears in any unsafe-terrain-read stack trace during generation.

## 3. Part A — remove the vine subsystem

Vines currently touch eleven files.

**Delete outright:**
- `ChunkFixer.generateVines`, `createVineStrip`, `vineRoll`, `vineContinueRoll`, and the call from `ChunkFixer.fix`
- `UrbexProfile.VINE_CHANCE`, its `ProfileSetup` registration, its `Settings.java` descriptor and its lang keys
- `WorldSettings`' `vinewest` / `vineeast` / `vinenorth` / `vinesouth` fields, codec entries and accessors
- the corresponding declarations in the bundled datapack
- any vine references in `CityGenerator`, `ChunkDriver` and `CommandDigest`

**Datapack compatibility.** The same shape as the `full` removal: `RecordCodecBuilder` reads only
declared keys and never validates the input's key set, so a third-party pack still declaring
`vinewest` continues to load with the value ignored. **Verify this against the actual codec rather
than assuming it** — it is the claim the CHANGELOG entry rests on.

**Migration.** A building asset can paint its own vines directly, and can reserve a margin inside
its own footprint to hang them in. That is strictly more expressive than the removed system: per
building rather than per world, and any block rather than four fixed states.

## 4. Part B — stop the driver reading across the border

`updateAdjacent` already refuses positions outside this chunk. The read is vanilla's: when we ask a
block sitting on the chunk boundary to recompute its shape, `updateShape` consults that block's own
outward neighbour, which lies in the next chunk.

`correct()` already carries the remedy, and says so in a comment: blocks at `lx`/`lz` of 0 or 15 are
passed to `markPosForPostProcessing`, so vanilla recomputes their connections from final neighbour
data — the same mechanism vanilla structures use across chunk borders.

**The change:** skip the `updateShape` call when the position being updated sits on the chunk
boundary, and ensure that position is marked for postprocessing. Fences, walls and stairs at chunk
edges then resolve their connections one step later, from complete rather than partial information.

Take care over which position is marked. Today the mark is applied to `current` when `current` is on
the border; the positions passed to `updateAdjacent` are its neighbours, and a neighbour on the
border must also end up marked. Establish this explicitly rather than assuming the existing mark
covers it.

## 5. Part C — compact `Rng.Purpose`

`Purpose` has 50 constants. Two are dead today — `STREET` and `HIGHWAY` — and Part A makes five more
dead: `VINES`, `VINES_CONTINUE`, `VINES_EAST`, `VINES_NORTH`, `VINES_SOUTH`. Delete all seven.

Re-pin `RngTest`'s `PURPOSE_COUNT`, `LAST_PURPOSE`, `PURPOSE_ORDER`, `GOLDEN_LAST` — **and
`GOLDEN`**. `STREET` is ordinal 1 and `RUINS` is 4, so compacting moves `RUINS` to 3 and the primary
golden vector changes too.

Remove the reserve-don't-delete comments along with the constants, so the next reader does not
inherit a rule that no longer applies. Keep the surrounding discipline intact: two logically
independent decisions must still never share an address and a key, and new consumers still append
rather than reorder.

## 6. Accepted cost: both safety nets move at once

This is the one piece of work in this sequence with **no mechanical guard holding still underneath
it**. `RngTest`'s golden vectors have been the proof for every hash change on this branch, and the
digest the proof for every generation change. Part C moves the vectors and Parts A and B move the
digest.

Mitigations, in order of value:

1. **Separate commits per part**, so a bisect can attribute a change to a step.
2. **The suite green at each step**, so only the pinned values move.
3. **Both goldens regenerated once, at the end**, each from two agreeing runs. Intermediate commits
   will carry a stale digest; that is expected on a branch and must not be worked around by
   regenerating repeatedly.
4. **The acceptance test below is independent of both goldens**, which is what makes this tolerable.

## 7. Verification

**The probe is the acceptance test.** The temporary mixin that found these sites is re-runnable: it
injects at `WorldGenRegion.getChunk`, compares the requested chunk against `centerChunkX`/
`centerChunkZ`/`writeRadius`, and prints one deduplicated stack trace per violation, tagged with the
`dev.krona.urbex` frames. After the fix, **no trace may carry an Urbex frame.** This measures the
thing #101 is actually about rather than a proxy for it.

**Make it permanent.** Rather than a throwaway probe, this should become a standing gate in the same
spirit as `requireBridge` and `requireSlope`: count unsafe reads attributable to Urbex frames during
a digest run and fail when the count is non-zero. Without it, the next cross-chunk read added
anywhere in generation reintroduces this silently — which is exactly how it went unnoticed for
months the first time.

**In-game check.** Fences, walls and stairs at chunk borders now resolve at postprocessing. Confirm
they still connect correctly across boundaries, and that no vines remain anywhere.

**Regression baseline.** Before/after unsafe-read counts on the same window, so the fix's effect is
a number rather than an impression. Current baseline on `main`: 88 occurrences across 8 distinct
chunk pairs.

## 8. Sequencing

| Step | Work | Goldens |
|---|---|---|
| A | Remove the vine subsystem | digest moves |
| B | Skip `updateShape` on boundary blocks | digest moves |
| C | Compact `Rng.Purpose` | digest and both `RngTest` vectors move |
| D | Permanent unsafe-read gate in `DigestRunner`/`DigestCheck` | none |
| E | Regenerate `digest.golden` and `digest-features.golden`, two agreeing runs each | pinned |

## 9. Known limitations accepted

- **Border vines are gone and not replaced in-tree.** The migration path is a datapack concern by
  design. Buildings will look barer at chunk edges than they did before `15dba5f2`, though not
  barer than they have looked since.
- **Border block connections resolve one step later.** If a block's postprocessing path does not
  produce the same result as an immediate `updateShape`, the difference will show at chunk borders.
  The in-game check exists for this.
- **This does not make cross-chunk access safe** — it removes the two places we do it. Any future
  feature needing neighbour terrain faces the same tension, and the permanent gate is what forces
  that conversation to happen at the time rather than months later.
