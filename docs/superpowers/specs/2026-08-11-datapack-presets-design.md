# Datapack-Driven Presets

**Status:** Approved design, awaiting written-spec review

**Date:** 2026-08-11

## 1. Decision

Replace the runtime-generated profile system with stored, datapack-driven
**presets**. Profiles are today the only content type that is not a dynamic
registry: they are built in code at mod init (`ProfileSetup`), serialized
through a hand-rolled `Configuration` class with no codec, and seeded into
`config/urbex/profiles/` only when absent. That seeding is the root cause of
issue #112 (stale files silently overriding built-ins) and of the observed
"all presets generate with lighting density 0" bug (files written before the
density feature carry `generateLighting: false`, which the legacy migration
maps to `0.0`).

The replacement follows the pattern of the twelve existing content registries:
a codec-backed dynamic registry, defaults shipped as JSON in the mod's bundled
datapack, and third-party packs contributing by dropping files into their own
namespace.

The user-facing and internal name changes from "profile" to "preset"
throughout: classes, config keys, saved data, lang keys, and documentation.

## 2. Compatibility Policy

Clean break, consistent with the sphere-removal spec. Urbex has not shipped;
no released worlds exist to protect.

- The old `Configuration` class, `ProfileSetup`, the legacy key migrations
  (`generateLighting`, `generateLoot`, `buildingWithoutLootChance`,
  `chestWithoutLootChance`, `basedOn`), and all reading/writing of
  `config/urbex/profiles/` are deleted with no replacement parser.
- Worlds whose saved data references the old format do not load their
  customizations; there is no migration. The changelog tells testers to
  recreate worlds.
- Issue #112 is closed by this change: no profile-shaped file exists in a run
  directory anymore, so nothing can go stale. The `build.gradle` digest-prep
  patch proposed in the issue becomes unnecessary, but the issue's closing
  note must verify nothing else in a digest run directory is
  read-but-never-reset.

## 3. Preset Registry

A thirteenth dynamic registry, keyed `urbex:presets` (registry path
`urbex/presets`), registered in `CustomRegistries` alongside the existing
twelve via Fabric `DynamicRegistries.register` with a `PresetRE` codec.

- Datapacks contribute at `data/<namespace>/urbex/presets/<name>.json`.
- The 12 built-in presets ship as JSON files in the mod's bundled datapack
  under `src/main/resources/data/urbex/urbex/presets/`.
- Preset ids are namespaced `ResourceLocation`s (`urbex:default`). Bare names
  in JSON and config default to the `urbex` namespace via the existing
  `DataTools.fromName` convention.
- Runtime access goes through `AssetRegistries` with a new
  `RegistryAssetRegistry<PresetRE, Preset>` entry, giving the same lazy
  caching and reset behavior as other assets.

## 4. File Format

Top level of a preset file:

- `parent` (optional preset id) — see §5.
- `description`, `extraDescription`, `warning`, `icon` (optional metadata,
  used by the create-world UI).
- Logical sections, each an optional object with optional fields. Target
  grouping (~10 sections; exact membership settled during implementation):
  `terrain`, `cities`, `buildings`, `roads`, `highways`, `railways`,
  `destruction` (ruins, explosions, rubble), `decoration` (foliage, loot and
  lighting densities), `spawn`, `atmosphere` (fog, horizon).

Every field is `optionalFieldOf` in the codec. Unknown keys are a load error
(codec strictness), not silence: the old format's "accept anything, drop it
on write" behavior is gone.

The `worldStyle` field does not exist in the format (§7). The old category
names (`lostcity`, `explosions`, `cities`, `client`) and the
`__readonly__`/comment machinery do not carry over.

## 5. Resolution and Inheritance

A preset resolves to a complete runtime `Preset` object (replacing
`UrbexProfile`) by walking the `parent` chain:

- Field lookup order: own value → nearest ancestor's value → code default.
- Parent chains are cycle-checked at load; a cycle or a dangling parent id is
  a load error.
- A parentless preset is valid: code defaults cover every field.

This makes shipped presets deltas: `rarecities.json` states only what differs
from `urbex:default`. Adding a new field later means touching the codec and
schema, not twelve JSON files.

## 6. Defaults Corrections

Two deliberate value changes ship with the new defaults:

- **`useAvgHeightmap` becomes `true` as the code default.** All presets
  inherit it unless a pack opts out. The mod-config gate
  (`heightSampleSize > 2`, default 3) is unchanged.
- **Every shipped preset carries an explicit, non-zero `lightingDensity`**
  (zero only where a preset deliberately wants darkness). Starting values are
  today's code values (base 0.15; overrides 0.05–1.0); exact per-preset
  numbers are settled during implementation.

Both change worldgen output, so the golden digests are regenerated once in
the same change.

## 7. World Style Leaves the Preset

The `worldStyle` field is deleted from the preset format. Selection becomes
first-class plumbing:

- `UrbexData` saved data becomes `{preset id, worldStyle id, optional inline
  overrides}` (overrides in the preset-delta format, for the Customize
  screen).
- The `dimensionsWithProfiles` config becomes `dimensionsWithPresets` and
  carries both ids per dimension.
- `DefaultDimensionInfo` receives the style id from the selection, not from
  the profile. The direct `profile.getWorldStyle()` read in `City` goes
  through the dimension info instead.
- The synthetic-"customized"-profile hack in `PresetSelection.publish()`
  (clone profile, mutate its worldStyle, smuggle as JSON) is deleted.

Worldstyles themselves already are a dynamic registry with defaults shipped
as a datapack; no change there.

## 8. UI Discovery Tag

The create-world screen lists the members of the preset-registry tag
`#urbex:presets` (tag file path `data/<namespace>/tags/urbex/presets/`).

- The bundled datapack tags all 12 built-ins.
- Third-party packs add presets by shipping their own tag file; tag files
  merge.
- If the tag is missing or empty, the UI falls back to listing the entire
  registry.

Presets not in the tag remain registered and selectable by id (config,
parents) — the tag is a browse filter, not a gate.

## 9. Customize Screen

The Customize screen edits an inline preset delta in the new format, stored
only in the world's saved data. The "save to config folder" path
(`CustomizeScreen.performSave` writing `basedOn` files) is removed. The
documented path for reusable or shareable presets is a small datapack; the
docs gain a short "make your own preset" section showing a delta file and
tag entry.

## 10. JSON Schema

- Hand-written `docs/schema/preset.schema.json` describing the full format.
- Tests keep it honest: (a) every shipped preset validates against the
  schema; (b) every shipped preset round-trips through the codec; (c) the
  schema's declared properties are diffed against the codec's field set so
  the two cannot drift.
- A short doc section covers wiring the schema into VSCode/IntelliJ file
  mappings.

## 11. Fallout

- `PacketReturnProfileToClient` / `PacketRequestProfile` (client fog/horizon
  sync) move to the new resolved-preset payload.
- `CommandSaveProfile` becomes `urbex savepreset`: a debug command that
  dumps the resolved preset (post-inheritance) as JSON to
  `<gamedir>/urbex-export/<name>.json` and prints the path.
- The `Settings` GUI descriptor registry and `SettingsCompletenessTest`
  follow the new field layout; density tests (`UrbexProfileDensityTest`,
  `ProfileSetupDensityTest`) are replaced by codec/resolution tests.
- `RecreateProfileRestore` reads the new saved-data shape.
- `UrbexProfile.CATEGORY_CITY_ID = "lostcity"` — the last `lostcit*` string
  in main — disappears with the format.

## 12. Sequencing

Two chunks, playable-early:

1. **Registry + format + selection plumbing.** PresetRE codec, resolution,
   bundled preset files, worldStyle plumbing, saved data, deletion of the old
   system. Endpoint: create a world in-game from a datapack preset, with
   average heightmap on and lights generating.
2. **Schema + tests + polish.** JSON schema and its guard tests, Customize
   delta flow, docs, digest golden regeneration, GUI descriptor cleanup.
