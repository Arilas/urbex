# Palette version 1 regression corpus

These files are test fixtures, not an installable datapack. They are kept under
`src/test/resources/format/palette/v1-snapshots/`, outside the live asset walkers rooted at
`src/main/resources/data`. Do not update them when an addon changes its live pack or palette format.

## Modern Tweaks: redistribution permitted

`urbexmt/` contains a byte-for-byte subset of the frozen version 1 snapshot from **Urbex Modern
Tweaks**, a mechanical port of **Lost Cities Modern Tweaks 3.0.0** by **morriswmz (Mianzhi Wang)**.
The original pack is available at [Modrinth](https://modrinth.com/datapack/lost-cities-modern-tweaks).
Its content remains MIT licensed; the complete original copyright and permission notice is included
in [MODERN-TWEAKS-LICENSE.txt](MODERN-TWEAKS-LICENSE.txt).

Provenance:

- Source port: **Urbex Modern Tweaks** (`Arilas/urbex-moderntweaks`).
- Frozen source commit: `00b325b5c4b5e435fca5737e1f0635dfebabbd68`.
- Snapshot commit: `c244ab03bae9bd4de1517838f2244d90942fb338`.
- Source directory: `reference/v1-snapshot/urbex/`.
- License copied unchanged from `pack/LICENSE.txt` at the snapshot commit.
- Each copied JSON file was verified against its Git blob at the snapshot commit. No contents were
  edited, and paths relative to the pack root are unchanged.

Only inputs read by `V1ToV2Test` are retained:

| Directory | Files | Purpose |
| --- | ---: | --- |
| `palettes` | 98 | Conversion, idempotence, and per-marker compiler comparisons |
| `variants` | 58 | Conversion into the definitions index used by converted references |
| `conditions` | 5 | Condition ids used to validate converted loot and spawner traits |
| `parts` | 60 | Every part containing an inline palette, for the converter's survey assertions |

The subset contains 221 JSON files (253,323 bytes). Parts without inline palettes and unrelated
registries are omitted. The retained parts are complete source files, not rewritten extracts.

The standard `test` task combines these fixtures with Urbex's bundled pack. It pins **186 converted
assets**, **24 palettes excluded for referencing the retired variants registry**, **74 compared
version 1 palettes**, and **267 compiled markers**. The two unresolved aliases in the snapshot have
no compiled marker until another palette supplies their targets.

## Private corpus: never redistribute

The Zombie Apocalypse Essentials snapshot is **not included**. Its source notice permits private
modification but grants no public redistribution permission. Do not add it to this directory, a CI
artifact, or a public repository.

An authorized local installation can run the complete original corpus with:

```sh
./gradlew privateCorpusTest -PurbexPrivateCorpus=/absolute/path/to/reference/v1-snapshot/urbex
```

Supply the frozen version 1 snapshot after its unassigned marker codepoints were reassigned, not the
live version 2 pack. The task requires the path, tracks the external directory as an input, and fails
if it is missing or the corpus does not match the assertions. It does not download private data,
silently skip checks, or write test results to a shared build cache.

Tests tagged `private-corpus` retain the original **204 converted assets**, **26 excluded palettes**,
**79 compared palettes**, **299 compiled markers**, and the survey and missing-block regression
checks. The standard `test` task excludes that tag; run both tasks for the public and private checks.
Local private results are written under `build/reports/tests/privateCorpusTest/` and
`build/test-results/privateCorpusTest/`; keep those reports private too.
