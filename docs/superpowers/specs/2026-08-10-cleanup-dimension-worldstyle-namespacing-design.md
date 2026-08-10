# Cleanup: dimension removal, standard_everywhere removal, namespace prefixing

**Date:** 2026-08-10
**Status:** Approved

## Goal

Three cleanups that shed legacy Lost Cities baggage from the Urbex fork:

1. Remove the `urbex:city` custom dimension (historical, no longer wanted).
2. Remove the `standard_everywhere` world style (backward-compat leftover, unmaintained).
3. Namespace-prefix all asset references in the shipped datapack, following the
   convention established by LostCities-ModernTweaks (LCMT): fully qualify
   everything we own; bare names remain a fallback that resolves to `urbex:`.

Behavior of world generation must not change. The pinned worldgen digest
(`digest.golden`) is the proof: it must be byte-identical after all three changes.

## Background findings

- The fork has **no custom chunk generator, biome source, or biomes** for the
  dimension. `urbex:city` is a pure datapack dimension (vanilla `minecraft:noise`
  generator, overworld multi-noise preset) plus three Java touch points.
- Spheres/space/biosphere profiles are dimension-agnostic: `urbex:spheres` is
  injected into all `minecraft:is_overworld` biomes and `LandscapeType` is a
  profile-level setting. Nothing worldgen-related depends on the dimension.
- `standard_everywhere` is referenced in exactly two places: its own JSON file
  and a test fixture string in `WorldStyleDialogTest`.
- Bare asset names resolve through `DataTools.fromName`, which defaults the
  namespace to `urbex`. Prefixing our own references is therefore behaviorally
  neutral — `"building1"` ≡ `"urbex:building1"` — but makes the datapack
  unambiguous next to third-party packs.
- Current state: 533 bare cross-references vs 3 prefixed across 294 JSONs.
- LCMT convention (reference: `/Volumes/Dev/Projects/krona/minecraft-mods/LostCities-ModernTweaks`):
  1587 prefixed vs 11 bare, where every bare name is a deliberate reference to a
  base-mod asset. LCMT also declares street/highway/railway/monorail part wiring
  explicitly in its datapack (`citystyle streetblocks.parts`, worldstyle `parts`)
  with fully-prefixed names, instead of relying on the bare Java codec defaults.
- Our fork already has the codec hooks for that: `WorldStyleRE` has an optional
  `parts` field (`PartSelector` → `monorails`/`highways`/`railways`) and
  `CityStyleRE.streetblocks` (`StreetSettings`) has an optional `parts` field
  (`StreetParts`). Our shipped datapack simply never uses them.

## Part 1 — Remove the `urbex:city` dimension

Delete outright:

- `src/main/resources/data/urbex/dimension/` (whole dir; only `city.json`)
- `src/main/resources/data/urbex/dimension_type/` (whole dir; only `city.json`)
- `src/main/java/dev/krona/urbex/setup/BedTeleport.java` (sleep-on-diamond-block
  teleport into the dimension — its only purpose)
- `src/main/java/dev/krona/urbex/varia/CustomTeleporter.java` (no callers after
  BedTeleport goes)

Edit:

- `setup/ServerEventHandlers.java` — drop the `EntitySleepEvents.ALLOW_SLEEPING`
  registration of `BedTeleport::onPlayerSleepInBed`, its import, and the javadoc
  mention.
- `setup/Registration.java` — remove `CITY_ID`, `DIMENSION`, `DIMENSION_TYPE`
  and now-unused imports. **Keep `init()`** — it registers the `urbex:city`
  *Feature* and `urbex:spheres` *Feature*, a different registry that only shares
  the id string.
- `config/UrbexConfig.java` — `dimensionsWithProfiles` default changes from
  `List.of("urbex:city=biosphere")` to `List.of()`. Remove the
  `specialBedBlock` record component and codec field.
- `setup/Config.java` — remove `SPECIAL_BED_BLOCK` supplier.
- Legacy TOML migration (`LegacyToml`) — remove any `specialBedBlock` mapping.
- Tests: `UrbexConfigTest` (new empty default, no `specialBedBlock`),
  `LegacyTomlTest` (swap `"urbex:city=biosphere"` sample strings for neutral
  ones).
- `README.md` — rewrite the "Usage" section: cities are enabled by picking a
  profile on the world-creation Cities tab (or mapping a dimension to a profile
  in server config); there is no bundled dimension.
- `CHANGELOG.md` — add removal entries (do not rewrite historical entries).
- Dev leftovers: delete `runs/server/world/dimensions/urbex/city/`; update
  `runs/digestcheck/config/urbex/urbex.json` if it still lists
  `urbex:city=biosphere`.

Product decision (confirmed): after this change a fresh install generates no
cities until the player selects a profile in the Cities tab or a server config
maps a dimension. No overworld default mapping is added.

Out of scope / explicitly kept: `IDimensionInfo`, `DefaultDimensionInfo`,
`DimensionCaches`, `CityFeature`'s dimension-keyed map, `SphereFeature`,
`Spheres`, `CitySphere`, spheres configured/placed features, mixins, spawn
placement, the generic `dimensionsWithProfiles` mechanism, and predefined
city/sphere `dimension` fields — all dimension-agnostic and in active use.

## Part 2 — Remove `standard_everywhere`

- Delete `src/main/resources/data/urbex/urbex/worldstyles/standard_everywhere.json`.
- `WorldStyleDialogTest` — replace the `"standard_everywhere"` fixture string
  with a neutral third name (the test only checks index lookup).
- `CHANGELOG.md` entry.

Side effect (desired): with only `standard` shipped, `CitiesTab` hides the
world-style dropdown (`worldStyles.size() > 1` guard), matching the GUI-redesign
spec.

## Part 3 — Namespace prefixing (LCMT convention)

Scope: **datapack JSONs only.** Java stays untouched; bare names keep resolving
to `urbex:` so existing third-party packs and profile files keep working, and
`DataTools.toName` keeps stripping `urbex:` when serializing.

1. Prefix all bare cross-references under `data/urbex/urbex/` with `urbex:`.
   Field → target-category map (from the audit; mirrors LCMT's `tools/validate.py`):

   | Source category | Fields | Target |
   |---|---|---|
   | buildings | `parts[].part`, `parts2[].part` | parts |
   | buildings, palettes | palette-entry `variant` | variants |
   | multibuildings | `buildings[][]` | buildings |
   | styles | `randompalettes[][].palette` | palettes |
   | citystyles | `selectors.{bridges,parks,fountains,stairs,fronts,raildungeons}[].value` | parts |
   | citystyles | `selectors.buildings[].value` | buildings |
   | citystyles | `selectors.multibuildings[].value` | multibuildings |
   | citystyles | `inherit` | citystyles |
   | citystyles | `style` | styles |
   | parts | `refpalette` | palettes |
   | palettes | `loot`, `mob` | **conditions** (known gotcha) |
   | worldstyles | `scattered.list[].name` | scattered |
   | worldstyles | `citystyles[].citystyle` | citystyles |
   | worldstyles | `outsidestyle` | styles |
   | scattered | `buildings[]`, `multibuilding` | buildings/multibuildings |
   | conditions | `inpart` | parts |

   The edit is a scripted per-field transformation over this map — not blind
   find-replace. `minecraft:` ids (blocks, mobs, loot tables) and the already-
   prefixed loot-table values in `conditions/chestloot.json` are untouched.

2. Declare part wiring explicitly, LCMT-style, mirroring the current Java
   defaults with `urbex:` prefixes:
   - `worldstyles/standard.json` gains a `parts` block:
     `monorails` (3 slots), `highways` (6 slots), `railways` (16 slots).
   - `citystyles/citystyle_common.json`'s `streetblocks` gains a `parts` block
     (7 street slots: full/straight/end/bend/t/none/all).
   The Java `StreetParts`/`HighwayParts`/`RailwayParts`/`MonorailParts` bare
   defaults remain as fallback for third-party worldstyles/citystyles.

3. New guard test: a datapack reference-integrity JUnit test that walks every
   JSON under `data/urbex/urbex/`, extracts references per the field map above,
   and asserts each resolves to an existing asset file (treating bare names as
   `urbex:`). This catches mass-edit typos and protects future datapack work.

## Verification

- `./gradlew build` (compiles, full test suite incl. the new integrity test).
- `./gradlew runDigestCheck` — `digest.golden` must be **unchanged**. Bare and
  prefixed names resolve identically, and the digest runs on the overworld with
  the `default` profile, so any digest drift means a real mistake.

## Non-goals

- No content/atmosphere changes (out of scope for the fork by standing decision).
- No change to name-resolution semantics (`DataTools.fromName`/`toName`).
- No prefixing of Java default constants or profile/config serialization.
- No new default generation target to replace the removed dimension mapping.
