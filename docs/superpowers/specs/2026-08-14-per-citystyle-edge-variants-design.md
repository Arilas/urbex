# Per-City-Style Edge Variants

**Status:** Approved design

**Date:** 2026-08-14

## 1. Decision

Extend each `worldStyle.citystyles[]` selector entry with an optional, atomic
`edge` definition:

```json
{
  "factor": 0.5,
  "citystyle": "urbex:citystyle_standard",
  "edge": {
    "citystyle": "urbex:citystyle_border",
    "threshold": 0.4
  }
}
```

The selected entry is one coherent city-style family. Its existing
`citystyle` is the base member, and its optional `edge.citystyle` is used where
that entry's city factor is strictly below `edge.threshold`.

Both the reference and the threshold belong to the selector entry. They do not
belong to the selected preset, the world style as a whole, or the referenced
city-style asset. An entry that declares no `edge` uses its base `citystyle`
everywhere.

Remove the preset fields `cities.cityStyleThreshold` and
`cities.cityStyleAlternative` with no compatibility path. Stale uses are load
errors that direct the author to the new per-entry `edge` object.

## 2. Why the current ownership is wrong

A preset describes generation shape: city chance, radii, the city mask,
damage, floor limits, and similar options. A world style describes what that
shape looks like. Urbex deliberately lets a player combine either selector
independently.

The current alternative mechanism crosses that boundary. The
`urbex:largecities` preset names `urbex:citystyle_border` directly, so selecting
Large Cities with ModernTweaks, Zombie Apocalypse, Chaos, or a mixed world
style can produce one pack's base style and Urbex's border style in the same
city. Conversely, every shipped add-on border city style is unreachable from
its own world style. There is no implicit binding through filenames,
`CityStyle.style`, or `extends`; only the preset reference activates a border.

The world style already owns the weighted and biome-filtered choice among
radically different city appearances. An edge is a spatial variant of that
choice, not another independent top-level draw. Binding the edge to the
selector entry keeps a megapolis with its megapolis edge and a village with
its village edge.

## 3. Goals and non-goals

### 3.1 Goals

- Let every `citystyles[]` entry opt into its own edge style and width.
- Keep entries without an edge valid and base-only.
- Keep base and edge inside the same selected datapack/style family.
- Apply the family under every preset; presets remain orthogonal.
- Make centred cities internally coherent.
- Make Perlin cities coherent over the existing 16-by-16-chunk style regions.
- Validate edge references and wiring at world load.
- Remove the old preset mechanism completely and report stale data clearly.
- Leave an obvious typed extension point for future spatial roles.

### 3.2 Non-goals

- District generation or a generic spatial-role engine.
- A generic `additions` property whose contents worldgen interprets
  dynamically.
- Edge declarations inside city-style assets.
- A world-style-wide edge shared implicitly by every selector entry.
- Legacy decoding, fallback, migration, or precedence for the two preset
  fields.
- Preserving the existing worldgen digests. The bundled world style will use
  edges under every preset, so the visual change is intentional.

## 4. Data model

### 4.1 Selector shape

`CityStyleSelector` keeps its existing fields:

- `factor` — positive weight used to choose this selector entry.
- `citystyle` — fully qualified base city-style id.
- `biomes` — optional biome matcher.

It gains:

- `edge` — optional `CityStyleEdge`.

`CityStyleEdge` has exactly two required fields:

- `citystyle` — fully qualified edge city-style id.
- `threshold` — finite float satisfying `0 < threshold <= 1`.

The nested object is atomic. Neither a style without a threshold nor a
threshold without a style is legal. A threshold of zero is meaningless; the
author expresses that state by omitting `edge`.

Existing world-style entries that omit `edge` remain valid. This is not a
fallback: absence has the positive meaning "this family has no edge."

### 4.2 Example with independent families

```json
"citystyles": [
  {
    "factor": 0.5,
    "citystyle": "example:megapolis",
    "edge": {
      "citystyle": "example:megapolis_edge",
      "threshold": 0.35
    }
  },
  {
    "factor": 1.0,
    "citystyle": "example:village"
  },
  {
    "factor": 9.0,
    "biomes": {
      "if_any": ["minecraft:desert", "minecraft:badlands"]
    },
    "citystyle": "example:desert_city",
    "edge": {
      "citystyle": "example:desert_outskirts",
      "threshold": 0.5
    }
  }
]
```

The village is base-only. Neither of the other entries can donate its edge to
it.

### 4.3 World-style inheritance

The existing `Mergeable<CityStyleSelector>` list semantics do not change. A
child world style replaces the inherited selector list by default or appends
when it declares `replace: false`. The edge travels atomically with its own
selector entry; selector entries do not merge field by field and do not inherit
edges from entries with matching base ids.

## 5. Runtime representation

Add two focused value types near the existing selector data:

```java
record CityStyleEdge(String cityStyle, float threshold) {}

record CityStyleSelection(String cityStyle,
                          Optional<CityStyleEdge> edge) {}
```

Names may follow the surrounding package's final conventions, but the two
responsibilities stay separate:

- `CityStyleSelector` owns eligibility and selection weight.
- `CityStyleSelection` is the family produced after that selector wins.
- `CityStyleEdge` decides the optional alternate member and its spatial
  boundary.

`WorldStyle.getRandomCityStyle` becomes a selection-returning operation. It
still filters entries by biome and uses `factor` as the weighted-draw input,
but returns the winning `CityStyleSelection` instead of a string id. Edge
selection is not a second random draw.

`City` resolves the final id from a selection and a city factor:

```text
edge present and cityFactor < edge.threshold  -> edge.citystyle
otherwise                                      -> selection.citystyle
```

Equality uses the base member, matching the current strict comparison.

## 6. Selection scope and data flow

### 6.1 Centred cities

For radius-based cities, select the family at the city centre using that
centre's world style, biome, and addressed `CITY_STYLE` random source. Every
chunk influenced by that centre asks for the same selection. Its individual
distance factor then chooses the base or edge member.

For a radius `r` and distance `d`, the current factor is `(r - d) / r`. An edge
threshold of `0.4` therefore covers the outer 40 percent of the radius, subject
to the preset's city mask threshold.

When several city centres influence one chunk, preserve the existing blend:

1. Resolve each centre's stable selection.
2. Resolve that selection to base or edge with that centre's factor.
3. Add the resulting id with the same factor weight used today.
4. Perform the existing addressed weighted draw among the candidates.

This keeps overlap behaviour while preventing a centre from borrowing another
family's edge.

### 6.2 Perlin cities

Perlin city masks have no discrete centre. Use the existing
`WorldStyleField.PERLIN_REGION_CHUNKS` grid as the family scope:

1. Compute the 16-by-16-chunk region with `floorDiv`, including for negative
   coordinates.
2. Use that region's world style, as `WorldStyleField.atChunk` already does.
3. Use a canonical chunk anchor for the region's biome lookup and
   `CITY_STYLE` RNG address.
4. Draw one `CityStyleSelection` for the region.
5. Use each chunk's Perlin city factor only to choose base versus edge.

This also fixes the current inconsistency where the mixed-world-style Perlin
path calls the city-centre accessor at every chunk and bypasses the coarse
region rule. A megapolis and village must not alternate chunk by chunk inside
one continuous Perlin blob.

The exact anchor convention is the region's minimum chunk coordinate
`(regionX * 16, regionZ * 16)`. The same helper must supply both biome and RNG
coordinates so the two cannot drift apart.

### 6.3 Biomes

Biome matching happens at the centre or Perlin-region anchor where the family
is selected. The biome under an edge chunk does not reroll the family. This is
the existing centred-city coherence rule extended to the whole selection.

### 6.4 Presets

An edge applies whenever its selector entry wins, under Default, Large Cities,
Only Cities, and every other preset. A preset still determines city factor and
whether the chunk is a city. The world style maps that factor to an appearance.

Some preset/world-style combinations can make an edge invisible—for example,
when the preset's city threshold is greater than or equal to the edge
threshold. That is a valid orthogonal combination, not a load error or warning.

## 7. Predefined cities

A predefined city with no explicit `citystyle` follows the ordinary
world-style selector and receives its selected family's optional edge.

A predefined city with an explicit `citystyle` deliberately bypasses the
selector. Represent it as a base-only `CityStyleSelection`; it has no implicit
edge. Adding explicit predefined-city spatial variants would be a separate
feature and schema decision.

## 8. Preset breaking change

Delete `cityStyleThreshold` and `cityStyleAlternative` from:

- `CitySettings` and its key set/codec/application path;
- `PresetDraft`, `Preset`, copies, accessors, and serialization;
- the customization GUI and setting registry;
- language strings and tooltips;
- preset JSON schema and documentation;
- runtime validation and asset-reachability traversal;
- tests and fixtures that describe the old fields.

Add both names to the preset retired-key guard. A registry preset or per-world
override that still contains either field must fail with a message equivalent
to:

```text
Preset key 'cities.cityStyleAlternative' was removed; declare
the selected world style's 'citystyles[].edge' with 'citystyle' and
'threshold' instead.
```

There is no deprecated fallback, legacy mode, automatic conversion, or
special-case behaviour for `urbex:largecities`.

## 9. Asset compilation and validation

Every world-style selector contributes these reachable city-style roots:

- its required base `citystyle`;
- its optional `edge.citystyle`.

The edge reference joins the same eager compilation, `extends` resolution,
wiring completeness checks, palette checks, and asset-graph validation as the
base reference. A missing or incomplete edge refuses the world during loading,
not later on a generation worker.

Remove the preset alternative route from `AssetCompiler.reachableCityStyles`.
Predefined-city references remain a separate route. Update the completeness
and reference-integrity tests to traverse nested edge objects.

An edge is a direct reference to a city-style asset, not inheritance and not a
recursive selection. The city-style asset itself gains no edge field, so this
change introduces no new graph cycle shape.

## 10. Bundled and add-on data migration

### 10.1 Urbex

In `worldstyles/standard.json`, add this edge to both the standard and desert
selector entries:

```json
"edge": {
  "citystyle": "urbex:citystyle_border",
  "threshold": 0.4
}
```

Using the same edge for both retains the old Large Cities relationship, where
the preset chose `urbex:citystyle_border` regardless of which biome-selected
base style won. The deliberate behaviour change is that this edge now appears
under every preset.

Remove both retired fields from `presets/largecities.json`.
`urbex:citystyle_border` then becomes reachable through its owning world style
rather than through an unrelated preset.

### 10.2 Add-on packs

Each add-on migrates its active pack independently after the Urbex schema
lands:

- ModernTweaks attaches `urbexmt:citystyle_border` only to the standard,
  desert, jungle, and snowy entries whose authors want that edge. There is no
  implicit all-entry attachment.
- Zombie Apocalypse attaches `urbexza:citystyle_border` to its standard entry.
- Chaos attaches `urbexchaos:citystyle_border1` to its standard entry.

Those repositories decide their own thresholds per entry. Urbex provides no
default threshold and does not infer one from the former Large Cities value.

## 11. Testing

### 11.1 Codec and schema

- A selector without `edge` decodes and round-trips unchanged.
- A complete edge decodes and round-trips.
- Missing `edge.citystyle` fails at that path.
- Missing `edge.threshold` fails at that path.
- Blank or unqualified edge ids fail with the qualified-id hint.
- Non-finite, zero, negative, and greater-than-one thresholds fail.
- Both retired preset keys fail independently and together with the migration
  hint.
- The generated/checked JSON schemas accept the new object and refuse the old
  fields.

### 11.2 Selection semantics

- A factor below the threshold uses the edge.
- A factor exactly equal to the threshold uses the base.
- A factor above the threshold uses the base.
- A selector without an edge always uses its base.
- One selector cannot inherit or borrow another selector's edge.
- Two entries can use different edge ids and thresholds.
- Biome matching chooses the family at its scope anchor, not at an observing
  edge chunk.

### 11.3 Spatial coherence

- Every chunk influenced by one centred city observes the same selection.
- Base and edge in a mixed-world-style city come from the same selector entry.
- Perlin chunks in one 16-by-16 region observe one selection.
- Adjacent Perlin regions can draw different selections deterministically.
- Negative coordinates use the intended floor-divided region and anchor.
- Overlap blending keeps its existing candidate weights after each candidate
  resolves base versus edge.
- An explicit predefined city is base-only.
- A predefined city without an explicit style follows the normal family.

### 11.4 Asset validation

- Edge ids are part of reachability and reference integrity.
- A missing edge id refuses load.
- An edge style with incomplete required wiring refuses load.
- The bundled `urbex:citystyle_border` is reachable without any preset route.
- Removing the final reference to an edge makes it an allowed extend-only or
  otherwise unreachable asset, matching existing reachability policy.

### 11.5 Generation regressions

Both generation digests are expected to change because the default world style
now uses an edge under every preset. Update goldens only after the targeted
tests above prove the new spatial boundary and family coherence. Record the
before/after digests and visually inspect at least one radius city and one
Large Cities Perlin boundary; do not treat accepting new digest output as the
test of the feature.

## 12. Documentation

Update the datapack guide and world-style schema with one base-only example and
one edge example. Explain the two similarly named numeric concepts explicitly:

- selector `factor` is a weighted-choice weight;
- `edge.threshold` is a spatial city-factor boundary.

Document that edge is per entry, optional, applies under all presets, and uses
the family anchor's biome. Document the breaking removal under preset
migration notes and remove all examples that place the alternative in a
preset.

The GUI exposes no replacement control. Edge composition is authored by the
datapack's world style, while the GUI continues to select a preset and world
style orthogonally.

## 13. Future districts

The selection object is the extension point, but this branch adds no generic
role collection and no district schema. A future design can add a typed
`districts` list beside `edge`, with its own weights, anchors, and spatial
algorithm, without changing the meaning of the base or edge members.

Avoiding a generic `additions` map is deliberate: an edge is chosen by city
factor, while a district may need region seeds, adjacency, size constraints,
or road-aware boundaries. Those should not share an untyped execution model
merely because both select city styles.

## 14. Acceptance criteria

The change is complete when:

1. Every world-style selector accepts an optional atomic edge.
2. Base and edge selection obey the exact threshold semantics above.
3. Centred and Perlin cities keep a stable family at their defined scope.
4. Entries without an edge remain base-only under every preset.
5. No runtime or data-model reference to the two preset fields remains.
6. Stale preset keys fail with a specific migration message.
7. Base and edge references receive identical eager asset validation.
8. Bundled standard and desert families use `urbex:citystyle_border` at `0.4`.
9. Targeted tests pass and the intentional digest changes are reviewed.
10. Datapack and schema documentation describe the new ownership accurately.
