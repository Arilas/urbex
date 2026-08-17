# `lightSource` palette entries

Date: 2026-08-17
Status: approved, ready for planning
Supersedes: the `torch` palette boolean, the `light` palette field, and the `urbex:lights` block tag
Builds on: `docs/superpowers/specs/2026-08-09-lighting-loot-density-design.md`

## 1. The bug this closes

`lightingDensity` only reaches a marker whose palette entry is a *light marker* — an entry carrying
`light` (a typed pool) or the legacy `torch: true`. Every other light-emitting block a pack author
placed is an ordinary palette entry and is placed unconditionally, at every density.

Counted over the shipped packs:

| Pack | Density-controlled markers | Always-on emitter markers |
|---|---:|---:|
| Urbex built-in | `T` 176, `h` 8 | `g` 63 |
| Zombie Apocalypse Essentials | `T` 176 | `h` 668 (glowstone), `g` 758 |
| ModernTweaks | **`T` 0** | `E` 212 + `e` 614 (lanterns), `h` 9, `g` 64 |

ModernTweaks authors its lighting entirely as `E`/`e` lanterns from `lanterns.json` and
`soul_lanterns.json`, and its parts contain no `T` marker at all. Its `common.json` still declares
`T` with the legacy `"torch": true`, so the setting is wired up and has nothing to act on. Moving
`lightingDensity` from 0 to 1 in a ModernTweaks world therefore changes nothing visible — exactly
the report. Zombie Apocalypse Essentials is the same failure at a milder ratio: 176 controlled
markers against 668 uncontrolled glowstone.

The root cause is structural, not a missing marker. "Is this optional?" is currently a property of
*which entry kind* the author used, when it should be a property the author states about the block
they already placed.

## 2. The change in one sentence

Any palette entry can declare `lightSource`; a light source is never filtered out of the output —
when the density roll rejects it, or when it cannot physically be placed, its authored **unlit
replacement** is written instead.

## 3. Schema

### 3.1 `lightSource`

`lightSource` replaces both `torch` and `light` on `PaletteEntry`. It accepts either `true` or an
object:

```json
{ "char": "e", "block": "minecraft:lantern[hanging=false]", "lightSource": true }
```

```json
{
  "char": "E",
  "block": "minecraft:lantern[hanging=true]",
  "lightSource": { "unlit": "minecraft:chain[axis=y]" }
}
```

```json
{
  "char": "T",
  "lightSource": {
    "floor":   [ { "weight": 6, "block": "minecraft:lantern[hanging=false]" },
                 { "weight": 3, "block": "minecraft:torch" } ],
    "wall":    [ { "weight": 8, "block": "minecraft:wall_torch[facing=north]" } ],
    "ceiling": [ { "weight": 8, "block": "minecraft:lantern[hanging=true]" } ],
    "free":    [ { "weight": 1, "block": "minecraft:sea_lantern" } ],
    "unlit":   "minecraft:air"
  }
}
```

`"lightSource": true` is sugar for `{}`.

Fields of the object, all optional:

| Field | Meaning |
|---|---|
| `floor`, `wall`, `ceiling`, `free` | Weighted lit candidates, as today's `light`. Present ⇒ **socket form**. |
| `unlit` | One block string written when the source is off or unplaceable. |
| `unlitBlocks` | A weighted `blocks` list written instead of `unlit`. Same shape as a palette entry's `blocks`. |

Each socket candidate may also carry its own `unlit` block string:

```json
{ "weight": 3, "block": "minecraft:torch", "unlit": "minecraft:candle[candles=1,lit=false]" }
```

The replacement belongs to the candidate because a socket's candidates are not interchangeable: an
unlit torch on a wall and an unlit torch on a floor are two different blocks, and one replacement
per socket could be right for at most one of its placements. A candidate naming none falls back to
the source's `unlit`, then to air. A candidate's `unlit` that emits light is a load error.

`unlit` and `unlitBlocks` are mutually exclusive; declaring both is a load error. Neither declared
means the replacement is air, which is what today's rejected light marker leaves behind.

### 3.2 The two forms

**Socket form** — the entry declares at least one of `floor`/`wall`/`ceiling`/`free`. The entry
needs no `block`/`blocks`/`variant`/`frompalette`; the pool *is* its block source. This is today's
`light` entry, renamed, plus a replacement. Placement is deferred so it can see its final
surroundings, discover support, orient itself, and check survival.

**In-place form** — the entry declares no placement list. Its own `block`/`blocks`/`variant`/
`frompalette` is the lit source, written exactly where and as the author wrote it, with no support
search and no survival check. This is the new form, and the one that fixes ModernTweaks: the author
already decided this lantern belongs on this ceiling.

The forms are distinguished by whether any placement list is present, never by a mode flag.

### 3.3 Validation

Socket form, unchanged from today except for the field name: at least one candidate across the four
lists; every weight positive; every candidate block-state string parseable; every resolved candidate
emitting light. A candidate naming a block this game does not have is dropped, not rejected (issue
#91); a pool whose candidates are *all* dropped compiles to a source that always writes its unlit
replacement, rather than failing the load.

In-place form: at least one of the entry's resolved states must emit light. An entry that resolves
to no state at all (every block absent from this game) is exempt — that is issue #91's case and
already resolves to air. A `lightSource` on an entry that resolves to states and none of them emit
is a load error naming the palette and the character; it is an authoring mistake with no reading
that makes sense.

`unlit`/`unlitBlocks` resolve like any other palette block: an absent block is dropped, and a
replacement whose every block is absent becomes air.

### 3.4 Removals

`torch` and `light` are removed from the codec. Both still *decode*, and `Palette.compile` throws a
migration error naming the palette, the character, and the replacement spelling. Silently ignoring
them would leave an old pack placing a permanent wall torch while its author believed the setting
still applied — the failure mode `docs/datapacks.md` calls out for misspelled keys, arrived at
deliberately.

This is a breaking datapack change and is intended as one.

## 4. The `urbex:lights` tag goes away

The tag has one consumer: `TagSnapshot.needsLightingUpdate`, which decides whether placing a state
has to tell the client to relight around it. That question has an exact answer already on the state
— `state.getLightEmission() > 0` — which needs no tag expansion, no `Set<BlockState>` membership
test on the hot path, and is right for modded emitters no bundled tag could enumerate.

`src/main/resources/data/urbex/tags/block/lights.json`, `UrbexTags.LIGHTS`, `UrbexTags.LIGHTS_TAG`
and `TagSnapshot`'s `statesNeedingLightingUpdate` set are deleted. `TagSnapshot`'s class javadoc,
which names `urbex:lights` as an example of a reloadable tag, is updated to name a surviving one.

Placement in `Parts.generatePart` currently reaches the lighting-update branch only through an
`else if` chain that a special entry short-circuits, so a light placed from a palette entry with
metadata never scheduled one. The emission check moves to a single site after `b` is final, so every
emitting block that is written schedules its update once, whatever branch produced it.

## 5. Generation

### 5.1 Compiled shape

`Palette.Info` carries `LightSource` in place of `boolean isTorch` and `LightPool light`:

```java
record LightSource(@Nullable LightPool pool, BlockChoice unlit) {
    boolean isSocket() { return pool != null; }
}
```

`BlockChoice` is a new two-case sealed type — one state, or 128 weighted slots built by the existing
`CompiledPalette.distributeSlots` — resolved by absolute position so it draws from no sequential
stream. It exists because the unlit replacement needs exactly the weighted-list semantics a palette
entry has and nothing else `CompiledPalette.Entry` carries.

### 5.2 At the marker

`Parts.handleLightMarker` becomes `Parts.handleLightSource`, called from both `Parts.generatePart`
and `Bridges.generateBridge`, with the already-resolved state in hand:

1. Roll `DensitySelector.lighting(seed, pos, profile.lightingDensity())`.
2. Socket form → queue the todo carrying the `LightSource` and the roll, and write air for now.
   Deferred either way: it is the support search that decides whether this marker holds a floor
   fixture or a wall one, and that is as true of an unlit wall torch as of a lit one.
3. In-place form → write the lit state, or the `unlit` resolved at `pos`.

### 5.3 In the deferred pass

`DeferredLightPlacer` keeps its selection — support discovery, weighted draw, orientation, survival —
and runs it twice over, once per roll outcome. Lit, the state tried at each candidate is its light and
a candidate the world refuses hands over to the next in the list. Unlit, the state tried is the
candidate's replacement and the first drawn candidate is the answer even when that replacement is
air, because falling through would put *another* candidate's replacement at a position that candidate
never won. Both passes address one stream at the marker, so the fixture a position would light is the
fixture standing there while it is dark.

When no opportunity yields anything, a lit marker falls back to the source's own `unlit`; air is
skipped rather than planned, since the marker already holds air and writing it again would add a
driver write where there was none.

`CityGenerator.placeOptionalLights` writes what was planned and schedules its update, as now.

### 5.4 Randomness

The lit pool draw keeps `Rng.Purpose.LIGHTING_VARIANT`. The unlit choice takes one new purpose,
**`LIGHTING_UNLIT`, appended at the end of the enum** — nothing above it moves, so no existing world
is reseeded. A separate purpose because an accepted-but-unplaceable socket consumes both at one
position, and deriving both from one hash would tie which replacement appears to which lit candidate
was tried.

Density stays on `LIGHTING_DENSITY`, so raising the density still adds a superset of lights and
never rerolls a source that was already accepted.

## 6. Bundled datapack

`common.json` `T` and `h` keep their pools under the new field name. Two `T` candidates gain an inert
stand-in — `minecraft:torch` → `minecraft:candle[candles=1,lit=false]`, `minecraft:lantern[hanging=true]`
→ `minecraft:iron_chain[axis=y]` — so a dark room still shows where its light was. Vanilla has no
unlit torch block and the obvious substitute, `redstone_torch[lit=false]`, relights itself on the next
block update, so the stand-ins are blocks that stay dark. Every other candidate names none and leaves
air, exactly as a rejected marker always has.

`oilrig.json` gains two in-place sources:

- `J` — `sea_lantern`, unlit `prismarine_bricks`. A rig deck light that reads as a dead fixture when
  the rig has no power.
- `|` — `redstone_wall_torch[lit=true]`, unlit `redstone_wall_torch[lit=false]`. The literal case in
  the request: lit when lighting is on, unlit when it is off, never absent.

`common.json` `g` (`redstone_torch[lit=true]`, 63 markers) stays a plain always-on entry. The
previous spec put it deliberately outside lighting density as functional rather than decorative, and
nothing here changes that judgement.

The oilrig entries and the two `T` stand-ins move the digest goldens. Those are the intended built-in
behaviour changes, and they are reported as such.

## 7. ModernTweaks

`lanterns.json` and `soul_lanterns.json`:

| Char | Lit | Unlit |
|---|---|---|
| `E` | `lantern[hanging=true]` / `soul_lantern[hanging=true]` | `chain[axis=y]` |
| `e` | `lantern[hanging=false]` / `soul_lantern[hanging=false]` | air |

The hanging pair keeps its chain so a dark street still reads as a street with lamps on it, rather
than a street that never had any. Both keep their existing `damaged: minecraft:iron_bars`.

`common.json`:

- `T` — the legacy `"torch": true` wall torch becomes a socket pool in ModernTweaks' own palette
  (lanterns first, torches second, matching what the pack actually uses elsewhere). No part contains
  a `T`, so this is a correctness fix to a declaration, not a visible change.
- `h` — glowstone, 9 markers, becomes an in-place source. Its unlit replacement is chosen after
  reading the parts that use it; a full-block emitter set into a wall must not become a hole.
- `g` — unchanged, as in the built-in pack.

Any other emitting block in the ModernTweaks palettes is swept for and converted the same way.

## 8. Zombie Apocalypse Essentials

- `T` — legacy `"torch": true` becomes a socket pool. 176 markers, unchanged in spirit.
- `h` — glowstone, 668 markers, becomes an in-place source with a non-emitting solid replacement.
  This is the pack's single largest lighting lever and is currently unreachable from the setting.
- `g` — unchanged.

Any other emitting block in the pack's palettes is swept for and converted the same way.

## 9. Component boundaries

- `LightSourceSettings` (regassets/data): decoding, including the `true`/object either, and the
  `unlit`/`unlitBlocks` exclusivity.
- `LightPool`: compiled lit candidates. Unchanged apart from its caller.
- `BlockChoice`: one compiled block-or-weighted-list, resolved by position. No lighting knowledge.
- `Palette.Info.LightSource`: pool plus replacement, immutable after compilation.
- `Parts.handleLightSource`: the density decision and which of three things gets written. No block
  taxonomy, no tag lookups.
- `DeferredLightPlacer`: selection and the unlit fallback. Already testable without a chunk.

## 10. Verification

Automated:

- `lightSource: true`, object form, and both replacement spellings decode; both replacements at once
  is a decode error.
- Socket form still rejects zero-light candidates, non-positive weights, empty pools, and
  unparseable states, with palette/character/placement/candidate in the message.
- In-place form on a non-emitting entry is a load error; on an all-absent entry it is not.
- `torch` and `light` produce a migration error naming the new spelling.
- A rejected density roll writes the replacement, not air, for both forms.
- A socket whose every opportunity fails survival writes the source's replacement.
- A socket candidate's own replacement wins over the source's, and an `unlit` that emits is refused.
- An in-place source is written at the authored position with the authored state.
- Raising lighting density stays monotonic and does not reroll accepted positions.
- Every emitting block written schedules exactly one lighting update; no `urbex:lights` reference
  survives in `src/`.
- Every bundled, ModernTweaks and ZAE palette decodes through the codec.

Digest goldens, per project rule: `runDigestCheck`, `runDigestCheckShuffled`,
`runDigestCheckFeatures`, `runDigestCheckAvoid` and variants, `runDigestCheckAvoidModes`,
`runDigestCheckRail`/`RailShuffled`, at the default worker count and at `-Dmax.bg.threads=2`. The
rename alone moved nothing — all ten suites reproduced the old hashes — which is what establishes
that `T` and `h` still generate what they did. The five goldens then moved on the `T` stand-ins, and
were re-pinned by deleting them and running twice for agreement, never by editing a value to match.

## 11. Out of scope

- Adding light markers to part geometry in any pack.
- Making `g` redstone torches or other functional emitters density-controlled.
- A separate "unlit variety" density or any second lighting control.
- Loot, spawner, or any other density.
- Light-level-aware mob-spawning guarantees.
