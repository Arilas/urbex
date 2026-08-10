# Carver-Stage Cross-Chunk Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** No `dev.krona.urbex` frame appears in any unsafe-terrain-read stack trace during generation.

**Architecture:** City generation runs at the tail of `applyCarvers`, where MC 26.2 permits a chunk to touch only itself. Two places break that: `ChunkFixer.generateVines` and the `updateShape` call inside `ChunkDriver.updateAdjacent`. Both are resolved by *removing* the cross-chunk access — the vine subsystem is deleted outright, and border blocks defer their shape update to vanilla's postprocessing. A permanent gate then makes a regression fail CI instead of going unnoticed for months.

**Tech Stack:** Java 25, Fabric Loader 0.19.3, Minecraft 26.2, Gradle (Loom 1.17.17), JUnit 5, SpongePowered Mixin, Mojang DFU codecs.

**Spec:** `docs/superpowers/specs/2026-08-10-carver-stage-cross-chunk-access-design.md`

## Global Constraints

- **Package root is `dev.krona.urbex`.** Fabric/MC 26.2; `ResourceLocation` is `Identifier` here.
- **All addressed randomness goes through `dev.krona.urbex.plan.Hash` or `dev.krona.urbex.varia.Rng`.** A `new Random(...)`, a shared `RandomSource` field, or `Math.random()` inside generation is a bug.
- **Both goldens move in this work, deliberately.** `digest.golden` currently reads `1be5211b0009554d`; `digest-features.golden` reads `a4c430de372b9b4f`. Do **not** regenerate them per-task — intermediate commits carry a stale digest, which is expected. They are regenerated once, in Task 5, each from two agreeing runs.
- **`RngTest`'s `GOLDEN` and `GOLDEN_LAST` also move**, in Task 3 only. Until then they must stay untouched and passing.
- **`./gradlew test` must pass at the end of every task.** Only the pinned digest values may be stale mid-branch.
- **The `plan` package must not import Minecraft**; `PlanPurityTest` enforces this.
- **Datapack references are fully namespaced** (`urbex:name`); `DatapackReferenceIntegrityTest` enforces it.
- **This branch closes both #101 and #20.** #20's defect is vine-specific — order-dependent gating on chunk status — and removing vines removes it. Its mention of post-todo writes is context explaining why the digest could not observe the vine path, not a second defect; it never claims those writes are order-dependent, and the probe found no post-todo frames in any unsafe read.

## Commands

| Purpose | Command |
|---|---|
| Unit tests | `./gradlew test` |
| One test class | `./gradlew test --tests 'dev.krona.urbex.varia.RngTest'` |
| Compile | `./gradlew build -x test` |
| Primary digest | `./gradlew prepareDigestCheck runDigestCheck` |
| Features digest | `./gradlew prepareDigestCheckFeatures runDigestCheckFeatures` |
| Client (visual check) | `./gradlew runClient` |

## Background the implementer needs

A stack-trace probe on `WorldGenRegion.getChunk` established exactly two offending sites:

| Site | Path |
|---|---|
| `ChunkFixer.generateVines` (lines 41, 55, 70, 84) | ← `ChunkFixer.fix` ← `CityGenerator.generate:337` |
| `ChunkDriver.updateAdjacent:411` | ← `ChunkDriver.correct:501-504` ← `correctionsPass` ← `actuallyGenerate` |

`generateVines` guards its cross-border work on the neighbour having reached `ChunkStatus.FEATURES`, which cannot happen at the carver stage. Measured: **179 guard evaluations, 0 passes.** The code is dead.

`updateAdjacent` already refuses out-of-chunk positions; the read is vanilla's, from `updateShape` consulting the block's own outward neighbour when the block sits on the boundary.

## File Structure

| File | Change |
|---|---|
| `worldgen/ChunkFixer.java` | delete `generateVines`, `createVineStrip`, `vineRoll`, `vineContinueRoll`, and the call from `fix` |
| `worldgen/ChunkDriver.java` | skip `updateShape` on boundary blocks; mark them for postprocessing; correct the stale blind-spot comment |
| `worldgen/DigestRunner.java` | report the unsafe-read count |
| `worldgen/lost/regassets/data/WorldSettings.java` | delete the four vine fields, codecs, accessors and the `VineBlock` import |
| `config/UrbexProfile.java`, `config/ProfileSetup.java` | delete `VINE_CHANCE` and its per-profile assignments |
| `gui/settings/Settings.java`, `assets/urbex/lang/en_us.json` | delete the `VINE_CHANCE` descriptor and lang keys |
| `commands/CommandDigest.java` | correct the stale blind-spot comment |
| `varia/Rng.java` | delete seven dead `Purpose` constants |
| `mixin/UnsafeReadGateMixin.java` (new) | count unsafe reads carrying an Urbex frame |
| `worldgen/UnsafeReadCounter.java` (new) | the counter the mixin writes and `DigestRunner` reads |
| `setup/DigestCheck.java` | gate on the count |
| `build.gradle`, `.github/workflows/build.yml` | wire the new property |
| `CHANGELOG.md` | the removal, its compatibility impact, and what it closes |

---

### Task 1: Remove the vine subsystem

Deletes a subsystem whose main surface — border vines — has been dead since `15dba5f2`, and which is the tracked order-dependence in #20 that the digest structurally cannot observe.

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/ChunkFixer.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/WorldSettings.java`
- Modify: `src/main/java/dev/krona/urbex/config/UrbexProfile.java:44,333`
- Modify: `src/main/java/dev/krona/urbex/config/ProfileSetup.java:323,328,347,409,431`
- Modify: `src/main/java/dev/krona/urbex/gui/settings/Settings.java:265-268`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json:120-121`
- Modify: `src/main/java/dev/krona/urbex/worldgen/ChunkDriver.java:44-56` (comment only)
- Modify: `src/main/java/dev/krona/urbex/commands/CommandDigest.java:25-29` (comment only)
- Modify: `CHANGELOG.md`
- Modify: the bundled datapack, wherever `vinewest`/`vineeast`/`vinenorth`/`vinesouth` are declared

**Interfaces:**
- Consumes: nothing.
- Produces: `Rng.Purpose.VINES`, `VINES_CONTINUE`, `VINES_EAST`, `VINES_NORTH`, `VINES_SOUTH` become unreferenced — Task 3 deletes them. **Leave them in place in this task**; deleting them here would move `RngTest`'s golden vectors while the digest is also moving, which the spec deliberately separates.

- [ ] **Step 1: Find every vine reference**

```bash
grep -rn -i "vine" src/main/java src/main/resources src/test/java
```

Expected: eleven files. Two of the hits are false positives — `CityGenerator.java:2474` says "ravine" and `ProfileSetup.java:79` says "ravines". Leave both alone.

- [ ] **Step 2: Delete the generation code**

In `ChunkFixer.java` remove `generateVines`, `createVineStrip`, `vineRoll`, `vineContinueRoll`, the call to `generateVines` from `fix`, and any imports left unused (`VineBlock`, `ChunkStatus` if nothing else uses it).

- [ ] **Step 3: Delete the profile setting**

`UrbexProfile.java:44` — remove `public float VINE_CHANCE = 0.009f;`
`UrbexProfile.java:333` — remove the `cfg.getFloat("vineChance", ...)` line.
`ProfileSetup.java` — remove the `profile.VINE_CHANCE = ...` assignments at lines 328, 347, 409 and 431. At line 323 the jungle profile's description reads "Ancient jungle city, vines and leafs, ruined buildings" — reword it so it does not promise vines; leaves are still generated, so "Ancient jungle city, leafs, ruined buildings" is accurate.

- [ ] **Step 4: Delete the GUI descriptor and lang keys**

`Settings.java:267-268` — remove the `r.slider("VINE_CHANCE", ...)` registration. Line 265's section comment reads "Overgrowth and debris: vines, stray leaf blocks, ..." — reword to drop vines.
`en_us.json:120-121` — remove both `urbex.setting.VINE_CHANCE` keys.

- [ ] **Step 5: Delete the datapack schema**

In `WorldSettings.java` remove the `vineWest`, `vineEast`, `vineSouth`, `vineNorth` record components, their four `optionalFieldOf` codec entries, their accessors, the `getVine(...)` helper if nothing else uses it, and the `net.minecraft.world.level.block.VineBlock` import. Remove the corresponding declarations from the bundled datapack.

- [ ] **Step 6: Establish the datapack compatibility impact — do not assume it**

The CHANGELOG entry depends on this. Read how `WorldSettings.CODEC` is built and determine whether a third-party datapack still declaring `vinewest` is *rejected* or *silently ignored*. The `full` removal established that `RecordCodecBuilder` reads only declared keys and never validates the input's key set, which would make this non-breaking — confirm that the same construction is used here rather than carrying the earlier finding across.

- [ ] **Step 7: Correct the two stale comments**

`ChunkDriver.java:44-56` and `CommandDigest.java:25-29` both name vine generation as a path that bypasses the driver and is therefore invisible to the digest, citing #20 as a known order-dependence.

Vines are gone, and #20 goes with them. Post-todo writes still bypass the driver, so the *blind spot* is real and the comments should still describe it — but drop the #20 citation and the "known to be order-dependent" framing, which was only ever true of vines. State it plainly: writes made straight to the world are not recorded by the driver and so are not covered by the digest, and today that is the post-todo callbacks.

Add that the gate introduced in Task 4 covers the residual risk from the other side: a post-todo write that ever crossed a chunk boundary would resolve the neighbour through `getChunk` and be counted.

- [ ] **Step 8: Run the tests**

Run: `./gradlew test`
Expected: PASS. `RngTest` still passes because Task 1 does not touch `Rng.Purpose`.

Run: `./gradlew build -x test`
Expected: PASS. Any compile error naming `VINE_CHANCE`, `vineWest` or `generateVines` is a leftover from steps 2–5.

- [ ] **Step 9: Write the CHANGELOG entry**

Under `## Unreleased`, in the file's established voice — read the neighbouring entries first and match them. It must cover: vines are removed entirely (generation, profile setting, GUI control and datapack fields); the compatibility impact you established in step 6, stated at the strength the evidence supports; that a building asset can paint its own vines, including a reserved margin, which is more expressive than the removed per-world chance; and that worlds generate differently.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor: remove the vine subsystem

Border vines have been dead since 15dba5f2 moved city generation to the
carver stage: the cross-chunk work is guarded on the neighbour having
reached FEATURES, which cannot happen there. Measured over the digest
window, 179 guard evaluations and 0 passes.

That guard is also the order-dependence tracked in #20 - whether a wall
got vines depended on which chunk generated first - and it wrote through
the world rather than the driver, so the digest could never see it.

Closes #20.

With chunk-sized buildings most vine surface is at chunk borders, so what
remained working was a small fraction of the intent. Removed rather than
repaired: a datapack can paint vines into a building asset, per building
and in any block, which the single global chance never allowed."
```

---

### Task 2: Stop the driver reading across the chunk border

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/ChunkDriver.java` (`updateAdjacent` around 397-420, `correct` around 490-512)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: no new public API.

- [ ] **Step 1: Establish whether border positions are already marked**

The spec flags this as something to determine, not assume. `correct()` marks `current` for postprocessing when `current` is on the chunk boundary:

```java
int lx = cx & 0xf;
int lz = cz & 0xf;
if (lx == 0 || lx == 15 || lz == 0 || lz == 15) {
    thisChunk.markPosForPostProcessing(pos.set(cx, cy, cz));
}
```

But `updateAdjacent` operates on *neighbours* of `current`, and a neighbour is only marked if it is itself written by the driver and so becomes `current` at some point. A neighbour that is untouched pre-existing terrain never is.

Confirm that by reading the call path. If it holds — and it should — the skip introduced below **must add its own mark**, or the block's connections would simply never be recomputed rather than being deferred. Record what you found in your report.

- [ ] **Step 2: Add a boundary predicate**

In `ChunkDriver`, beside the existing `isThisChunk`:

```java
    /**
     * True when this position sits on the edge of its own chunk, so at least one of its four
     * horizontal neighbours lives in the next chunk along.
     */
    private static boolean isOnChunkBoundary(BlockPos pos) {
        int lx = pos.getX() & 0xf;
        int lz = pos.getZ() & 0xf;
        return lx == 0 || lx == 15 || lz == 0 || lz == 15;
    }
```

- [ ] **Step 3: Skip `updateShape` for boundary blocks**

In `updateAdjacent`, after the existing `LadderBlock` early return and before the `try` block that calls `updateShape`:

```java
        if (isOnChunkBoundary(pos)) {
            // updateShape consults this block's own outward neighbour, which lives in the next
            // chunk. At the carver stage the write radius is 0, so that read is forbidden - and
            // the neighbour is not finished anyway. Defer instead: vanilla recomputes the
            // connections from final neighbour data when the chunk is postprocessed, the same
            // mechanism vanilla structures use across chunk borders. Marking here rather than
            // relying on correct()'s mark is deliberate: that one only fires for positions the
            // driver itself writes, and this position may be untouched terrain.
            thisChunk.markPosForPostProcessing(pos.immutable());
            return adjacent;
        }
```

`pos.immutable()` is unnecessary, not load-bearing: `ProtoChunk.markPosForPostProcessing` packs the position to a `short` via `packOffsetCoordinates` before returning, so it never retains the `BlockPos` reference past the call - marking the shared mutable instance directly would be just as safe. Harmless either way, so leave the call as written; this note exists only so the reasoning is not copied forward as if the copy were required.

- [ ] **Step 4: Verify the unsafe reads from this site are gone**

The permanent gate arrives in Task 4, so verify by hand here. Temporarily add a probe mixin on `WorldGenRegion` (the same shape Task 4 will make permanent — see that task for the full code), run `./gradlew prepareDigestCheck runDigestCheck`, and confirm no trace carries a `ChunkDriver` frame. Revert the probe afterwards and confirm `git status` is clean.

Report what you saw. If `ChunkFixer` frames also appear, Task 1 was incomplete and should be fixed before continuing.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: PASS.

The digest will now differ from `digest.golden`. That is expected and must not be regenerated here.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix(worldgen): defer border-block shape updates instead of reading across

updateAdjacent already refused out-of-chunk positions, but updateShape
consults the block's own outward neighbour, so asking a boundary block to
recompute its shape reads the next chunk - which the carver stage forbids
and which is not finished anyway.

Boundary blocks are now marked for postprocessing and left alone, so
vanilla recomputes their connections from final neighbour data. correct()
already did this for positions the driver writes; the mark here covers
neighbours that are untouched terrain and so never become current."
```

---

### Task 3: Compact `Rng.Purpose`

**Files:**
- Modify: `src/main/java/dev/krona/urbex/varia/Rng.java`
- Modify: `src/test/java/dev/krona/urbex/varia/RngTest.java:190-195` and its `GOLDEN`, `GOLDEN_LAST`, `PURPOSE_COUNT`, `LAST_PURPOSE` constants

**Interfaces:**
- Consumes: Task 1 having removed every use of the five vine purposes.
- Produces: a `Purpose` enum of 44 constants where the ordinals of everything after `BUILDING` have shifted.

- [ ] **Step 1: Confirm exactly which constants are dead**

```bash
grep -oE "^\s{8}[A-Z][A-Z_0-9]*," src/main/java/dev/krona/urbex/varia/Rng.java | tr -d ' ,' > /tmp/consts.txt
while read c; do
  n=$(grep -rn "Purpose\.$c\b" src/main/java src/test/java 2>/dev/null | grep -vc "varia/Rng.java" || true)
  if [ "$n" -eq 0 ] 2>/dev/null; then echo "DEAD: $c"; fi
done < /tmp/consts.txt
```

Expected after Task 1: exactly seven — `STREET`, `HIGHWAY`, `VINES`, `VINES_CONTINUE`, `VINES_EAST`, `VINES_NORTH`, `VINES_SOUTH`. If the list differs, stop and report: either Task 1 left a use behind, or something else became dead unexpectedly.

- [ ] **Step 2: Delete the seven constants and their reserve comments**

Remove each constant and the comment blocks that explain why it was being kept. Those comments say deleting would "silently change every world ever generated" — that rule protects released worlds, and this mod has none. Do not leave them behind to confuse the next reader.

Keep the surrounding discipline intact: the javadoc explaining that two logically independent decisions must never share an address and a key, and that new consumers append rather than reorder, both still apply.

- [ ] **Step 3: Run `RngTest` and read the failures**

Run: `./gradlew test --tests 'dev.krona.urbex.varia.RngTest'`
Expected: FAIL, on `PURPOSE_COUNT` (51 → 44), `LAST_PURPOSE`, `PURPOSE_ORDER`, and **both** `GOLDEN` and `GOLDEN_LAST`.

`GOLDEN` moving is the part that is easy to miss: it pins `Rng.at(42L, 100, -100, Purpose.RUINS)`, and `STREET` at ordinal 1 sits before `RUINS` at ordinal 4, so compacting shifts `RUINS` to 3.

- [ ] **Step 4: Re-pin the vectors from observed output**

Update `PURPOSE_COUNT`, `LAST_PURPOSE` and the `PURPOSE_ORDER` string to the new enum. Regenerate `GOLDEN` and `GOLDEN_LAST` from the values the failing test reports, exactly as their existing comments describe.

Do not weaken any assertion to make it pass. These vectors are the only guard on the mixing, and the point of re-pinning is that they keep guarding it at new values.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(rng): drop seven dead Purpose constants

STREET and HIGHWAY have had no callers for some time, and Task 1 left the
five vine purposes unused. They were being kept because removing a
constant shifts every ordinal after it and so changes generated output -
a rule that protects released worlds. There are none: the mod is
unreleased and the changelog already disclaims cross-version stability.

Both RngTest vectors are re-pinned, GOLDEN included: STREET sat at
ordinal 1, ahead of RUINS at 4, so compacting moved the value GOLDEN
pins."
```

---

### Task 4: Permanent unsafe-read gate

Makes a regression fail CI instead of going unnoticed. This is the reason the two sites in this plan survived for months.

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/UnsafeReadCounter.java`
- Create: `src/main/java/dev/krona/urbex/mixin/UnsafeReadGateMixin.java`
- Modify: `src/main/resources/urbex.mixins.json`
- Modify: `src/main/java/dev/krona/urbex/worldgen/DigestRunner.java`
- Modify: `src/main/java/dev/krona/urbex/setup/DigestCheck.java`
- Modify: `build.gradle`, `.github/workflows/build.yml`

**Interfaces:**
- Consumes: `DigestRunner.Result`, which already carries `bridgeChunks` and `slopeChunks`.
- Produces: `UnsafeReadCounter.count()`, `UnsafeReadCounter.firstSample()`, `UnsafeReadCounter.reset()`; `DigestCheck.PROP_FAIL_ON_UNSAFE_READ`; `DigestRunner.Result.unsafeReads()`.

- [ ] **Step 1: Create the counter**

```java
package dev.krona.urbex.worldgen;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counts cross-chunk terrain reads made by Urbex during world generation.
 *
 * <p>City generation runs at the tail of {@code applyCarvers}, where Minecraft gives a chunk a
 * write radius of 0 - it may touch only itself. Reading a neighbour there is a contract violation:
 * what it sees depends on worker scheduling, so it is a latent source of order-dependent output.
 * Two such reads survived for months because nothing watched for them; this is what watches.
 *
 * <p>Only reads whose stack carries an Urbex frame are counted. Vanilla makes cross-chunk reads of
 * its own that are none of our business and that we could not fix.
 */
public final class UnsafeReadCounter {

    private static final AtomicLong COUNT = new AtomicLong();
    private static final AtomicReference<String> FIRST_SAMPLE = new AtomicReference<>();

    private UnsafeReadCounter() {
    }

    /** Records one violation. {@code frame} is the innermost Urbex frame, for the failure message. */
    public static void record(String frame) {
        COUNT.incrementAndGet();
        FIRST_SAMPLE.compareAndSet(null, frame);
    }

    public static long count() {
        return COUNT.get();
    }

    /** The innermost Urbex frame of the first violation seen, or null if there were none. */
    public static String firstSample() {
        return FIRST_SAMPLE.get();
    }

    public static void reset() {
        COUNT.set(0);
        FIRST_SAMPLE.set(null);
    }
}
```

- [ ] **Step 2: Create the mixin**

```java
package dev.krona.urbex.mixin;

import dev.krona.urbex.setup.DigestCheck;
import dev.krona.urbex.worldgen.UnsafeReadCounter;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts Urbex-attributable cross-chunk reads, for the digest check's gate.
 *
 * <p>Disabled unless {@link DigestCheck#PROP_FAIL_ON_UNSAFE_READ} is set, which only the digest run
 * configurations do. The flag is a {@code static final boolean} read once so the JIT can eliminate
 * the whole body in normal play - this sits on {@code getChunk}, a hot path.
 */
@Mixin(WorldGenRegion.class)
public abstract class UnsafeReadGateMixin {

    @Shadow private int centerChunkX;
    @Shadow private int centerChunkZ;
    @Shadow private int writeRadius;

    @Unique
    private static final boolean urbex$enabled = System.getProperty(DigestCheck.PROP_FAIL_ON_UNSAFE_READ) != null;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"))
    private void urbex$countUnsafeRead(int chunkX, int chunkZ, ChunkStatus status, boolean load,
                                       CallbackInfoReturnable<ChunkAccess> cir) {
        if (!urbex$enabled) {
            return;
        }
        int distance = Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ));
        if (distance <= writeRadius) {
            return;
        }
        for (StackTraceElement element : new Throwable().getStackTrace()) {
            if (element.getClassName().startsWith("dev.krona.urbex")) {
                UnsafeReadCounter.record(element.toString());
                return;
            }
        }
    }
}
```

Register it by adding `"UnsafeReadGateMixin"` to the `mixins` array in `src/main/resources/urbex.mixins.json`.

- [ ] **Step 3: Report the count from `DigestRunner`**

Add `unsafeReads` to `Result` and to `driverLine(...)`, beside `bridgeChunks` and `slopeChunks`, with javadoc in the same voice. Call `UnsafeReadCounter.reset()` at the start of `run(...)` — before the generation loop — so the count covers this run only, and read `UnsafeReadCounter.count()` after the loop.

- [ ] **Step 4: Gate on it in `DigestCheck`**

Mirror `PROP_REQUIRE_BRIDGE` and `PROP_REQUIRE_SLOPE` exactly:

```java
    /**
     * When set, the check fails if Urbex made any cross-chunk terrain read during generation. Only
     * the digest run configurations set it; see {@code UnsafeReadGateMixin} for why it is opt-in.
     */
    public static final String PROP_FAIL_ON_UNSAFE_READ = "urbex.digestCheck.failOnUnsafeRead";
```

and in the verdict chain, in the same style as the existing arms:

```java
                } else if (failOnUnsafeRead && result.unsafeReads() > 0) {
                    verdict(FAIL + " (" + result.unsafeReads() + " cross-chunk terrain read(s) from Urbex "
                            + "during generation, first at " + UnsafeReadCounter.firstSample()
                            + " - city generation runs at the carver stage, where a chunk may touch only itself)");
```

- [ ] **Step 5: Wire the property into both run configurations**

In `build.gradle`, add `vmArg '-Durbex.digestCheck.failOnUnsafeRead'` to **both** `digestCheck` and `digestCheckFeatures`. Unlike `requireBridge`/`requireSlope` this is not window-specific — any window should be free of Urbex cross-chunk reads.

Add a short comment saying why the gate exists: two such reads survived for months because nothing watched.

- [ ] **Step 6: Prove the gate can fail**

A gate that cannot fail is the defect this task exists to prevent. Temporarily reintroduce a cross-chunk read — the simplest is to delete the `isOnChunkBoundary` early return added in Task 2 — then run `./gradlew prepareDigestCheck runDigestCheck` and confirm the check **fails** with the new message and a `ChunkDriver` frame in it. Restore, re-run, confirm it passes.

Report that RED/GREEN.

- [ ] **Step 7: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "test(worldgen): fail the digest check on Urbex cross-chunk reads

City generation runs where a chunk may touch only itself, and two places
violated that for months because nothing watched. This watches: a mixin
counts reads whose stack carries an Urbex frame, and the digest check
fails when the count is non-zero.

Vanilla's own cross-chunk reads are excluded - they are not ours to fix,
and one appears in a normal run. The mixin is behind a property only the
digest runs set, read once into a static final boolean so the hot path
costs nothing in play."
```

---

### Task 5: Regenerate the goldens and verify

**Files:**
- Modify: `digest.golden`, `digest-features.golden`

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: the pinned values the branch ships with.

- [ ] **Step 1: Regenerate the primary golden**

```bash
rm digest.golden
./gradlew prepareDigestCheck runDigestCheck    # note the printed DRIVERDIGEST
./gradlew prepareDigestCheck runDigestCheck    # must print the same value
```

Both runs must agree before you write the file. If they disagree, generation is not deterministic and that is a defect introduced by this branch — stop and report rather than picking a value.

Confirm `unsafeReads=0` on both runs.

- [ ] **Step 2: Regenerate the features golden**

```bash
rm digest-features.golden
./gradlew prepareDigestCheckFeatures runDigestCheckFeatures
./gradlew prepareDigestCheckFeatures runDigestCheckFeatures
```

Same rule. Also confirm `bridgeChunks` and `slopeChunks` are both non-zero — the window must still contain the features it exists to guard, and this branch changed generation, so they could in principle have moved.

- [ ] **Step 3: Full verification**

Run: `./gradlew test`
Expected: PASS.

Run both digest checks once more against the newly written goldens.
Expected: both `URBEX-DIGEST-CHECK: OK`.

- [ ] **Step 4: In-game check**

Run: `./gradlew runClient`

Create a world with the `default` profile and check:

1. **Fences, walls and stairs at chunk borders connect correctly.** This is the one behavioural risk in the branch — their connections now resolve at postprocessing rather than during generation. Walk along a chunk boundary through a city and look at railings, fences and stair runs that cross it.
2. **No vines anywhere**, on buildings or elsewhere.
3. **No "Detected unsafe terrain read" lines in the log** during exploration. This is the acceptance criterion of the whole branch, and the client exercises paths the digest does not.

Record what you saw. Item 1 is the one worth being slow about.

- [ ] **Step 5: Commit**

```bash
git add digest.golden digest-features.golden
git commit -m "test: regenerate both goldens after removing cross-chunk access

Generation changed in three ways: vines are gone, border blocks defer
their shape update to postprocessing, and Rng.Purpose lost seven dead
constants so every ordinal after BUILDING shifted. Each golden was
established from two agreeing runs, with unsafeReads=0 and the features
window still containing its bridge and slope."
```

- [ ] **Step 6: Update the issues**

Close **#101** with the before/after unsafe-read counts — the baseline on `main` was 88 occurrences across 8 distinct chunk pairs.

Close **#20** as well. Its defect is the chunk-status gating in `ChunkFixer`, which no longer exists; the invisibility it describes was the reason that defect could not be caught, not a second bug. Say so in the closing comment, and record two things for anyone who finds the issue later: post-todo writes still bypass the driver and so remain outside the digest's coverage, and Task 4's gate now catches any write or read that crosses a chunk boundary, which is the failure mode that made the vine bug matter.

---

## Self-Review

**Spec coverage.** §3 Part A → Task 1. §4 Part B → Task 2, including the mark-ownership question the spec flagged as needing to be established rather than assumed (Task 2 step 1). §5 Part C → Task 3, with the `GOLDEN`-moves-too trap called out explicitly. §7's permanent gate and its two constraints — keying on Urbex frames, and costing nothing in normal play — → Task 4, with a RED/GREEN reachability proof. §7's in-game check and regression baseline → Task 5. §8's sequencing is the task order, with the "regenerate once at the end" rule stated in Global Constraints so no task regenerates early.

**Deliberate sequencing.** Task 1 leaves the five vine `Purpose` constants in place even though it makes them dead, so that the digest moves and the `RngTest` vectors move in separate commits rather than together.

**Judgement left to the implementer.** Task 1 step 6 (the codec's unknown-key behaviour) and Task 2 step 1 (whether border neighbours are already marked) are both written as findings to establish, because assuming either wrong would produce a plausible-looking change that is silently incorrect.
