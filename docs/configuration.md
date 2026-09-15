# Configuring Urbex

Urbex reads JSON settings from two locations:

| File | Purpose | When it loads |
| --- | --- | --- |
| `config/urbex/urbex.json` | Installation defaults and Cities tab settings | Mod initialization |
| `<world>/serverconfig/urbex.json` | Optional overrides for one world | Server startup |

The global file is created on first launch. After a successful load, Urbex writes all supported
keys back to it, including defaults, so the available settings are visible. A world file contains
only the keys it changes and is never created or rewritten automatically. Its values replace the
global values per key; an array replaces the entire global array.

Restart the game or server after editing the global file. Reopen the world or restart its server
after editing world overrides. An unreadable or invalid file produces a log message and stays on
disk for repair. Urbex uses code defaults for an invalid global file and the global settings for
invalid world overrides.

These files select presets and control installation settings. Generation settings such as city
rarity, building heights and damage belong in [datapack presets](presets.md). Palette definitions
belong in [datapacks](datapacks.md), separately from these settings files.

## Select a preset and world style

For example, a global configuration that starts the Cities tab on the built-in large-cities
preset with the standard world style is:

```json
{
  "selectedPreset": "urbex:largecities",
  "selectedWorldStyle": "urbex:standard",
  "citiesTabAccess": "editable"
}
```

`citiesTabAccess` accepts `editable` (players choose), `locked` (the configured selection is shown
but cannot be changed), or `hidden` (the Cities tab is absent). Put this setting in the global file:
world overrides have not loaded when the create-world screen opens.

Preset and world-style references must include their namespace, such as `urbex:default` and
`urbex:standard`. Leaving `selectedPreset` empty leaves the installation default unselected;
leaving `selectedWorldStyle` empty uses `urbex:standard` when a preset is selected.

Dimension rules use `dimension=preset[@worldstyle]`:

```json
{
  "dimensionsWithPresets": [
    "minecraft:overworld=urbex:default@urbex:standard"
  ]
}
```

A world's Cities tab selection or saved selection takes precedence over its overworld dimension
rule. On a world without a saved selection, `selectedPreset` also takes precedence over an
overworld dimension rule. Changing installation defaults does not replace a selection already
saved in a world.

## Supported settings

All keys are optional. The following are the code defaults used when a key is absent from both
files. A present value with the wrong type or outside its allowed range invalidates the file.

| Key | Default | Accepted values |
| --- | --- | --- |
| `dimensionsWithPresets` | `[]` | Array of dimension rules |
| `selectedPreset` | `""` | Fully qualified preset id, or empty |
| `selectedWorldStyle` | `""` | Fully qualified world-style id, weighted mix, or empty |
| `citiesTabAccess` | `"editable"` | `"editable"`, `"locked"`, `"hidden"` |
| `experimentalMultiWorldStyles` | `false` | Boolean; enables weighted world-style mixes |
| `heightSampleSize` | `3` | Integer, 1–100 |
| `todoQueueSize` | `20` | Integer, 1–100000 |
| `forceSaplingGrowth` | `true` | Boolean |
| `cacheCleanupSeconds` | `300` | Integer, 1–86400 |
| `cacheMaxEntries` | `16384` | Integer, 256–1000000; limit per planning cache |
| `avoidStructures` | See below | Array of structure ids |
| `avoidSurfaceStructures` | `false` | Boolean |
| `structuresYieldToCities` | `false` | Boolean |
| `avoidVillages` | `true` | Boolean |
| `avoidFlattening` | `true` | Boolean |

The default `avoidStructures` list is `minecraft:mansion`, `minecraft:jungle_pyramid`,
`minecraft:desert_pyramid`, `minecraft:igloo`, `minecraft:swamp_huts`, and
`minecraft:pillager_outpost`.

With `experimentalMultiWorldStyles` enabled, `selectedWorldStyle` and the `@worldstyle` part of
a dimension rule can use a weighted expression such as
`urbex:standard*1+mypack:abandoned*3`. Every named style must exist in the loaded datapacks.
Without the opt-in, a mix is reduced to its primary (highest-weight) style.

## Retired TOML configuration

`config/urbex/common.toml` and `<world>/serverconfig/urbex-server.toml` are no longer read or
automatically converted. Existing JSON files continue to work. Obsolete TOML files are left
untouched and can be archived or removed once any settings you need are represented in JSON.

To carry settings from an old installation forward:

1. Keep a copy of the old files. Launch Urbex once to create the global JSON file, then stop the
   game or server before editing it.
2. Copy supported settings into the JSON file using the names and types above. Use JSON object
   entries (`"todoQueueSize": 42`) instead of TOML assignments (`todoQueueSize = 42`); do not copy
   TOML section headers or comments.
3. Replace old profile selections with explicit preset ids: `selectedProfile` becomes
   `selectedPreset`, and `dimensionsWithProfiles` becomes `dimensionsWithPresets`. Map each old
   profile to an available preset; for example, use `urbex:default` rather than `default`. Custom
   profile generation settings must be ported to a [datapack preset](presets.md).
4. For per-world differences, create `<world>/serverconfig/urbex.json` with only those keys.
   Restart and check the log for invalid settings or unresolved preset/style references.

There is no runtime support for the old Forge/NeoForge config specification or arbitrary old
profile fields. The [preset guide](presets.md) documents the current generation format.
