# Changelog

## Unreleased

- **Removed city spheres and their supporting system.** The `space`, `spheres`, and `cavernspheres`
  landscape types and the `space`, `biosphere`, and `biosphere_caves` presets are removed, along
  with predefined spheres, sphere profile settings, sphere asset fields, and sphere spawn targeting.
  The old monorail implementation is removed because it only connected spheres. A world config
  that still references one of the removed landscape types (for example a stale
  `config/urbex/profiles/space.json`) will crash on startup with `Bad landscape type: space!`;
  there is no fallback. Delete or edit any such profile file before upgrading.
- **Removed the `urbex:city` dimension.** It existed for historical reasons only (it was a plain
  overworld clone). Cities are enabled by picking a profile on the world-creation Cities tab or
  via the `dimensionsWithProfiles` config. The sleep-on-a-special-bed teleport and its
  `specialBedBlock` config option are gone with it, and `dimensionsWithProfiles` now defaults to
  empty. If an existing world has this dimension generated, leave it (return to the overworld)
  before upgrading — the dimension disappears and players still inside it will be relocated by
  vanilla.
- **Removed the `standard_everywhere` world style.** A backward-compatibility leftover that had
  not been kept up to date. `standard` is the only bundled world style; with a single style the
  world-style dropdown on the Cities tab stays hidden.
- **The bundled datapack is now fully namespaced.** Every internal asset reference is written
  `urbex:name` instead of relying on bare-name defaulting, and street/highway/railway part wiring
  is declared explicitly in `worldstyles/standard` and `citystyles/citystyle_common`
  (previously implicit Java defaults). Bare names in third-party datapacks still work and still
  default to the `urbex` namespace. A new test enforces that every shipped reference is
  namespaced and resolves.
- **Hierarchical streets replace the per-chunk street/park coin flip.** Every dimension now builds
  one deterministic road field of primary, secondary and tertiary roads, computed once from (seed,
  dimension id, road settings) rather than decided chunk by chunk. Primaries render through a new
  wide-road asset family (`urbex:street_large_*`, `urbex:street_stair`) at roughly double the width
  of an ordinary street, with a `connector` part overlaying every edge where an 8-wide minor road
  meets a 14-wide primary so the two surfaces meet without a gap. A primary planned across open
  water is carried on a planned bridge (the city style's `largebridges`, falling back to its
  ordinary bridge) when a deterministic per-span roll passes; a minor road one level below a
  same-level neighbour slopes up to meet it (`urbex:street_stair`) instead of stepping. A city
  chunk with neither a road nor a building is now an open lot - always grass - and is furnished
  with a weighted park part according to the chance below. All of this is configurable from a new
  **Roads** tab (spacing, activation and force-interval for primaries; count, separation and edge
  distance for secondaries; chance and length for tertiaries; chance, max length and the
  multibuilding/road conflict policy for bridges), with its own preview mode colouring each road
  class and `/urbex debug` street diagnostics alongside it.
- **Datapack- and config-breaking removals that come with hierarchical streets.** The city-style
  `parkchance` override (in `ParkSettings`) is gone with the legacy park-nomination path it tuned -
  a third-party datapack that sets it should remove the field. The `parkChance` profile setting
  (`PARK_CHANCE`) is removed; its replacement, `openLotParkChance` (`OPEN_LOT_PARK_CHANCE`), means a
  different thing - the chance that an open lot is furnished with a park part, not the chance a
  chunk becomes a park at all - so a config carrying the old key will simply stop taking effect.
  `StreetType.FULL` and `StreetType.randomNonPark()` are deleted; a planned road is always
  `StreetType.NORMAL`.
- **Removed the `full` street part.** `full` was a street *style* — a chunk paved corner to corner
  with no verge — not a topology; `all` is the four-way. It has been unreachable from generation for
  years upstream, was briefly revived by accident during this fork's Fabric port, and was removed
  again in the hierarchical-streets backport (#100). This finishes the job: the
  `street_full`/`street_large_full` assets, their declarations in `citystyle_common`'s `parts` and
  `largeparts` blocks, and the `full` component of `StreetParts` (record field, codec entry and
  `DEFAULT`) are gone. This is not a breaking change for third-party datapacks: `StreetParts` is
  decoded with `RecordCodecBuilder`, which reads only the field names it's built from and never
  validates an input object's key set, so a datapack that still declares `"full"` under `parts` or
  `largeparts` has that key silently ignored rather than rejected.
- **Removed the vine subsystem.** `ChunkFixer.generateVines` guarded all four of its wall passes on
  the neighbouring chunk having already reached `ChunkStatus.FEATURES` — a status city generation,
  now running at the carver stage, cannot reach there. Instrumented over the digest window: 179
  guard evaluations, 0 passes. The subsystem has been dead code since city generation moved to the
  carver stage (`15dba5f2`); with chunk-sized buildings most vine surface sits at exactly the border
  this guard blocked, so what still worked before that move was already a small fraction of the
  intent. It was also the order-dependence tracked as issue #20 (now closed): `createVineStrip`
  wrote straight to the world instead of through the driver, so whichever neighbouring chunk
  generated first silently decided the outcome, invisibly to `/urbex digest`. Removed rather than
  repaired: the generation code (`generateVines`, `createVineStrip`, `vineRoll`,
  `vineContinueRoll`), the `VINE_CHANCE` profile setting and its GUI slider, and the datapack's
  `vinewest`/`vineeast`/`vinesouth`/`vinenorth` `WorldSettings` fields are all gone. This is not a
  breaking change for third-party datapacks: `WorldSettings` is decoded with `RecordCodecBuilder`,
  the same construction confirmed above for `StreetParts`, which reads only the field names it's
  built from and never validates an input object's key set — a datapack that still declares
  `vinewest` (or its siblings) under `settings` has that key silently ignored rather than rejected.
  A building asset can already paint vines directly into its own design through the ordinary block
  palette, which accepts any block state at any position in the footprint including the border
  columns, so an asset can reserve a margin inside itself to hang them in — strictly more expressive
  than the system it replaces, which offered a single chance for the whole world and only four fixed
  vine-facing states. Because the guard never fired within the profiled generation window, this
  removal does not itself move block placement in already-generated chunks; as always, the mod makes
  no promise that a world regenerates identically after an update.
- **Every city layout moved.** `Rng.Purpose` no longer carries a single dead constant. Alongside the
  five vine constants above it dropped `STREET` and `HIGHWAY`, dead since long before this release,
  and `SPHERE`, `SPHERE_BLOCKS` and `SPHERE_CITY_LEVEL`, dead as of the sphere removal at the top of
  this section — ten constants gone, taking the enum from 51 entries to 41, every one of which now
  has a live caller. The alternative, keeping a dead constant as a reserved slot so that the ordinals
  below it never move, exists to protect *released* worlds; this fork has none to protect and already
  promises nothing about cross-version world stability, so the slots were deleted outright rather
  than renamed and kept. Every consumer addresses its stream as `purpose.ordinal() + 1`, so every
  constant after `BUILDING` shifted address, and with it every random stream downstream of one —
  which is effectively all of city generation. This is the largest user-visible effect in this
  section: every city in every world lays out differently past the point these changes land, not only
  at chunk borders. As always, the mod makes no promise that an existing world regenerates
  identically after an update.
- **Border fences, walls and stairs resolve their connections one step later.** `ChunkDriver` used
  to read — and, when the neighbour happened to already be FULL, write into — the neighbouring chunk
  to compute a border block's connection state; which of two adjacent chunks a worker thread
  generated first could change the result. Border positions are now marked for vanilla's own
  postprocessing pass instead, the same mechanism vanilla structures use across chunk borders, so
  the connection is computed once from every neighbour's final state rather than mid-generation.
- **Worldgen no longer logs cross-chunk read errors.** The two fixes above were the entire source of
  the `Detected unsafe terrain read during worldgen` spam in the log: a fixed digest window that
  logged 88 such warnings before these two fixes logs zero after them. A permanent gate
  (`UnsafeReadGateMixin`, enforced on every digest run via `-Durbex.digestCheck.failOnUnsafeRead`)
  now fails the check if a future change reintroduces a cross-chunk read or write anywhere in city
  generation.
- **Worlds generate differently past the already-generated chunk border, again.** As with `0.1.0`,
  this is expected and permitted: the mod makes no promise that an existing world regenerates
  identically after an update, and the road field changes what every city chunk resolves to.

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
