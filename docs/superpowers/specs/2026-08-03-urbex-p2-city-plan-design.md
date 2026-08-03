# Urbex P2 — the city plan

Date: 2026-08-03
Status: approved, ready for planning
Depends on: P0 (done). Independent of P1a, though it reuses `Rng`'s mixing.
Consumed by: P4 (structure-based placement). Constrains P3 (asset model v2).

## 1. Why this exists

Urbex's cities are still Lost Cities' cities: a radial blob of chunk-aligned 16×16 buildings with
no road network and no districts. Three findings from the fork's original investigation all point
here:

- **C1** — a building is exactly one chunk. All 186 shipped parts are `xsize:16, zsize:16`;
  multibuildings are N×M *chunks*. Every façade sits on a 16-block grid, which is why Lost Cities
  is recognisable from 200 blocks up.
- **C2** — there is no road network. A street is "a city chunk that has no building". No
  intersections, no avenues, no plan.
- **C3** — a city is a radial blob with one style. `City.getCityFactor()` sums distance falloff
  from random centres; building selection is a flat weighted list.

P2 answers the question the whole fork rests on: **do lot-based cities actually look better than
chunk-grid cities?** It is a prototype in the sense that it is judged by eye before it is wired to
anything — but it is not throwaway, because P4 consumes its output directly.

It has zero Minecraft coupling by construction, so it can be iterated in seconds against synthetic
terrain and a browser viewer, without launching the game.

## 2. Decisions taken

| Decision | Choice | Rationale |
|---|---|---|
| City extent | Bounded, with five settlement classes | Planning cost scales with *lots*, not chunks — a metropolis is a few thousand lot records, not 9216 chunk records. And the expensive class is the rare class. |
| Layout algorithm | Hybrid: grown arterials, extracted blocks, subdivided lots | The only option where a medieval radial town and a modern downtown are the same code with different parameters. |
| Terrain | Terrain-aware through a two-method injected interface | Buys roads that follow contours, waterfront districts, and bridges that exist because something needs crossing. Keeps the module pure and fast to test. |
| Deliverable | JSON dump plus a self-contained interactive HTML viewer | Flipping through fifty seeds in a minute is how judgement about a generator gets built. One PNG at a time is not. |

### 2.1 A note on bridges

Today `xBridge`/`zBridge` are rolled from `profile.BRIDGE_CHANCE` per chunk behind parity guards.
Nothing checks whether there is anything to cross, which is exactly why the current bridges look
wrong — an elevated road over dry ground is not a bridge.

In this design a bridge is **derived, never rolled**: a road edge is a bridge because its span
crosses water or a ravine. The same applies to elevated segments and, later, retaining walls at
district edges.

## 3. Module boundary

**Package `dev.krona.urbex.plan`. No Minecraft imports, enforced by a test** that scans the package
and fails on `net.minecraft`. Without the test the coupling returns within a month.

### 3.1 The pure hash extraction

`Rng` already does pure `long` math; only its return type (`net.minecraft.util.RandomSource`) is
Minecraft. Extract the mixing into a pure `Hash` class and have `Rng` delegate to it.

**`Hash` lives inside `dev.krona.urbex.plan`, not in `varia`.** Dependencies must point *into* the
pure module, never out of it: `Rng` (which is Minecraft-coupled) depends on `Hash`, and `Hash`
depends on nothing. Putting `Hash` in `varia` alongside Minecraft-coupled classes would leak the
guarantee — `plan` would be importing from a package that is not itself pure, and the import test
would pass while the property it protects quietly stopped holding.

The purity test therefore checks two things: no class in `plan` imports `net.minecraft`, and no
class in `plan` imports any package outside `plan` and the JDK.

`RngTest`'s pinned golden vectors are the safety net for the extraction itself: if it changes a
single bit, they fail. The `Purpose` ordinal contract is unaffected — nothing in the enum moves.

P2 uses `Hash` directly and never touches `Rng`.

## 4. Settlement placement

**One lattice per settlement class**, not one lattice with a class roll.

Each class has its own cell size. Within each cell, a centre is jittered from `(seed, cellX, cellZ,
class)` with a margin guaranteeing the settlement's extent never leaves its cell. Where two
classes' extents overlap, the larger wins and the smaller is dropped.

| Class | Extent (chunks) | Character |
|---|---|---|
| hamlet | ~2×2 | a handful of lots on one road |
| village | ~4×4 | a crossroads, no districts |
| town | ~12×12 | a core and a fringe |
| city | ~32×32 | full district set |
| metropolis | ~96×96 | a landmark; rare |

Small cell sizes make hamlets common; enormous ones make a metropolis a genuine landmark. This is
essentially how vanilla structure sets and `StructurePlacement` work, and it buys three properties:
per-class density control, local computability from `(seed, x, z)` with no global state, and a
settlement hierarchy that falls out rather than being imposed.

Exact cell sizes and class weights are parameters, tuned by eye in the viewer. The numbers above
are starting points, not requirements.

## 5. The layout pipeline

Six units, each with one job, each independently testable.

### 5.1 `TerrainSampler`

The only thing the planner knows about the world:

```java
public interface TerrainSampler {
    int heightAt(int x, int z);
    boolean isWaterAt(int x, int z);
}
```

Test implementations: `FlatTerrain`, `HillTerrain`, `RiverTerrain`, `CoastTerrain`, `CliffTerrain`.

**P4's real implementation must sample the terrain *function* — the noise/heightmap query, a pure
function of the seed — and never placed blocks.** Reading placed blocks is precisely the mechanism
behind issue #18, where vanilla vegetation bleeding across a chunk border changed what a fill loop
saw. A sampler that reads blocks would reintroduce that class of bug at the planning layer, where
it would be far worse.

### 5.2 `ArterialGrowth`

Grows the major road skeleton outward from the settlement centre, within its bounds, terrain-aware:
prefer shallow slopes, cross water at narrow points, snap to nearby existing nodes to close loops
rather than dead-ending.

Parameterised so a radial town (spokes plus ring roads) and a modern downtown (grid) are the same
code with different parameters. Class and district settings supply those parameters.

### 5.3 `RoadGraph`

Nodes and edges. Each edge carries a `RoadClass` (arterial / collector / local) and derived flags:
`bridge`, `elevated`. Derived, per §2.1 — computed from geometry plus terrain, never rolled.

### 5.4 `BlockExtractor`

Finds the enclosed faces of the road graph and turns them into city blocks.

**This is the fiddliest component in the project.** Planar face traversal has to survive dead-end
roads, collinear nodes, self-touching faces and near-degenerate geometry. It warrants
disproportionate test attention; discovering its edge cases during P4 would be much more expensive.

### 5.5 `DistrictMap`

Assigns each block a district by concentric ring from the centre — core, inner, outer, fringe —
plus terrain-driven specials such as waterfront.

This is the single knob that makes a citadel-and-suburbs medieval town and a downtown-and-sprawl
modern city the same model. Density, lot size and later the palette all read from it.

### 5.6 `LotSubdivider`

Cuts each block into lot rectangles, inserting alleys where a block is too deep to be reached from
its perimeter. Lot size comes from the district: small and dense in the core, large and loose at
the fringe.

## 6. Output contract

`CityPlan` is immutable and holds the settlement, road graph, blocks, districts and lots.

`PlanQuery` is the runtime face: given `(seed, chunkX, chunkZ)`, what is here — nothing, a road, a
lot, or open ground within a settlement.

Each `Lot` carries:

- its footprint rectangle
- its district
- a size class
- the road edge it fronts onto
- its ground height
- **which of its four sides face water**, as a 4-bit mask, and the `WaterShape` derived from it

Each `RoadEdge` carries, besides its class and its `bridge` flag, **the length of water it actually
crosses**.

### 6.1 Why water gets its own fields

The shipped datapack has one bridge piece, a single 16×16 chunk, and canal pieces that assume banks
on both sides. A river is frequently not one chunk wide, and a bank is frequently not straight — so
a plan that emits only `bridge: true` leaves P4 unable to choose between a short crossing and a long
one, and leaves P3 with no way to know whether a bank piece should be straight, a corner, or the tip
of a peninsula.

Recording which sides face water makes the variants fall out of the mask rather than being
enumerated by hand:

| Sides facing water | Shape | |
|---|---|---|
| none | `INLAND` | no water piece |
| one | `STRAIGHT` | the plain canal or riverbank edge |
| two adjacent | `CORNER` | an L |
| two opposite | `CHANNEL` | a canal running straight through |
| three | `PENINSULA` | a U |
| four | `ISLAND` | |

P3 authors one piece per shape; P2 decides which applies. Cases nobody thought to author are then
visible as a missing piece rather than as a silently wrong one.

**This set is the contract P3's asset model must satisfy.** Getting it wrong is expensive later, so
it deserves review on its own terms rather than as an implementation detail. In particular: a lot
knowing which road it faces is what lets P3 orient a building's entrance, and a lot carrying a
ground height is what lets P4 hand vanilla a `TerrainAdjustment` box.

## 7. Testing

**Structural invariants**, asserted across many seeds rather than one. Each is a bug that would
otherwise ship:

- Every lot touches a road. A building with no access is the classic failure of generated cities.
- Lots never overlap, and each lies within exactly one block.
- The road graph is connected — no orphaned islands.
- No lot sits under water; no road exceeds the maximum slope.
- Settlement extents never overlap.

**Terrain response**, using the synthetic samplers. With `RiverTerrain`, every edge crossing the
river is flagged `bridge` and none runs along the riverbed. With `CliffTerrain`, no road exceeds
max slope. With `CoastTerrain`, waterfront districts appear on the coast and not inland.

**Placement**, over a large sample: per-class densities match their configured rates, the larger
class wins conflicts, extents never overlap.

**A plan digest**, pinned for a few seeds. The same technique that caught real defects in P1a: a
refactor that silently changes layout should fail a test, not be noticed by eye six weeks later.

**The no-Minecraft-imports test**, per §3.

## 8. The viewer

Java serialises `CityPlan` to JSON. One self-contained HTML file renders it on a canvas with a seed
box, settlement-class picker, layer toggles (districts, roads, lots, terrain, bridges) and zoom.
Published as an artifact so it is a URL rather than a file to hunt for.

Its purpose is judgement, not decoration: the question "does this look better than a chunk grid" is
answered by flipping through many seeds, and anything slower than that produces a worse answer.

## 9. Non-goals

P2 ships as **dead code from the mod's perspective**, wired into nothing. That is intentional.

- No Minecraft integration — P4.
- No block placement, no palettes, no building selection — P3 and P4.
- No buildings. A lot is a rectangle with a district and a size class, not a structure.
- No changes to the existing generator. The current chunk-grid path stays exactly as it is until
  P4 replaces it.

## 10. Risks

| Risk | Mitigation |
|---|---|
| `BlockExtractor`'s planar face traversal is the hardest code here and its edge cases are easy to miss. | Disproportionate test attention, including deliberately degenerate graphs. Treat it as the component most likely to need a second pass. |
| P4's real `TerrainSampler` will be called many times when planning a metropolis, and may be slow. | Keep the interface to two cheap methods, sample the noise function rather than blocks, and measure before P4 commits. Consider a coarse sampling grid with interpolation. |
| Worldgen threads will request the same plan concurrently once P4 wires it up. | The plan cache uses the shape learned in P1a: compute outside the map, then `putIfAbsent`. Never `computeIfAbsent` — the P1a caches deadlocked on it because they were mutually recursive. |
| The prototype answers "does the pipeline work" rather than "does it look better". | The viewer exists specifically to prevent this. Judge on many seeds across all five classes before declaring P2 done. |
| Tuned parameters (cell sizes, class weights, ring radii) get hardcoded and calcify. | Keep them in one parameters object from the start. P5 makes them datapack-driven; P2 only has to avoid scattering them. |

## 11. Open questions

None blocking. One to settle during implementation rather than before: whether districts are
strictly concentric rings or whether a sector component (so one side of a town can be industrial)
earns its complexity. Start concentric; add sectors only if the viewer shows cities looking too
uniform.
