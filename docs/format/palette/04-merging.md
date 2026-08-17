# 04 · Merging

`[DRAFT]` · Area `MERGE` · Palette format version 2

What `extends` does, and what replaces what. The operand that makes extension explicit is
[`$super`](03-pointers.md#22-super).

---

## 1. `extends`

> **MERGE.001** · `MUST` — `extends` names one palette this file builds on. The chain is resolved
> root-first, and each file is applied over the accumulated result.

> **MERGE.002** · `MUST` — `palette` merges **by marker**. A file redefining two markers out of
> thirty replaces exactly those two and leaves the other twenty-eight untouched.

> > **Why** — replacing the whole map would silently drop everything the child did not restate;
> > appending would register a marker twice.

> **MERGE.003** · `MUST` — `$defs` merges **by definition name**, by the same rule.

> **MERGE.004** · `MUST` — A declared entry **replaces** what it inherits. It does not extend it,
> and it does not merge into it.

> > **Why** — the alternative is a reader who cannot tell, from an entry alone, whether it adds to
> > its ancestor or supplants it. Making replacement the only implicit behaviour means every entry
> > says what it is: what you see is the whole marker.

> **MERGE.005** · `MUST` — To build on what an entry inherits, name it with
> [`$super`](03-pointers.md#22-super). Extension is written down, never inferred.

```json fixture:MERGE.005 accept name=extend-vs-replace
{
  "version": 2,
  "extends": "urbex:bricks_standard",
  "palette": {
    "X": { "block": "minecraft:deepslate_bricks" },
    "$": { "$ref": "$super", "traits": { "urbex:rotatable": false } }
  }
}
```

`X` is replaced outright — whatever the ancestor said about it is gone, traits included. `$` keeps
everything it inherited and adds one trait. Both are legible without opening the ancestor.

> **MERGE.006** · `MUST` — Redefining a definition repaints every marker that references it,
> including markers this file does not mention.

> > **Why** — this is the mechanism that makes "the same layout in different materials" a short
> > file. Modern Tweaks ships 460 palette-file pairs sharing 90% or more of their markers.

```json fixture:MERGE.006 accept
{
  "version": 2,
  "extends": "urbex:bricks_standard",
  "$defs": {
    "wall": {
      "kind": "weighted",
      "choices": [
        { "share": 0.1, "block": "minecraft:gray_concrete" },
        { "rest": true, "block": "minecraft:light_gray_concrete" }
      ]
    }
  }
}
```

> **MERGE.007** · `REJECT` (`DIAG.002`) — `palette` is required somewhere in the chain. A chain
> where no file declares one is refused, naming the asset and the field.

> **MERGE.008** · `MUST` — An overridden marker takes its traits with it. A marker a child repaints
> does not keep its ancestor's `urbex:damaged`.

> **MERGE.011** · `MUST` — A palette written inline in a part or building declares `version`, and is
> read by the rules of the version it declares.

> **MERGE.012** · `ACCEPT` `[NO-FIXTURE: a part carrying an inline palette]` — An inline palette may carry `$imports` and `$defs`.

> > **Why** — it is a palette. Withholding the two keys that shorten repetition from the one place
> > repetition is worst would be perverse: the shipped inline palettes hold 6,527 entries of which
> > 1,242 are distinct.

> **MERGE.009** · `REJECT` (`DIAG.031`) — `extends` inside a palette written inline in a part or
> building is refused.

> > **Why** — an inline palette is not a registry entry, so nothing can resolve the link. Accepting
> > a key and ignoring it is how a pack ends up meaning something other than what it says.

> **MERGE.010** · `REJECT` (`DIAG.038`) `[NO-FIXTURE: a version 1 and a version 2 file]` — An `extends` chain may not cross format versions, in
> either direction: a version 2 palette may not `extends` a version 1 palette, and a version 1 palette
> may not `extends` a version 2 one. The diagnostic names both assets and both versions.

> > **Why** — the alternative was an invariant that a version 1 palette and its version 2 translation
> > compile to identical forms, maintained for every construct, forever. It is a heavy promise bought
> > for one convenience, and it caps version 2 at whatever version 1 could already express: per-slot
> > traits, `$super` and pointers have no version 1 counterpart to be equal to. The two formats are
> > developed independently, and neither is a dialect of the other.

> > **Why a merge is the thing refused, and not a composition** — a style's `randompalettes` may still
> > draw a version 1 palette and a version 2 palette into one selection ([VER.006](09-migration.md#1-versioning)).
> > That operates on compiled palettes rather than on JSON, so it needs no correspondence between the two
> > formats; forbidding it as well would mean a pack could only migrate every palette at once, and the
> > packs this has to work for hold 30 and 98 palette files.

```json fixture:MERGE.009 reject=DIAG.031 name=inline-extends
{
  "xsize": 16, "zsize": 16,
  "palette": { "version": 2, "extends": "urbex:common", "palette": { "b": "minecraft:grass_block" } },
  "slices": []
}
```

```json fixture:MERGE.007 reject=DIAG.002
{ "version": 2, "$defs": { "wall": "minecraft:stone_bricks" } }
```


## 2. Precedence, in one table


When several rules could decide one value, this is the order. It is stated once, here, and no other
document restates it.

| # | Wins over the rest | Rule |
|---|---|---|
| 1 | a node's own keys | REF.003 |
| 2 | the node it `$ref`s | REF.002 |
| 3 | the same marker in a later file of the `extends` chain | MERGE.002 |
| 4 | the same marker in an earlier file of the chain | MERGE.001 |

Traits are not in this table, and they do not behave alike at every step of it. Over a `$ref` — rows 1
and 2 — `traits` merge by id rather than replacing the set (REF.004, TRAIT.006). Down the `extends`
chain — rows 3 and 4 — they do not: a marker a later file declares replaces the earlier one whole and
takes its traits with it (MERGE.008). To keep what an entry inherits, name it with `$super`, which turns
the chain step into a `$ref` and so into the first behaviour.


## Tombstones

*None. This document has not yet left draft.*