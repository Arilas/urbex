# Changelog

## Unreleased

- **Removed the `urbex:city` dimension.** It existed for historical reasons only (it was a plain
  overworld clone). Cities are enabled by picking a profile on the world-creation Cities tab or
  via the `dimensionsWithProfiles` config. The sleep-on-a-special-bed teleport and its
  `specialBedBlock` config option are gone with it, and `dimensionsWithProfiles` now defaults to
  empty.

## 0.1.0 — 2026-08-03 (alpha preview)

First preview release. Forked from Lost Cities 9.4.2 (Fabric/26.2 port) by McJty. See
`docs/history/CHANGELOG-lostcities.txt` for the upstream history.

**Alpha.** The mod generates and runs, but this release exists to get it in front of people, not
because it is finished. Worlds created with it are not guaranteed to generate identically on a
later version.

### The headline

Worldgen is now reproducible from the world seed. Previously it was not, in three separate ways:

- The generation feature held one `RandomSource` shared across every chunk in a dimension, and
  reseeded it *after* city generation had already drawn from it — so ruins and part selection
  consumed the previous chunk's leftover state.
- Rubble and leaf placement ran off a `static int` that no world seed ever touched.
- City placement itself ignored the seed entirely. Every world on a given profile put its cities in
  identical chunks at identical radii. Changing the seed did not move a single city.

All three are fixed. Randomness is now *addressed*: a stream is a pure function of the world seed,
a coordinate and a named purpose, so generation order cannot influence output and adding a consumer
cannot perturb an existing one.

### Also in this release

- **Parallel worldgen restored.** Generation was serialized behind a per-dimension lock to work
  around shared mutable state. That state is gone — generation state is now per-invocation, caches
  are owned by the dimension and concurrent, and lazily-initialized fields on shared assets are
  eager. The lock is removed.
- **World height.** Six sites assumed a 0–255 world. They now read the level's real bounds.
- **Profiles.** Editing a standard profile used to be impossible: the mod rewrote every profile
  JSON on launch and *then* read the directory, so your changes were overwritten before they were
  seen. Built-in profiles now go to `profiles/defaults/` as read-only reference, and files in
  `profiles/` are only created when absent.
- **Startup.** An unknown `selectedProfile` now fails at server start with the valid names listed,
  instead of throwing a `NullPointerException` during world initialization.
- Renamed throughout from Lost Cities to Urbex: mod id `urbex`, namespace `urbex:`, command root
  `/urbex` (alias `/ubx`), dimension `urbex:city`, config in `config/urbex/`.
- The NeoForge-shaped public API package was removed rather than ported. A Fabric-native API will be
  designed against the new generator's shapes.

### Not compatible with Lost Cities

Different mod id, namespace, saved-data ids and dimension. Lost Cities worlds, datapacks and configs
will not load. This is deliberate — see the design doc under `docs/superpowers/specs/`.

### Known limitations

Tracked, not hidden:

- [#18](https://github.com/Arilas/urbex/issues/18) — a small residual order-dependence (~11 block
  positions per 169 chunks) remains, because the mod reads neighbouring vanilla state during the
  decoration step. Architectural; the fix is structure-based placement.
- [#20](https://github.com/Arilas/urbex/issues/20) — vine generation is order-dependent *and*
  invisible to the verification harness, because those writes bypass the block driver.
- [#19](https://github.com/Arilas/urbex/issues/19) — the explosion-height scan is still bounded to
  Y 0–255 even though the surrounding bounds were widened.
- [#29](https://github.com/Arilas/urbex/issues/29) — the east-west and north-south highway networks
  are diagonal reflections of one another. Inherited from upstream, not introduced here.
- [#21](https://github.com/Arilas/urbex/issues/21)–[#28](https://github.com/Arilas/urbex/issues/28),
  [#30](https://github.com/Arilas/urbex/issues/30) — smaller items.

### Worldgen output changes

Output differs from Lost Cities, deliberately and in more than one way. Do not expect a Lost Cities
seed to reproduce here.

- Fixing the seed-independence bugs above necessarily moved everything that depended on them.
- **`PerlinNoiseGenerator14` now seeds `SimplexNoise` from `XoroshiroRandomSource` instead of
  `LegacyRandomSource`.** The two produce different permutation tables from the same seed, so the
  noise field differs and highway placement and city rarity move with it. The class was already
  seed-deterministic, so this was *not* required by the reproducibility work — it was taken because
  every other randomness source in generation was modernised in the same pass. Kept rather than
  reverted, because reverting would move output a second time.
- **Palette variant placement changed late in development.** Weighted palette characters were
  resolving to the same variant index at a given block, so minority variants (mossy, cracked) landed
  at identical offsets for every character. Each character is now addressed independently.
