# Urbex

Procedural abandoned-city worldgen for Minecraft, for Fabric.

Urbex is a hard fork of [Lost Cities](https://github.com/McJtyMods/LostCities) by McJty, which is
MIT licensed and whose history is preserved in this repository. It diverges deliberately: cities
are planned rather than scattered, buildings are placed on lots rather than snapped to chunks,
and worldgen is reproducible from the world seed. It is not compatible with Lost Cities worlds,
datapacks or configs.

## Status

Early. See `docs/superpowers/specs/` for the design and `docs/superpowers/plans/` for what is
being built now.

## Building

    ./gradlew build

Requires JDK 25. The jar lands in `build/libs/`.

## License

MIT. See `LICENSE` — both the original and the fork's notices apply.
