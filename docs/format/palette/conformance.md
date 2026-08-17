# Conformance index

`[GENERATED]` — do not edit. Regenerate with `./gradlew regenerateConformance`;
`ConformanceIndexTest` fails if the checked-in copy differs from what the documents say.

Every rule in this specification, its class, its fixtures, and the tests that cite it. See
[the specification system](../README.md#5-the-conformance-index) for what this file is for.

## Totals

| Area | Rules | Fixtures |
|---|---:|---:|
| `MODEL` | 42 | 22 |
| `TRAIT` | 41 | 16 |
| `REF` | 52 | 11 |
| `MERGE` | 12 | 4 |
| `WEIGHT` | 37 | 11 |
| `CHAR` | 14 | 4 |
| `LOAD` | 24 | 0 |
| `DIAG` | 46 | 0 |
| `VER` | 21 | 2 |
| **total** | **289** | **70** |

## Outstanding

**Rules relying on the draft suspension of fixture-completeness (0):** none — this specification is ready to leave draft on this criterion.

**Rules marked `[NO-FIXTURE]` (13), which must each be covered by a citing test:**

| Rule | Reason |
|---|---|
| `REF.043` | a second asset |
| `REF.045` | a second asset |
| `REF.062` | a parent palette |
| `MERGE.012` | a part carrying an inline palette |
| `MERGE.010` | a version 1 and a version 2 file |
| `WEIGHT.019` | a parent palette to spread from |
| `WEIGHT.063` | a generated 129-choice list |
| `CHAR.011` | a part file, not a palette |
| `CHAR.022` | a command invocation |
| `LOAD.013` | a style with several palette groups |
| `VER.005` | a version 1 and a version 2 file |
| `VER.006` | a style and two palettes |
| `VER.013` | a palette and a conditions asset |

**Tests:** none yet. Every row below shows `—` in the Tests column until the harness lands;
`ConformanceIndexTest` will fail on any rule that still shows `—` once this document leaves draft.

## Rules

### `palette/00-model.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `MODEL.001` | `MUST` |  | `accept` | — |
| `MODEL.002` | `REJECT` | `DIAG.001` | `reject=DIAG.001` | — |
| `MODEL.003` | `MUST` |  |  | — |
| `MODEL.004` | `REJECT` | `DIAG.003` | `reject=DIAG.003` | — |
| `MODEL.005` | `MUST` |  |  | — |
| `MODEL.010` | `MUST` |  |  | — |
| `MODEL.011` | `DEFAULT` |  | `equiv=default-kind`, `equiv=default-kind` | — |
| `MODEL.012` | `REJECT` | `DIAG.004` | `reject=DIAG.004` | — |
| `MODEL.013` | `MUST` |  | `reject=DIAG.003` | — |
| `MODEL.020` | `EQUIV` |  | `equiv=stone-brick-marker`, `equiv=stone-brick-marker` | — |
| `MODEL.021` | `MUST NOT` |  |  | — |
| `MODEL.030` | `MUST` |  |  | — |
| `MODEL.031` | `MUST` |  |  | — |
| `MODEL.032` | `MUST` |  |  | — |
| `MODEL.033` | `REJECT` | `DIAG.005` | `reject=DIAG.005` | — |
| `MODEL.040` | `MUST` |  |  | — |
| `MODEL.041` | `MUST` |  |  | — |
| `MODEL.042` | `ACCEPT` |  | `accept` | — |
| `MODEL.043` | `REJECT` | `DIAG.006` | `reject=DIAG.006` | — |
| `MODEL.044` | `MUST` |  | `accept` | — |
| `MODEL.045` | `REJECT` | `DIAG.007` | `reject=DIAG.007` | — |
| `MODEL.046` | `MUST` |  |  | — |
| `MODEL.047` | `ACCEPT` |  | `accept` | — |
| `MODEL.050` | `MUST` |  | `accept` | — |
| `MODEL.051` | `MUST` |  | `reject=DIAG.003` | — |
| `MODEL.052` | `MUST` |  |  | — |
| `MODEL.053` | `REJECT` | `DIAG.008` | `reject=DIAG.008` | — |
| `MODEL.060` | `MUST` |  |  | — |
| `MODEL.061` | `MUST` |  |  | — |
| `MODEL.062` | `REJECT` | `DIAG.009` | `reject=DIAG.009` | — |
| `MODEL.063` | `MUST` |  |  | — |
| `MODEL.064` | `MUST` |  |  | — |
| `MODEL.070` | `MUST` |  |  | — |
| `MODEL.071` | `MUST` |  |  | — |
| `MODEL.072` | `REJECT` | `DIAG.010` | `reject=DIAG.010` | — |
| `MODEL.073` | `MUST` |  |  | — |
| `MODEL.074` | `MUST` |  |  | — |
| `MODEL.075` | `MUST` |  |  | — |
| `MODEL.076` | `MUST` |  |  | — |
| `MODEL.080` | `MUST` |  |  | — |
| `MODEL.081` | `REJECT` | `DIAG.011` | `reject=DIAG.011` | — |
| `MODEL.082` | `ACCEPT` |  | `accept` | — |

### `palette/01-traits.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `TRAIT.001` | `MUST` |  |  | — |
| `TRAIT.002` | `MUST` |  |  | — |
| `TRAIT.003` | `REJECT` | `DIAG.020` | `reject=DIAG.020` | — |
| `TRAIT.004` | `MUST` |  | `accept` | — |
| `TRAIT.005` | `MUST` |  | `accept` | — |
| `TRAIT.006` | `MUST` |  |  | — |
| `TRAIT.007` | `MUST NOT` |  |  | — |
| `TRAIT.008` | `MUST` |  |  | — |
| `TRAIT.009` | `MUST` |  | `accept` | — |
| `TRAIT.010` | `MUST` |  |  | — |
| `TRAIT.011` | `MUST` |  |  | — |
| `TRAIT.012` | `ACCEPT` |  | `accept` | — |
| `TRAIT.020` | `MUST` |  |  | — |
| `TRAIT.021` | `REJECT` | `DIAG.021` | `reject=DIAG.021` | — |
| `TRAIT.022` | `MUST` |  |  | — |
| `TRAIT.030` | `MUST` |  |  | — |
| `TRAIT.031` | `REJECT` | `DIAG.021` | `reject=DIAG.021` | — |
| `TRAIT.032` | `MUST` |  |  | — |
| `TRAIT.040` | `MUST` |  |  | — |
| `TRAIT.041` | `REJECT` | `DIAG.022` | `reject=DIAG.022` | — |
| `TRAIT.042` | `MUST NOT` |  |  | — |
| `TRAIT.050` | `MUST` |  |  | — |
| `TRAIT.051` | `DEFAULT` |  | `equiv=absent-unlit`, `equiv=absent-unlit` | — |
| `TRAIT.052` | `REJECT` | `DIAG.023` | `reject=DIAG.023` | — |
| `TRAIT.053` | `REJECT` | `DIAG.024` | `reject=DIAG.024` | — |
| `TRAIT.054` | `MUST` |  |  | — |
| `TRAIT.055` | `MUST` |  |  | — |
| `TRAIT.060` | `MUST` |  |  | — |
| `TRAIT.061` | `MUST` |  |  | — |
| `TRAIT.062` | `DEFAULT` |  | `equiv=absent-replacement`, `equiv=absent-replacement` | — |
| `TRAIT.063` | `MUST` |  |  | — |
| `TRAIT.064` | `REJECT` | `DIAG.025` | `reject=DIAG.025` | — |
| `TRAIT.065` | `MUST` |  |  | — |
| `TRAIT.070` | `MUST` |  |  | — |
| `TRAIT.071` | `DEFAULT` |  | `accept` | — |
| `TRAIT.072` | `MUST` |  |  | — |
| `TRAIT.073` | `MUST` |  |  | — |
| `TRAIT.090` | `MUST` |  |  | — |
| `TRAIT.091` | `MUST` |  |  | — |
| `TRAIT.092` | `MUST NOT` |  |  | — |
| `TRAIT.093` | `MUST` |  |  | — |

### `palette/02-references.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `REF.001` | `MUST` |  |  | — |
| `REF.002` | `MUST` |  |  | — |
| `REF.003` | `MUST` |  | `accept` | — |
| `REF.004` | `MUST` |  |  | — |
| `REF.005` | `MUST` |  |  | — |
| `REF.010` | `MUST` |  |  | — |
| `REF.011` | `MUST` |  |  | — |
| `REF.012` | `MUST NOT` |  |  | — |
| `REF.013` | `REJECT` | `DIAG.030` | `reject=DIAG.030` | — |
| `REF.014` | `MUST` |  |  | — |
| `REF.015` | `MUST NOT` |  |  | — |
| `REF.016` | `MUST` |  |  | — |
| `REF.017` | `MUST` |  |  | — |
| `REF.018` | `MUST` |  |  | — |
| `REF.020` | `ACCEPT` |  | `accept` | — |
| `REF.021` | `MUST` |  |  | — |
| `REF.022` | `MUST NOT` |  |  | — |
| `REF.030` | `MUST` |  |  | — |
| `REF.031` | `MUST` |  |  | — |
| `REF.032` | `REJECT` | `DIAG.032` | `reject=DIAG.032` | — |
| `REF.033` | `MUST` |  |  | — |
| `REF.034` | `INVARIANT` |  |  | — |
| `REF.035` | `INVARIANT` |  |  | — |

### `palette/03-pointers.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `REF.040` | `MUST` |  |  | — |
| `REF.041` | `MUST` |  |  | — |
| `REF.042` | `MUST` |  | `accept` | — |
| `REF.043` | `DEFAULT` |  | *n/a* | — |
| `REF.044` | `MUST` |  |  | — |
| `REF.045` | `REJECT` | `DIAG.034` | *n/a* | — |
| `REF.046` | `MUST` |  |  | — |
| `REF.050` | `MUST` |  |  | — |
| `REF.051` | `MUST` |  | `accept` | — |
| `REF.052` | `MUST` |  |  | — |
| `REF.053` | `REJECT` | `DIAG.035` | `reject=DIAG.035` | — |
| `REF.054` | `MUST NOT` |  |  | — |
| `REF.060` | `MUST` |  |  | — |
| `REF.061` | `MUST` |  |  | — |
| `REF.062` | `REJECT` | `DIAG.036` | *n/a* | — |
| `REF.063` | `MUST NOT` |  |  | — |
| `REF.070` | `MUST` |  | `accept` | — |
| `REF.071` | `REJECT` | `DIAG.037` | `reject=DIAG.037` | — |
| `REF.072` | `MUST` |  |  | — |
| `REF.073` | `MUST` |  |  | — |
| `REF.074` | `MUST` |  |  | — |
| `REF.075` | `MUST` |  |  | — |
| `REF.080` | `MUST` |  |  | — |
| `REF.081` | `MUST` |  | `accept` | — |
| `REF.082` | `MUST` |  |  | — |
| `REF.083` | `REJECT` | `DIAG.039` | `reject=DIAG.039` | — |
| `REF.084` | `MUST NOT` |  |  | — |
| `REF.085` | `MUST` |  |  | — |
| `REF.086` | `MUST` |  |  | — |

### `palette/04-merging.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `MERGE.001` | `MUST` |  |  | — |
| `MERGE.002` | `MUST` |  |  | — |
| `MERGE.003` | `MUST` |  |  | — |
| `MERGE.004` | `MUST` |  |  | — |
| `MERGE.005` | `MUST` |  | `accept` | — |
| `MERGE.006` | `MUST` |  | `accept` | — |
| `MERGE.007` | `REJECT` | `DIAG.002` | `reject=DIAG.002` | — |
| `MERGE.008` | `MUST` |  |  | — |
| `MERGE.011` | `MUST` |  |  | — |
| `MERGE.012` | `ACCEPT` |  | *n/a* | — |
| `MERGE.009` | `REJECT` | `DIAG.031` | `reject=DIAG.031` | — |
| `MERGE.010` | `ACCEPT` |  | *n/a* | — |

### `palette/05-weights.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `WEIGHT.001` | `MUST` |  |  | — |
| `WEIGHT.002` | `REJECT` | `DIAG.040` | `reject=DIAG.040` | — |
| `WEIGHT.003` | `MUST` |  |  | — |
| `WEIGHT.004` | `MUST` |  |  | — |
| `WEIGHT.005` | `MUST` |  |  | — |
| `WEIGHT.010` | `MUST` |  | `accept` | — |
| `WEIGHT.011` | `MUST` |  | `accept` | — |
| `WEIGHT.012` | `MUST` |  |  | — |
| `WEIGHT.013` | `REJECT` | `DIAG.041` | `reject=DIAG.041` | — |
| `WEIGHT.014` | `REJECT` | `DIAG.045` | `reject=DIAG.045` | — |
| `WEIGHT.015` | `INVARIANT` |  |  | — |
| `WEIGHT.016` | `MUST` |  |  | — |
| `WEIGHT.017` | `MUST` |  | `accept` | — |
| `WEIGHT.018` | `MUST` |  |  | — |
| `WEIGHT.019` | `REJECT` | `DIAG.045` | *n/a* | — |
| `WEIGHT.020` | `MUST` |  | `accept` | — |
| `WEIGHT.021` | `MUST` |  | `accept` | — |
| `WEIGHT.022` | `MUST` |  |  | — |
| `WEIGHT.023` | `MUST` |  |  | — |
| `WEIGHT.024` | `REJECT` | `DIAG.043` | `reject=DIAG.043` | — |
| `WEIGHT.025` | `MUST NOT` |  |  | — |
| `WEIGHT.030` | `ACCEPT` |  | `accept` | — |
| `WEIGHT.031` | `MUST` |  |  | — |
| `WEIGHT.032` | `REJECT` | `DIAG.043` | `reject=DIAG.043` | — |
| `WEIGHT.040` | `MUST` |  |  | — |
| `WEIGHT.041` | `MUST` |  |  | — |
| `WEIGHT.042` | `INVARIANT` |  |  | — |
| `WEIGHT.043` | `MUST` |  |  | — |
| `WEIGHT.050` | `MUST` |  |  | — |
| `WEIGHT.051` | `MUST` |  |  | — |
| `WEIGHT.052` | `MUST` |  |  | — |
| `WEIGHT.053` | `INVARIANT` |  |  | — |
| `WEIGHT.060` | `MUST` |  |  | — |
| `WEIGHT.061` | `INVARIANT` |  |  | — |
| `WEIGHT.062` | `MUST` |  |  | — |
| `WEIGHT.063` | `REJECT` | `DIAG.044` | *n/a* | — |
| `WEIGHT.064` | `MUST` |  |  | — |

### `palette/06-characters.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `CHAR.001` | `MUST` |  |  | — |
| `CHAR.002` | `MUST` |  |  | — |
| `CHAR.003` | `REJECT` | `DIAG.050` | `reject=DIAG.050` | — |
| `CHAR.004` | `REJECT` | `DIAG.051` | `reject=DIAG.051` | — |
| `CHAR.005` | `REJECT` | `DIAG.052` | `reject=DIAG.052` | — |
| `CHAR.006` | `ACCEPT` |  | `accept` | — |
| `CHAR.007` | `MUST NOT` |  |  | — |
| `CHAR.010` | `MUST` |  |  | — |
| `CHAR.011` | `REJECT` | `DIAG.053` | *n/a* | — |
| `CHAR.020` | `MUST` |  |  | — |
| `CHAR.021` | `MUST` |  |  | — |
| `CHAR.022` | `REJECT` | `DIAG.054` | *n/a* | — |
| `CHAR.030` | `INVARIANT` |  |  | — |
| `CHAR.031` | `INVARIANT` |  |  | — |

### `palette/07-compilation.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `LOAD.001` | `MUST` |  |  | — |
| `LOAD.002` | `MUST` |  |  | — |
| `LOAD.003` | `MUST` |  |  | — |
| `LOAD.004` | `MUST` |  |  | — |
| `LOAD.010` | `MUST` |  |  | — |
| `LOAD.011` | `INVARIANT` |  |  | — |
| `LOAD.012` | `MUST` |  |  | — |
| `LOAD.013` | `ACCEPT` |  | *n/a* | — |
| `LOAD.014` | `INVARIANT` |  |  | — |
| `LOAD.020` | `MUST` |  |  | — |
| `LOAD.021` | `MUST` |  |  | — |
| `LOAD.022` | `INVARIANT` |  |  | — |
| `LOAD.023` | `MUST` |  |  | — |
| `LOAD.024` | `INVARIANT` |  |  | — |
| `LOAD.025` | `MUST` |  |  | — |
| `LOAD.030` | `INVARIANT` |  |  | — |
| `LOAD.031` | `MUST NOT` |  |  | — |
| `LOAD.040` | `INVARIANT` |  |  | — |
| `LOAD.041` | `INVARIANT` |  |  | — |
| `LOAD.042` | `INVARIANT` |  |  | — |
| `LOAD.043` | `INVARIANT` |  |  | — |
| `LOAD.044` | `MUST` |  |  | — |
| `LOAD.050` | `MUST` |  |  | — |
| `LOAD.051` | `MUST` |  |  | — |

### `palette/08-errors.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `DIAG.900` | `MUST` |  |  | — |
| `DIAG.901` | `MUST` |  |  | — |
| `DIAG.902` | `MUST` |  |  | — |
| `DIAG.903` | `MUST` |  |  | — |
| `DIAG.904` | `MUST` |  |  | — |
| `DIAG.001` | `DIAG` |  |  | — |
| `DIAG.002` | `DIAG` |  |  | — |
| `DIAG.003` | `DIAG` |  |  | — |
| `DIAG.004` | `DIAG` |  |  | — |
| `DIAG.005` | `DIAG` |  |  | — |
| `DIAG.006` | `DIAG` |  |  | — |
| `DIAG.007` | `DIAG` |  |  | — |
| `DIAG.008` | `DIAG` |  |  | — |
| `DIAG.009` | `DIAG` |  |  | — |
| `DIAG.010` | `DIAG` |  |  | — |
| `DIAG.011` | `DIAG` |  |  | — |
| `DIAG.020` | `DIAG` |  |  | — |
| `DIAG.021` | `DIAG` |  |  | — |
| `DIAG.022` | `DIAG` |  |  | — |
| `DIAG.023` | `DIAG` |  |  | — |
| `DIAG.024` | `DIAG` |  |  | — |
| `DIAG.025` | `DIAG` |  |  | — |
| `DIAG.030` | `DIAG` |  |  | — |
| `DIAG.031` | `DIAG` |  |  | — |
| `DIAG.032` | `DIAG` |  |  | — |
| `DIAG.033` | `DIAG` |  |  | — |
| `DIAG.034` | `DIAG` |  |  | — |
| `DIAG.035` | `DIAG` |  |  | — |
| `DIAG.036` | `DIAG` |  |  | — |
| `DIAG.037` | `DIAG` |  |  | — |
| `DIAG.039` | `DIAG` |  |  | — |
| `DIAG.038` | `DIAG` |  |  | — |
| `DIAG.040` | `DIAG` |  |  | — |
| `DIAG.041` | `DIAG` |  |  | — |
| `DIAG.045` | `DIAG` |  |  | — |
| `DIAG.042` | `DIAG` |  |  | — |
| `DIAG.043` | `DIAG` |  |  | — |
| `DIAG.044` | `DIAG` |  |  | — |
| `DIAG.050` | `DIAG` |  |  | — |
| `DIAG.051` | `DIAG` |  |  | — |
| `DIAG.052` | `DIAG` |  |  | — |
| `DIAG.053` | `DIAG` |  |  | — |
| `DIAG.054` | `DIAG` |  |  | — |
| `DIAG.060` | `DIAG` |  |  | — |
| `DIAG.061` | `DIAG` |  |  | — |
| `DIAG.910` | `MUST` |  |  | — |

### `palette/09-migration.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `VER.001` | `MUST` |  |  | — |
| `VER.002` | `MUST` |  |  | — |
| `VER.003` | `MUST` |  |  | — |
| `VER.004` | `MUST NOT` |  |  | — |
| `VER.005` | `REJECT` | `DIAG.038` | *n/a* | — |
| `VER.006` | `ACCEPT` |  | *n/a* | — |
| `VER.007` | `MUST` |  |  | — |
| `VER.013` | `ACCEPT` |  | *n/a* | — |
| `VER.008` | `MUST` |  |  | — |
| `VER.009` | `MUST` |  |  | — |
| `VER.010` | `REJECT` | `DIAG.060` | `reject=DIAG.060` | — |
| `VER.011` | `REJECT` | `DIAG.061` | `reject=DIAG.061` | — |
| `VER.012` | `MUST NOT` |  |  | — |
| `VER.020` | `MUST` |  |  | — |
| `VER.021` | `MUST` |  |  | — |
| `VER.022` | `MUST` |  |  | — |
| `VER.023` | `MUST` |  |  | — |
| `VER.030` | `MUST` |  |  | — |
| `VER.031` | `MUST` |  |  | — |
| `VER.040` | `MUST` |  |  | — |
| `VER.041` | `MUST` |  |  | — |

