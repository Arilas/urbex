# Immutable AssetSnapshot (issue #128)

Phase 2, step 4 of epic #134. Written before any code because this is the largest single boundary in
the epic and will not land in one PR; the decomposition below is the contract between those PRs.

## Where compiled assets live today

Twelve `RegistryAssetRegistry` instances, each a `static final` field on `AssetRegistries`, each
holding a `ConcurrentHashMap<Identifier, T>` of compiled assets. `load(level)` eagerly compiles
every registered entry into those maps and latches a `stuffIndex.loaded()` flag; `reset()` empties
them. A lookup that misses compiles on demand, from whatever thread asked.

Three consequences this issue exists to remove:

1. **Compilation is not finished when generation starts.** `Style.getRandomPalette`,
   `BuildingPart.getLocalPalette` and `Building.getLocalPalette` resolve a palette reference the
   first time a chunk needs it, cache it on the asset, and take a `CommonLevelAccessor` purely to
   reach the static registry. A datapack error in a palette named only by a `refpalette` therefore
   still surfaces from a worldgen worker.
2. **The compiled state is mutable and process-global.** `IAsset.setRegistryName` writes the
   registry id back onto the decoded definition during resolution, so the authored model is mutated
   by compilation.
3. **Its lifecycle is a latch and a reset**, which is what #125 had to work around rather than fix.

## Target shape

```java
public record AssetSnapshot(
    AssetIndex<Variant> variants,
    AssetIndex<Palette> palettes,
    AssetIndex<Condition> conditions,
    AssetIndex<Style> styles,
    AssetIndex<BuildingPart> parts,
    AssetIndex<Building> buildings,
    AssetIndex<MultiBuilding> multiBuildings,
    AssetIndex<ScatteredBuilding> scattered,
    AssetIndex<WorldStyle> worldStyles,
    AssetIndex<CityStyle> cityStyles,
    AssetIndex<PredefinedCity> predefinedCities,
    AssetIndex<StuffObject> stuff,
    StuffByTag stuffByTag
) {}
```

`AssetIndex<T>` is an immutable `Map<Identifier, T>` plus the `get` / `getOrThrow` / `getOrWarn`
contract the current registries expose, minus the level argument — an index needs no level, which is
the whole point.

One snapshot per **world load**, not per level: the thirteen registries are Fabric dynamic
registries, frozen when the world loads and shared by every level in it (#61). So
`GenerationSession` compiles and owns it, and each `DimensionRuntime` references the same instance.

## Compile order

The compiler resolves in dependency order and each stage may read the indexes already built. This is
not tidiness — a stage that reads an index built after it sees an empty one, which is the class of
silent failure `AssetRegistries.load`'s own ordering comments already warn about:

```
variants → palettes → conditions → styles → parts → buildings
        → multiBuildings → scattered → worldStyles → cityStyles
        → predefinedCities → stuff → stuffByTag
```

- `palettes` needs `variants` (a `variant` entry).
- `styles` needs `palettes` (its `randompalettes` become resolved `Palette` objects).
- `parts` and `buildings` need `palettes` (`refpalette`) and `variants` (inline palettes).
- `predefinedCities` needs `cityStyles`.

Diagnostics aggregate through `AssetDiagnostics` (already in place from #56's first half), so a pack
with problems in four stages is one report.

**City styles are the exception, and it is not optional.** Requiredness is a property of the end of a
chain, and a city style may exist only to be extended — the bundled `citystyle_config` declares a
street width and nothing else, and is complete only through `citystyle_common`. So every registered
style is compiled (a chunk may name any of them), but a compile failure is only *fatal* when
something can select it: a world style's `citystyles` selectors, a preset's `cityStyleAlternative`,
or a predefined city's `citystyle`. An earlier draft of the compiler failed on all of them and
refused the shipped pack's own world; the digest run caught it.

## The PRs

Each is one boundary and each leaves the tree green.

**128a — the snapshot exists and generation reads it.** `AssetIndex`, `AssetSnapshot`,
`AssetCompiler`; `GenerationSession` compiles once per world load and hands the snapshot to every
`DimensionRuntime`; `IDimensionInfo.assets()` exposes it. The three lazy palette sites resolve at
compile time, which drops the `CommonLevelAccessor` from `getLocalPalette` and `getRandomPalette`.
`AssetRegistries` and its statics, latches and `reset()` are deleted. The ~84 lookups move to
`assets()` in the same PR: leaving a facade behind would mean two ways to reach compiled assets and a
half-migrated tree, which is worse than a larger diff.

*Preview:* `NullDimensionInfo` compiles its own snapshot from the client's `RegistryAccess` when the
preview opens. It has no session and must not acquire one.

**128b — `TagSnapshot`.** Block-tag-derived state (`CityGenerator`'s expanded `urbex:lights` /
`urbex:needspoi` sets, the `rotatable` lookups) becomes its own immutable snapshot that `/reload`
can swap without touching the `AssetSnapshot` in-flight chunks hold. Today the whole runtime is
republished for this; separating them is what makes a tag reload cheap and an asset reload
impossible-by-construction.

**128c — block resolution at compile time.** `Tools.stringToState` parses blockstates against
`WorldTools.getOverworld()`; moving that into the compiler retires the last non-editor `ServerAccess`
users. Decide #91 (optional/missing mod blocks) here: "resolve blocks before publication" plus
aggregated diagnostics *is* the load-time validation #91 asks for, and its weight re-normalization
over surviving entries is digest-affecting, so it needs its own golden approval.

**128d — rename `*RE` → `*Definition`/`*Patch`.** Mechanical only, no logic, so the rename noise
never hides a semantic change. Also retires `IAsset.setRegistryName`: identity travels beside the
decoded value instead of being written into it.

**Then #56's remainder** becomes possible — the palette-character / slice-size / dangling-reference
walk, which needs the compile-time palette merge 128a introduces. See the sequencing comment on #56.

## Digest expectations

128a, 128b and 128d must not move either golden: they relocate where compiled assets live, not what
they compile to. 128c may move them via #91 and must isolate that.
