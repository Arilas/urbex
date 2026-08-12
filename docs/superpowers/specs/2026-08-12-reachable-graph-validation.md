# Reachable-graph validation (issue #56, second half)

Phase 2, step 5 of epic #134. The first half landed in #140 (aggregated diagnostics, `/urbex
validate`, null-guarded `ErrorLogger`); this is the per-reference / per-character walk it deferred.

## Why it waited, and what actually unblocked it

The sequencing note on #56 blocked this on "#128 moves that merge to compile time, into the immutable
`AssetSnapshot`". Read literally that did not happen, and it cannot: `CityGenerator.computePalette`
builds a `CompiledPalette` per chunk from the style/building palette plus the part's own, and *which*
style a part is used under is a property of the chunk, not of the part.

What the note was actually protecting against is gone. It feared a validator would have to **guess**
at the merge. It does not: every input is now fixed snapshot data — `Style.getRandomPalette` draws
from resolved `Palette` objects, `getLocalPalette()` is a field read, `frompalette` is a
character-to-character reference resolved inside `CompiledPalette`. A validator can therefore build
the *exact* palette generation will build, by calling the same constructor. The only registries
generation still reads are non-asset vanilla ones: structures, `BLOCK_ENTITY_TYPE`, biomes.

## What is already checked, so this does not re-litigate it

- **Per-part slice geometry.** `BuildingPart.checkGeometry` refuses a part whose slice strings do not
  total `xsize * zsize`, at compile time, naming the part.
- **Typed references resolved during compilation**: a style's `randompalettes`
  (`Style` → `palettes.getOrThrow`), a part's `refpalette`, a predefined city's `citystyle`, and
  every `extends` chain. These already fail the world naming the file.
- **Requiredness after chain resolution** (`Resolved.require`), retired keys, one-character palette
  markers, undrawable `randompalettes` groups.

## What is missing

Everything reached by a **name held as a String in the compiled model** and looked up during
generation. There are ~40 such lookups (`assets().parts().getOrThrow(...)` and friends in
`CityGenerator`, `Highways`, `Railways`, `Scattered`). Each is a datapack error that surfaces from a
worldgen worker on whichever chunk first needs it.

Three checks, in the order they are worth having:

### 1. Dangling references reachable from what a world can select

A walk from the roots a world can actually select, following every String-held reference:

```
worldStyle ─┬─ outsidestyle ──────────────► style
            ├─ citystyles[].citystyle ────► cityStyle
            ├─ scattered.list[].name ─────► scatteredBuilding
            └─ parts.{highways,railways}.* ► part

cityStyle ──┬─ style ────────────────────► style
            ├─ selectors.{buildings,multibuildings} ► building / multiBuilding
            ├─ selectors.{bridges,largebridges,parks,fountains,stairs,fronts,raildungeons} ► part
            └─ streetblocks.{parts,largeparts,tertiaryparts}.* ► part

building ───┬─ parts[]/parts2[].part ────► part
            └─ {inpart,belowpart,inbuilding} ► part / building
multiBuilding ─ buildings[][] ───────────► building
scatteredBuilding ─┬─ buildings[] ───────► building
                   └─ multibuilding ─────► multiBuilding
condition ── values[].{inpart,belowpart,inbuilding} ► part / building
predefinedCity ─ buildings[].building ───► building
```

**Roots** are the world styles and the predefined cities, plus the preset's
`cityStyleAlternative`. That mirrors `AssetCompiler`'s existing reachability rule for city styles: a
city style that nothing can select is allowed to be incomplete, so a *part* only reachable through
one must not be a load error either.

**Fatal, not a warning.** These already throw from a worker today; moving them to load time makes
them earlier, not stricter. The exception is anything currently reached with `getOrWarn` (the
optional street/highway components), which stays a warning so a pack that deliberately omits one
keeps working.

### 2. Undefined palette characters

Two populations:

- **A part's slice characters.** Every distinct character in `getVslices()`.
- **A city style's character fields**: `streetBlock`, `streetBaseBlock`, `streetVariantBlock`,
  `railMainBlock`, `grassBlock`, `ironbarsBlock`, `glowstoneBlock`, `leavesBlock`, `rubbleDirtBlock`.

Both resolve against the merged palette for the context the part is used in. A part is used in
several contexts, and the merge differs per context, so the check is per **(context, part)** pairing
the walk above already enumerates.

**The guaranteed-character set.** A style's palette is one choice per `randompalettes` group, merged.
Checking one arbitrary selection is wrong (it passes for characters that only some worlds get);
enumerating the cartesian product is exponential and unnecessary. A character is guaranteed iff:

```
c ∈ partPalette ∪ buildingPalette ∪ ⋃over groups g ( ⋂over choices p in g  chars(p) )
```

— i.e. defined by the part or building outright, or defined by *every* choice of at least one group.
That is exact, and linear in the number of palettes.

`frompalette` is followed by building the merge through `CompiledPalette`'s own constructor rather
than reimplementing it, so the validator cannot drift from what generation does.

### 3. Slice sizes relative to the role a part is wired into

`BuildingPart.checkGeometry` proves a part is self-consistent, not that it fits where it is used.
`ChunkDriver.current` converts chunk-local to absolute without clamping, and `block()`/`add()` then
mask with `& 0xf` — so **a part wider than 16 wraps around and overwrites its own beginning**,
silently, with no exception and nothing in the log.

A part reached through a street, highway or railway wiring slot must therefore be 16×16. Building
parts are not covered by this rule (a building part is placed at a computed offset and may legitimately
differ), so the check is per role, which the walk already knows.

## Shape

`AssetGraph` — one pass over an `AssetSnapshot`, producing diagnostics through the existing
`AssetDiagnostics`. Called from `AssetCompiler.compile` after every index is built, so it is part of
the one report a world load already produces, and `/urbex validate` gets it for free.

It reads the snapshot and nothing else. No registry, no level, no `ServerAccess` — which is only
possible because #128 finished.

## The PRs

**56a — the reachable walk and dangling references.** `AssetGraph`, the root set, the traversal, and
check 1. The traversal is the expensive part to get right and is what checks 2 and 3 ride on.

**56b — characters and role sizes.** Checks 2 and 3, on the pairings 56a's walk already produces.

## Digest expectations

Neither PR may move a golden. Both are load-time refusals over data the bundled pack already
satisfies — `DatapackReferenceIntegrityTest` has enforced the same reference rules against the
shipped files since #94, so a moved golden would mean the walk changed what compiles, not what is
reported.
