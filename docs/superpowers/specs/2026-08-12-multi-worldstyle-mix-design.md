# Weighted World-Style Mixing

**Status:** Approved design

**Date:** 2026-08-12

## 1. Decision

Let a world be created with **several world styles at once, balanced by
weight**, so that each city draws its own style and a single world can contain
cities from several datapacks.

An experimental config flag, `experimentalMultiWorldStyles`, gates the whole
feature. With it off — the default — everything behaves exactly as it does
today: one world style per dimension, one picker row click, byte-identical
worldgen.

The first consumer is `Urbex-ModernTweaks`, which registers
`urbexmt:moderntweaks` alongside the built-in `urbex:standard`. A world created
with `urbex:standard` at `0.1` and `urbexmt:moderntweaks` at `0.9` should
generate roughly one Urbex-flavoured city for every nine ModernTweaks ones,
with each individual city internally coherent.

## 2. Compatibility Policy

Additive and reversible. The feature must be invisible when the flag is off.

- Existing saves carry `worldStyle` in `UrbexData`; they keep working
  untouched.
- A single-entry mix takes a fast path that draws **no random at all**, so it
  resolves to exactly the same `WorldStyle` object as today.
- **Both worldgen digests must be unchanged by this branch.** `digest.golden`
  and `digest-features.golden` are the acceptance gate for the whole feature —
  they run the default preset with the default single world style, which is
  precisely the path that must not move.

## 3. What a world style actually governs

`WorldStyleRE` carries eight settings blocks, and they do not share a scope.
Mixing forces that to become explicit. Today all twelve read sites go through
one dimension-wide `IDimensionInfo.getWorldStyle()`:

| Call site | Reads | Scope under mixing |
|---|---|---|
| `City.getCityStyleForCityCenter` | `citystyles` | the centre's own style |
| `City.getCityStyleInt` (no city in range) | `citystyles` | chunk |
| `BuildingInfo.getOutsideStyle` | `outsidestyle` | chunk |
| `CityGenerator.rotatableTag` | `rotatable` | chunk |
| `MultiChunk.getOrCreate` | `multisettings.areasize` | **world-wide** (§5.2) |
| `MultiChunk.calculateBuildings`, `isMultiBuildingOk` | rest of `multisettings` | multichunk anchor |
| `CityGenerator.doNormalChunk` | `scattered` | scatter-area anchor |
| `Highways`, `Railway`, `Railways` | `parts` | world-wide |
| `City.getCityFactor` | `citybiomemultipliers` | world-wide (§5.3) |
| `BuildingInfo` railway avoidance / height | `settings` | world-wide |

## 4. Components

### 4.1 `WorldStyleMix` — `dev.krona.urbex.setup`

A validated, ordered list of `(Identifier style, float weight)`.

- Non-empty; every weight strictly positive. A mix that cannot satisfy that
  fails to construct rather than silently dropping entries.
- `primary()` — the highest-weighted entry, ties broken by id string, so two
  equally-weighted styles still pick the same primary on every machine and
  every run.
- `single()` — `Optional<Identifier>`, present iff there is exactly one entry.
- `of(Identifier)` — the single-entry mix every non-mixing path builds.
- A `Codec` for saved data, and a compact string grammar (§6) shared by config
  parsing and persistence, so there is one parser rather than three.

`PresetChoice.worldStyle()` becomes `worldStyles()` returning a
`WorldStyleMix`. The record's other two components are unchanged.

### 4.2 `WorldStyleField` — `dev.krona.urbex.worldgen`

Resolves the mix's ids into `WorldStyle` objects once, at dimension
construction, and answers *which style applies here*:

```java
WorldStyle primary();
WorldStyle atCityCenter(ChunkCoord center);
WorldStyle atScatterArea(ChunkCoord anchor);
WorldStyle atMultiArea(ChunkCoord anchor);
WorldStyle atChunk(ChunkCoord coord);
```

Each draw is `Tools.getRandomFromList` over the entries with
`Rng.at(seed, x, z, Rng.Purpose.WORLD_STYLE)` — the same addressed-randomness
discipline as every other worldgen decision, so generation order cannot change
what a world looks like.

**Single-entry fast path.** Every method returns the one resolved style
directly, without touching `Rng`. This is what makes §2's digest guarantee
hold, and it is the property `WorldStyleFieldTest` asserts most directly.

`IDimensionInfo.getWorldStyle()` is **replaced** by `worldStyles()` returning
the field. It is a replacement rather than an addition on purpose: leaving the
old accessor in place would let any of the twelve call sites keep reading a
dimension-wide style by accident, and the compiler would not say so.

### 4.3 `atChunk` — the dominant-city rule

`atChunk` is what gives a chunk near a city the *city's* flavour for
`outsidestyle` and `rotatable`.

- **Radius mode** (`CITY_CHANCE >= 0`): scan the same `CITY_MAXRADIUS`
  neighbourhood `City.getCityStyleInt` already scans, keep the centre with the
  highest distance factor (ties broken by chunk coordinate), and return
  `atCityCenter` of that centre. No centre in range → `primary()`.
- **Perlin mode** (`CITY_CHANCE < 0`, shipped by `largecities.json`): there are
  no discrete centres to attribute a chunk to. Draw instead on a coarse
  16-chunk region grid, so a contiguous city blob shares one style rather than
  flickering per chunk.

Cached in `DimensionCaches` as a `TimedCache<ChunkCoord, WorldStyle>`,
populated with `getOrCompute` rather than `computeIfAbsent` for the same
recursive-population reason every other cache in that class documents.

## 5. Scope decisions that are not "per city"

### 5.1 Highways, railways and world settings

`parts` and `settings` come from `primary()`. A highway or rail line runs for
hundreds of chunks between cities; drawing per segment would let it change
datapack partway along its run, and the cross-chunk part continuity the carver
stage depends on would have to agree across a boundary where two packs disagree
about what a tunnel looks like.

### 5.2 `multisettings.areasize`

From `primary()`, unlike the rest of `multisettings`. `areasize` *defines* the
multichunk grid that `MultiChunk.getMultiCoord` divides by — a per-area value
would have to be read from an area that has not been identified yet. The
remaining fields (`minimum`, `maximum`, `attempts`, `correctstylefactor`) draw
at the multichunk anchor and so do vary.

### 5.3 `citybiomemultipliers`

From `primary()`. It is read inside `City.getCityFactor`, which decides whether
a city exists at all; attributing it to a nearby city would be circular.

## 6. Config

### 6.1 The flag

`UrbexConfig` gains `experimentalMultiWorldStyles`, default `false`.

When off, a mix carrying more than one entry is reduced to its `primary()` and
the reduction logged, at every entry point — config parse, client publish, and
saved-data read. The flag gates *behaviour*, not just the UI, so a world file
hand-edited to carry a mix does not quietly get one on an install that has not
opted in.

### 6.2 `dimensionsWithPresets` grammar

```
minecraft:overworld=urbex:default@urbex:standard*0.1+urbexmt:moderntweaks*0.9
```

- `@` introduces the style spec, unchanged from today.
- `+` separates entries; `*` separates an id from its weight; the weight is
  optional and defaults to `1`.
- The separators are forced: `:` and `/` belong to `Identifier`, and `,`
  already separates entries of the `dimensionsWithPresets` JSON list itself.
- The existing single form `@urbex:standard` parses unchanged.
- Ids stay strictly qualified — `DataTools.fromName`, as today, so
  `@standard*0.1` is refused with the same "add a namespace" hint.

Malformed entries are logged and dropped, not thrown, matching
`parseDimensionPresetEntry`'s existing contract that one bad line does not take
the whole list down.

The global `selectedWorldStyle` stays a single id. It is the overworld-only
default for installs that never open the Cities tab, and mixing there adds a
third place to look for one setting without adding reach.

## 7. Persistence

`UrbexData` gains `worldStyleMix`, an optional string in the §6.2 entry grammar
(the `@`-less tail: `urbex:standard*0.1+urbexmt:moderntweaks*0.9`).

- The existing `worldStyle` field is kept, and is still the only thing written
  for a single-style world. Old saves and single-style worlds round-trip
  byte-identically.
- Read order: `worldStyleMix` when non-empty, else `worldStyle`, else
  `Config.DEFAULT_WORLD_STYLE`.

`Config.worldStyleFromClient` becomes `worldStyleMixFromClient`, a
`WorldStyleMix`. `PresetSelection.restore` and `discardPublication` follow it;
the Re-Create flow therefore restores a mix as faithfully as it restores a
single style today.

## 8. GUI

### 8.1 The dialog

`WorldStyleDialog` grows a mix mode, offered only when
`experimentalMultiWorldStyles` is on *and* at least two styles are registered.

```
Select World Style                              [x] Mix

  [x] urbex:standard            [-]  0.10  [+]     10%
  [x] urbexmt:moderntweaks      [-]  0.90  [+]     90%
  [ ] urbex:cavern              [-]  1.00  [+]      -

                 2 styles              [ Done ] [ Cancel ]
```

- Weights are **raw and normalized for display**. A player types the balance
  they mean — `0.1` and `0.9` — and reads back `10%` / `90%` without being
  made to produce numbers that sum to anything.
- Stepper granularity `0.05`, clamped to `[0.05, 10.0]`.
- Disabling the last enabled style is refused; the toggle is simply inert, so
  there is no state in which Done produces an empty mix.
- Cancel discards, as today.

With the flag off — or with one registered style — the dialog is exactly
today's list: click a row, it commits and closes. No new widget renders.

### 8.2 The Cities tab

- One style: `World Style: urbex:standard`, unchanged.
- Several: `World Style: 2 mixed`, with a tooltip listing each id and its
  normalized percentage.

`PresetSelection` carries a nullable `WorldStyleMix` in place of the nullable
style string; `effectiveWorldStyle()` is kept for the preview's single-style
key and `effectiveWorldStyleMix()` added beside it.

### 8.3 Preview

`CityPreview.Key` takes the mix's canonical string instead of one style id, and
`NullDimensionInfo` takes a `WorldStyleMix`. The preview then shows mixed
cities for a mixed selection, which is the only way a player can judge a
balance before committing to the world.

## 9. Determinism

- `Rng.Purpose.WORLD_STYLE` is **appended** to the enum, never inserted, per
  that enum's own standing rule.
- Every draw is addressed by `(seed, coordinate, purpose)`. Two chunks
  generated in either order see the same style.
- `primary()`'s tie-break is on the id string, not on registry iteration order,
  which is `ConcurrentHashMap` bucket order and would make the primary depend
  on file names.

## 10. Testing

| Test | Proves |
|---|---|
| `WorldStyleMixTest` | grammar round-trip; `primary()` and its tie-break; rejection of empty, zero and negative weights |
| `WorldStyleFieldTest` | single-entry path draws no random and returns the one style; weighted draw over many coordinates tracks the weights; same `(seed, coord)` → same style |
| `ConfigDimensionMixTest` | §6.2 grammar including the unchanged single form; malformed entries logged and dropped, not thrown |
| `UrbexDataMixTest` | old `worldStyle`-only saves round-trip; new saves prefer `worldStyleMix`; single-style worlds still write `worldStyle` |
| `WorldStyleDialogTest` (extended) | normalization and percentage maths; the last enabled style cannot be disabled |
| `ExperimentalGateTest` | flag off reduces a multi-entry mix to its primary at every entry point |
| `digestCheck`, `digestCheckFeatures` | both goldens unchanged — §2's gate |

Beyond the suite, the feature is verified against a real second datapack:
`Urbex-ModernTweaks` installed alongside the built-ins, a world created at
`urbex:standard` `0.1` / `urbexmt:moderntweaks` `0.9`, confirming that

1. cities of both flavours appear, in roughly that ratio,
2. each individual city is internally coherent — no half-and-half city,
3. **scattered structures mix too** — `urbex:radiotower` and `urbexmt:cabin`
   both appear, each drawn at its own scatter-area anchor,
4. highways and railways stay on one pack's parts for their whole run.

## 11. Files

**New:** `setup/WorldStyleMix.java`, `worldgen/WorldStyleField.java`, and the
tests of §10.

**Modified:** `setup/PresetChoice.java`, `setup/Config.java`,
`config/UrbexConfig.java`, `data/UrbexData.java`,
`worldgen/IDimensionInfo.java`, `worldgen/DefaultDimensionInfo.java`,
`worldgen/DimensionCaches.java`, `worldgen/CityFeature.java`,
`worldgen/CityGenerator.java`, `worldgen/lost/City.java`,
`worldgen/lost/BuildingInfo.java`, `worldgen/lost/MultiChunk.java`,
`worldgen/lost/Railway.java`, `worldgen/gen/Highways.java`,
`worldgen/gen/Railways.java`, `gui/CitiesTab.java`,
`gui/WorldStyleDialog.java`, `gui/PresetSelection.java`,
`gui/NullDimensionInfo.java`, `gui/preview/CityPreview.java`,
`varia/Rng.java`, `README.md`, `docs/datapacks.md`.
