# Urbex P2 — City Plan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pure, Minecraft-free module that turns a world seed and a coordinate into a city plan — settlements, roads, blocks, districts and lots — and a browser viewer to judge whether lot-based cities look better than the chunk grid.

**Architecture:** A new package `dev.krona.urbex.plan` with no imports outside itself and the JDK, enforced by a test. Settlements are placed by one lattice per size class. Inside a settlement, arterial roads are grown outward from the centre against an injected `TerrainSampler`, blocks are extracted as the enclosed faces of the resulting graph, and each block is subdivided into lots. Output serialises to JSON for a self-contained HTML viewer.

**Tech Stack:** Java 25, JUnit 5 (already configured), plain HTML/Canvas for the viewer. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-03-urbex-p2-city-plan-design.md`

## Global Constraints

Every task's requirements implicitly include these.

- **`dev.krona.urbex.plan` may import nothing outside `dev.krona.urbex.plan` and the JDK.** No `net.minecraft`, no `dev.krona.urbex.varia`, no third-party libraries. Task 1 adds the test that enforces this; if a later task needs something from outside, the answer is to copy the small thing in, not to relax the test.
- Dependencies point **inward**: `Rng` (Minecraft-coupled) depends on `Hash` (pure). Never the reverse.
- `Rng.Purpose` must not be reordered, renamed or have constants removed — the ordinal feeds the world-generation hash. `RngTest`'s `GOLDEN`, `GOLDEN_LAST`, `PURPOSE_COUNT` and `PURPOSE_ORDER` are the tripwire.
- Everything in `plan` is deterministic: same inputs, same output, every run. No `Math.random()`, no `java.util.Random`, no `System.currentTimeMillis()`, no `HashMap` iteration order affecting results.
- All plan types are immutable. Use records where they fit.
- No changes to the existing generator. P2 ships as dead code from the mod's perspective and is wired into nothing.
- Minecraft `26.2`, Fabric Loader `0.19.3`, Fabric API `0.155.2+26.2`, Loom `1.17.17`, Java `25`. Do not bump any.
- Branch `feat/p2-city-plan`. Commit at the end of each task.

---

## File Structure

All paths under `src/main/java/dev/krona/urbex/plan/` unless noted.

| Path | Responsibility |
|---|---|
| `Hash.java` | Pure mixing. The only randomness primitive in the module. |
| `PlanPurpose.java` | Named, stable keys for independent random streams. |
| `geom/Vec2.java` | Immutable integer 2D point. |
| `geom/Rect.java` | Immutable integer axis-aligned rectangle. |
| `geom/Polygon.java` | Immutable closed ring of `Vec2`, with area and containment. |
| `PlanParams.java` | Every tunable number in one place. |
| `SettlementClass.java` | The five classes and their extents, cell sizes and spawn chances. |
| `Settlement.java` | One placed settlement: class, centre, extent. |
| `SettlementMap.java` | `(seed, chunkX, chunkZ)` → which settlement, if any. |
| `TerrainSampler.java` | The two questions the planner may ask about the world. |
| `terrain/` | `FlatTerrain`, `HillTerrain`, `RiverTerrain`, `CoastTerrain`, `CliffTerrain`. |
| `road/RoadClass.java` | arterial / collector / local. |
| `road/RoadNode.java`, `road/RoadEdge.java`, `road/RoadGraph.java` | The network. |
| `road/ArterialGrowth.java` | Grows the skeleton. |
| `road/BridgeDetector.java` | Marks edges that cross water or a ravine. |
| `block/CityBlock.java`, `block/BlockExtractor.java` | Enclosed faces of the road graph. |
| `district/District.java`, `district/DistrictMap.java` | Concentric assignment. |
| `lot/Lot.java`, `lot/LotSubdivider.java` | Lots within blocks. |
| `CityPlan.java` | The assembled immutable result. |
| `Planner.java` | `plan(Settlement, TerrainSampler, PlanParams)` → `CityPlan`. |
| `PlanQuery.java` | Runtime face, with the plan cache. |
| `PlanJson.java` | `CityPlan` → JSON string. |
| `src/main/java/dev/krona/urbex/varia/Rng.java` | Modified: delegates to `Hash`. |
| `src/test/java/dev/krona/urbex/plan/**` | Tests, mirroring the above. |
| `viewer/plan-viewer.html` (repo root) | Self-contained viewer. Not shipped in the jar. |

---

### Task 1: Pure foundation — `Hash`, purity enforcement, geometry

Establishes the module and the property that makes it worth having. No planning logic yet.

**Files:**
- Create: `plan/Hash.java`, `plan/PlanPurpose.java`, `plan/geom/Vec2.java`, `plan/geom/Rect.java`, `plan/geom/Polygon.java`
- Modify: `varia/Rng.java`
- Test: `src/test/java/dev/krona/urbex/plan/PurityTest.java`, `plan/HashTest.java`, `plan/geom/GeomTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `Hash.at(long seed, int x, int z, long key) -> long`
  - `Hash.atPos(long seed, int x, int y, int z, long key) -> long`
  - `Hash.atSlot(long seed, int x, int z, long slot, long key) -> long`
  - `Hash.index(long h, int bound) -> int`
  - `Hash.unit(long h) -> float`
  - `Hash.mix(long z) -> long`
  - `PlanPurpose` enum with `long key()`
  - `Vec2(int x, int z)`, `Rect(int minX, int minZ, int maxX, int maxZ)`, `Polygon(List<Vec2> ring)`

- [ ] **Step 1: Write the purity test first — it is the point of the task**

Create `src/test/java/dev/krona/urbex/plan/PurityTest.java`:

```java
package dev.krona.urbex.plan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan module's whole value is that it can be iterated in seconds without Minecraft. That
 * property does not survive contact with a codebase unless something enforces it: one convenient
 * import of a Minecraft type, and the module needs a game to test.
 */
class PurityTest {

    private static final Path PLAN_SRC = Path.of("src/main/java/dev/krona/urbex/plan");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)");

    private static final List<String> ALLOWED_PREFIXES = List.of(
            "dev.krona.urbex.plan.",
            "java.",
            "javax."
    );

    @Test
    void planModuleImportsNothingOutsideItselfAndTheJdk() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PLAN_SRC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher m = IMPORT.matcher(line.strip());
                    if (!m.find()) {
                        continue;
                    }
                    String imported = m.group(1);
                    boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(imported::startsWith);
                    if (!allowed) {
                        violations.add(file.getFileName() + " imports " + imported);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "plan must not depend on anything outside itself and the JDK:\n  "
                        + String.join("\n  ", violations));
    }
}
```

Note this reads source rather than bytecode, so it also catches an import that a later refactor makes unused-but-present. `javax.` is allowed only because `javax.annotation.Nullable` is already a compileOnly dependency elsewhere; if nothing in `plan` needs it, tighten the list to `java.` alone.

- [ ] **Step 2: Run it and watch it fail on a missing directory**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.PurityTest'
```
Expected: FAIL — `NoSuchFileException: src/main/java/dev/krona/urbex/plan`. That is the correct first failure.

- [ ] **Step 3: Write `HashTest` pinning the values `Rng` must keep producing**

This is the extraction's safety net. Create `src/test/java/dev/krona/urbex/plan/HashTest.java`:

```java
package dev.krona.urbex.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashTest {

    @Test
    void sameInputsGiveTheSameHash() {
        assertEquals(Hash.at(1L, 2, 3, 4L), Hash.at(1L, 2, 3, 4L));
        assertEquals(Hash.atPos(1L, 2, 3, 4, 5L), Hash.atPos(1L, 2, 3, 4, 5L));
    }

    @Test
    void eachArgumentChangesTheHash() {
        long base = Hash.at(1L, 2, 3, 4L);
        assertNotEquals(base, Hash.at(9L, 2, 3, 4L));
        assertNotEquals(base, Hash.at(1L, 9, 3, 4L));
        assertNotEquals(base, Hash.at(1L, 2, 9, 4L));
        assertNotEquals(base, Hash.at(1L, 2, 3, 9L));
    }

    @Test
    void xAndZAreNotInterchangeable() {
        assertNotEquals(Hash.at(1L, 5, 9, 1L), Hash.at(1L, 9, 5, 1L));
    }

    @Test
    void negativeCoordinatesDoNotAliasOntoPositiveOnes() {
        assertNotEquals(Hash.at(1L, -3, 7, 1L), Hash.at(1L, 3, 7, 1L));
        assertNotEquals(Hash.atPos(1L, -3, 7, -11, 1L), Hash.atPos(1L, 3, 7, 11, 1L));
    }

    @Test
    void indexStaysInBounds() {
        for (int i = 0; i < 5000; i++) {
            int v = Hash.index(Hash.at(7L, i, -i, 3L), 16);
            assertTrue(v >= 0 && v < 16, "index out of bounds: " + v);
        }
    }

    @Test
    void indexReachesBothEnds() {
        boolean sawLow = false;
        boolean sawHigh = false;
        for (int i = 0; i < 5000 && !(sawLow && sawHigh); i++) {
            int v = Hash.index(Hash.at(7L, i, 0, 3L), 8);
            sawLow |= v == 0;
            sawHigh |= v == 7;
        }
        assertTrue(sawLow && sawHigh, "index never reached both ends of its range");
    }

    @Test
    void unitStaysInRange() {
        for (int i = 0; i < 5000; i++) {
            float v = Hash.unit(Hash.at(7L, i, i, 2L));
            assertTrue(v >= 0.0f && v < 1.0f, "unit out of range: " + v);
        }
    }

    @Test
    void unitIsRoughlyUniform() {
        int[] buckets = new int[10];
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            buckets[Math.min(9, (int) (Hash.unit(Hash.at(11L, i, 0, 1L)) * 10))]++;
        }
        for (int b = 0; b < 10; b++) {
            // Each bucket should hold ~10%. A 3x tolerance catches a broken extractor without
            // being flaky: a correct one lands within a fraction of a percent at this n.
            assertTrue(buckets[b] > n / 30 && buckets[b] < n / 3,
                    "bucket " + b + " held " + buckets[b] + " of " + n);
        }
    }
}
```

- [ ] **Step 4: Implement `Hash`, reproducing `Rng`'s mixing exactly**

Create `src/main/java/dev/krona/urbex/plan/Hash.java`. The constants and the order of operations are copied verbatim from `Rng` — **do not "clean up" the sequence**, the world's entire generated output depends on it:

```java
package dev.krona.urbex.plan;

/**
 * The pure mixing behind every addressed random value in Urbex.
 * <p>
 * This lives in {@code plan} rather than in {@code varia} on purpose: dependencies must point
 * <em>into</em> the pure module, never out of it. {@code Rng} is Minecraft-coupled and depends on
 * this; this depends on nothing. Putting it beside {@code Rng} would mean the plan module imported
 * from a package that is not itself pure, and the purity test would keep passing while the property
 * it protects quietly stopped holding.
 * <p>
 * The constants and the order of operations are load-bearing. They reproduce what {@code Rng}
 * produced before the extraction, and {@code RngTest}'s pinned golden vectors fail if a single bit
 * moves.
 */
public final class Hash {

    private Hash() {
    }

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long X_MULTIPLIER = 0x9E3779B97F4A7C15L;
    private static final long Z_MULTIPLIER = 0xC2B2AE3D27D4EB4FL;
    private static final long KEY_MULTIPLIER = 0x165667B19E3779F9L;

    /** Addressed by a 2D cell or chunk coordinate. */
    public static long at(long seed, int x, int z, long key) {
        long h = mix(seed);
        h = mix(h ^ (x * X_MULTIPLIER));
        h = mix(h ^ (z * Z_MULTIPLIER));
        h = mix(h ^ (key * KEY_MULTIPLIER));
        return h;
    }

    /** Addressed by a 3D block position. */
    public static long atPos(long seed, int x, int y, int z, long key) {
        long h = mix(seed);
        h = mix(h ^ (x * X_MULTIPLIER));
        h = mix(h ^ (y * GOLDEN_GAMMA));
        h = mix(h ^ (z * Z_MULTIPLIER));
        h = mix(h ^ (key * KEY_MULTIPLIER));
        return h;
    }

    /** Addressed by a 2D coordinate plus an arbitrary slot within it. */
    public static long atSlot(long seed, int x, int z, long slot, long key) {
        long h = mix(seed);
        h = mix(h ^ (x * X_MULTIPLIER));
        h = mix(h ^ (z * Z_MULTIPLIER));
        h = mix(h ^ (slot * GOLDEN_GAMMA));
        h = mix(h ^ (key * KEY_MULTIPLIER));
        return h;
    }

    /** A value in {@code [0, bound)}. Multiply-shift over the top 32 bits: no division, no bias worth the name. */
    public static int index(long h, int bound) {
        return (int) (((h >>> 32) * bound) >>> 32);
    }

    /** A value in {@code [0, 1)}, using the top 24 bits — the same width as {@code nextFloat()}. */
    public static float unit(long h) {
        return (h >>> 40) * 0x1.0p-24f;
    }

    /** splitmix64 finalizer. */
    public static long mix(long z) {
        z += GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
```

- [ ] **Step 5: Make `Rng` delegate, then prove nothing moved**

In `src/main/java/dev/krona/urbex/varia/Rng.java`, replace the five bodies and delete the now-duplicated private helpers and constants. `Rng`'s `Purpose` enum, its javadoc and its ordinal contract are untouched:

```java
public static RandomSource at(long worldSeed, int chunkX, int chunkZ, Purpose purpose) {
    return new XoroshiroRandomSource(Hash.at(worldSeed, chunkX, chunkZ, key(purpose)));
}

public static RandomSource atPos(long worldSeed, int x, int y, int z, Purpose purpose) {
    return new XoroshiroRandomSource(Hash.atPos(worldSeed, x, y, z, key(purpose)));
}

public static int indexAtPos(long worldSeed, int x, int y, int z, Purpose purpose, int bound) {
    return Hash.index(Hash.atPos(worldSeed, x, y, z, key(purpose)), bound);
}

public static float floatAtPos(long worldSeed, int x, int y, int z, Purpose purpose) {
    return Hash.unit(Hash.atPos(worldSeed, x, y, z, key(purpose)));
}

public static RandomSource atSlot(long worldSeed, int chunkX, int chunkZ, long slot, Purpose purpose) {
    return new XoroshiroRandomSource(Hash.atSlot(worldSeed, chunkX, chunkZ, slot, key(purpose)));
}

/** The enum's ordinal is what the hash actually consumes; +1 so the first constant is not zero. */
private static long key(Purpose purpose) {
    return purpose.ordinal() + 1L;
}
```

Add `import dev.krona.urbex.plan.Hash;`. Delete `Rng`'s private `mix` and `hashPos`, and its `GOLDEN_GAMMA` / `X_MULTIPLIER` / `Z_MULTIPLIER` / `PURPOSE_MULTIPLIER` constants — they now live in `Hash` and two copies will drift.

- [ ] **Step 6: Run the full suite — this is the moment the extraction is proved**

```bash
./gradlew test
```
Expected: PASS, including `RngTest.streamIsStableAcrossRuns` and `streamIsStableForTheLastPurpose`. **If either golden vector fails, the extraction changed the mixing — revert and redo it; do not regenerate the vectors.** Their whole purpose is to fail here.

- [ ] **Step 7: Add `PlanPurpose`**

Create `src/main/java/dev/krona/urbex/plan/PlanPurpose.java`:

```java
package dev.krona.urbex.plan;

/**
 * Named keys for independent random streams inside the planner.
 * <p>
 * Same discipline as {@code Rng.Purpose}: two logically independent decisions taken at the same
 * address under the same key get the identical stream and silently correlate. Give a new decision
 * a new constant rather than reusing a neighbour's.
 * <p>
 * Keys are offset well clear of {@code Rng.Purpose}'s range so that a plan stream and a
 * world-generation stream can never coincide even where their coordinate spaces overlap.
 */
public enum PlanPurpose {
    SETTLEMENT_EXISTS,
    SETTLEMENT_JITTER_X,
    SETTLEMENT_JITTER_Z,
    SETTLEMENT_STYLE,
    SPOKE_COUNT,
    SPOKE_ANGLE,
    SPOKE_STEP,
    RING_COUNT,
    RING_RADIUS,
    BLOCK_SPLIT_AXIS,
    BLOCK_SPLIT_POS,
    LOT_SIZE,
    LOT_JITTER,
    DISTRICT_NOISE;

    private static final long OFFSET = 1000L;

    public long key() {
        return OFFSET + ordinal();
    }
}
```

- [ ] **Step 8: Add the geometry primitives**

Create `plan/geom/Vec2.java`, `Rect.java` and `Polygon.java`. Keep them small and total — no nulls, no mutation.

```java
package dev.krona.urbex.plan.geom;

/** An immutable integer point in the XZ plane. Block coordinates, not chunk coordinates. */
public record Vec2(int x, int z) {

    public Vec2 plus(int dx, int dz) {
        return new Vec2(x + dx, z + dz);
    }

    public long distanceSquaredTo(Vec2 other) {
        long dx = (long) x - other.x;
        long dz = (long) z - other.z;
        return dx * dx + dz * dz;
    }
}
```

```java
package dev.krona.urbex.plan.geom;

/** An immutable axis-aligned rectangle in block coordinates. Bounds are inclusive. */
public record Rect(int minX, int minZ, int maxX, int maxZ) {

    public Rect {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("inverted rect: " + minX + "," + minZ + " -> " + maxX + "," + maxZ);
        }
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public int area() {
        return width() * depth();
    }

    public boolean contains(Vec2 p) {
        return p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ;
    }

    public boolean intersects(Rect other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public Vec2 center() {
        return new Vec2((minX + maxX) / 2, (minZ + maxZ) / 2);
    }
}
```

```java
package dev.krona.urbex.plan.geom;

import java.util.List;

/**
 * An immutable closed ring of points. The ring is implicitly closed — the last point connects back
 * to the first, and must not repeat it.
 */
public record Polygon(List<Vec2> ring) {

    public Polygon {
        if (ring.size() < 3) {
            throw new IllegalArgumentException("polygon needs at least 3 points, got " + ring.size());
        }
        ring = List.copyOf(ring);
    }

    /** Twice the signed area. Positive is counter-clockwise. Exact in long arithmetic. */
    public long signedDoubleArea() {
        long total = 0;
        for (int i = 0; i < ring.size(); i++) {
            Vec2 a = ring.get(i);
            Vec2 b = ring.get((i + 1) % ring.size());
            total += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        return total;
    }

    public boolean isCounterClockwise() {
        return signedDoubleArea() > 0;
    }

    /** Even-odd ray casting. Points exactly on an edge are not guaranteed either way. */
    public boolean contains(Vec2 p) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            Vec2 a = ring.get(i);
            Vec2 b = ring.get(j);
            if ((a.z() > p.z()) != (b.z() > p.z())) {
                long cross = (long) (b.x() - a.x()) * (p.z() - a.z())
                        - (long) (p.x() - a.x()) * (b.z() - a.z());
                boolean bAbove = b.z() > a.z();
                if ((cross > 0) == bAbove) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    public Rect boundingBox() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vec2 p : ring) {
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
        }
        return new Rect(minX, minZ, maxX, maxZ);
    }
}
```

- [ ] **Step 9: Test the geometry**

Create `src/test/java/dev/krona/urbex/plan/geom/GeomTest.java`:

```java
package dev.krona.urbex.plan.geom;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeomTest {

    @Test
    void rectMeasuresInclusiveBounds() {
        Rect r = new Rect(0, 0, 15, 15);
        assertEquals(16, r.width());
        assertEquals(16, r.depth());
        assertEquals(256, r.area());
    }

    @Test
    void rectRejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Rect(10, 0, 0, 10));
    }

    @Test
    void rectContainmentIncludesItsEdges() {
        Rect r = new Rect(0, 0, 10, 10);
        assertTrue(r.contains(new Vec2(0, 0)));
        assertTrue(r.contains(new Vec2(10, 10)));
        assertFalse(r.contains(new Vec2(11, 5)));
    }

    @Test
    void rectsTouchingAtAnEdgeIntersect() {
        assertTrue(new Rect(0, 0, 10, 10).intersects(new Rect(10, 10, 20, 20)));
        assertFalse(new Rect(0, 0, 10, 10).intersects(new Rect(11, 11, 20, 20)));
    }

    @Test
    void polygonAreaSignIndicatesWinding() {
        List<Vec2> ccw = List.of(new Vec2(0, 0), new Vec2(10, 0), new Vec2(10, 10), new Vec2(0, 10));
        assertTrue(new Polygon(ccw).isCounterClockwise());
        assertFalse(new Polygon(ccw.reversed()).isCounterClockwise());
    }

    @Test
    void polygonAreaIsExactForASquare() {
        Polygon square = new Polygon(List.of(
                new Vec2(0, 0), new Vec2(10, 0), new Vec2(10, 10), new Vec2(0, 10)));
        assertEquals(200, Math.abs(square.signedDoubleArea()));
    }

    @Test
    void polygonContainmentHandlesAConcaveShape() {
        // An L-shape: the notch must be outside.
        Polygon l = new Polygon(List.of(
                new Vec2(0, 0), new Vec2(20, 0), new Vec2(20, 10),
                new Vec2(10, 10), new Vec2(10, 20), new Vec2(0, 20)));
        assertTrue(l.contains(new Vec2(5, 5)));
        assertTrue(l.contains(new Vec2(5, 15)));
        assertFalse(l.contains(new Vec2(15, 15)));
    }

    @Test
    void polygonRejectsDegenerateRings() {
        assertThrows(IllegalArgumentException.class,
                () -> new Polygon(List.of(new Vec2(0, 0), new Vec2(1, 1))));
    }
}
```

- [ ] **Step 10: Run everything and commit**

```bash
./gradlew test
```
Expected: PASS. `PurityTest` now finds the directory and reports no violations.

```bash
git add -A
git commit -m "feat(plan): pure foundation - Hash, purity enforcement, geometry

Extracts Rng's mixing into dev.krona.urbex.plan.Hash so the plan module can
depend on it without depending on Minecraft. Hash lives inside plan rather
than beside Rng because dependencies must point into the pure module: Rng
depends on Hash, Hash depends on nothing.

PurityTest fails the build if anything in plan imports outside plan and the
JDK. RngTest's golden vectors are unchanged and still pass, which is what
proves the extraction moved no bits."
```

---

### Task 2: Settlement placement

Where settlements are and what size they are, as a pure function of the seed. No layout yet.

**Files:**
- Create: `plan/SettlementClass.java`, `plan/Settlement.java`, `plan/SettlementMap.java`, `plan/PlanParams.java`
- Test: `src/test/java/dev/krona/urbex/plan/SettlementMapTest.java`

**Interfaces:**
- Consumes: `Hash.at`, `Hash.index`, `Hash.unit`, `PlanPurpose`, `Vec2`, `Rect`.
- Produces:
  - `SettlementClass` enum: `HAMLET`, `VILLAGE`, `TOWN`, `CITY`, `METROPOLIS`, each with `extentChunks()`, `cellSizeChunks()`, `spawnChance()`
  - `Settlement(SettlementClass cls, int centerChunkX, int centerChunkZ)` with `extentChunks()`, `boundsChunks() -> Rect`, `centerBlock() -> Vec2`, `radiusBlocks() -> int`
  - `SettlementMap.at(long seed, int chunkX, int chunkZ, PlanParams params) -> Settlement` (null when none)
  - `PlanParams` record with defaults via `PlanParams.defaults()`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/dev/krona/urbex/plan/SettlementMapTest.java`:

```java
package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementMapTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final long SEED = 1337L;

    @Test
    void theSameCoordinateAlwaysGivesTheSameAnswer() {
        for (int i = 0; i < 200; i++) {
            Settlement a = SettlementMap.at(SEED, i, -i, P);
            Settlement b = SettlementMap.at(SEED, i, -i, P);
            assertEquals(a, b, "settlement at " + i + "," + -i + " was not stable");
        }
    }

    @Test
    void differentSeedsPlaceSettlementsDifferently() {
        List<Settlement> a = scan(SEED, 400);
        List<Settlement> b = scan(9999L, 400);
        assertTrue(!a.equals(b), "two seeds produced identical settlement placement");
    }

    @Test
    void everyChunkOfASettlementReportsThatSameSettlement() {
        Settlement found = firstSettlement(SEED, 2000);
        assertNotNull(found, "no settlement found while scanning 2000 chunks");
        Rect bounds = found.boundsChunks();
        for (int cx = bounds.minX(); cx <= bounds.maxX(); cx++) {
            for (int cz = bounds.minZ(); cz <= bounds.maxZ(); cz++) {
                assertEquals(found, SettlementMap.at(SEED, cx, cz, P),
                        "chunk " + cx + "," + cz + " inside the settlement reported something else");
            }
        }
    }

    @Test
    void settlementsNeverOverlap() {
        Set<Settlement> seen = new HashSet<>();
        for (int cx = -600; cx < 600; cx += 2) {
            for (int cz = -600; cz < 600; cz += 2) {
                Settlement s = SettlementMap.at(SEED, cx, cz, P);
                if (s != null) {
                    seen.add(s);
                }
            }
        }
        List<Settlement> all = new ArrayList<>(seen);
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertTrue(!all.get(i).boundsChunks().intersects(all.get(j).boundsChunks()),
                        "overlapping settlements: " + all.get(i) + " and " + all.get(j));
            }
        }
    }

    @Test
    void smallerClassesAreMoreCommonThanLargerOnes() {
        Map<SettlementClass, Integer> counts = new EnumMap<>(SettlementClass.class);
        Set<Settlement> seen = new HashSet<>();
        for (int cx = -800; cx < 800; cx += 2) {
            for (int cz = -800; cz < 800; cz += 2) {
                Settlement s = SettlementMap.at(SEED, cx, cz, P);
                if (s != null && seen.add(s)) {
                    counts.merge(s.cls(), 1, Integer::sum);
                }
            }
        }
        assertTrue(counts.getOrDefault(SettlementClass.HAMLET, 0)
                        > counts.getOrDefault(SettlementClass.TOWN, 0),
                "hamlets should outnumber towns, got " + counts);
        assertTrue(counts.getOrDefault(SettlementClass.TOWN, 0)
                        >= counts.getOrDefault(SettlementClass.METROPOLIS, 0),
                "towns should be at least as common as metropolises, got " + counts);
    }

    @Test
    void aSettlementNeverLeavesItsOwnCell() {
        Set<Settlement> seen = new HashSet<>();
        for (int cx = -400; cx < 400; cx += 2) {
            for (int cz = -400; cz < 400; cz += 2) {
                Settlement s = SettlementMap.at(SEED, cx, cz, P);
                if (s != null) {
                    seen.add(s);
                }
            }
        }
        for (Settlement s : seen) {
            int cell = s.cls().cellSizeChunks();
            Rect b = s.boundsChunks();
            assertEquals(Math.floorDiv(b.minX(), cell), Math.floorDiv(b.maxX(), cell),
                    s + " spans two cells in x");
            assertEquals(Math.floorDiv(b.minZ(), cell), Math.floorDiv(b.maxZ(), cell),
                    s + " spans two cells in z");
        }
    }

    private static List<Settlement> scan(long seed, int span) {
        List<Settlement> out = new ArrayList<>();
        for (int cx = 0; cx < span; cx += 4) {
            for (int cz = 0; cz < span; cz += 4) {
                out.add(SettlementMap.at(seed, cx, cz, P));
            }
        }
        return out;
    }

    private static Settlement firstSettlement(long seed, int span) {
        for (int cx = 0; cx < span; cx++) {
            for (int cz = 0; cz < span; cz++) {
                Settlement s = SettlementMap.at(seed, cx, cz, P);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run and watch it fail**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.SettlementMapTest'
```
Expected: FAIL — `SettlementClass` and friends do not exist.

- [ ] **Step 3: Implement `SettlementClass`**

```java
package dev.krona.urbex.plan;

/**
 * The settlement size classes, each placed on its own lattice.
 * <p>
 * One lattice per class rather than one lattice with a size roll: cell size controls how common a
 * class is, so hamlets can be scattered everywhere while a metropolis is a landmark, and each
 * class's density is tuned independently. The cell must be at least twice the extent so a jittered
 * centre can never push a settlement out of its own cell, which is what makes non-overlap a
 * property of the construction rather than something to check for afterwards.
 */
public enum SettlementClass {

    HAMLET(2, 24, 0.55f),
    VILLAGE(4, 48, 0.45f),
    TOWN(12, 128, 0.35f),
    CITY(32, 384, 0.30f),
    METROPOLIS(96, 1024, 0.25f);

    private final int extentChunks;
    private final int cellSizeChunks;
    private final float spawnChance;

    SettlementClass(int extentChunks, int cellSizeChunks, float spawnChance) {
        if (cellSizeChunks < extentChunks * 2) {
            throw new IllegalArgumentException(name() + ": cell must be at least twice the extent");
        }
        this.extentChunks = extentChunks;
        this.cellSizeChunks = cellSizeChunks;
        this.spawnChance = spawnChance;
    }

    public int extentChunks() {
        return extentChunks;
    }

    public int cellSizeChunks() {
        return cellSizeChunks;
    }

    public float spawnChance() {
        return spawnChance;
    }
}
```

- [ ] **Step 4: Implement `Settlement` and `PlanParams`**

```java
package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;

/** One placed settlement. Its bounds are a square of {@code extentChunks} centred on its centre chunk. */
public record Settlement(SettlementClass cls, int centerChunkX, int centerChunkZ) {

    public int extentChunks() {
        return cls.extentChunks();
    }

    public Rect boundsChunks() {
        int half = cls.extentChunks() / 2;
        return new Rect(centerChunkX - half, centerChunkZ - half,
                centerChunkX + half, centerChunkZ + half);
    }

    public Vec2 centerBlock() {
        return new Vec2(centerChunkX * 16 + 8, centerChunkZ * 16 + 8);
    }

    public int radiusBlocks() {
        return (cls.extentChunks() * 16) / 2;
    }
}
```

```java
package dev.krona.urbex.plan;

/**
 * Every tunable number in one place, so that tuning by eye in the viewer does not mean hunting
 * constants through six files. P5 makes these datapack-driven; P2 only has to avoid scattering them.
 */
public record PlanParams(
        int spokeCountMin,
        int spokeCountMax,
        int ringCountMin,
        int ringCountMax,
        int segmentLengthBlocks,
        int snapRadiusBlocks,
        int maxSlopePerSegment,
        int maxBridgeSpanBlocks,
        int minBlockAreaBlocks,
        int maxLotDepthBlocks,
        int coreLotSizeBlocks,
        int fringeLotSizeBlocks
) {
    public static PlanParams defaults() {
        return new PlanParams(
                3, 8,          // spokes
                1, 3,          // rings
                48,            // segment length
                24,            // snap radius
                6,             // max slope per segment
                64,            // max bridge span
                256,           // min block area
                40,            // max lot depth before an alley is needed
                12,            // core lot size
                28             // fringe lot size
        );
    }
}
```

- [ ] **Step 5: Implement `SettlementMap`**

```java
package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Vec2;

/**
 * Which settlement, if any, covers a chunk.
 * <p>
 * Classes are tested largest first, so where two classes' cells would both produce a settlement
 * covering this chunk, the larger wins and the smaller is simply never reported. That is the whole
 * conflict rule; because a settlement can never leave its own cell, nothing else is needed.
 */
public final class SettlementMap {

    private SettlementMap() {
    }

    private static final SettlementClass[] LARGEST_FIRST = {
            SettlementClass.METROPOLIS,
            SettlementClass.CITY,
            SettlementClass.TOWN,
            SettlementClass.VILLAGE,
            SettlementClass.HAMLET
    };

    /** The settlement covering this chunk, or {@code null}. */
    public static Settlement at(long seed, int chunkX, int chunkZ, PlanParams params) {
        for (SettlementClass cls : LARGEST_FIRST) {
            Settlement s = candidateFor(seed, chunkX, chunkZ, cls);
            if (s != null && s.boundsChunks().contains(new Vec2(chunkX, chunkZ))) {
                return s;
            }
        }
        return null;
    }

    /** The settlement of {@code cls} owned by the cell containing this chunk, or {@code null}. */
    private static Settlement candidateFor(long seed, int chunkX, int chunkZ, SettlementClass cls) {
        int cell = cls.cellSizeChunks();
        int cellX = Math.floorDiv(chunkX, cell);
        int cellZ = Math.floorDiv(chunkZ, cell);
        long key = cls.ordinal() * 100L;

        if (Hash.unit(Hash.at(seed, cellX, cellZ, key + PlanPurpose.SETTLEMENT_EXISTS.key()))
                >= cls.spawnChance()) {
            return null;
        }

        // Jitter within the cell, keeping half the extent clear of every edge so the settlement
        // cannot cross into a neighbouring cell.
        int margin = cls.extentChunks() / 2 + 1;
        int span = cell - 2 * margin;
        int offsetX = Hash.index(Hash.at(seed, cellX, cellZ, key + PlanPurpose.SETTLEMENT_JITTER_X.key()), span);
        int offsetZ = Hash.index(Hash.at(seed, cellX, cellZ, key + PlanPurpose.SETTLEMENT_JITTER_Z.key()), span);

        return new Settlement(cls, cellX * cell + margin + offsetX, cellZ * cell + margin + offsetZ);
    }
}
```

Note the `key + PlanPurpose...key()` composition: the class ordinal shifts each class onto its own key range, so two classes never draw the same stream at the same cell coordinates.

- [ ] **Step 6: Run the tests**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.SettlementMapTest'
```
Expected: PASS, all six. If `settlementsNeverOverlap` fails, the margin arithmetic in `candidateFor` is wrong — that is the invariant it exists to protect.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(plan): settlement placement, one lattice per size class

Cell size controls how common a class is, so hamlets scatter everywhere and
a metropolis is a landmark. A jitter margin of half the extent means a
settlement can never leave its own cell, which makes non-overlap a property
of the construction rather than a check. Classes resolve largest-first, so a
metropolis simply hides the hamlets inside it."
```

---

### Task 3: Terrain and the arterial road skeleton

**Files:**
- Create: `plan/TerrainSampler.java`, `plan/terrain/{FlatTerrain,HillTerrain,RiverTerrain,CoastTerrain,CliffTerrain}.java`, `plan/road/{RoadClass,RoadNode,RoadEdge,RoadGraph,ArterialGrowth,BridgeDetector}.java`
- Test: `src/test/java/dev/krona/urbex/plan/road/ArterialGrowthTest.java`, `plan/road/BridgeDetectorTest.java`

**Interfaces:**
- Consumes: `Hash`, `PlanPurpose`, `PlanParams`, `Settlement`, `Vec2`.
- Produces:
  - `TerrainSampler` with `int heightAt(int x, int z)` and `boolean isWaterAt(int x, int z)`
  - `RoadGraph` with `List<RoadNode> nodes()`, `List<RoadEdge> edges()`, `boolean isConnected()`
  - `RoadNode(int id, Vec2 pos)`
  - `RoadEdge(int fromId, int toId, RoadClass cls, boolean bridge, int waterSpanBlocks)`
  - `ArterialGrowth.grow(long seed, Settlement s, TerrainSampler t, PlanParams p) -> RoadGraph`
  - `BridgeDetector.mark(RoadGraph g, TerrainSampler t, PlanParams p) -> RoadGraph`

- [ ] **Step 1: Write `TerrainSampler` and the fakes**

```java
package dev.krona.urbex.plan;

/**
 * The only thing the planner may ask about the world.
 * <p>
 * Deliberately two methods. A real implementation must answer from the terrain <em>function</em> —
 * the noise or heightmap query, a pure function of the world seed — and never from placed blocks.
 * Reading placed blocks is the mechanism behind issue #18, where vanilla vegetation bleeding across
 * a chunk border changed what a fill loop saw; the same mistake at the planning layer would make
 * whole road networks depend on chunk generation order.
 */
public interface TerrainSampler {

    int heightAt(int x, int z);

    boolean isWaterAt(int x, int z);
}
```

The five fakes, each a small deterministic function — no randomness, so tests can reason about them exactly:

```java
package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** Featureless ground at a constant height. The control case. */
public record FlatTerrain(int height) implements TerrainSampler {
    @Override public int heightAt(int x, int z) { return height; }
    @Override public boolean isWaterAt(int x, int z) { return false; }
}
```

```java
package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** A single smooth hill centred at the origin, falling off linearly to {@code base}. */
public record HillTerrain(int base, int peak, int radius) implements TerrainSampler {
    @Override public int heightAt(int x, int z) {
        double d = Math.sqrt((double) x * x + (double) z * z);
        if (d >= radius) {
            return base;
        }
        return base + (int) ((peak - base) * (1.0 - d / radius));
    }
    @Override public boolean isWaterAt(int x, int z) { return false; }
}
```

```java
package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** A straight river of {@code width} blocks running along the z axis at x = {@code atX}. */
public record RiverTerrain(int groundHeight, int atX, int width) implements TerrainSampler {
    @Override public int heightAt(int x, int z) {
        return isWaterAt(x, z) ? groundHeight - 4 : groundHeight;
    }
    @Override public boolean isWaterAt(int x, int z) {
        return Math.abs(x - atX) * 2 < width;
    }
    /** True when the segment from a to b crosses the river channel. */
    public boolean crosses(int x1, int x2) {
        return (x1 < atX) != (x2 < atX);
    }
}
```

```java
package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** Land where x is below {@code shoreX}, open water beyond it. */
public record CoastTerrain(int groundHeight, int shoreX) implements TerrainSampler {
    @Override public int heightAt(int x, int z) {
        return x < shoreX ? groundHeight : groundHeight - 8;
    }
    @Override public boolean isWaterAt(int x, int z) { return x >= shoreX; }
}
```

```java
package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** A sheer step of {@code rise} blocks at x = {@code atX}. Nothing may road up it. */
public record CliffTerrain(int lowHeight, int rise, int atX) implements TerrainSampler {
    @Override public int heightAt(int x, int z) {
        return x < atX ? lowHeight : lowHeight + rise;
    }
    @Override public boolean isWaterAt(int x, int z) { return false; }
}
```

- [ ] **Step 2: Write the failing road tests**

Create `src/test/java/dev/krona/urbex/plan/road/ArterialGrowthTest.java`:

```java
package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.terrain.CliffTerrain;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArterialGrowthTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final Settlement TOWN = new Settlement(SettlementClass.TOWN, 0, 0);

    @Test
    void growthIsDeterministic() {
        RoadGraph a = ArterialGrowth.grow(1L, TOWN, new FlatTerrain(64), P);
        RoadGraph b = ArterialGrowth.grow(1L, TOWN, new FlatTerrain(64), P);
        assertEquals(a.edges(), b.edges());
        assertEquals(a.nodes(), b.nodes());
    }

    @Test
    void differentSeedsGrowDifferentNetworks() {
        RoadGraph a = ArterialGrowth.grow(1L, TOWN, new FlatTerrain(64), P);
        RoadGraph b = ArterialGrowth.grow(2L, TOWN, new FlatTerrain(64), P);
        assertTrue(!a.edges().equals(b.edges()), "two seeds grew the identical network");
    }

    @Test
    void theNetworkIsConnected() {
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, new FlatTerrain(64), P);
            assertTrue(g.isConnected(), "seed " + seed + " produced a disconnected network");
        }
    }

    @Test
    void everyNodeStaysInsideTheSettlement() {
        int r = TOWN.radiusBlocks();
        Vec2 c = TOWN.centerBlock();
        for (long seed = 0; seed < 25; seed++) {
            for (RoadNode n : ArterialGrowth.grow(seed, TOWN, new FlatTerrain(64), P).nodes()) {
                assertTrue(Math.abs(n.pos().x() - c.x()) <= r && Math.abs(n.pos().z() - c.z()) <= r,
                        "seed " + seed + " put node " + n.pos() + " outside the settlement");
            }
        }
    }

    @Test
    void aBiggerClassGrowsABiggerNetwork() {
        int town = ArterialGrowth.grow(5L, TOWN, new FlatTerrain(64), P).edges().size();
        int city = ArterialGrowth.grow(5L, new Settlement(SettlementClass.CITY, 0, 0),
                new FlatTerrain(64), P).edges().size();
        assertTrue(city > town, "a city (" + city + " edges) should out-grow a town (" + town + ")");
    }

    @Test
    void noRoadClimbsACliff() {
        TerrainSampler cliff = new CliffTerrain(64, 40, 0);
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, cliff, P);
            for (RoadEdge e : g.edges()) {
                if (e.bridge()) {
                    continue;
                }
                Vec2 a = g.nodeAt(e.fromId()).pos();
                Vec2 b = g.nodeAt(e.toId()).pos();
                int slope = Math.abs(cliff.heightAt(a.x(), a.z()) - cliff.heightAt(b.x(), b.z()));
                assertTrue(slope <= P.maxSlopePerSegment(),
                        "seed " + seed + " ran a road up a slope of " + slope);
            }
        }
    }

    @Test
    void roadsDoNotRunAlongARiverbed() {
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, river, P);
            for (RoadEdge e : g.edges()) {
                Vec2 a = g.nodeAt(e.fromId()).pos();
                Vec2 b = g.nodeAt(e.toId()).pos();
                boolean bothInWater = river.isWaterAt(a.x(), a.z()) && river.isWaterAt(b.x(), b.z());
                assertTrue(!bothInWater,
                        "seed " + seed + " ran a road along the riverbed: " + a + " -> " + b);
            }
        }
    }
}
```

Create `src/test/java/dev/krona/urbex/plan/road/BridgeDetectorTest.java`:

```java
package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeDetectorTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final Settlement TOWN = new Settlement(SettlementClass.TOWN, 0, 0);

    @Test
    void everyEdgeCrossingTheRiverIsABridge() {
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, river, P);
            for (RoadEdge e : g.edges()) {
                Vec2 a = g.nodeAt(e.fromId()).pos();
                Vec2 b = g.nodeAt(e.toId()).pos();
                if (river.crosses(a.x(), b.x())) {
                    assertTrue(e.bridge(),
                            "seed " + seed + ": edge " + a + " -> " + b + " crosses the river but is not a bridge");
                }
            }
        }
    }

    @Test
    void nothingIsABridgeOnDryGround() {
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, new FlatTerrain(64), P);
            for (RoadEdge e : g.edges()) {
                assertTrue(!e.bridge(), "seed " + seed + " built a bridge over dry ground");
            }
        }
    }
}
```

That second test is the one that matters. Lost Cities rolls bridges from `BRIDGE_CHANCE` with no reference to terrain, which is exactly why its bridges look wrong — an elevated road over dry ground is not a bridge. This test makes that failure mode impossible to reintroduce.

- [ ] **Step 3: Run and watch them fail**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.road.*'
```
Expected: FAIL — the road package does not exist.

- [ ] **Step 4: Implement the graph types**

```java
package dev.krona.urbex.plan.road;

/** How major a road is. Drives width, and later the palette. */
public enum RoadClass {
    ARTERIAL,
    COLLECTOR,
    LOCAL
}
```

```java
package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.geom.Vec2;

public record RoadNode(int id, Vec2 pos) {
}
```

```java
package dev.krona.urbex.plan.road;

/**
 * {@code bridge} is derived from terrain by {@link BridgeDetector}, never rolled.
 * <p>
 * {@code waterSpanBlocks} is how much water the edge actually crosses, 0 when dry. Downstream
 * phases need the span, not just the flag: the current datapack's bridge is a single 16x16 chunk
 * piece, which is only correct for a river exactly that wide. A span lets P4 choose an asset, or
 * repeat a section, instead of assuming one chunk.
 */
public record RoadEdge(int fromId, int toId, RoadClass cls, boolean bridge, int waterSpanBlocks) {

    public RoadEdge asBridge(int waterSpanBlocks) {
        return new RoadEdge(fromId, toId, cls, true, waterSpanBlocks);
    }
}
```

`RoadGraph` holds the nodes and edges and answers structural questions. Give it `nodeAt(int id)`, `edgesAt(int nodeId)`, and `isConnected()` implemented as a breadth-first walk from node 0 checking every node is reached. Keep the node list indexed by id so `nodeAt` is O(1). It must be immutable — build through a small `RoadGraph.Builder` that hands back a finished graph.

- [ ] **Step 5: Implement `ArterialGrowth`**

The algorithm, stated precisely so the implementation is a transcription rather than a design exercise:

1. Place a node at the settlement centre. Roll `spokeCount` in `[spokeCountMin, spokeCountMax]` from `PlanPurpose.SPOKE_COUNT` at the settlement's centre chunk.
2. For each spoke `i`, its initial bearing is `i * 2π / spokeCount`, jittered by up to ±`π / spokeCount / 2` from `PlanPurpose.SPOKE_ANGLE` addressed at `(centreChunkX, centreChunkZ)` with slot `i`.
3. Walk outward in steps of `segmentLengthBlocks`. At each step, consider the current bearing and bearings ±15° and ±30°. Score each candidate by the absolute height difference between the current node and the candidate endpoint; reject any candidate exceeding `maxSlopePerSegment` unless it lands over water. Take the lowest-scoring survivor. If none survives, stop that spoke.
4. Stop a spoke when it would leave the settlement's radius.
5. Before adding a node, check for an existing node within `snapRadiusBlocks`; if one exists, connect to it instead and stop the spoke — that is what closes loops rather than leaving dead ends.
6. Roll `ringCount` in `[ringCountMin, ringCountMax]` from `PlanPurpose.RING_COUNT`. For each ring `r`, at radius `(r + 1) / (ringCount + 1)` of the settlement radius (jittered from `PlanPurpose.RING_RADIUS`), connect each spoke's nearest node at that radius to the next spoke's, going around.
7. Spokes are `ARTERIAL`; rings are `COLLECTOR`.

Then call `BridgeDetector.mark(...)` on the result before returning, so a caller can never forget to.

- [ ] **Step 6: Implement `BridgeDetector`**

For each edge, sample the terrain at intervals of 4 blocks along the segment. If any sample is water, the edge is a bridge, and its `waterSpanBlocks` is the length of the longest contiguous run of water samples along it — that is the number P4 needs to size a crossing. If that run is longer than `maxBridgeSpanBlocks`, the edge is instead *rejected* — return a graph without it — because a 500-block bridge is not a road, it is a mistake. Rejecting an edge may disconnect the graph, so re-run connectivity afterwards and drop any component not containing the centre node.

- [ ] **Step 7: Run the tests**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.road.*'
```
Expected: PASS, all nine.

`roadsDoNotRunAlongARiverbed` is the one most likely to fail first. If it does, step 3's scoring needs a term that penalises a candidate endpoint sitting in water without crossing it — following a river valley is exactly what a naive lowest-slope heuristic wants to do.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(plan): terrain sampler and the arterial road skeleton

Roads grow outward from the settlement centre, scoring candidate steps by
slope and snapping to nearby nodes so the network closes loops instead of
dead-ending. Terrain enters through a two-method interface with five
synthetic implementations, so road behaviour against a river, a coast and a
cliff is unit-testable without Minecraft.

Bridges are derived: an edge is a bridge because its span crosses water. A
test asserts nothing is a bridge on flat dry ground, which is the failure
mode Lost Cities has today - it rolls bridges from BRIDGE_CHANCE without
reference to whether there is anything to cross."
```

---

### Task 4: Block extraction

The enclosed faces of the road graph. Isolated as its own task because it is the hardest code in the project and a reviewer should be able to reject it on its own merits.

**Files:**
- Create: `plan/block/CityBlock.java`, `plan/block/BlockExtractor.java`
- Test: `src/test/java/dev/krona/urbex/plan/block/BlockExtractorTest.java`

**Interfaces:**
- Consumes: `RoadGraph`, `RoadNode`, `RoadEdge`, `Polygon`, `Vec2`, `PlanParams`.
- Produces:
  - `CityBlock(int id, Polygon outline)` with `Rect boundingBox()`, `long areaDoubled()`
  - `BlockExtractor.extract(RoadGraph g, PlanParams p) -> List<CityBlock>`

- [ ] **Step 1: Write the failing tests, including the degenerate cases**

```java
package dev.krona.urbex.plan.block;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.ArterialGrowth;
import dev.krona.urbex.plan.road.RoadClass;
import dev.krona.urbex.plan.road.RoadGraph;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockExtractorTest {

    private static final PlanParams P = PlanParams.defaults();

    /** Four nodes in a square, four edges. Exactly one enclosed face. */
    @Test
    void aSingleSquareYieldsOneBlock() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0))
                .node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 0, RoadClass.ARTERIAL)
                .build();
        List<CityBlock> blocks = BlockExtractor.extract(g, P);
        assertEquals(1, blocks.size(), "expected exactly one face, got " + blocks);
        assertEquals(20000, Math.abs(blocks.get(0).outline().signedDoubleArea()));
    }

    /** Two squares sharing an edge. Two faces, and the outer boundary must not become one. */
    @Test
    void twoAdjacentSquaresYieldTwoBlocks() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0)).node(new Vec2(200, 0))
                .node(new Vec2(200, 100)).node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 4, RoadClass.ARTERIAL)
                .edge(4, 5, RoadClass.ARTERIAL).edge(5, 0, RoadClass.ARTERIAL)
                .edge(1, 4, RoadClass.COLLECTOR)
                .build();
        assertEquals(2, BlockExtractor.extract(g, P).size());
    }

    /** A dangling spur encloses nothing and must not produce a zero-area face. */
    @Test
    void aDeadEndSpurProducesNoBlock() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0))
                .node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .node(new Vec2(50, 200))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 0, RoadClass.ARTERIAL)
                .edge(2, 4, RoadClass.LOCAL)
                .build();
        List<CityBlock> blocks = BlockExtractor.extract(g, P);
        assertEquals(1, blocks.size(), "the spur should not enclose anything, got " + blocks);
    }

    /** A path with no cycle at all encloses nothing. */
    @Test
    void anOpenPathProducesNoBlocks() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0)).node(new Vec2(200, 0))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .build();
        assertTrue(BlockExtractor.extract(g, P).isEmpty());
    }

    /** Three collinear nodes must not create a degenerate face. */
    @Test
    void collinearNodesDoNotCreateASliver() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(50, 0)).node(new Vec2(100, 0))
                .node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 4, RoadClass.ARTERIAL)
                .edge(4, 0, RoadClass.ARTERIAL)
                .build();
        List<CityBlock> blocks = BlockExtractor.extract(g, P);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).areaDoubled() > 0, "collinear run produced a zero-area block");
    }

    @Test
    void facesSmallerThanTheMinimumAreDropped() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(4, 0)).node(new Vec2(4, 4)).node(new Vec2(0, 4))
                .edge(0, 1, RoadClass.LOCAL).edge(1, 2, RoadClass.LOCAL)
                .edge(2, 3, RoadClass.LOCAL).edge(3, 0, RoadClass.LOCAL)
                .build();
        // 4x4 = 16 blocks of area, far under minBlockAreaBlocks (256).
        assertTrue(BlockExtractor.extract(g, P).isEmpty());
    }

    @Test
    void blocksFromARealNetworkDoNotOverlap() {
        Settlement town = new Settlement(SettlementClass.TOWN, 0, 0);
        for (long seed = 0; seed < 20; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, town, new FlatTerrain(64), P);
            List<CityBlock> blocks = BlockExtractor.extract(g, P);
            for (int i = 0; i < blocks.size(); i++) {
                for (int j = i + 1; j < blocks.size(); j++) {
                    Vec2 c = blocks.get(i).outline().boundingBox().center();
                    assertTrue(!blocks.get(j).outline().contains(c),
                            "seed " + seed + ": block " + i + " centre lies inside block " + j);
                }
            }
        }
    }

    @Test
    void extractionIsDeterministic() {
        Settlement town = new Settlement(SettlementClass.TOWN, 0, 0);
        RoadGraph g = ArterialGrowth.grow(3L, town, new FlatTerrain(64), P);
        assertEquals(BlockExtractor.extract(g, P), BlockExtractor.extract(g, P));
    }
}
```

- [ ] **Step 2: Run and watch them fail**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.block.BlockExtractorTest'
```
Expected: FAIL — `BlockExtractor` does not exist. Note `RoadGraph.builder()` must already support `node(Vec2)` returning the builder and assigning sequential ids; add that in this task if Task 3 did not.

- [ ] **Step 3: Implement `CityBlock` and `BlockExtractor`**

The algorithm is standard planar face traversal. State it exactly:

1. Build the set of **directed half-edges** — each undirected edge becomes two, one per direction.
2. At each node, sort its outgoing half-edges by bearing (`Math.atan2(dz, dx)`).
3. Starting from any unvisited half-edge, walk: arriving at a node along half-edge `u -> v`, the next half-edge is the one **immediately clockwise** from the reverse direction `v -> u` in `v`'s sorted list. Continue until returning to the starting half-edge. Mark every half-edge in the walk visited.
4. Each closed walk is a face. Compute its signed area.
5. **Discard the outer face.** With the clockwise rule above, interior faces come out with one winding and the single outer face with the other; the outer face is also the one with the largest absolute area. Use the winding test and assert the largest-area face agrees, so a bug in one shows up rather than silently passing.
6. Drop faces whose absolute area is below `minBlockAreaBlocks * 2` (the polygon returns *twice* the area).
7. Drop faces of fewer than 3 distinct points.
8. Sort the surviving faces by their bounding box's `(minX, minZ)` so extraction is deterministic regardless of walk order, then assign sequential ids.

Step 8 is not optional. Half-edge iteration order otherwise leaks into block ids, and `extractionIsDeterministic` will catch it.

- [ ] **Step 4: Run the tests**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.block.BlockExtractorTest'
```
Expected: PASS, all eight.

`aDeadEndSpurProducesNoBlock` is the classic failure: a naive traversal walks out along the spur and back, producing a zero-area face. Step 6's area filter catches it, but check the walk terminates rather than looping.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(plan): extract city blocks as the enclosed faces of the road graph

Standard planar face traversal: half-edges sorted by bearing at each node,
next-clockwise-from-the-reverse to walk a face, outer face discarded by
winding. Faces are sorted by bounding box before ids are assigned, so
half-edge iteration order cannot leak into the output.

Tested against the degenerate cases that break naive implementations: dead-end
spurs, open paths with no cycle, collinear runs, and faces below the minimum
area."
```

---

### Task 5: Districts and lots

**Files:**
- Create: `plan/district/District.java`, `plan/district/DistrictMap.java`, `plan/lot/Lot.java`, `plan/lot/WaterShape.java`, `plan/lot/LotSubdivider.java`, `plan/CityPlan.java`, `plan/Planner.java`
- Test: `src/test/java/dev/krona/urbex/plan/lot/LotSubdividerTest.java`, `plan/PlannerTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces:
  - `District` enum: `CORE`, `INNER`, `OUTER`, `FRINGE`, `WATERFRONT`
  - `DistrictMap.assign(CityBlock b, Settlement s, TerrainSampler t) -> District`
  - `Lot(int id, Rect footprint, District district, int sizeClass, int frontingEdgeIndex, int groundHeight, int waterSides)` with `WaterShape waterShape()`
  - `WaterShape` enum: `INLAND`, `STRAIGHT`, `CORNER`, `CHANNEL`, `PENINSULA`, `ISLAND`, via `WaterShape.of(int mask)`
  - `LotSubdivider.subdivide(long seed, CityBlock b, District d, RoadGraph g, TerrainSampler t, PlanParams p) -> List<Lot>`
  - `CityPlan(Settlement settlement, RoadGraph roads, List<CityBlock> blocks, Map<Integer, District> districts, List<Lot> lots)`
  - `Planner.plan(long seed, Settlement s, TerrainSampler t, PlanParams p) -> CityPlan`

- [ ] **Step 1: Write the failing invariant tests**

These are the invariants from spec §7. Create `src/test/java/dev/krona/urbex/plan/PlannerTest.java`:

```java
package dev.krona.urbex.plan;

import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.lot.Lot;
import dev.krona.urbex.plan.terrain.CoastTerrain;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final Settlement TOWN = new Settlement(SettlementClass.TOWN, 0, 0);

    @Test
    void everyLotTouchesARoad() {
        // A building with no access is the classic failure of generated cities.
        for (long seed = 0; seed < 20; seed++) {
            CityPlan plan = Planner.plan(seed, TOWN, new FlatTerrain(64), P);
            for (Lot lot : plan.lots()) {
                assertTrue(lot.frontingEdgeIndex() >= 0
                                && lot.frontingEdgeIndex() < plan.roads().edges().size(),
                        "seed " + seed + ": lot " + lot.id() + " fronts onto no road");
            }
        }
    }

    @Test
    void lotsNeverOverlap() {
        for (long seed = 0; seed < 20; seed++) {
            CityPlan plan = Planner.plan(seed, TOWN, new FlatTerrain(64), P);
            var lots = plan.lots();
            for (int i = 0; i < lots.size(); i++) {
                for (int j = i + 1; j < lots.size(); j++) {
                    assertTrue(!lots.get(i).footprint().intersects(lots.get(j).footprint()),
                            "seed " + seed + ": lots " + i + " and " + j + " overlap");
                }
            }
        }
    }

    @Test
    void everyLotLiesInsideExactlyOneBlock() {
        for (long seed = 0; seed < 20; seed++) {
            CityPlan plan = Planner.plan(seed, TOWN, new FlatTerrain(64), P);
            for (Lot lot : plan.lots()) {
                Vec2 c = lot.footprint().center();
                long containing = plan.blocks().stream()
                        .filter(b -> b.outline().contains(c))
                        .count();
                assertEquals(1, containing,
                        "seed " + seed + ": lot " + lot.id() + " lies in " + containing + " blocks");
            }
        }
    }

    @Test
    void noLotSitsUnderWater() {
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        for (long seed = 0; seed < 20; seed++) {
            for (Lot lot : Planner.plan(seed, TOWN, river, P).lots()) {
                Vec2 c = lot.footprint().center();
                assertTrue(!river.isWaterAt(c.x(), c.z()),
                        "seed " + seed + ": lot " + lot.id() + " sits in the river");
            }
        }
    }

    @Test
    void theRoadNetworkIsConnected() {
        for (long seed = 0; seed < 20; seed++) {
            assertTrue(Planner.plan(seed, TOWN, new FlatTerrain(64), P).roads().isConnected(),
                    "seed " + seed + " produced a disconnected network");
        }
    }

    @Test
    void waterfrontDistrictsAppearOnACoastAndNotInland() {
        CityPlan coastal = Planner.plan(4L, TOWN, new CoastTerrain(64, 64), P);
        CityPlan inland = Planner.plan(4L, TOWN, new FlatTerrain(64), P);
        assertTrue(coastal.districts().containsValue(District.WATERFRONT),
                "no waterfront district on a coast");
        assertTrue(!inland.districts().containsValue(District.WATERFRONT),
                "waterfront district appeared inland");
    }

    @Test
    void coreLotsAreSmallerThanFringeLots() {
        CityPlan plan = Planner.plan(7L, new Settlement(SettlementClass.CITY, 0, 0),
                new FlatTerrain(64), P);
        double core = averageArea(plan, District.CORE);
        double fringe = averageArea(plan, District.FRINGE);
        assertTrue(core < fringe,
                "core lots (" + core + ") should be smaller than fringe lots (" + fringe + ")");
    }

    @Test
    void planningIsDeterministic() {
        assertEquals(Planner.plan(11L, TOWN, new FlatTerrain(64), P),
                Planner.plan(11L, TOWN, new FlatTerrain(64), P));
    }

    private static double averageArea(CityPlan plan, District d) {
        return plan.lots().stream()
                .filter(l -> l.district() == d)
                .mapToInt(l -> l.footprint().area())
                .average()
                .orElseThrow(() -> new AssertionError("no lots in district " + d));
    }
}
```

- [ ] **Step 2: Run and watch them fail**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.PlannerTest'
```
Expected: FAIL — `Planner` does not exist.

- [ ] **Step 3: Implement `District` and `DistrictMap`**

```java
package dev.krona.urbex.plan.district;

/**
 * Concentric bands from the settlement centre, plus terrain-driven specials.
 * <p>
 * This is the single knob that makes a citadel-and-suburbs medieval town and a
 * downtown-and-sprawl modern city the same model: the rings are the same, only the parameters and
 * (later) the palette differ.
 */
public enum District {
    CORE,
    INNER,
    OUTER,
    FRINGE,
    WATERFRONT
}
```

`DistrictMap.assign` takes the block's bounding-box centre, measures its distance from the settlement centre as a fraction of the settlement radius, and returns `CORE` below 0.2, `INNER` below 0.45, `OUTER` below 0.75, else `FRINGE`. Before that, if any of the block's outline points is within 24 blocks of water, return `WATERFRONT` — that check comes first so a coastal core is still waterfront.

- [ ] **Step 4: Implement `Lot` and `LotSubdivider`**

```java
package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Rect;

/**
 * One buildable plot.
 * <p>
 * This record is the contract P3's asset model must satisfy. Two fields do specific future work:
 * {@code frontingEdgeIndex} is what lets a building be oriented so its entrance faces its road,
 * and {@code groundHeight} is what lets P4 hand vanilla a {@code TerrainAdjustment} box.
 */
public record Lot(
        int id,
        Rect footprint,
        District district,
        int sizeClass,
        int frontingEdgeIndex,
        int groundHeight,
        int waterSides
) {

    /** North, east, south, west as bits 0-3 of {@link #waterSides}. */
    public WaterShape waterShape() {
        return WaterShape.of(waterSides);
    }
}
```

`LotSubdivider.subdivide` works on the block's bounding box clipped to its outline:

1. Target lot size comes from the district: `coreLotSizeBlocks` for `CORE`, interpolating to `fringeLotSizeBlocks` for `FRINGE`. `WATERFRONT` uses the `OUTER` size.
2. Recursively split the block's bounding rect: while a rect's longer side exceeds twice the target size, cut it perpendicular to that side at a position jittered ±20% around the midpoint, drawn from `PlanPurpose.BLOCK_SPLIT_POS` addressed at the rect's own `(minX, minZ)` — addressing by the rect rather than by a counter means the split of one sub-rect cannot perturb its sibling.
3. Discard any leaf rect whose centre is outside the block outline, or whose centre is over water.
4. For each surviving leaf, find the nearest road edge to its centre. If that distance exceeds `maxLotDepthBlocks`, discard the lot — that is a lot with no access, and dropping it is honest. (Inserting alleys is a later refinement; the spec allows it, but a prototype that drops unreachable lots is testable now and looks like a courtyard, not a bug.)
5. `sizeClass` is 0, 1 or 2 by area tertile within the settlement.
6. `groundHeight` is `terrain.heightAt(centre)`.

Shrink each leaf rect by 1 block on every side before emitting it, so adjacent lots never share a boundary block and `lotsNeverOverlap` holds by construction rather than by luck.

- [ ] **Step 4b: Implement `WaterShape` and populate `waterSides`**

Create `src/main/java/dev/krona/urbex/plan/lot/WaterShape.java`:

```java
package dev.krona.urbex.plan.lot;

/**
 * What kind of water frontage a lot has, derived from which of its four sides face water.
 * <p>
 * The shapes are not enumerated by hand — they fall out of the 4-bit mask, which is why this can
 * cover cases nobody thought to author a piece for. P3 authors one piece per shape; P2 decides
 * which applies. A river is frequently not one chunk wide, so a single "bridge" piece and a single
 * "canal side" piece cannot cover the real geometry.
 */
public enum WaterShape {
    /** No side faces water. */
    INLAND,
    /** One side. The plain canal or riverbank edge. */
    STRAIGHT,
    /** Two adjacent sides — an outside corner where two banks meet. */
    CORNER,
    /** Two opposite sides — a channel running straight through. */
    CHANNEL,
    /** Three sides — the tip of a peninsula. */
    PENINSULA,
    /** All four sides. */
    ISLAND;

    public static final int NORTH = 1;
    public static final int EAST = 1 << 1;
    public static final int SOUTH = 1 << 2;
    public static final int WEST = 1 << 3;

    public static WaterShape of(int mask) {
        return switch (Integer.bitCount(mask & 0b1111)) {
            case 0 -> INLAND;
            case 1 -> STRAIGHT;
            case 2 -> isOpposite(mask) ? CHANNEL : CORNER;
            case 3 -> PENINSULA;
            default -> ISLAND;
        };
    }

    private static boolean isOpposite(int mask) {
        return mask == (NORTH | SOUTH) || mask == (EAST | WEST);
    }
}
```

In `LotSubdivider`, after a lot's footprint is fixed, set `waterSides` by probing a few blocks
beyond each of its four edges — sample three points along each side at `probeDistanceBlocks` (add
it to `PlanParams`, default 6) and set that side's bit if any sample is water. Probing several
points rather than one matters: a river meeting a lot at an angle touches part of a side, not its
midpoint.

Add to `LotSubdividerTest`:

```java
@Test
void aLotBesideAStraightRiverHasExactlyOneWaterSide() {
    RiverTerrain river = new RiverTerrain(64, 0, 24);
    CityPlan plan = Planner.plan(3L, new Settlement(SettlementClass.TOWN, 0, 0), river, P);
    long waterfront = plan.lots().stream().filter(l -> l.waterSides() != 0).count();
    assertTrue(waterfront > 0, "a town on a river should have lots fronting it");
    for (Lot lot : plan.lots()) {
        if (lot.waterSides() != 0) {
            assertEquals(WaterShape.STRAIGHT, lot.waterShape(),
                    "lot " + lot.id() + " beside a straight river should have a straight frontage");
        }
    }
}

@Test
void inlandLotsHaveNoWaterSides() {
    CityPlan plan = Planner.plan(3L, new Settlement(SettlementClass.TOWN, 0, 0),
            new FlatTerrain(64), P);
    for (Lot lot : plan.lots()) {
        assertEquals(0, lot.waterSides(), "lot " + lot.id() + " found water on flat dry ground");
        assertEquals(WaterShape.INLAND, lot.waterShape());
    }
}

@Test
void theShapeTaxonomyCoversEveryMask() {
    assertEquals(WaterShape.INLAND, WaterShape.of(0));
    assertEquals(WaterShape.STRAIGHT, WaterShape.of(WaterShape.NORTH));
    assertEquals(WaterShape.CORNER, WaterShape.of(WaterShape.NORTH | WaterShape.EAST));
    assertEquals(WaterShape.CHANNEL, WaterShape.of(WaterShape.NORTH | WaterShape.SOUTH));
    assertEquals(WaterShape.CHANNEL, WaterShape.of(WaterShape.EAST | WaterShape.WEST));
    assertEquals(WaterShape.PENINSULA,
            WaterShape.of(WaterShape.NORTH | WaterShape.EAST | WaterShape.SOUTH));
    assertEquals(WaterShape.ISLAND, WaterShape.of(0b1111));
}
```

- [ ] **Step 5: Implement `CityPlan` and `Planner`**

`CityPlan` is a record holding the settlement, roads, blocks, a `Map<Integer, District>` keyed by block id, and lots. All collections copied immutably in the compact constructor.

`Planner.plan` runs the pipeline: grow roads, extract blocks, assign districts, subdivide each block, assign sequential lot ids across the whole settlement in block order.

- [ ] **Step 6: Run every test in the module**

```bash
./gradlew test
```
Expected: PASS. `everyLotTouchesARoad`, `lotsNeverOverlap` and `everyLotLiesInsideExactlyOneBlock` are the three that justify this task existing.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(plan): districts, lot subdivision, and the assembled CityPlan

Districts are concentric bands from the centre with a waterfront special that
takes precedence, so a medieval citadel town and a modern downtown are the
same model with different parameters. Lots come from recursive rectangular
splits addressed by the rect being split, so one sub-rect's split cannot
perturb its sibling.

The invariants are the point: every lot fronts a road, lots never overlap,
each lies in exactly one block, none sits under water."
```

---

### Task 6: JSON, the viewer, and the plan digest

**Files:**
- Create: `plan/PlanJson.java`, `plan/PlanQuery.java`, `viewer/plan-viewer.html`
- Test: `src/test/java/dev/krona/urbex/plan/PlanJsonTest.java`, `plan/PlanDigestTest.java`, `plan/PlanQueryTest.java`

**Interfaces:**
- Consumes: `CityPlan`, `Planner`, `SettlementMap`.
- Produces:
  - `PlanJson.toJson(CityPlan plan) -> String`
  - `PlanQuery.at(long seed, int chunkX, int chunkZ, TerrainSampler t, PlanParams p) -> PlanQuery.Result`
  - `PlanQuery.Result` — a sealed interface with `None`, `Road`, `LotAt`, `OpenGround`

- [ ] **Step 1: Write the plan digest test**

The same technique that caught real defects in P1a. Create `src/test/java/dev/krona/urbex/plan/PlanDigestTest.java`:

```java
package dev.krona.urbex.plan;

import dev.krona.urbex.plan.terrain.FlatTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the layout for a few seeds so a refactor that silently changes it fails a test rather than
 * being noticed by eye six weeks later. Regenerate deliberately, never to make the build green.
 */
class PlanDigestTest {

    private static final PlanParams P = PlanParams.defaults();

    @Test
    void townLayoutIsPinned() {
        assertEquals(TOWN_DIGEST, digest(1337L, SettlementClass.TOWN));
    }

    @Test
    void cityLayoutIsPinned() {
        assertEquals(CITY_DIGEST, digest(1337L, SettlementClass.CITY));
    }

    private static String digest(long seed, SettlementClass cls) {
        CityPlan plan = Planner.plan(seed, new Settlement(cls, 0, 0), new FlatTerrain(64), P);
        long h = 0xCBF29CE484222325L;
        for (var e : plan.roads().edges()) {
            h = fold(h, e.fromId());
            h = fold(h, e.toId());
            h = fold(h, e.bridge() ? 1 : 0);
        }
        for (var l : plan.lots()) {
            h = fold(h, l.footprint().minX());
            h = fold(h, l.footprint().minZ());
            h = fold(h, l.footprint().maxX());
            h = fold(h, l.footprint().maxZ());
            h = fold(h, l.district().ordinal());
            h = fold(h, l.waterSides());
        }
        return String.format("%016x", h);
    }

    private static long fold(long h, long v) {
        return (h ^ v) * 0x100000001B3L;
    }

    // Generated once by running the tests and pasting the actual values. Do not edit to fix a
    // failure - a changed digest means the layout changed, which is either the point of your
    // commit or a bug.
    private static final String TOWN_DIGEST = "0000000000000000";
    private static final String CITY_DIGEST = "0000000000000000";
}
```

- [ ] **Step 2: Run it, then pin the real digests**

```bash
./gradlew test --tests 'dev.krona.urbex.plan.PlanDigestTest'
```
Expected: FAIL, reporting the actual digests. Paste those two values into `TOWN_DIGEST` and `CITY_DIGEST`, then re-run — expected PASS.

- [ ] **Step 3: Implement `PlanJson`**

Hand-rolled JSON, because the module may not take a dependency. One method, `toJson(CityPlan)`, emitting:

```json
{
  "settlement": {"class": "TOWN", "centerChunkX": 0, "centerChunkZ": 0, "radiusBlocks": 96},
  "nodes": [{"id": 0, "x": 8, "z": 8}],
  "edges": [{"from": 0, "to": 1, "class": "ARTERIAL", "bridge": false}],
  "blocks": [{"id": 0, "district": "CORE", "ring": [[0,0],[100,0],[100,100]]}],
  "lots": [{"id": 0, "minX": 4, "minZ": 4, "maxX": 20, "maxZ": 20, "district": "CORE", "sizeClass": 1, "ground": 64}]
}
```

Escape nothing beyond what enum names and integers need — every string emitted is an enum constant, so a full escaper is not warranted. Write a test asserting the output parses as balanced JSON (count braces and brackets) and contains one entry per node, edge, block and lot.

- [ ] **Step 4: Implement `PlanQuery` with the cache**

```java
public final class PlanQuery {

    public sealed interface Result {
        record None() implements Result {}
        record OpenGround(Settlement settlement) implements Result {}
        record Road(Settlement settlement, int edgeIndex) implements Result {}
        record LotAt(Settlement settlement, Lot lot) implements Result {}
    }
}
```

The cache is keyed by `Settlement` and must use **get, compute outside the map, then `putIfAbsent`** — never `computeIfAbsent`. In P1a the city caches deadlocked on `computeIfAbsent` because they were mutually recursive, and once P4 wires this up, worldgen threads will request the same plan concurrently. Recomputing on a race is harmless because planning is a pure function of the seed.

Write a test asserting that two threads calling `at` for the same settlement both get equal results and neither deadlocks — a simple `ExecutorService` with two tasks and a `CountDownLatch` is enough.

- [ ] **Step 5: Write a `main` that dumps JSON for the viewer**

Add `PlanJson.main(String[] args)` taking `seed`, `class` and an output path, so the viewer's data can be regenerated without a test run:

```bash
./gradlew -q runPlanDump --args="1337 TOWN viewer/plan-1337-town.json"
```

Add the matching `JavaExec` task to `build.gradle`. Keep it out of the `jar` — this is development tooling.

- [ ] **Step 6: Write the viewer**

`viewer/plan-viewer.html`, a single self-contained file, no build step and no external requests:

- A seed input, a settlement-class select, and a **Regenerate** button that fetches `plan-<seed>-<class>.json`.
- A canvas rendering, back to front: terrain tint, block outlines filled by district colour, roads stroked by class width, bridges drawn in a distinct colour, lots as filled rectangles.
- Layer checkboxes for districts, roads, lots, bridges.
- Mouse-wheel zoom and drag to pan.
- A readout showing counts: nodes, edges, blocks, lots, and the district breakdown.

The point is flipping seeds quickly, so bind the seed box to Enter and keep regeneration under a second.

- [ ] **Step 7: Run everything and commit**

```bash
./gradlew test
```
Expected: PASS.

```bash
git add -A
git commit -m "feat(plan): JSON output, plan query with cache, and the viewer

PlanQuery is the face P4 will consume; its cache uses get/compute-outside/
putIfAbsent rather than computeIfAbsent, because the P1a caches deadlocked on
computeIfAbsent when reached recursively and worldgen threads will request the
same plan concurrently.

PlanDigestTest pins the layout for two seeds so a refactor that silently
changes it fails a test. The viewer exists to answer the question P2 was
created for, which needs many seeds looked at quickly rather than one looked
at carefully."
```

---

## Done criteria for P2

1. `./gradlew test` green, including `PurityTest`, the invariant tests in `PlannerTest`, and both pinned plan digests.
2. `PurityTest` confirms `plan` imports nothing outside itself and the JDK.
3. `RngTest`'s golden vectors still pass, proving the `Hash` extraction moved no bits.
4. The viewer renders plans for all five settlement classes, and you have looked at enough seeds across enough classes to answer the question P2 exists for: **do lot-based cities look better than the chunk grid?**

Criterion 4 is a judgement, not a test, and it is the one that matters. If the answer is no, that is a successful outcome for a prototype — it is far cheaper to learn it here than in P4.

## What P2 deliberately does not do

No Minecraft integration, no block placement, no palettes, no building selection, no changes to the existing generator. P2 ships as dead code from the mod's perspective; P4 is what connects it.
