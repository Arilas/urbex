# Independent lighting and loot density controls

Date: 2026-08-09
Status: approved, ready for planning
Supersedes: the `generateLighting` and `generateLoot` profile booleans and the two legacy loot-suppression chances

## 1. Goal

Replace the binary lighting and loot profile switches with two independent `0.0..1.0` density
controls:

- `lightingDensity` decides independently at every optional decorative-light marker.
- `lootDensity` decides independently at every loot-container marker.

Lighting source selection becomes data-driven. A palette can offer different weighted sources for
floor, wall, ceiling, or support-independent placement, allowing lanterns, end rods, full-block
lights, and datapack-provided sources instead of converting every accepted marker into a vanilla
torch.

The Customize screen exposes both values as percentage sliders. Bundled profiles receive thematic
defaults, while existing user profile files remain untouched and migrate when loaded.

## 2. Current behavior and constraints

The current implementation has several distinct mechanisms that look related but serve different
purposes:

- `LostCityProfile.GENERATE_LIGHTING` and `GENERATE_LOOT` are independent booleans.
- Only palette entries with `"torch": true` obey `GENERATE_LIGHTING`.
- `fixTorches()` stores only marker positions and replaces every accepted marker with a literal
  torch or wall torch. The palette's selected block state is discarded.
- The `urbex:lights` block tag includes lanterns and many other emitters, but it is used only to
  schedule lighting updates after a palette block is placed. It is not an optional-light pool.
- The common palette's glowstone (`h`) and lit redstone-torch (`g`) entries are always placed.
- Loot scarcity is split between `generateLoot`, `chestWithoutLootChance`, and
  `buildingWithoutLootChance`.
- The building-wide `noLoot` decision also suppresses spawners, so the existing advanced loot
  setting is not actually independent from hostile-content generation.

The bundled assets contain 176 optional `T` markers across 77 parts, 34 loot-container `C` markers
across 20 parts, eight glowstone `h` markers, and 73 redstone-torch `g` markers. The redstone
torches occur primarily in rail, monorail, oil-rig, and radio-tower parts and are functional rather
than decorative. They must remain outside the new lighting-density system.

Urbex worldgen uses addressed RNG: a decision is a pure function of seed, position, and
`Rng.Purpose`. New purposes must be appended to the enum, never inserted or reordered.

## 3. Profile model

### 3.1 New fields

`LostCityProfile` gains:

```java
public float LIGHTING_DENSITY;
public float LOOT_DENSITY;
```

They serialize in the `lostcity` category as:

```json
{
  "lostcity": {
    "lightingDensity": 0.15,
    "lootDensity": 0.65
  }
}
```

Both are constrained to `0.0..1.0`. These are the only settings that control decorative-light
presence and loot-container population in newly written profiles.

### 3.2 Retired fields

Newly written profiles omit:

- `generateLighting`
- `generateLoot`
- `chestWithoutLootChance`
- `buildingWithoutLootChance`

The corresponding Java fields and the building-wide `noLoot` state are removed. Spawners check
only `generateSpawners`; no loot setting or loot roll participates in a spawner decision.

### 3.3 Legacy migration

The JSON loader recognizes the retired fields only when a new density field is absent. A new field
always wins if a profile contains both forms. Lighting and loot migrate independently, so a profile
may use a new field for one feature and a legacy field for the other. When neither form is present,
the profile keeps its in-memory default.

Legacy lighting maps directly:

```text
generateLighting == false -> lightingDensity = 0.0
generateLighting == true  -> lightingDensity = 1.0
```

Legacy loot maps its former average for a single building into one per-container probability:

```text
generateLoot == false -> lootDensity = 0.0
generateLoot == true  -> lootDensity =
    (1 - buildingWithoutLootChance) * (1 - chestWithoutLootChance)
```

Missing legacy chance fields use their old defaults of `0.2`, producing `0.64` for an ordinary
legacy profile. This is necessarily approximate: multibuildings previously skipped the
building-wide suppression roll, and the old distribution clustered empty containers by building.
The new behavior deliberately uses one independent roll per marker everywhere.

Migrated objects serialize only the new density fields. Files under `config/urbex/profiles/` are
still never overwritten; the migration is in memory unless the user explicitly saves a profile.

## 4. Typed palette light pools

### 4.1 Schema

A `PaletteEntry` can use `light` as a block-source alternative alongside `block`, `blocks`,
`variant`, and `frompalette`:

```json
{
  "char": "T",
  "light": {
    "floor": [
      { "weight": 3, "block": "minecraft:lantern" },
      { "weight": 2, "block": "minecraft:torch" }
    ],
    "wall": [
      { "weight": 4, "block": "minecraft:wall_torch[facing=north]" },
      { "weight": 1, "block": "minecraft:end_rod[facing=north]" }
    ],
    "ceiling": [
      { "weight": 1, "block": "minecraft:lantern[hanging=true]" }
    ],
    "free": [
      { "weight": 1, "block": "minecraft:sea_lantern" }
    ]
  }
}
```

All four lists are optional, but at least one must be non-empty. Weights are positive integers and
may sum to any positive value; unlike legacy `BlockEntry.random`, they do not need to fill a
128-entry array.

Placement kinds mean:

- `floor`: requires a supporting block below; directional states face up.
- `wall`: requires horizontal support; directional states face away from that support.
- `ceiling`: requires support above; directional states face down. Hanging state must be authored
  in the block-state string where the block uses a separate property such as `hanging=true`.
- `free`: does not require an anchor and preserves the authored orientation. It is intended for
  full-block emitters and similar sources.

The first decoded candidate is the representative state used by editor/display paths that require
one concrete block. Generation always uses the typed pool.

### 4.2 Validation

Datapack loading rejects a pool with:

- no candidates;
- a non-positive weight;
- an unknown or invalid block-state string;
- a state whose emitted block light is zero;
- a placement/state combination that is structurally impossible to orient.

Diagnostics identify the palette resource, character, placement kind, and offending candidate.
Custom datapacks may deliberately use weak nonzero emitters. A resource-validation test applies a
stricter rule to Urbex's bundled pools: every shipped candidate must emit at least light level 14.
This excludes redstone torches and all soul-light variants from the bundled decorative pools while
leaving datapack authors free to opt into them.

### 4.3 Compatibility and the light tag

Existing `"torch": true` entries remain valid and retain the old floor/wall torch-placement
behavior. This path is compatibility-only; bundled assets migrate to `light` pools.

The `urbex:lights` tag continues to mean "schedule a lighting update for this placed palette
state." It does not make a block optional and is not a source-selection pool. Optional lights
schedule their update directly after successful placement, so custom light-pool candidates do not
depend on being added to that tag.

## 5. Lighting generation

### 5.1 Addressed decisions

Append two purposes to `Rng.Purpose`:

```java
LIGHTING_DENSITY,
LIGHTING_VARIANT
```

At every typed or legacy optional-light marker:

```java
Rng.floatAtPos(seed, x, y, z, LIGHTING_DENSITY) < profile.LIGHTING_DENSITY
```

decides whether the marker is accepted. A rejected marker becomes air.

Accepted typed markers queue a `LightTodo` containing the absolute position and immutable compiled
pool. `BuildingInfo.torchTodo` becomes a typed light-todo collection, and `fixTorches()` becomes
`placeOptionalLights()`. Both ordinary part generation and bridge generation use the same path.

The placement phase remains where torch fixing runs today: after city and transport parts are
assembled, but before ruins/explosions finish damaging the chunk. This lets a source inspect its
final local support while preserving the rule that later damage may destroy it.

### 5.2 Placement selection

For an accepted marker, placement proceeds deterministically:

1. Create one `RandomSource` addressed at the marker position with `LIGHTING_VARIANT`.
2. Visit placement opportunities in the stable order floor, west wall, east wall, north wall,
   south wall, ceiling, then `free`. Skip an anchored opportunity when its support is absent and
   skip any opportunity whose pool is empty.
3. For the current opportunity, draw once from its total weight and try the selected candidate.
4. Apply the anchor-derived direction to a state with a compatible facing property and check the
   state's actual survival rule against the generated surroundings.
5. If it cannot survive, try the other candidates from that same list in JSON order, starting after
   the selected candidate and wrapping once. These fallback attempts make no random draws.
6. If that list has no surviving candidate, continue to the next placement opportunity. Its
   weighted choice consumes the next draw from the same position-addressed stream.
7. Place the first valid result and schedule a client/light update. If every opportunity fails,
   leave air.

Density and variant selection use separate purposes. Increasing density therefore adds a stable
set of new lights; it never changes the source type at a position that was already accepted.
Generation order and chunk-thread scheduling cannot affect either decision.

Placement failure is expected for some damaged or unusual surroundings and does not log per
marker. Invalid pool definitions fail at resource load instead of producing worldgen log spam.

### 5.3 Redstone exclusions

The common palette's `g` mapping remains an ordinary lit redstone-torch block. Its 73 markers are
not converted to optional lights, do not roll lighting density, and are not source candidates.
Changing lighting density cannot add, remove, or reroll them.

## 6. Loot generation

Append a third purpose:

```java
LOOT_DENSITY
```

The existing `LOOT` purpose remains responsible for condition/table selection. Keeping density on
its own purpose means changing density cannot reroll the contents of a container that remains
populated.

For every palette entry with loot metadata:

1. Place the container block unconditionally, exactly as an old rejected/no-loot marker did.
2. Roll `LOOT_DENSITY` at the container's absolute position.
3. Queue the existing post-generation loot work only when the roll is below `LOOT_DENSITY`.
4. Use the existing `LOOT` stream to choose and assign the loot table.

At `0.0`, every marked container exists but is empty. At `1.0`, every surviving marked container
receives a loot table. Intermediate values operate independently per container, including in
multibuildings.

`BuildingInfo.noLoot` and its building-wide roll are deleted. `handleSpawner()` checks only
`GENERATE_SPAWNERS`, so worlds generated with the same seed and spawner setting have identical
spawners at every loot density.

Lighting and loot use different purposes and no shared sequential stream. Neither density can
affect the other feature's positions, variants, or outcomes.

## 7. Bundled assets and profile defaults

### 7.1 Common palette

The common palette changes as follows (weights are relative within each list):

- `T.floor`: regular lantern `6`, torch `3`, upward end rod `1`.
- `T.wall`: wall torch `8`, outward-facing end rod `2`.
- `T.ceiling`: hanging regular lantern `8`, downward end rod `2`.
- `h.free`: glowstone `6`, sea lantern `2`, shroomlight `1`, ochre froglight `1`.
- `g` remains the existing redstone-torch mapping.

No part geometry needs to change. Existing `T` and `h` characters gain density and variety through
their palette mappings.

### 7.2 Standard profiles

New installations and the regenerated read-only `profiles/defaults/` references use:

| Profiles | Lighting density | Loot density |
|---|---:|---:|
| `default`, `nodamage`, `floating`, `rarecities`, `onlycities`, `tallbuildings`, `atlantis` | 0.15 | 0.65 |
| `cavern`, `biosphere_caves` | 0.65 | 0.65 |
| `space`, `biosphere` | 0.50 | 0.65 |
| `largecities` | 0.35 | 0.65 |
| `ancient`, `wasteland`, `bio_wasteland` | 0.05 | 0.40 |
| `safe` | 1.00 | 0.00 |
| `void_outside` | 0.00 | 0.00 |

The source-defined standard-profile values do not overwrite files in `config/urbex/profiles/`.
Legacy user files loaded from that directory use the migration in section 3.3.

## 8. Customize UI

On the Customize screen's `Various` page, replace the two boolean elements with percentage
sliders:

- `Lighting density: N%`
- `Loot density: N%`

A focused `PercentageSliderElement` maps `0..100` integer display steps to the normalized float in
the profile configuration. Each slider moves in one-percentage-point increments, updates its label
immediately, participates in the existing customized-profile copy/serialization flow, and is
enabled only for the customized profile like the other fields.

The existing Spawners On/Off control remains separate and adjacent. The slider tooltips use the
profile comments to explain that lighting operates on optional decorative markers and loot
operates on marked containers.

## 9. Component boundaries

- `LostCityProfile`: values, constraints, serialization, and legacy migration only.
- Palette codec/data classes: typed-pool decoding and structural validation.
- Compiled palette: immutable compiled candidates and representative state.
- A focused optional-light placer: support discovery, orientation, weighted selection, survival
  fallback, and update scheduling.
- Terrain generation: density decision and todo queuing only.
- Loot generation: per-container decision and loot-table assignment only.
- Customize UI: percentage presentation and normalized-value binding only.

The light-placement logic should not become another collection of block-specific branches inside
`LostCityTerrainFeature`. Its inputs and outputs must be small enough to test without generating a
world chunk.

## 10. Verification

### 10.1 Automated tests

Profile tests cover:

- density constraints and JSON round trips;
- omission of all four retired fields;
- new fields winning when both schema forms are present;
- false/true legacy lighting migration;
- disabled and enabled legacy loot migration, including the default `0.64` result;
- exact standard-profile values;
- non-overwrite behavior for existing profile files.

Palette and placement tests cover:

- valid floor/wall/ceiling/free pools;
- invalid state strings, zero-light states, non-positive weights, and empty pools;
- diagnostics containing palette, character, placement, and candidate;
- legacy `"torch": true` decoding and placement;
- floor, every wall direction, ceiling, and free placement;
- candidate orientation and deterministic fallback;
- no-support/no-valid-candidate behavior;
- bundled candidates all emitting at least level 14;
- the common `g` mapping remaining a non-optional redstone torch;
- successful decoding of every bundled asset.

RNG and generation tests cover:

- reproducibility at fixed seed and position;
- independence from iteration and generation order;
- monotonic lighting density: a higher density is a superset, with unchanged variants at shared
  positions;
- lighting decisions unchanged across all loot densities and vice versa;
- containers present but empty at `0.0` loot density;
- every surviving marked container populated at `1.0`;
- identical spawner output across all loot densities;
- loot tables unchanged at shared accepted positions when density changes.

### 10.2 Smoke tests

- Open Customize, create a customized profile, move both sliders through `0%`, an intermediate
  value, and `100%`, and verify the selected JSON values.
- Generate fixed-seed comparison areas at lighting `0%`, `50%`, and `100%`; confirm subset behavior,
  mixed high-output source types, correct support/orientation, and unchanged redstone torches.
- Generate fixed-seed comparison areas at loot `0%`, `50%`, and `100%`; confirm container presence,
  subset behavior, stable contents at shared positions, and identical spawners.
- Load one unmodified legacy profile and one legacy datapack containing `"torch": true`.

## 11. Acceptance criteria

- Profiles expose exactly one lighting density and one loot density, each `0.0..1.0`.
- Customize exposes both as independent `0..100%` sliders.
- Optional lighting is decided per marker and loot is decided per container.
- Increasing either density is deterministic and monotonic for its own feature.
- Changing one density cannot affect the other feature.
- Loot density cannot affect spawners, and containers remain present when loot is rejected.
- Bundled optional lights include multiple placement-compatible high-output source types.
- Bundled redstone torches are neither optional nor part of a light pool.
- External legacy torch assets and legacy profiles load without crashing.
- Existing user profile files are not overwritten.
- All automated tests pass and the fixed-seed smoke comparisons satisfy section 10.2.

## 12. Out of scope

- Loot quality, table contents, or rarity tiers.
- A numeric spawner-density control.
- Making functional emitters such as lava, portals, brewing stands, or redstone devices optional.
- Automatically treating every block in `urbex:lights` as decorative.
- Reauthoring building-part geometry to add more marker positions.
- Guaranteeing that arbitrary custom low-output sources prevent hostile spawning.
