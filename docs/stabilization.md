# Post-Palette v2 stabilization

Audit date: 2026-09-15. Starting point: `05ca3969` (`main`, Palette v2 merge).
All 24 initially open GitHub issues were read, including their comments, and compared with the current source.
An issue's old line numbers or implementation plan are not evidence that its defect remains.
The audit added #222–#224, closed previously completed #213/#215, updated #134's phase checklist,
and applied `priority:P1`, `priority:P2` and `priority:P3` labels to the remaining issues.

## Priority and scope

No P0 failure was established. P1 means data preservation or incorrect Palette v2 behavior;
P2 means the next bounded correctness or verification work; P3 means features, design changes or
mechanical reorganization. These priorities describe this stabilization effort, not a release promise.

The first batch fixes invalid-config data loss, per-part damage identity, rotation, optional
selection, nested block-entity validation and schema validation; removes TOML migration;
strengthens coverage checks; and documents
supported asset migration.
It was merged in [PR #225](https://github.com/Arilas/urbex/pull/225), commit `f59eb60f`.
The second batch completes marker-specific damage across procedural materials and deferred lights.
Package moves and terrain redesign belong in separate changes so their effects can be reviewed.

### P1: addressed first

| Item | Verification | First-batch result |
| --- | --- | --- |
| [#222](https://github.com/Arilas/urbex/issues/222): invalid global JSON was overwritten | `ConfigRepository.loadGlobal` wrote defaults back after either a JSON parse failure or a codec rejection. This destroyed settings the owner could repair. | Preserve invalid/unreadable existing files; use defaults for the current run. Regression tests compare the original file contents after loading. |
| [#216](https://github.com/Arilas/urbex/issues/216): marker-specific damage | `CompiledPalette` collapsed damage by block state; the post-placement passes no longer knew which part marker placed it. Ruins also discarded the retrieved target and wrote generic bars. | Part and park-lamp placement retain per-position damage metadata, including an explicit absence of a damaged form. Actual damage decisions and chunk writes are tested. Procedural placement remained for the second batch below. |
| [#218](https://github.com/Arilas/urbex/issues/218): schema validation skipped `#` | Reproduced directly with 1.5.6. Changing error path format did not fix it. The upstream defect skipped hash-prefixed keys in `additionalProperties`, so it was broader than one palette marker. | Upgrade the test dependency to 2.0.7, retain Jackson 2, remove key rewriting, and test original keys at marker, definition, node and trait-field positions. |
| [#223](https://github.com/Arilas/urbex/issues/223): rotation trait ignored | `Parts` still used the legacy world-style block tag; the adapter discarded the compiled marker rotation policy. Compile-time tests did not exercise placement. | Apply the selected slot's default-on/opt-out policy in part placement; transform damaged satellites using their own policy. Remove obsolete rotation-tag scaffolding. |
| [#224](https://github.com/Arilas/urbex/issues/224): optional trait ignored | No generation consumer used the optional trait. A marker still placed its primary block at density 0. Draft examples also named a nonexistent `stuff` density. | Carry the selection into placement before transformation and decoration, using actual preset density names, explicit unknown-name rejection and position-addressed replacement selection. |

The validator fix is documented in [upstream #1252](https://github.com/networknt/json-schema-validator/issues/1252)
and shipped on the 2.x line in [2.0.2](https://github.com/networknt/json-schema-validator/releases/tag/2.0.2).
The selected [2.0.7 release](https://github.com/networknt/json-schema-validator/releases/tag/2.0.7)
uses the renamed Schema/SchemaRegistry API described in the
[upstream migration notes](https://github.com/networknt/json-schema-validator/blob/2.0.7/doc/migration-2.0.0.md).
This is a test-only dependency.

### Migration and verification follow-ups

| Issue | Verified state and action |
| --- | --- |
| [#220](https://github.com/Arilas/urbex/issues/220) | The v2 reference walker already existed, but did not measure its coverage. Added corpus counts by source category/reference kind and failing-reference fixtures for palettes, definitions, parts and buildings. P2, addressed in PR #225. |
| [#217](https://github.com/Arilas/urbex/issues/217) | No standing mutation harness existed. Added 16 independent, in-memory mutations that must compile and then fail the same coverage assertions used by the unmodified pack. P2, addressed in PR #225. |
| [#214](https://github.com/Arilas/urbex/issues/214) | Both owner codecs already dispatch inline v2 palettes correctly. Added explicit MERGE.011/MERGE.012 coverage for parts and buildings, including rejection of version 1 and unversioned inline palettes. Runtime premise was stale; acceptance coverage completed in PR #225. |
| [#215](https://github.com/Arilas/urbex/issues/215) | Closed as already fixed on the starting revision: the converse conformance check exists and all three rules are REJECT. No duplicate implementation change was needed. |
| [#213](https://github.com/Arilas/urbex/issues/213) | Closed: all ten tasks, bundled migration, removal of the variants registry and refusal of v1 loading are already on main. Separately tracked defects remain open. |

Runtime TOML fallback was isolated in `ConfigRepository` and `LegacyToml`; the old Forge config
dependency was already gone. Both fallback branches and the parser are removed. Existing TOML files
are untouched. [Configuration](configuration.md) explains manual migration and current precedence.

The runtime already refused v1 palettes, but VER.006 still permitted a style to load v1 and v2
together. That rule is now a tombstone, its old test uses two real v2 palettes, and VER.013 no longer
claims the removed variants registry loads. [Migrating from Lost Cities](migrating-from-lost-cities.md)
explains the converter's real scope and embedded-palette workflow.

Do not delete `V1ToV2`, frozen licensed conversion fixtures or the normative translation rules:
pack authors still need them. Some legacy compiler support remains under `src/main` solely for
converter-equivalence and internal tests. Move those dependencies into test support as a separate
extraction; removing them blindly would remove the evidence used to verify conversion.

### Second batch: complete marker-specific damage

Procedural walls, supports, door frames, railways, vegetation and rubble now carry their selected palette
material into the chunk driver. Deferred socket lights retain both lit and unlit choices, including
weighted alternatives that resolve to the same block. The runtime state-keyed damage fallback is
removed. Existing primary sampling positions, terrain loops and random-purpose addresses are preserved.

The driver retains sparse metadata for accepted writes with damage traits. Literal and traitless
overwrites clear it; intermediate flushes preserve it. Weighted damage targets and nested optional
selection use each destination's coordinates, and precompiled transform views apply each satellite's
own rotation policy. Ruins and explosions retain the selected replacement's own damage trait for
later passes, even when the replacement has the same block state.

`MarkerDamageTest`, `ProceduralMarkerDamageTest`, `DeferredMarkerDamageTest` and `RuinDamageTest`
exercise compilation, procedural writes, deferred planning, accepted chunk commits and repeated
damage. This completes the marker-identity acceptance condition in #216. Conformance checks also
exercise status parsing with explicit examples, so they no longer require an unfinished rule to exist.

The audit separately reproduced [#226](https://github.com/Arilas/urbex/issues/226): damaged replacements
and deferred light candidates can retain authored block-entity NBT without dispatching its placement
handler. A damaged chest and a socket campfire both compile cleanly and commit their block states but
lose the authored NBT. This is P2 follow-up work on decoration effects, separate from damage identity.

### Remaining backlog

These are source-verified findings. Terrain issues below have not been reproduced in an interactive
game session during this audit; historical performance percentages are not fresh measurements.

| Priority | Issue | Current evidence and next action |
| --- | --- | --- |
| P2 | [#226](https://github.com/Arilas/urbex/issues/226): decoration effects on replacements | Reproduced missing block-entity NBT on damaged replacements and deferred socket candidates. Apply supported decoration handlers with the right generation context, or reject unsupported combinations during compilation. |
| P2 | [#194](https://github.com/Arilas/urbex/issues/194): short highway supports | Both loops in `gen/Highways` still stop after 40 blocks. Replace with one helper bounded by the world's minimum height, preserving water traversal, and test a deep drop. |
| P2 | [#193](https://github.com/Arilas/urbex/issues/193): floating debris | `CityGenerator` still descends through air/fluids only and writes `h + 1` unconditionally. Define debris-specific support and destination rules, including the top-of-world case. |
| P2 | [#195](https://github.com/Arilas/urbex/issues/195): misleading throughput | `DigestRunner` still times generation, hashing and coverage scans together. Separate generation time from verification and name the write-recording overhead. |
| P2 | [#6](https://github.com/Arilas/urbex/issues/6): preset drift | Saved selection records a preset id and optional overrides, not the resolved definition. Decide before release whether to snapshot definitions or freeze released ids. TOML removal does not solve this. |
| P2 | [#69](https://github.com/Arilas/urbex/issues/69): editor correctness | Same-state marker collisions, nonpersistent sessions, chunk-origin placement and append-only edit records remain. Resume now indexes actual world states, so the old first-variant-only explanation is stale. |
| P2 | [#106](https://github.com/Arilas/urbex/issues/106): streets follow-ups | Preview still omits accepted multibuilding footprints and connectors can point into non-road lots. The old NullDimensionInfo/asset-lookup blocker is gone; the preview rationale needs updating. `RoadCell.isRoad` remains unused. |
| P2 | [#63](https://github.com/Arilas/urbex/issues/63): authoring docs/schemas | Authoring docs, preset schema and Palette v2 schema now exist. Re-scope to remaining registry schemas. The old append-only city-style claim is false: lists replace by default, with explicit append. |
| P2/P3 | [#22](https://github.com/Arilas/urbex/issues/22): bridge termination | The parity-based recursion now lives in `BridgeDecisions`; the termination argument is still undocumented. Document it and test boundary cases. No actual overflow was reproduced. |
| P3 | [#134](https://github.com/Arilas/urbex/issues/134): architecture epic | All phase 1–4 child issues are closed and their ownership types exist. Keep the epic open for phase 5, #133; its old unchecked task list is stale. |
| P3 | [#133](https://github.com/Arilas/urbex/issues/133): package reorganization | `worldgen.lost`, `cityassets`, `regassets` and `varia` remain. Perform package moves separately from behavioral fixes and golden updates. |
| P3 | [#29](https://github.com/Arilas/urbex/issues/29): correlated highway noise | Both orientation fields and all octaves still share their seeds. Deliberately changing those seeds moves highway layouts; this requires an intentional generation change. |
| P3 | [#15](https://github.com/Arilas/urbex/issues/15): stepped terrain | `CityField` has eight thresholds producing nine level bands. Continuous adaptation is a terrain design change, not a migration leftover. |
| P3 | [#16](https://github.com/Arilas/urbex/issues/16): floor height | `CityGenerator.FLOORHEIGHT` is still 6. Design variable heights together with the part/building changes in #12. |
| P3 | [#12](https://github.com/Arilas/urbex/issues/12): chunk-sized buildings | Parts and multibuildings still use chunk/grid placement. Sub-chunk lots are a feature and asset-model redesign. |
| P3 | [#14](https://github.com/Arilas/urbex/issues/14): districts | Districts remain absent, but edge styles, style-specific floor bounds and distance weighting now exist. Re-scope the feature around those capabilities. |
| P3 | [#72](https://github.com/Arilas/urbex/issues/72): missing commands | Locate-city, reset-chunks and asset-list commands remain absent. Active-preset visibility and effective preset export already exist; avoid duplicate commands. |
| P3 | [#77](https://github.com/Arilas/urbex/issues/77): integration API | An experimental site-generation API now exists. Read-only minimap/profile queries and events still need their own design. |

## Verification

The first batch passed **1,350 tests**. The second batch's
`./gradlew regenerateConformance build` passes all **1,359 tests**, with no failures or skips,
and builds `build/libs/urbex-fabric-26.2-0.2.0.jar`. Conformance is regenerated from the actual rules
and citing tests. The public
converter fixtures are self-contained; `privateCorpusTest` needs an explicitly supplied private
snapshot and is not part of this audit.

All nine server digest configurations in `.github/workflows/build.yml` pass for both batches, with
**zero unsafe reads** in every run. The second batch preserves every first-batch golden below.
Feature-coverage gates remained enabled:

| Configuration | Verified driver digest |
| --- | --- |
| Base | `b15838a5928f87f7` |
| Features | `b3c5ee9450c7e008` |
| Avoidance: normal, shuffled, two workers, forced cache expiry | `c1db48b62956eb59` |
| Avoidance: other modes | `463fea98e213ef0f` |
| Rail collisions: normal and shuffled | `7123e2081fe2c82b` |

The final nested block-entity validation change also received a repeated server load and avoidance
check. These checks exercise generated server output; they do not replace an interactive client
playtest. No interactive client session or private corpus was used in this audit.

### Explained first-batch golden changes

The original commit was built in an independent checkout and reproduced both original avoidance
digests. Per-position dumps from that checkout and PR #225 show exactly two changed states in
each window, with no added or removed writes:

| Position | Original state | Corrected state |
| --- | --- | --- |
| `(-29, 113, -59)` | Iron bars, connected on all four sides | Air |
| `(-28, 113, -62)` | Iron bars, connected on all four sides | Air |

Both positions belong to marker `l` in `urbex:street_large_end`, using the local
`urbex:street_large` palette. That marker is a double smooth-stone slab with no damage trait.
The old block-state map borrowed the iron-bar replacement from the default palette's `u`/`S`
markers, which use the same state. Retaining this marker's explicit absence of a damaged form
lets the damage pass destroy it normally. These are two instances of the #216 correction;
rotation and optional selection did not change the sampled outputs.

The standard avoidance window has 5,119,665 unchanged writes and two changed writes; the other-modes
window has 5,118,649 unchanged writes and the same two changes. The reviewed golden updates are
`7a8155f727a1d735` → `c1db48b62956eb59` and `54840fa48a550903` → `463fea98e213ef0f` respectively.
The base, feature and rail goldens remain unchanged.
