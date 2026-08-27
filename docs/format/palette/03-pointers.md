# 03 · Pointers, operands and imports

`[DRAFT]` · Area `REF` · Palette format version 2

How a node in another asset is named, the four `$` operands that consume a pointer, and how a file
shortens the pointers it repeats. [02 · Definitions](02-references.md) defines the plain names these
build on.

---

## 1. Pointers

A **pointer** names a node. It is the value of `$ref`, of `$spread`, and of any future operand that
has to say *which node*.

> **REF.040** · `MUST` — A pointer takes one of four forms, once any [alias](#3-imports) in it has been
> expanded.

| Form | Example | Names |
|---|---|---|
| bare name | `rubble` | a definition in this file's `$defs`, or one inherited through `extends` |
| bare name with fragment | `rubble#/traits` | a node or a list inside such a definition |
| registry asset | `urbex:rubble` | an asset of the `definitions` registry |
| asset with fragment | `urbex:common#/$defs/rubble` | a node inside another asset |

`$super` is the fifth spelling and not a fifth form: it stands where a name stands, with or without a
fragment, and [§2.2](#22-super) defines what it resolves against. An alias is not a form either —
`$mat/Damageable` is replaced by its prefix *before* the pointer is parsed ([REF.081](#3-imports)), and
what remains is one of the four above.

> **REF.041** · `MUST` — A pointer containing `:` and no `#` names an asset in the `definitions`
> registry, and resolves to that asset's node. This is the form [REF.010](02-references.md#2-where-a-name-resolves)
> defines.

> **REF.042** · `MUST` — A pointer containing `#` is a base, then `#`, then an
> [RFC 6901 JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) into what that base names. The base
> is an asset id when it contains `:` and a definition name of this file when it does not, by the same
> colon rule [REF.012](02-references.md#2-where-a-name-resolves) states — and it may also be `$super`.

> > **Why** — an asset id may itself contain `/`, so `urbex:bricks/standard` is a real palette and a
> > slash cannot separate the id from the path into it. `#` cannot appear in a resource path at all,
> > so it is the one delimiter that never needs escaping or a lookahead rule.

> > **Why a local base** — a fragment is the only way into a key of a node, because
> > [REF.054](#21-only-and-without) restricts `$only` and `$without` to top-level keys and says so
> > outright: "to reach inside a key, point at it with a fragment". Without a local base that route
> > exists only for a node in another asset, so a file could reach into `urbex:common`'s `choices` and
> > not into its own — and a palette written inline in a part
> > ([MERGE.012](04-merging.md#1-extends)) has no asset id to write at all, which would leave it no
> > route anywhere.

> **REF.043** · `DEFAULT` `[NO-FIXTURE: a second asset]` — An asset id in a fragment pointer names a `palettes` entry. To point
> into another registry, prefix the registry name and a slash: `definitions/urbex:rubble#/traits`.

> **REF.044** · `MUST` — A fragment pointer resolves against the target's document **after** its own
> `extends` chain is applied and **before** any of its `$ref`s are.

> > **Why** — pointing at `urbex:common#/$defs/rubble` should find what `urbex:common` means by
> > `rubble`, including what it inherited. Resolving the target's own references first would make a
> > pointer's meaning depend on how far the loader had got, which is the class of ordering bug
> > [REF.031](02-references.md#5-resolution-order-and-cycles) exists to remove.

> **REF.045** · `REJECT` (`DIAG.034`) `[NO-FIXTURE: a second asset]` — A pointer naming no asset, or naming an asset that has no
> node at that path, is refused; the diagnostic names which half failed.

> **REF.046** · `MUST` — A pointer into another asset participates in cycle detection exactly as a
> local one does, by [REF.032](02-references.md#5-resolution-order-and-cycles).

```json fixture:REF.042 accept
{
  "version": 2,
  "palette": {
    "}": { "$ref": "urbex:common#/$defs/rubble" },
    "X": { "$ref": "urbex:bricks_standard#/palette/X", "traits": { "urbex:rotatable": false } }
  }
}
```

## 2. Operands

`$ref` is one of four `$`-prefixed keys. All of them are resolved at load and none survives into a
compiled palette.

> **REF.050** · `MUST` — Inside a node the operands are `$ref`, `$only`, `$without` and `$spread`.
> At the file level the structural keys are `$imports` and `$defs`. No other `$`-prefixed key is
> accepted anywhere, and neither set is accepted in the other's position.

> > **Why** — the `$` prefix is reserved for structure precisely so that a future kind, trait or
> > block property can never collide with one. A closed set keeps that promise checkable.

### 2.1 `$only` and `$without`

> **REF.051** · `MUST` — `$only` is a list of top-level keys; a `$ref` carrying it contributes only
> those keys of its target.

> **REF.052** · `MUST` — `$without` is a list of top-level keys; a `$ref` carrying it contributes
> every key of its target except those.

> **REF.053** · `REJECT` (`DIAG.035`) — A node carrying both `$only` and `$without` is refused.

> **REF.054** · `MUST NOT` — `$only` and `$without` name top-level keys of the target node only.
> They are not paths; to reach inside a key, point at it with a fragment.

> **REF.055** · `REJECT` (`DIAG.072`) — A `$only` or `$without` naming something that is not a key of a
> node is refused, and the diagnostic names it.

> > **Why** — a filter key the format does not define contributes nothing, and nothing about the result
> > says why: `$only: ["trait"]` takes none of its target's traits and the marker then fails as
> > [MODEL.081](00-model.md#5-completeness), naming a completeness problem the author did not have. This
> > is the same silence [MODEL.004](00-model.md#1-the-file) removes one level up, in the one place a key
> > name appears as a *value* and so escapes it.

```json fixture:REF.055 reject=DIAG.072
{ "version": 2, "$defs": { "d": { "traits": {} } },
  "palette": { "X": { "$ref": "d", "$only": ["trait"], "block": "minecraft:stone" } } }
```

> **REF.056** · `REJECT` (`DIAG.073`) — A `$only` or `$without` written on a node that carries no
> `$ref` is refused.

> > **Why** — a filter selects the keys a *reference* contributes, so one with no reference has nothing
> > to select from and nothing about the node says so: the keys the author wrote are kept, the filter is
> > discarded, and the node means what it would have meant without it. It is the same failure as a
> > misspelt filter key one step earlier, and the same one [MODEL.004](00-model.md#1-the-file) removes
> > for keys the format does not define — a key that is accepted and then does nothing.

```json fixture:REF.056 reject=DIAG.073
{ "version": 2,
  "palette": { "X": { "$only": ["traits"], "block": "minecraft:stone" } } }
```

> > **Why** — `$ref` without a filter is all-or-nothing, which makes one common intent
> > inexpressible: taking a node's traits while supplying a different block. Written plainly it
> > produces an incoherent node — the target's `kind: weighted` arrives, the sibling `block` is
> > declared, and [MODEL.013](00-model.md#2-the-node) refuses the result. The filter is what makes
> > "the rubble treatment, on my block" sayable.

```json fixture:REF.053 reject=DIAG.035
{ "version": 2, "$defs": { "d": { "traits": {} } },
  "palette": { "X": { "$ref": "d", "$only": ["traits"], "$without": ["kind"],
                      "block": "minecraft:stone" } } }
```

```json fixture:REF.051 accept
{
  "version": 2,
  "palette": {
    "X": {
      "$ref": "urbex:common#/$defs/rubble",
      "$only": ["traits"],
      "block": "minecraft:deepslate_bricks"
    }
  }
}
```

### 2.2 `$super`

> **REF.060** · `MUST` — `$super` names the value this entry inherited from its `extends` chain:
> what would have stood at this marker or definition name had this file not declared it.

> **REF.061** · `MUST` — `$super` is scoped to the entry it appears in, and may be used at any depth
> within that entry, including as the base of a fragment: `$super#/choices`.

> **REF.062** · `REJECT` (`DIAG.036`) `[NO-FIXTURE: a parent palette]` — `$super` in an entry that inherits nothing — because the
> file declares no `extends`, or because no ancestor declares that marker or name — is refused.

> **REF.063** · `MUST NOT` — `$super` names the *inherited* value, not a named ancestor. A file that
> changes what it extends changes what `$super` means, and does not need editing.

> > **Why** — the alternative is writing the ancestor's id in a pointer, which duplicates what
> > `extends` already says and goes stale silently when `extends` changes. That is a file that
> > declares one parent and builds on a different one.

### 2.3 `$spread`

> **REF.070** · `MUST` — `{ "$spread": "<pointer>" }` as an element of a list is replaced by the
> elements of the list the pointer names, in order, at that position.

> **REF.071** · `REJECT` (`DIAG.037`) — A `$spread` whose pointer does not name a list is refused.

> **REF.072** · `MUST` — A `$spread` element carries no other key. To change what it spreads, point
> somewhere else.

> **REF.073** · `MUST` — Spreading is positional. A list may carry several `$spread` elements, and
> elements before and after each one keep their places.

> > **Why** — `$ref` replaces whole keys, so extending an inherited `weighted` node cannot add one
> > choice to it: declaring `choices` replaces the list. Version 1 met the same need on its list
> > fields with `{"replace": false, "values": [...]}`, which could only append and only from the
> > parent. Naming the source and choosing the position costs no more to write and answers both.

```json fixture:REF.071 reject=DIAG.037
{ "version": 2, "$defs": { "d": { "block": "minecraft:stone" } },
  "palette": { "#": { "kind": "weighted", "choices": [ { "$spread": "d#/block" } ] } } }
```

```json fixture:REF.070 accept name=append-a-choice
{
  "version": 2,
  "extends": "urbex:bricks_standard",
  "palette": {
    "#": {
      "$ref": "$super",
      "choices": [
        { "$spread": "$super#/choices" },
        { "weight": 4, "block": "minecraft:cracked_deepslate_bricks" }
      ]
    }
  }
}
```

> **REF.074** · `MUST` — A spread list is flattened before sizes are apportioned, so a spread choice
> carrying `rest` or `share` is subject to
> [WEIGHT.013 and WEIGHT.014](05-weights.md#2-share-weight-and-rest) exactly as a written one is.

> **REF.075** · `MUST` — Spreading changes no choice's size. Because sizes are relative or exact and
> never counts, a spread composes with what surrounds it; see
> [WEIGHT.016](05-weights.md#21-composition).

> > **Why** — this is the rule that makes `$spread` usable at all. An earlier draft made weights
> > absolute counts out of 128 whenever a `rest` was present, so spreading a list that already summed
> > to 120 left a following choice eight slots however it was weighted, and spreading one at 128 left
> > it none. The operator was fine; the size model underneath it did not compose.

## 3. Imports

A pointer into another asset is long, and a file that reaches into the same asset repeatedly repeats
it. `$imports` names prefixes once.

> **REF.080** · `MUST` — `$imports` is an object at the top level of a palette file, mapping an alias
> name to a pointer prefix.

> **REF.081** · `MUST` — An alias is used as `$<name>`, and expansion is textual: `$<name>` is
> replaced by its prefix before the pointer is parsed. Nothing is inserted at the join.

> > **Why** — textual expansion means an alias needs no rules of its own. It can stand for an asset id,
> > or for an asset and a fragment, and what follows it is read by the pointer grammar that was going to
> > read it anyway.

> > **Why the name ends where it does** — an alias name runs to the first `/` or `#`, which are the two
> > characters the pointer grammar already uses as delimiters. The alternative is to match the longest
> > declared alias, which would let an alias stand for any prefix of a path — and would cost REF.083:
> > with it, a file declaring `mat` and writing `$matt` expands to the `mat` prefix followed by a stray
> > `t` instead of naming the misspelt import. Reporting an unknown alias as one is worth more than
> > aliasing an arbitrary prefix.

> **REF.082** · `REJECT` (`DIAG.070`) — `$super` is a built-in alias, available in every file, and an
> `$imports` entry declaring it is refused.

> > **Why** — `$super` was already an alias in everything but name: a token standing for a pointer
> > prefix, usable with a fragment after it. Making imports the general mechanism and `$super` its one
> > built-in means there is one notation to learn rather than two that look alike.

```json fixture:REF.082 reject=DIAG.070
{ "version": 2, "$imports": { "super": "urbex:common#/palette" },
  "palette": { "X": "minecraft:stone" } }
```

> **REF.083** · `REJECT` (`DIAG.039`) — A pointer beginning with `$` whose alias is neither `$super`
> nor declared in `$imports` is refused.

> > **Why** — the alternative is treating an unknown alias as a bare local name, which would report
> > the failure as a missing definition and never mention the misspelt import.

> **REF.084** · `MUST NOT` — A bare definition name may not contain `/` or begin with `$`, so no
> local name can be mistaken for an alias.

> **REF.085** · `MUST` — An alias whose expansion is not a valid pointer is refused by
> [REF.045](#1-pointers), and the diagnostic shows the expanded form as well as the written one.

```json fixture:REF.083 reject=DIAG.039
{ "version": 2, "$imports": { "mat": "urbex:common#/$defs" },
  "palette": { "X": { "$ref": "$mats/Damageable", "block": "minecraft:stone" } } }
```

```json fixture:REF.081 accept name=imports
{
  "version": 2,
  "$imports": {
    "mat":   "urbex:common#/$defs",
    "brick": "urbex:bricks_standard#/palette"
  },
  "palette": {
    "X": { "$ref": "$mat/Damageable", "$only": ["traits"], "block": "minecraft:deepslate_bricks" },
    "$": { "$ref": "$brick/$" },
    "}": { "$ref": "$mat/rubble" }
  }
}
```

`$mat/Damageable` expands to `urbex:common#/$defs/Damageable`. This is the case imports exist for: a
definition that carries a set of traits — a weighted damage palette, so that broken walls do not all
break identically — applied to every wall in a file without restating where it came from.

> **REF.086** · `MUST` — Imports are file-local and are not inherited through `extends`.

> > **Why** — an inherited alias would make a pointer's meaning depend on a file the reader is not
> > looking at, which is the property `$super` exists to avoid needing. An alias is four words to
> > restate; a pointer whose prefix is defined two files up is not.


## Tombstones

*None. This document has not yet left draft.*