# The resolved preset becomes immutable (#10)

Phase 3, item 10 of epic #134: "Make the resolved runtime preset immutable as part of #130."

Implemented in stack #159 as two PRs, after the rest of Phase 3 and the removal of the dead
`atmosphere` section (#172) had settled what the field list actually is. The epic's own rule —
*separate mechanical moves and renames from logic changes* — is why it is two and not one.

## What is already true

The properties #10 was originally filed as blocking are all done:

- **Resolved once per epoch.** `PlanningContext.preset` is a final component of a record built when
  the level's `DimensionRuntime` is created (#129). Generation cannot swap it, and a `/reload` or an
  unload publishes a new runtime for the *next* chunk rather than mutating this one.
- **Nothing publishes a draft object.** The create-world screen hands over a `WorldSelection` whose
  patch is encoded `PresetDefinition` JSON (#130, PR #170), so an editor draft never reaches the
  server as an object at all.
- **The customization editor already works on a copy.** `Preset.copy()` exists for it.

What is left is the last sentence of the issue: *"the resolved runtime object is mutable and
public"*. Nothing stops worldgen writing to a `Preset` it was handed; the discipline that keeps the
shared one read-only is convention. That is hygiene, and the issue says so — but it is the kind of
hygiene the rest of this epic replaced with types everywhere else, and #126's lesson was that an
audit which searched for assignments missed a field being decremented on every call.

## Shape

`Preset` has **112 public mutable instance fields**, read at **738 sites across 24 files**, written
by the twelve `*Settings.applyTo(Preset)` methods, and bound to the GUI by `SettingDescriptor`'s
lambda pairs:

```java
new SettingDescriptor<>(..., p -> p.CITY_CHANCE, (p, v) -> p.CITY_CHANCE = v)
```

That seam is deliberate and load-bearing: it is what let the profile class be swapped out without
touching the settings framework, so whatever replaces the fields has to keep an equivalent one.

## Direction

Two types, from one builder:

- **`ResolvedPreset`** — what generation reads. Per-section records mirroring the `*Settings` split
  (`Terrain`, `Cities`, `Buildings`, `Roads`, `Highways`, `Railways`, `Destruction`, `Decoration`,
  `Spawn`, `Atmosphere`, `Misc`), so a section can be passed to the code that needs it instead of the
  whole preset. `PlanningContext` holds this.
- **`PresetDraft`** — a mutable builder, screen-scoped, which the customize editor edits and
  `toDefinition()` encodes. `Presets.resolve` produces a draft, applies the `extends` chain and the
  patch, and seals it into a `ResolvedPreset`.

The GUI seam becomes `(getter, wither)` over the draft: `p -> p.cityChance()`,
`(p, v) -> p.withCityChance(v)`. `SettingDescriptor` needs one change — its setter returns the new
draft rather than mutating in place — and the 112 descriptor declarations change shape mechanically.

## What was done

1. **Accessors first, fields still public** (`10a`). A getter per field, named for the JSON key its
   codec reads it from — so `cityChance()` is what a datapack author already calls `CITY_CHANCE` —
   and all 707 reads moved onto them. No behaviour change and no type change, which is what makes
   the second diff readable: one that also changed a `.FIELD` to a `.field()` on every line would
   have hidden everything else.

2. **The draft/resolved split** (`10b`). `Preset`'s fields became `private final`, and the compiler
   named every writer: the eleven `*Settings.apply` methods, `copy()`, and `SettingDescriptor`'s
   setters. Nothing else appeared. Those writers moved to `PresetDraft`, which carries the same
   values mutably; `Presets.resolve` settles a draft into a `Preset`, and `Preset.toDraft()` is what
   `copy()` was.

Two things went differently from the plan above:

- **No per-section records.** `ResolvedPreset` composed of `Terrain`, `Cities`, … would have been a
  second large mechanical pass over the same 707 sites for a grouping nothing currently asks for.
  `Preset` *is* the resolved value; if a planner later wants only the road settings, the sections
  can be carved out then, against a type that is already immutable.
- **No `(getter, wither)` seam.** It was not needed. `PresetDraft` carries the same public fields
  under the same names *and* answers the same accessors, so all 119 descriptor lambda pairs compile
  against it untouched — only the type parameter moved. A wither per field would have been 119
  allocations per keystroke to express the same thing less clearly.

Three fields on `Preset` are not final: the icon identifier and the two resolved `BlockState`s.
They are memoized derivations of final fields, computed on first ask because resolving a block needs
a registry the class is not handed, and nothing can observe whether one has been computed.

## Verification

Both PRs left the five digest goldens unchanged, which is the check that matters here: a
transcription error in a mechanical pass over 707 sites shows up as a moved golden rather than as a
compile failure, so neither could be merged on unit tests alone.
