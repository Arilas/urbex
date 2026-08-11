# Explicit References and Asset Extension

**Status:** Approved design, awaiting written-spec review

**Date:** 2026-08-11

## 1. Decision

Make every datapack asset reference explicit, and give every asset registry
one shared way to build on another asset.

Three rules replace what is today three inconsistent mechanisms and a layer of
silent fallback:

1. **`extends`** — one inheritance key across all thirteen registries,
   replacing `inherit` (citystyles) and `parent` (presets).
2. **Resolution happens once, at load** — `extends` is applied while building
   the immutable runtime asset. Generation never observes a half-built asset.
3. **Nothing implicit** — an unqualified reference is a load error, and no
   asset reference has a code-side default. No reference resolves that no file
   wrote.

This is motivated work, not tidying. The current merge rule produces a live
generation bug in the mod's own datapack (§7.1), and the test meant to prevent
unqualified references does not cover the registry that still contains one
(§9.1).

The first consumer is Urbex-ModernTweaks, a port of Lost Cities Modern Tweaks
that registers its own worldstyle. Under today's rules that pack silently
inherits `urbex:street_large_*` for road classes it never mentions, putting
Urbex-styled primary roads through its cities. That is the failure mode rule 3
exists to prevent.

## 2. Compatibility Policy

Clean break, consistent with the sphere-removal and datapack-preset specs.
Urbex has not shipped; no released worlds exist to protect.

- `inherit` and `parent` are deleted, not aliased. A file using either fails to
  load with an error naming the file and the replacement key.
- Third-party packs written against today's rules fail at datapack load rather
  than generating differently. Loud failure is the point: the quiet version of
  this is the bug in §7.1.
- No migration, no compatibility shim, no deprecation window.

## 3. The `extends` Mechanism

`extends` takes one fully-qualified asset id, in the same registry as the file
declaring it. It is available on all thirteen registries: `worldstyles`,
`citystyles`, `buildings`, `parts`, `palettes`, `styles`, `multibuildings`,
`scattered`, `conditions`, `variants`, `stuff`, `predefinedcities`, `presets`.

Uniformity is the point. An author should never have to look up which asset
types support extension.

Chains are arbitrarily deep and are applied root-first: the root's values land
first, then each descendant's declared fields, ending with the file that was
asked for. The bundled pack already relies on this —
`citystyle_border` → `citystyle_common` → `citystyle_config`. This is the
resolution order presets use today under `parent`, so preset semantics are
preserved exactly by the rename; their sections are objects of scalars, and
"scalar: child wins" (§4) is the rule they already follow.

`extends` crosses namespaces freely — that is its primary use:

```json
{ "extends": "urbex:citystyle_common", "style": "urbexmt:standard" }
```

### 3.1 `refpalette` is not inheritance and stays

`refpalette` names the palette a part draws with. `extends` says what a part is
like. They compose, and the combination is the motivating case for putting
`extends` on parts at all:

```json
{ "extends": "urbex:radiotower", "refpalette": "urbexmt:radiotower_rusted" }
```

That part inherits the tower's slices and dimensions and repaints it. Neither
key alone expresses it.

## 4. Merge Shapes

The merge rule follows the **shape of the field**, not the asset type. There
are exactly three shapes, which is what makes a uniform mechanism learnable.

| Shape | Rule | Examples |
|---|---|---|
| Scalar | child wins when present | `style`, `outsidestyle`, `buildingChance`, `xsize`, `refpalette` |
| Ordered list | child **replaces**; append is opt-in | `selectors.buildings`, `citystyles`, `streetblocks.parts.straight`, `randompalettes` |
| Keyed collection | merge by key; child's key wins | `palette` (keyed by `char`) |

> **Corrected during implementation (Task 7).** This table originally cited `scattered.list` and
> `slices` as ordered lists. Neither is: `slices` is a plain `Codec.list` on `BuildingPartRE` and
> `scattered.list` a plain, *required* `Codec.list` inside `ScatteredSettings`, so the
> `{"replace": false, "values": [...]}` form fails to decode on both. Two further fields the rule
> would mispredict came to light with them: `multibuildings.buildings` is a plain list, and
> `citystyles.stuff_tags` is a fourth behaviour — `CityStyle.applyFrom` unions it into a set, so a
> child can neither replace nor remove an inherited tag. The `scattered` block is additionally a
> *scalar*: `WorldStyle` swaps it wholesale, alongside `multisettings` and `settings`. Three shapes
> remains the right design and the implementation follows it; what was wrong was the claim that it
> covers every field. `docs/datapacks.md` enumerates the exceptions, and no code was changed to
> make the original wording true.

### 4.1 Ordered lists: replace by default

A declared list means exactly that list. An explicitly empty list means empty.
Both are currently inexpressible (§7.1).

Appending is opt-in per list, using the array-or-object shape datapack authors
already know from vanilla tags:

```json
"parks": {
  "replace": false,
  "values": [ { "factor": 0.5, "value": "urbexmt:park_cherry_blossom" } ]
}
```

A bare array is equivalent to `"replace": true`. Appended entries follow the
parent's, so the parent's list order is stable as children are added.

This extends the existing array-or-scalar helper at
`Tools.listOrStringList` (`varia/Tools.java`), which already builds an
`either` codec; it grows a third arm, and gains a sibling for object-list
fields such as weighted selectors.

Two alternatives were considered and rejected: a `"parks+"` key suffix (terser,
but unfamiliar in Minecraft data and invisible to JSON schema tooling), and a
splice-sentinel list element (would additionally control insertion position,
but complicates every element codec for a case nothing needs).

### 4.2 Keyed collections: merge by key

A palette is addressed by character. Overriding two characters out of thirty
must leave the other twenty-eight intact — this is the semantics
`CompiledPalette` already implements for `refpalette` plus a local palette, and
the reason "everything replaces" would not have worked as a single rule.

### 4.3 Parts

A part extending another inherits `slices`, `xsize` and `zsize`. Declaring
`slices` replaces them wholesale. Declaring an `xsize` or `zsize` that
contradicts inherited slices is a load error, not a silent truncation.

## 5. Resolution: Immutable Assets Built at Load

`extends` is applied **once, while constructing the runtime asset**. There is
no separate resolution pass, no lazy initialisation, and no mutation after
construction.

- `*RE` classes stay raw decoded datapack entries, carrying `extends`
  unresolved.
- The `cityassets/*` classes become uniformly what `WorldStyle` already is:
  immutable, built once from the registry entry, exposing helper methods rather
  than raw fields.
- A cycle or a dangling `extends` id is a load error naming the file and the
  chain.

### 5.1 What this deletes

`CityStyle` is today the only self-resolving asset, and carries the machinery
that requires: an `init()` that writes fields after construction, a `volatile
initialized` flag, a `synchronized` block, and a
`ThreadLocal<Set<CityStyle>>` cycle guard whose documented behaviour on a cycle
is to *return the style half-built*. All of it goes.

This is also a determinism improvement. Urbex's digest harness runs with
`-Durbex.digestCheck.failOnUnsafeRead` because lazily-initialised fields on
shared assets have caused ordering bugs in this codebase before. Assets that
are fully built before generation starts cannot participate in that class of
bug.

### 5.2 Why not resolve on read

A wrapper that walked the `extends` chain per accessor call would avoid the
build step, but these accessors sit in hot paths — palette lookups run inside
per-block column loops, city-style selection runs per city chunk. A chain walk
plus a keyed palette merge per block placement is not acceptable there, and
re-resolving during generation reintroduces exactly the shared-mutable-state
risk §5.1 removes. Resolve once; read O(1) thereafter.

### 5.3 Assets are constructed with their worldstyle

Generation code holds a wrapper constructed with an explicit worldstyle, never
a dimension-global lookup. This costs nothing today — `City` already routes
every read through `provider.getWorldStyle()`, and `getCityStyle(provider,
coord)` is already coordinate-aware.

It is done now because it is cheap now and expensive to retrofit. **Per-city
worldstyle selection is explicitly not part of this spec**: what picks the
style, whether it varies by biome, and how neighbouring cities of different
styles meet at a shared highway are separate questions deserving their own
design.

## 6. No Implicit Asset References

Unqualified references and code-side wiring defaults are the same defect: a
reference no file wrote. The defaults are themselves unqualified names.

```java
Tools.listOrStringList("straight", "street_straight", StreetParts::straight)
//                                  ^ bare -> DataTools.fromName -> urbex:street_straight
```

### 6.1 Unqualified references become load errors

`DataTools.fromName` requires a namespace. A string without `:` is a load error
naming the string and the file that contains it.

`PresetRE` decodes `parent` with `Identifier.CODEC`, under which a bare name
resolves to `minecraft:` — a third defaulting rule, differing from both the
`urbex:` default elsewhere and from an error. It is brought under the same
strict resolution.

### 6.2 Wiring defaults are deleted

Thirty `listOrStringList` call sites carry a default asset name:
`HighwayParts` (6), `RailwayParts` (16), `StreetParts` (8). Exactly two are
namespaced — `urbex:street_large_connector` and `urbex:street_stair`, added
during hierarchical streets, evidence the codebase was already moving this way.

All thirty defaults go; those fields become required. `PartSelector`,
`StreetParts`, `HighwayParts` and `RailwayParts` lose their `DEFAULT`
constants, which §5 makes redundant: a resolved asset either holds a value or
the load already failed.

### 6.3 What keeps its defaults

`MultiSettings.DEFAULT` (`10, 1, 5, 0.8f, 50`) and `WorldSettings.DEFAULT`
(`IGNORE, 1`) hold numbers and enums, not asset references. They stay.

The rule is *no asset reference exists that no file wrote*. It is deliberately
not "every field must be written": settings may still default, and the
preset format's field-level optionality (`docs/presets.md`) is unaffected.

### 6.4 Requiredness applies after resolution

A file need not restate what it inherits. `citystyle_border` declares no street
`parts` and correctly takes `citystyle_common`'s.

The load-time check is therefore: **every registered worldstyle, and every
citystyle reachable from one, has complete wiring after resolution.** Chain
roots that are only ever extended, such as `citystyle_config`, are not required
to be complete on their own.

## 7. Fallout in the Bundled Datapack

Small, because the pack is nearly explicit already.

### 7.1 The `citystyle_border` bug this fixes

`citystyle_border` lists 5 buildings. `CityStyle.init()` then appends
`citystyle_common`'s 8 with no deduplication, so at runtime it holds 13
entries: `building1`–`building5` at double weight, and `building6`–`building8`
present despite never being listed.

Its `"multibuildings": []` is worse. An explicitly empty list decodes to
`Optional.of([])`, adds nothing, and then all 12 of the parent's are appended.
There is currently no way to express "none here".

Under §4.1 both start meaning what they say. This is a bug fix, not a content
change.

### 7.2 Everything else

- `presets/largecities.json`: `"cityStyleAlternative": "citystyle_border"`
  becomes `"urbex:citystyle_border"` — the only unqualified reference left in
  the bundled pack.
- `inherit` → `extends` in 4 citystyles (`common`, `standard`, `desert`,
  `border`; `config` is the chain root and has none).
- `parent` → `extends` in 11 presets (all but `default`).
- Wiring dropped from code is written down where the chain needs it: in
  practice `citystyles/citystyle_common` and `worldstyles/standard`, which
  already declare most of it.

### 7.3 The digest goldens should not move

`runs/digestcheck` and `runs/digestcheckfeatures` run `urbex:default`, which
never sets `cityStyleAlternative`, so `citystyle_border` is unreachable in the
digest window. Every other change here is output-neutral by construction.

**A golden that shifts is a signal to investigate, not to regenerate.** The
`citystyle_border` fix is verified by a targeted resolution test instead
(§9.2).

## 8. What This Does Not Change

- Numeric and enum settings keep their defaults (§6.3).
- `refpalette` stays (§3.1).
- Per-city worldstyle selection is enabled, not built (§5.3).
- The preset format's sections, field optionality and resolution order are
  unchanged apart from the `parent` → `extends` rename.
- No worldgen output changes are intended (§7.3).

## 9. Verification

Every rule here is designed to fail at datapack load, naming a file. That
matters: worldgen cannot be verified locally by launching the game, so a load
error a test can assert on is worth more than a visual difference nobody can
check.

### 9.1 The integrity test's coverage gap

`DatapackReferenceIntegrityTest` switches on the asset category and lets
unknown categories fall through to `default -> { }`. `presets` is such a
category, which is why `largecities.json`'s unqualified reference survives a
green run.

- The `presets` case is added (`extends`, `cityStyleAlternative`, `icon`).
- `extends` targets are checked to resolve, in every category.
- The `default` arm becomes a test failure for unknown categories, so a
  fourteenth registry cannot silently skip coverage.

### 9.2 New tests

- Resolution: cycle → error; dangling `extends` → error; list replace;
  `{"replace": false}` append with parent-first ordering; explicitly empty list
  stays empty; palette keyed merge preserves untouched characters; part
  inherits slices and dimensions; contradictory `xsize` → error.
- `citystyle_border` resolves to exactly 5 buildings and 0 multibuildings.
- No asset-reference codec field carries a default — the guard that stops §6.2
  regressing once adding "just one" convenience default is easy again.
- Reachability: every registered worldstyle, and every citystyle reachable from
  one, is complete after resolution.
- Generation never observes an unresolved asset.

### 9.3 Schema and docs

- `docs/schema/preset.schema.json`: `parent` → `extends`. The existing
  schema-versus-codec drift test keeps it honest.
- `docs/presets.md` updated for the rename.
- New `docs/datapacks.md`: `extends`, the three merge shapes, the explicitness
  rules, and the per-registry field reference. Urbex-ModernTweaks is its first
  real consumer, so it doubles as that pack's authoring guide.

## 10. Sequencing

Two chunks, each independently playable.

1. **`extends`.** The unified key, the three merge shapes, resolution at
   construction, `inherit`/`parent` deleted, `CityStyle`'s lazy-init machinery
   removed, resolution tests. Endpoint: the bundled pack loads and
   `citystyle_border` means what it says.
2. **Strictness.** Unqualified references become errors, the thirty wiring
   defaults are deleted, the bundled pack is made fully explicit, the integrity
   test is widened, docs are written. Endpoint: no reference resolves that no
   file wrote.

Urbex-ModernTweaks follows, authored against the finished rules.
