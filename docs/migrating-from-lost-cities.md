# Migrating Lost Cities assets to Urbex

Urbex loads Palette v2 for registered palettes and palettes embedded in parts or buildings. Every
palette must declare `"version": 2`; unversioned and version 1 palettes fail with a diagnostic naming
`convertPalettes`. The old `variants` registry is replaced by `definitions`.

There are two separate jobs:

| Starting point | Work required |
|---|---|
| A Lost Cities pack or exporter | Port its asset layout, references, inheritance and required wiring to Urbex, then convert its palettes. |
| An earlier Urbex pack with v1 palettes | Convert registered palettes and variants, convert embedded palettes, then validate the assembled pack. |
| An Urbex pack already using v2 | Keep its existing `definitions` and other registries. The converter returns a v2 palette unchanged; it does not validate the complete pack or modernize its design. |

The converter handles **Urbex's former palette format**. It is not a general Lost Cities importer,
world-save converter or TOML configuration importer. This guide describes the current workflow and
does not promise compatibility with arbitrary upstream releases, addons or existing worlds.

## 1. Prepare a copy and map the assets

Work in a separate pack directory and keep the source pack or exporter available for comparison.
For a Lost Cities source, use [Authoring Urbex datapacks](datapacks.md) as the target contract:

- Put registry assets under `data/<namespace>/urbex/<registry>/<name>.json` and provide a `pack.mcmeta`
  appropriate to the Minecraft version you target.
- Give every asset reference an explicit namespace. A file owned by your pack is usually
  `<your-namespace>:<name>`; a reference to a bundled Urbex asset must name its actual `urbex:` id.
  Merely changing every namespace to `urbex` can overwrite or misidentify assets.
- Replace retired `inherit` and `parent` keys with `extends`, then review the merge rules. Lists in
  the non-palette registries replace by default; the explicit append form uses `"replace": false`.
- Supply the required street, highway and railway part references. Extending an appropriate Urbex
  base can supply this wiring; copied upstream files do not acquire missing references automatically.
- Remove a worldstyle's old `rotatable` key. Palette v2 blocks rotate with their part by default;
  use `"urbex:rotatable": false` in a palette node's `traits` for blocks whose facing must stay fixed.
  Rotation block tags no longer control placement, and the converter does not rewrite worldstyles.
- Check block ids, state properties, loot tables and conditions against the installed mods. Palette
  loot and spawner traits name Urbex `conditions` assets, whose values select the actual loot or mobs.
- Port world-generation tuning through [datapack presets](presets.md). Palette conversion does not
  translate profiles or configuration files.

If an exporter generates your pack, make these changes in the exporter as well. Otherwise its next
run will restore the old format over the converted output.

## 2. Convert registered palettes and variants

Run the repository's Gradle wrapper from the Urbex checkout. The input is the **registry root** that
contains `palettes/` and optionally `variants/`, not the outer directory containing `pack.mcmeta`:

```sh
./gradlew convertPalettes \
  -PurbexPackIn=/path/to/source-pack/data/mycity/urbex \
  -PurbexPackOut=/path/to/conversion-output/urbex
```

The task reads `palettes/**/*.json` into output `palettes/` and `variants/**/*.json` into output
`definitions/`, preserving their relative paths. Review every warning and blocker. A blocker makes
the command fail and skips that file; other files may already have been written, so a failed run's
output is incomplete. Resolve the reported decisions in the source and rerun into a clean output
directory.

The output is **not a complete datapack**. The converter does not copy `pack.mcmeta`, parts, buildings,
conditions, textures or existing v2 definitions. Merge reviewed output into your working pack, preserve
those files, and retire the converted `variants` files from the working pack once their replacements
and references are in place. Check for a name collision before replacing an existing definition.

The [migration specification](format/palette/09-migration.md) defines the translations. For example:

<!-- example: palette-v1 -->
```json
{ "palette": [
  { "char": "#", "block": "minecraft:stone_bricks", "damaged": "minecraft:iron_bars" },
  { "char": "C", "block": "minecraft:chest", "loot": "mycity:chestloot" }
] }
```

becomes a v2 palette with the marker as the object key and metadata expressed as traits:

<!-- example: palettes -->
```json
{ "version": 2, "palette": {
  "#": { "block": "minecraft:stone_bricks",
         "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } },
  "C": { "block": "minecraft:chest",
         "traits": { "urbex:loot": { "pool": "mycity:chestloot" } } }
} }
```

Do not translate weighted lists by blindly renaming `random` to `weight`. V1 allocated slots in
declaration order out of 128; v2 weights are relative. The converter computes the old slot allocations,
including a trailing value that filled the remainder, and emits those allocations as weights.

## 3. Convert palettes embedded in parts and buildings

The Gradle task does not rewrite an owner's embedded `palette` object. For each such owner:

1. Extract the value of its `palette` field into a temporary file under
   `<temporary-input>/palettes/<unique-name>.json`. Extract the whole palette object, not only its
   inner array of entries. Record which owner each temporary file belongs to.
2. Run `convertPalettes` with that temporary input registry root and a separate output root.
3. Put the converted JSON object back as the owner's `palette` value. Its own `"version": 2` belongs
   inside that object, not on the surrounding part or building.
4. Preserve the owner's slice rows and other fields. Inline palettes may use `$defs` and `$imports`,
   but may not declare their own `extends`; inheritance belongs to the owning part or building.

For exporter authors, the same single-document translation is available as
`V1ToV2.paletteFile(sourceJson, assetLabel)` in the repository. Check `Converted.blocked()` and report
its findings before using `Converted.json()`; the returned text alone does not establish success.

Marker conversion does not rewrite geometry. If a source used invalid or colliding characters,
choose valid markers and update both the palette keys and every corresponding slice position.
See the [character rules](format/palette/06-characters.md).

## 4. Validate the assembled pack

Check the complete pack, including registered and embedded palettes:

1. All palettes and definitions declare version 2, and no v1 entry keys remain in them. Review
   diagnostics for retired keys rather than discarding those fields without deciding their meaning.
2. Asset references, `$ref` targets, imports and condition pools resolve in the full installed pack
   set. The converter's output decoding checks do not resolve all cross-file references.
3. Palette inheritance and style composition supply every marker used by each part's slices.
4. Load a disposable world with the intended mod and datapack set. Exercise the migrated styles and
   parts, including loot, spawners, lighting and damage. Review load diagnostics and generated chunks.
5. If comparing old and converted output, use the same seed and an installation where the source's
   block ids exist. Absent weighted choices redistribute differently between v1 and v2; successful
   conversion alone does not promise identical chunks in an installation missing those blocks.

After the pack works, improve shared definitions and traits in a separate pass. The converter reports
duplication opportunities but does not extract them or infer the author's intent.

## Reference

- [Datapack authoring guide](datapacks.md): registry wiring, inheritance and working examples.
- [Palette format](format/palette/00-model.md) and [migration rules](format/palette/09-migration.md):
  the target format and exact conversion semantics.
- [Palette JSON schema](schema/palette.v2.schema.json): editor assistance; load-time resolution still
  validates references, blocks and traits.
- [Datapack presets](presets.md): the world-generation settings that are outside palette conversion.
