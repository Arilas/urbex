# Urbex

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

By default Urbex generates cities **only in the `urbex:city` dimension**, which the mod adds. An
ordinary new world will look completely untouched — the overworld is not opted in unless you ask
for it. If you install the jar and see no cities, this is why.

To get cities:

- **In `urbex:city`** — travel there, or `/execute in urbex:city run tp @s ~ ~ ~`. This dimension
  uses the `biosphere` profile out of the box.
- **In the overworld** — on the world-creation screen, open the **More** tab and use the **Cities**
  button to pick a profile before creating the world. With no profile selected (the default) the
  overworld generates normally.

Server owners can map further dimensions to profiles with the `dimensionsWithProfiles` config
option (entries look like `urbex:city=biosphere`).

## Status

Early. See `docs/superpowers/specs/` for the design and `docs/superpowers/plans/` for what is
being built now.

## Building

    ./gradlew build

Requires JDK 25. The jar lands in `build/libs/`.

## License

MIT. See `LICENSE` — both the original and the fork's notices apply.
