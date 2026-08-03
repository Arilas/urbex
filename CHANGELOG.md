# Changelog

## Unreleased

Forked from Lost Cities 9.4.2 (Fabric/26.2 port). See `docs/history/CHANGELOG-lostcities.txt`
for the upstream history.

### Worldgen output changes

- **`PerlinNoiseGenerator14` now seeds `SimplexNoise` from `XoroshiroRandomSource` instead of
  `LegacyRandomSource`.** This is a **deliberate, one-time change to generated output**, not a
  no-op refactor: the two random sources produce different permutation tables from the same seed,
  so the noise field is different, and highway placement and city rarity move with it. The class
  was already seed-deterministic beforehand, so this was *not* required by the reproducible-worldgen
  work — it was taken because every other randomness source in generation was modernised in the
  same pass and leaving one legacy source behind would have been the odd case to explain. It is
  kept rather than reverted because reverting would move generated output a second time. Anyone
  comparing pre-fork and post-fork worlds should expect highways and city placement to differ; do
  not read this as a refactor that preserved output.
