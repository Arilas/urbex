<p align="center">
  <img src="art/icon-master.png" alt="Urbex ruined city icon" width="192">
</p>

<h1 align="center">Urbex</h1>

Procedural abandoned-city worldgen for Minecraft, for Fabric.

Urbex is a hard fork of [Lost Cities](https://github.com/McJtyMods/LostCities) by McJty, which is
MIT licensed and whose history is preserved in this repository.

What is true of the fork today:

- **Fabric only.** The NeoForge and multi-loader scaffolding is gone.
- **Worldgen is reproducible from the world seed.** Randomness is addressed by seed, position and
  purpose rather than drawn from a shared sequential stream, so generation order cannot change
  what a world looks like.
- **Not compatible with Lost Cities worlds, datapacks or configs.** Do not expect a Lost Cities
  save, profile or asset pack to load.

City layout itself is still inherited from Lost Cities: cities are scattered by noise and
buildings are snapped to the chunk grid.

## Roadmap — not yet built

These are the intended divergences from Lost Cities. **None of them are implemented yet**; do not
read them as features of the current build.

- Planned cities rather than noise-scattered ones.
- Buildings placed on lots rather than snapped to chunks.

## Usage

Urbex does not generate anything until you opt in. A new world looks completely untouched unless
you pick a preset.

To get cities:

- On the world-creation screen, open the **Cities** tab and pick a preset before creating the
  world. **Disabled** is the default and leaves the world alone. **Customize this preset…** edits a
  copy, which is stored in the world rather than in your config, so it travels with the save.
- Server owners can map any dimension to a preset with the `dimensionsWithPresets` config option.
  Entries are `dimension=preset`, optionally `dimension=preset@worldstyle`, and **every id names
  its namespace**: `minecraft:overworld=urbex:default`, not `minecraft:overworld=default`.

### Mixing world styles (experimental)

Set `experimentalMultiWorldStyles: true` in `config/urbex/urbex.json` and the **World Style** picker
on the Cities tab gains a **Mix** mode: tick several styles and give each a weight. Weights are
relative and shown as percentages, so `0.1` and `0.9` mean roughly one city in ten comes from the
first style. Each city draws its own style, so one world can hold cities from several datapacks at
once — which is the point: install a second asset pack and its cities appear alongside the built-in
ones instead of replacing them. Leave it off, or tick a single style, and everything behaves exactly
as before.

Server owners get the same thing in `dimensionsWithPresets`, with `+` between entries and `*` before
a weight:

    minecraft:overworld=urbex:default@urbex:standard*0.1+urbexmt:moderntweaks*0.9

Highways, railways and the world settings come from the heaviest style, so a highway never changes
pack partway along its run. Scattered structures do mix — they are drawn per scatter area, like
cities are drawn per centre.

With the flag off, a mix from either source is reduced to its heaviest style and the reduction
logged, so a config or save carrying one never quietly takes effect on an install that did not opt
in.

## Status

Early. See `docs/superpowers/specs/` for the design and `docs/superpowers/plans/` for what is
being built now.

Writing a datapack: `docs/datapacks.md` is the authoring guide for all thirteen asset registries —
where files go, how `extends` works, and what a pack must declare. `docs/presets.md` covers the
preset format specifically.

## Building

    ./gradlew build

Requires JDK 25. The jar lands in `build/libs/`.

## Releasing

Tags name the mod version, without the Minecraft prefix `gradle.properties` carries: `v0.1.0` for
`version=26.2-0.1.0`. CI refuses a tag that says anything else.

1. Bump `version` in `gradle.properties`, move the `CHANGELOG.md` entries under a heading for it.
2. Push the tag. The tagged commit builds like any other — full suite, both worldgen digest
   checks — and only then does the release job run, so a release is never built from an unverified
   run.
3. That job leaves a **draft** GitHub release with the jar attached. Write the notes from
   `CHANGELOG.md` and publish. (Creating the release from GitHub's own UI works too: it creates the
   tag, which triggers the same run, and the job attaches the jar to the release you started.)
4. Upload that same jar to Modrinth and CurseForge by hand.

## License

MIT. See `LICENSE` — both the original and the fork's notices apply.
