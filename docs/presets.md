# Authoring Urbex presets

A **preset** selects and tunes the worldgen behaviour Urbex uses for a dimension: city rarity,
building shapes, road layout, explosion damage, spawn rules, and so on. Presets are plain
datapack JSON — no code, no rebuild, no mod jar changes. Drop a file in the right place, add it to
a tag, and it shows up in the preset picker.

Presets are one of Urbex's thirteen datapack registries and follow the same rules as the other
twelve: one `extends` key naming a fully-qualified id in the same registry, chains applied
root-first, and no bare references anywhere. [`docs/datapacks.md`](datapacks.md) is the guide to
those shared rules and to the registries this document does not cover; what follows here is what is
specific to the preset format.

## What a preset is

A preset is one JSON file registered in the `urbex:presets` dynamic registry, at:

```
data/<namespace>/urbex/presets/<name>.json
```

Its registry id is `<namespace>:<name>` (for example `urbex:default`, or `mypack:ruins`).

`urbex:disabled` and `urbex:customized` are reserved: they name the Cities tab's own built-in
"no cities" row and its transient hand-edited entry, not registry-backed presets. Registering a
preset at either id is unsupported — the UI's built-in rows shadow it and it will never be shown
or selectable.

Every field in the file is **optional**. A preset only needs to state what it changes;
anything left out falls through to what it `extends` (see [Resolution rules](#resolution-rules)
below). The top-level object has six plain metadata fields (`extends`, `name`, `description`,
`extraDescription`, `warning`, `icon`) plus ten **sections**, each grouping related settings:
`terrain`, `cities`, `buildings`, `roads`, `highways`, `railways`, `destruction`, `decoration`,
`spawn`, `misc`. A section is only applied if it's present in the file, and within a present
section only the fields you actually write are overridden — you never have to restate a
whole section to change one number in it.

`name` is what the Cities tab puts on the row: a plain human label like `Tall Buildings`, not an id
and not a translation key. Leave it out and the list falls back to showing the fully-qualified id,
which is what every preset looked like before the field existed. Set it — and set it on **every**
preset you ship, because like all the others it is inherited: a preset extending `urbex:default`
that doesn't restate `name` is listed as **Default**, next to the real one.

The full field list, types, numeric ranges and enum values are documented in the JSON Schema at
[`docs/schema/preset.schema.json`](schema/preset.schema.json) (see [IDE wiring](#ide-wiring) to get
inline docs and validation while you edit). Keys starting with `_` (e.g. `_comment`) are ignored
everywhere in the file — use them freely for your own notes.

Unknown keys elsewhere are *not* a hard error — a decode never fails outright over a typo — but
each one logs a `WARN` naming the bad key and the section, so a misspelled field doesn't silently
do nothing. The schema is the stricter net: editors wired up to it (see below) flag a typo as you
type instead of waiting for you to check the log.

## A complete minimal example datapack

This is a full, working datapack that adds one preset — a "ruins" variant that just dials up ruin
chance and turns off spawners — and makes it selectable in the preset picker.

```
myruinspack/
├── pack.mcmeta
└── data/
    ├── mypack/
    │   └── urbex/
    │       └── presets/
    │           └── ruins.json
    └── urbex/
        └── tags/
            └── urbex/
                └── presets/
                    └── presets.json
```

**`pack.mcmeta`** — `pack_format` must match your target Minecraft version's data pack format (this
repository currently targets format `107`, for Minecraft `26.2`; check the [Minecraft Wiki's Pack
format page](https://minecraft.wiki/w/Pack_format) for the value matching the version you're
playing):

```json
{
  "pack": {
    "pack_format": 107,
    "description": "My ruins preset"
  }
}
```

**`data/mypack/urbex/presets/ruins.json`** — a delta on top of the built-in default preset. Only
the fields that change from `urbex:default` need to be listed:

```json
{
  "extends": "urbex:default",
  "name": "Ruins",
  "description": "Heavily ruined cities, no spawners",
  "destruction": {
    "ruinChance": 0.6,
    "ruinMinlevelPercent": 0.2,
    "ruinMaxlevelPercent": 0.9
  },
  "buildings": {
    "generateSpawners": false
  }
}
```

**`data/urbex/tags/urbex/presets/presets.json`** — the `#urbex:presets` tag drives the
preset-selection UI (`Presets.listBrowsable`); a preset that exists but isn't tagged still
resolves and works (e.g. as something another preset `extends`), it just won't show up to pick
directly. To
add your preset alongside the built-ins, `"replace": false` merges your tag with the mod's own
rather than overwriting it:

```json
{
  "replace": false,
  "values": ["mypack:ruins"]
}
```

Zip the `myruinspack` folder's contents (not the folder itself) into `myruinspack.zip`, drop it in
your world's `datapacks/` folder (or the global `resourcepacks`-style datapack folder your server
uses), and **Ruins** appears in the **Cities** tab's preset list next to the built-ins. (Drop the
`name` and the row reads `mypack:ruins` instead.)

## Resolution rules

A preset resolves in two layers:

1. **Extends chain.** Starting from the requested preset, Urbex follows `extends` links until it
   reaches a preset with no `extends` (or errors if the chain cycles, or an `extends` id doesn't
   resolve to a registered preset). It then applies each preset in the chain **root-first**: the
   root's fields land first, then each descendant's present fields overwrite them, ending with the
   requested preset itself. A field a preset doesn't mention is simply not touched at that step —
   it keeps whatever the chain has set so far.
2. **Code defaults.** The chain is applied onto a fresh `Preset` object, which starts out with the
   hardcoded defaults in `Preset.java` (the same values the old built-in profiles used). A field no
   preset in the whole chain ever sets keeps its code default. In practice every shipped preset
   extends `urbex:default` (except `urbex:default` itself, which has no `extends`), so
   `urbex:default` is effectively every other built-in preset's baseline, and the code defaults are
   `urbex:default`'s own baseline.

There is no other source of truth: no config file, no legacy migration, no partial application
based on world type. A preset with a bad `extends` (cycle, or an id nothing provides) fails to
resolve rather than falling back to something implicit.

## `urbex savepreset`: show me everything

Because most fields in a preset file are typically absent (inherited from the extends chain), a
preset file alone doesn't show you the *effective* settings for a running world. The
`/urbex savepreset` command (admin-only; `/ubx savepreset` also works, `ubx` is the short command
alias) writes the **fully resolved** preset for your current dimension — extends chain walked,
every field populated, nothing omitted — to `<game dir>/urbex-export/<preset-id-path>.json`. Use it
to:

- See every field and its current effective value, as a starting point for a new preset (copy the
  parts you want to change into a new file, add `extends`, delete the rest).
- Confirm what an `extends` chain actually resolves to before shipping a datapack.
- Diff two presets' effective settings by exporting both and comparing the files.

The exported file round-trips: it validates against the same schema as any other preset file, and
can itself be used as an `extends` value for another preset.

## IDE wiring

Pointing your editor at [`docs/schema/preset.schema.json`](schema/preset.schema.json) gets you
inline field documentation, enum/range validation, and unknown-key errors while you type — the
schema tracks the same field list this document does, and both are checked against the section
codecs by a test (`PresetSchemaTest`) so they can't silently drift from what the mod actually
accepts.

### VS Code

Add to your workspace or user `settings.json` (adjust the `url` to wherever your checkout of this
repository lives, or to the raw GitHub URL if you'd rather not depend on a local path):

```json
"json.schemas": [
  {
    "fileMatch": ["**/data/*/urbex/presets/*.json"],
    "url": "./docs/schema/preset.schema.json"
  }
]
```

### IntelliJ IDEA

**Settings → Languages & Frameworks → Schemas and DTDs → JSON Schema Mappings** → **+** → set
**Schema file** to `docs/schema/preset.schema.json`, **Schema version** to `JSON Schema version
2020-12`, and add a file path pattern of `data/*/urbex/presets/*.json` (or `*.json` scoped to your
`presets` directory, if IntelliJ's glob support in your version doesn't like the wildcard segment).
