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

Urbex does not generate anything until you opt in. A new world looks completely untouched
unless you pick a profile.

To get cities:

- On the world-creation screen, open the **More** tab and use the **Cities** button to pick a
  profile before creating the world. With no profile selected (the default) the world generates
  normally.
- Server owners can map any dimension to a profile with the `dimensionsWithProfiles` config
  option (entries look like `minecraft:overworld=default`).

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
