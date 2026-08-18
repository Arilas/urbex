# Palette format v2 — design record

Date: 2026-08-18
Status: implemented on `feat/palette-v2`
Specification: [`docs/format/`](../../format/README.md) — 314 identifiers
Plan: [`2026-08-17-palette-v2.md`](../plans/2026-08-17-palette-v2.md)
Tracking: [#213](https://github.com/Arilas/urbex/issues/213)

`docs/format/README.md` §2 forbids a specification document from carrying discussion,
alternatives considered, or measurements, on the grounds that a design record holds them. This is
that record. It exists so the rules can stay terse and so a rejected alternative is findable by
someone who wonders why the rule is not the other thing.

## 1. Why version 1 needed replacing

Measured over the shipped corpus of Urbex, Urbex-ModernTweaks and Urbex-Zombie-Apocalypse-Essentials:

- **84%** of palette entries were one block with no metadata, so the format's complexity was paid
  for by the other 16%.
- `damaged` had **one distinct value across all 60 uses** — a field that was really a global
  default wearing a per-entry disguise.
- **12 of 12** weighted lists ended in a fill-the-remainder sentinel, so nobody wrote real
  proportions and the actual odds of any choice were invisible without summing.
- Two incompatible weight spellings coexisted **inside one file** — `random` with sums near 1100,
  `weight` with sums of 10 — distinguishable only by which parent key you were under, and selected
  by different algorithms.
- `block`, `blocks`, `variant`, `frompalette` and a socket `lightSource` were mutually exclusive
  with nothing saying so: an `if`/`else if` ladder took the first present key in source order and
  dropped the rest silently.
- Zombie Apocalypse Essentials carried **6,527 inline entries of which 1,242 were distinct**.
- Its palettes used **244 markers, 162 non-ASCII**, sweeping contiguously through Greek, Coptic and
  Cyrillic — including **eight unassigned codepoints**, because `/exportpart` walked codepoints in
  sequence.

The deeper failure was uniform: a claim about the format existed in exactly one place and nothing
compared it to anything else. The guide documented 7 of the 11 keys the codec accepted. Three
shipped palettes wrote `damaged` where nothing read it, for the pack's whole lifetime. An addon's
hand-maintained table of which fields were references drifted until **35–55% of real references
went unchecked** in two places without either exiting non-zero.

## 2. Decisions, and what each one cost

**One recursive node type.** A palette entry and a weighted choice are the same thing, so a choice
can carry traits and be named. `$defs`/`$ref` follow from that rather than being added to it.

**Sizes are relative or exact fractions, never counts.** Absolute weights do not compose: spreading
a list already summing to 120 left a following choice 8 slots however it was weighted. 128 is now a
materialisation detail that appears in no palette file.

**Traits are per slot, not per marker**, because two alternatives of one marker can differ — and
they apply in phases (selection → transformation → decoration) because `urbex:light` decides which
block stands there while loot, spawner and block-entity decorate a block already chosen.

**`$defs` are addressable from other files**, so every named definition is API. A deliberate loss of
a file-private tier; the alternative forced a pointless promotion step on anyone wanting a base
pack's definition.

**`urbex:rotatable` became a trait defaulting to on**, replacing a hand-maintained 43-entry block tag
that excluded nothing and had a test whose only job was catching it falling behind. Opt-in would
have reintroduced mis-facing blocks one pack at a time.

**An `extends` chain may not cross versions**, though a style's `randompalettes` may still compose a
version 1 and a version 2 palette — without that, a pack could only migrate every file at once, and
the packs this had to work for hold 30 and 98 palette files.

## 3. Rejected alternatives

Four whole-format shapes were costed before the node model was chosen. **Vanilla-style `type`
dispatch** was rejected on verbosity: ~68 characters to say "this is stone bricks", when 84% of
entries are exactly that. **A compact shorthand format** (a string is a block, an array is weighted)
was rejected because its sigil space collides with the marker space — `#` and `@` are both real
markers in the shipped packs — and because it has a hard extensibility ceiling. A **material library
plus a thin marker map** was folded in as `$defs` rather than adopted as the top-level shape.

Within the chosen shape: `$select` was rejected in favour of `$only`/`$without` on `$ref`, to keep
one concept with a filter rather than two that overlap; a `$ref` inside a *trait object* was rejected
because partial definitions already share a trait; and JSON Schema's pointer grammar was adopted
for fragments rather than invented, on the principle that diverging from a notation while keeping
its spelling is worse than either following it or choosing a different word.

## 4. What implementation taught the specification

Fifty-plus rules were wrong, incomplete or contradictory, and every one was found by implementing
them rather than by review. The instructive ones:

- **A rule contradicted its own advice.** `DIAG.044`'s remedy told authors to "nest the rare choices
  under one weighted choice" — and rare choices are exactly the ones carrying `when`, so following
  the advice with two optional mods absent refused the pack.
- **Two rules refused and renormalised the same list.** A shares-total-1 list losing one choice to a
  missing mod hit both `WEIGHT.014` and `WEIGHT.021`.
- **A rule was not decidable where it was written.** `MODEL.062` — refusing an alias whose target no
  palette defines — cannot be answered by one `extends` chain. `PaletteCharacterCheck`'s history
  records that checking a palette in isolation once produced **45 warnings about a correct pack**,
  which is why `ACCEPT` exists as a rule class.
- **A convention with no tooling is not a convention.** §3.4 required a tombstone when a rule
  retires and nothing parsed them, so writing the first one broke the harness. The identical defect
  recurred for `[NOT-YET-REACHED]`.

**The dominant defect class, found nine times:** *a diagnostic derived from a value is only true if
the value is the one the file wrote.* A message naming a definition that does declare the key it is
said to lack; a socket told it declared no candidate when its candidate merely failed to decode; a
diagnostic choosing between two sentences from the wrong file's `extends`.

**The second class, found four times: a guard that silently stopped covering anything.** `isSimple`
tested `instanceof` against one case of a sealed type and answered `false` for every version 2
marker, leaving a fast path dead for the whole format — a recurrence of issue #33, ten months later,
in the same method, under a javadoc claiming a sealed type made it impossible. Two reference walks
keyed on version 1's shape checked nothing after conversion. And the entire `AssetCompiler` →
version 2 path was reached by **zero tests**: disabling the definitions lookup left all 1,251 tests
passing. In every case the suite stayed green, which is the point — a green suite is
indistinguishable from a covering one, and that is what [#217](https://github.com/Arilas/urbex/issues/217)
and [#220](https://github.com/Arilas/urbex/issues/220) exist for.

## 5. Known and open

`TRAIT.011` is marked `[NOT-YET-REACHED]` — damage is keyed by marker in the compiled palette and
the damage pass reads block states back out of the chunk, which is why version 1 keyed by state
([#216](https://github.com/Arilas/urbex/issues/216)). Six shipped files still carry version 1
**inline** palettes, so version 1 is not yet removable and `variants/` cannot be deleted
([#219](https://github.com/Arilas/urbex/issues/219)). `docs/datapacks.md` has no version 2 content.
The schema validator silently accepts anything under a `"#"` property key
([#218](https://github.com/Arilas/urbex/issues/218)), which matters because `#` is the most common
marker in the corpus. All are recorded in `docs/format/README.md` §9.
