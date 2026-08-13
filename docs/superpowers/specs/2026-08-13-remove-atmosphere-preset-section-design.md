# Remove the `atmosphere` preset section

Issue [#73](https://github.com/Arilas/urbex/issues/73) · Epic [#134](https://github.com/Arilas/urbex/issues/134)

## Problem

The preset fields `HORIZON`, `FOG_RED`, `FOG_GREEN`, `FOG_BLUE` and `FOG_DENSITY` are dead. Nothing
reads them on either side. They are declared in `Preset.java`, copied by `Preset`'s own copy, written
by `AtmosphereSettings.apply`, round-tripped by its codec, labelled in `en_us.json`, and exposed as
editable ADVANCED sliders in `Settings.java` — and that cycle is the whole of their existence. The
only other mention of fog anywhere in `src/main` is a comment on `Settings.java:464`. No renderer, no
mixin, no client event handler reads any of the five.

They are dead in singleplayer as well as multiplayer: the `network` package that would have carried
them to a client was deleted, and there is no client-side consumer for the integrated server to hand
them to either. A player who moves these sliders gets nothing.

Issue #73 named this as half of its **Decide:** — *implement a configuration-phase handshake, or
delete the network package and the client fog/horizon settings.* The network-package half has
already landed. This is the rest.

## Decision

Delete the section (option 1 of the two #73 offers). The alternative — building a fog/horizon
renderer hook plus a configuration-phase handshake — is real feature work for a cosmetic setting
nobody can currently be relying on, and #73's own framing treats deletion as an equally valid
resolution. Approved by the repo owner on 2026-08-13.

### Relation to epic #134's breaking-change constraint

Epic #134 constrains: *"Preserve external datapack registry IDs unless a separate breaking migration
is explicitly approved."* Strictly, this change does not trigger it. The `urbex:presets` registry
survives, and so does every preset ID in it; what goes is one section key **inside** the preset
format. The removal is approved regardless.

## Scope

Two changes, one PR.

### 1. Delete the `atmosphere` section end-to-end

**Java**

| File | Change |
|---|---|
| `config/Preset.java` | the 5 field declarations (`:168-172`); the 5 lines in the copy (`:424-428`); the `AtmosphereSettings` local and its five `Optional.of` arguments (`:574-580`); the `Optional.of(atmosphere)` argument in the `new PresetDefinition(...)` call (`:602`) |
| `worldgen/lost/regassets/data/preset/AtmosphereSettings.java` | deleted |
| `worldgen/lost/regassets/PresetDefinition.java` | `"atmosphere"` out of `KEYS`; the codec group entry; the field; the `atmosphere()` accessor; the `applyTo` line; the import |
| `gui/settings/Settings.java` | the five slider registrations (`:487-496`); the `:464` comment loses "and client fog/horizon tuning" |

**Resources and docs**

| File | Change |
|---|---|
| `assets/urbex/lang/en_us.json` | the 10 keys at `:322-331` |
| `data/urbex/urbex/presets/cavern.json` | its `atmosphere` section (`:13-19`) |
| `data/urbex/urbex/presets/floating.json` | its `atmosphere` section (`:23-25`) |
| `docs/schema/preset.schema.json` | the `atmosphere` property block (`:761-`) |
| `docs/presets.md` | `:32` "eleven **sections**" → "ten"; `atmosphere` dropped from the list |

**Tests**

| File | Change |
|---|---|
| `config/PresetCodecTest.java` | the `re.atmosphere().isEmpty()` assertion (`:52`) |
| `config/PresetSchemaTest.java` | the import and the `EXPECTED_SECTION_KEYS.put("atmosphere", ...)` entry |
| `config/PresetRoundTripTest.java` | the import; the `EXPECTED_SECTION_KEYS` entry; the `p.HORIZON = 100f` / `assertEquals(100f, resolved.HORIZON)` pair |
| `config/PresetResolutionTest.java` | one fewer `Optional.empty()` in the `new PresetDefinition(...)` call (`:31`) |

`roundTripPreservesValues` sets one representative field per section, so `HORIZON` drops without
needing a replacement — every other section keeps its representative.

### 2. Collapse `PresetDefinition.Meta`

`Meta` exists only because `RecordCodecBuilder.group` caps at sixteen fields and the flat form needed
seventeen. Its own javadoc calls it *"not a shape anyone asked for … this record exists purely to be
flattened again and never appears in the format."* Removing `atmosphere` frees the slot: six metadata
fields plus ten sections is exactly sixteen.

Delete `Meta`, the `META` map codec, the `meta()` getter, and the private delegating constructor.
The six metadata fields move back into the flat `RecordCodecBuilder.group` in their existing order
(`extends`, `name`, `description`, `extraDescription`, `warning`, `icon`), and the existing public
constructor becomes the codec's own. The javadoc explaining the seventeen-field workaround goes with
the record it explains.

This is not a format change — those six were always top-level keys in the JSON, and their order in
the group does not affect decoding. Both call sites of the public constructor (`Preset.toDefinition`
and `PresetResolutionTest`) already pass the metadata positionally, so they only lose the
`atmosphere` argument.

## Migration behaviour

No new migration code. A datapack that still declares `atmosphere` falls into the existing
`UnknownKeys` path and logs:

```
Ignoring unknown key(s) in preset preset: [atmosphere]
```

The preset then loads normally, and because nothing ever read the five values its generation output
is byte-identical to before. The JSON Schema is the stricter net: the top level is
`additionalProperties: false`, so an editor wired to the schema flags `atmosphere` as invalid while
the author types.

`RetiredKeys` was considered and rejected. Its contract is *"deleted, not aliased: use `X` instead"*
— a rename where silence is expensive because inheritance vanishes without either key being named in
the resulting error. Neither condition holds here. There is no replacement key to point at, and
hard-failing the decode would turn a section that provably never did anything into a preset that
refuses to load — strictly worse for pack authors than today, with no correctness gain.

## Verification

`./gradlew test`.

The digest suites are not implicated. Epic #134's PR rules run them for any planning, RNG, palette,
block-write or ordering change; none of the five fields ever reached generation, so no generation
path changes. The PR body should state that reasoning explicitly rather than leaving it implied.

## Issue hygiene

The PR links #73 and #134.

It answers #73's **Decide:** — the deletion branch, whose network-package half already landed — but
it does **not** close #73. The static `Config.profileFromClient` / `Config.jsonFromClient` handoff
that the issue's title is actually about is untouched, and that half is epic #134 phase 3 item 11,
*"Replace the static client-to-integrated-server selection handoff with an explicit, versioned
boundary."* The PR body must avoid GitHub's auto-closing keywords for #73 and say plainly what
remains.
