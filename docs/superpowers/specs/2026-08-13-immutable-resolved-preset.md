# The resolved preset becomes immutable (#10)

Phase 3, item 10 of epic #134: "Make the resolved runtime preset immutable as part of #130."

This is the one Phase 3 item not implemented in stack #159. It is written down here rather than
attempted at the end of that stack because it is a 738-call-site mechanical rewrite, and the epic's
own rule — *separate mechanical moves and renames from logic changes* — is exactly the rule that
makes it a stack of its own.

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

## Order

One PR per step, in this order, each digest-checked:

1. **Accessors first, fields still public.** Add a getter per field; migrate the 738 read sites to
   them. No behaviour change, no type change, and it is the step that makes the rest reviewable — a
   later diff that also changed a `.FIELD` to a `.field()` on every line would hide everything else.
2. **Fields private.** The compiler now finds every writer: the twelve `applyTo` methods, `copy()`,
   and `SettingDescriptor`'s setters. Nothing else should appear; anything that does is the defect
   this issue is about, and belongs in its own commit with a note.
3. **Sections.** Group the fields into the per-section records. `ResolvedPreset` composes them.
4. **The draft.** `PresetDraft` becomes the mutable side; `Presets.resolve` returns a
   `ResolvedPreset`; `PlanningContext.preset` changes type.
5. **The GUI seam.** `SettingDescriptor` takes `(getter, wither)`.

Step 1 is the large diff and the safe one. Steps 2–5 are small and each is a real boundary.

## Verification

Every step must leave the five digest goldens unchanged. Steps 1 and 3 in particular are where a
transcription error would show up as a moved golden rather than as a compile failure, so neither may
be merged on unit tests alone.
