# 00 · The node model

`[DRAFT]` · Area `MODEL` · Palette format version 2

Defines the shape of a palette file and the single value type it is built from. Read this before
any other document in this directory; everything else names terms defined here.

Design record: not yet written. The measurements and the rejected alternatives behind these rules
live in the research documents this specification was drawn from; §7 of the
[specification system](../README.md#7-relationship-to-other-documents) says where a design record
belongs once it exists.

---

## 1. The file

A **palette** maps single characters, called **markers**, to the blocks a part places where that
character appears in its slices.

> **MODEL.001** · `MUST` — A palette file is a JSON object with the keys `version`, `extends`,
> `$imports`, `$defs` and `palette`, and no others.

> **MODEL.002** · `REJECT` (`DIAG.001`) — A palette file whose `version` is absent is a version 1
> palette and is read by the version 1 rules; a palette file declaring any `version` other than
> `1` or `2` is refused.

> **MODEL.003** · `MUST` — `palette` maps each marker to a [node](#2-the-node). It is required
> somewhere in the [`extends` chain](04-merging.md#1-extends), not in every file.

> **MODEL.004** · `REJECT` (`DIAG.003`) — A key that this specification does not define is refused,
> at every level of a version 2 palette file.

> > **Why** — version 1 discarded unrecognised keys silently, in every registry but `presets`. The
> > guide listed the resulting symptom as *"(no message at all)"*. Three shipped palettes wrote
> > `damaged` inside `blocks[]` elements, where nothing read it, for the lifetime of the pack.

```json fixture:MODEL.002 reject=DIAG.001
{ "version": 3, "palette": { "X": "minecraft:stone" } }
```

```json fixture:MODEL.001 accept
{
  "version": 2,
  "palette": {
    "X": "minecraft:stone_bricks",
    "q": "minecraft:smooth_quartz"
  }
}
```

```json fixture:MODEL.004 reject=DIAG.003
{
  "version": 2,
  "palette": {
    "X": { "block": "minecraft:stone_bricks", "damagd": "minecraft:iron_bars" }
  }
}
```

> **MODEL.005** · `MUST` — Markers are unique by construction: `palette` is an object, so a file
> cannot declare the same marker twice.

> > **Why** — version 1 held entries in a list, each carrying its own `char`. A file declaring a
> > character twice was accepted, and the last declaration won silently.

## 2. The node

A **node** is the single value type of the palette format. It states what block goes somewhere, and
what else is true about that block.

```
node ::= {
    "kind"   : "block" | "weighted" | "tag" | "alias" | "light_socket",
    …kind-specific keys…,
    "traits" : { <trait-id>: <trait-object> },
    "$ref"   : <definition-name>
}
```

> **MODEL.010** · `MUST` — A node appears in exactly five positions: as the value of a marker in
> `palette`, as the value of a name in [`$defs`](02-references.md#1-defs-and-ref), as an entry in a
> [`weighted`](#42-weighted) node's `choices`, as a candidate in a
> [`light_socket`](#45-light_socket) placement list, and as the value of a
> [block-valued trait field](01-traits.md#3-block-valued-fields).

> **MODEL.011** · `DEFAULT` — A node with no `kind` has kind `block`.

> > **Why** — 84% of markers in the shipped corpus are one block with no metadata. The common case
> > pays nothing for the existence of the uncommon ones.

> **MODEL.012** · `REJECT` (`DIAG.004`) — A `kind` outside the five defined values is refused.

> **MODEL.013** · `MUST` — `kind` selects exactly one block source. The kind-specific keys of one
> kind are not accepted on another, and are caught by MODEL.004.

> > **Why** — version 1 allowed `block`, `blocks`, `variant`, `frompalette` and a socket
> > `lightSource` on one entry. They were mutually exclusive but nothing said so: an `if`/`else if`
> > ladder took the first present key in source order and dropped the rest without a word.

```json fixture:MODEL.012 reject=DIAG.004
{ "version": 2, "palette": { "X": { "kind": "blocks", "block": "minecraft:stone" } } }
```

```json fixture:MODEL.011 equiv=default-kind
{ "version": 2, "palette": { "X": { "block": "minecraft:stone" } } }
```

```json fixture:MODEL.011 equiv=default-kind
{ "version": 2, "palette": { "X": { "kind": "block", "block": "minecraft:stone" } } }
```

```json fixture:MODEL.013 reject=DIAG.003
{
  "version": 2,
  "palette": {
    "#": { "kind": "block", "block": "minecraft:stone", "choices": [] }
  }
}
```

### 2.1 String shorthand

> **MODEL.020** · `EQUIV` — Wherever a node is expected, a JSON string is that node with kind
> `block` and that string as its `block`.

```json fixture:MODEL.020 equiv=stone-brick-marker
{ "version": 2, "palette": { "X": "minecraft:stone_bricks" } }
```

```json fixture:MODEL.020 equiv=stone-brick-marker
{ "version": 2, "palette": { "X": { "kind": "block", "block": "minecraft:stone_bricks" } } }
```

> **MODEL.021** · `MUST NOT` — A string is never a reference. A definition is named only by an
> explicit `$ref` on an object node.

> > **Why** — a definition name and a block id are both strings, and both may carry a namespace. If
> > either could appear bare, resolving `"urbex:rubble"` would depend on which registry answered
> > first. Requiring the object form also keeps indirection visible where it is used, which is the
> > single largest readability cost of [references](02-references.md).

## 3. Alternatives and satellites

A node's children fall into two groups, and they behave differently. Both terms are used throughout
this specification.

An **alternative** is a node that *is* the parent node, chosen instead of its siblings: an entry in
`choices`, or a candidate in a `light_socket` placement list.

A **satellite** is a node a trait *points at* — a different block, placed under a different
condition: `urbex:damaged.into`, `urbex:light.unlit`, `urbex:optional.replacement`.

> **MODEL.030** · `MUST` — Exactly one alternative of a node is realised at any position.

> **MODEL.031** · `MUST` — A satellite is not an alternative: realising a node never realises its
> satellites, and a satellite is written only when its trait says so.

> **MODEL.032** · `MUST` — A satellite node may itself carry traits, kinds and `$ref`, with the one
> restriction in MODEL.033.

> **MODEL.033** · `REJECT` (`DIAG.005`) — A satellite node may not have kind `light_socket`.

> > **Why** — a socket defers placement so it can search for a support and orient itself. A
> > satellite is written at a position already decided, so there is nothing for it to search.

```json fixture:MODEL.033 reject=DIAG.005
{
  "version": 2,
  "palette": {
    "L": {
      "block": "minecraft:lantern",
      "traits": { "urbex:light": { "unlit": { "kind": "light_socket", "floor": [
        { "weight": 1, "block": "minecraft:torch" } ] } } }
    }
  }
}
```

The trait-inheritance consequence of this split is [TRAIT.004 and TRAIT.005](01-traits.md#2-inheritance).

## 4. The kinds

### 4.1 `block`

> **MODEL.040** · `MUST` — A `block` node places the single block state named by its required
> `block` field.

> **MODEL.041** · `MUST` — `block` accepts a block id, optionally followed by a bracketed property
> expression: `minecraft:oak_stairs[facing=north,half=top]`.

> **MODEL.042** · `ACCEPT` — A `block` naming an id no installed mod provides resolves to air, and
> the load succeeds.

> **MODEL.043** · `REJECT` (`DIAG.006`) — A `block` whose id resolves but whose property expression
> does not apply to it is refused.

> > **Why** — the two failures have different causes. An absent id is a pack naming optional
> > cross-mod content, and refusing the world over it would break every pack written for an optional
> > dependency. A bad property expression is a mistake in the file that installing a mod cannot fix.

```json fixture:MODEL.042 accept
{ "version": 2, "palette": { "c": "create:andesite_casing" } }
```

```json fixture:MODEL.043 reject=DIAG.006
{ "version": 2, "palette": { "s": "minecraft:stone[facing=north]" } }
```

### 4.2 `weighted`

> **MODEL.044** · `MUST` — A `weighted` node places one of the nodes in its required `choices`
> list, drawn by the rules in [03 · Weights and selection](05-weights.md).

> **MODEL.045** · `REJECT` (`DIAG.007`) — A `weighted` node whose `choices` is empty is refused.

> **MODEL.046** · `MUST` — Each entry in `choices` is a node carrying additionally `weight` or
> `rest`, and optionally `when`. See [WEIGHT.001](05-weights.md).

> **MODEL.047** · `ACCEPT` — A choice may itself be a `weighted` node; nesting is unbounded except
> by the cycle rule [REF.004](02-references.md).

```json fixture:MODEL.047 accept
{
  "version": 2,
  "palette": {
    "#": {
      "kind": "weighted",
      "choices": [
        { "share": 0.75, "block": "minecraft:stone_bricks" },
        { "rest": true, "kind": "weighted", "choices": [
          { "share": 0.2, "block": "minecraft:cobweb" },
          { "rest": true, "block": "minecraft:mossy_stone_bricks" } ] }
      ]
    }
  }
}
```

```json fixture:MODEL.045 reject=DIAG.007
{ "version": 2, "palette": { "#": { "kind": "weighted", "choices": [] } } }
```

`weighted` subsumes version 1's `blocks` list and its `variants` registry, which compiled to the
same structure by two spellings. A named weighted node in the
[definitions registry](02-references.md#2-where-a-name-resolves) is what a `variant` was.

```json fixture:MODEL.044 accept
{
  "version": 2,
  "palette": {
    "#": {
      "kind": "weighted",
      "choices": [
        { "share": 0.08, "block": "minecraft:cracked_stone_bricks" },
        { "share": 0.07, "block": "minecraft:mossy_stone_bricks" },
        { "rest": true,  "block": "minecraft:stone_bricks" }
      ]
    }
  }
}
```

### 4.3 `tag`

> **MODEL.050** · `MUST` — A `tag` node places one block drawn uniformly from the block tag named
> by its required `tag` field.

> **MODEL.051** · `MUST` — `tag` names a block tag with a leading `#` and a namespace:
> `#minecraft:planks`.

> **MODEL.052** · `MUST` — A tag is expanded at load, against the tag epoch the palette is compiled
> under, and never read during generation.

> **MODEL.053** · `REJECT` (`DIAG.008`) — A `tag` that expands to no blocks is refused.

> > **Why** — an empty tag has no reading that places anything, and the alternative — air at that
> > marker — is a claim the author did not make. This differs from MODEL.042 because a tag that
> > exists and is empty is not the same as content from an uninstalled mod.

```json fixture:MODEL.053 reject=DIAG.008
{ "version": 2, "palette": { "p": { "kind": "tag", "tag": "#urbex:empty_for_test" } } }
```

```json fixture:MODEL.050 accept
{ "version": 2, "palette": { "p": { "kind": "tag", "tag": "#minecraft:planks" } } }
```

```json fixture:MODEL.051 reject=DIAG.012
{ "version": 2, "palette": { "p": { "kind": "tag", "tag": "minecraft:planks" } } }
```

### 4.4 `alias`

> **MODEL.060** · `MUST` — An `alias` node resolves to whatever the marker named by its required
> `of` field resolves to, in the same merged palette.

> **MODEL.061** · `MUST` — `of` is exactly one character, subject to [CHAR.001](06-characters.md).

> **MODEL.062** · `REJECT` (`DIAG.009`) — An `alias` whose target is defined by no palette of the
> merge a part is generated with is refused. It is decided there, and not against one palette's
> `extends` chain: by [MODEL.064](#44-alias) that merge includes markers contributed by palettes this
> file never mentions, so a chain on its own cannot say whether an alias resolves.

> > **Why** — version 1's `frompalette` read only the first character of its value, so `"ab"`
> > silently meant `"a"`; it could not override a marker already defined, so its effect depended on
> > merge order; and an unresolvable one left the marker undefined with no diagnostic at all.

> > **Why it is not checked one palette at a time** — because that refuses files that are correct.
> > A palette naming one alias and nothing else is the shipped idiom — `urbex:glass_side_variant_glass`
> > maps `@` to `a` and declares no marker of its own — and an earlier validator that read one palette at
> > a time reported 45 problems in a pack that generates correctly. Over-rejection costs a pack author
> > exactly what under-rejection does, which is why `ACCEPT` is a rule class here at all.

> **MODEL.063** · `MUST` — An `alias` carries the traits of its target, then its own, by
> [TRAIT.006](01-traits.md#2-inheritance).

> **MODEL.064** · `MUST` — An `alias` and a [pointer](03-pointers.md#1-pointers) resolve against
> different things and are not interchangeable. An `alias` names a marker and is answered by the
> merged palette the part is generated with — including markers contributed by palettes this file
> never mentions. A pointer names a node in a document, and is answered by that document.

> > **Why** — the two are one keystroke apart in intent and produce different worlds. `"of": "#"`
> > follows whatever the style's draw put at `#`, which is how a side-glass palette tracks the wall
> > it sits beside; `urbex:bricks_standard#/palette/#` pins one answer forever. A file that meant the
> > first and wrote the second stops responding to the palette group it was written for.

> > **Why** — version 1 dropped a `damaged` written beside a `frompalette` without a word.

```json fixture:MODEL.062 reject=DIAG.009
{ "version": 2, "palette": { "@": { "kind": "alias", "of": "#" } } }
```

### 4.5 `light_socket`

> **MODEL.070** · `MUST` — A `light_socket` node has no block of its own; the candidates in its
> placement lists are its block source.

> **MODEL.071** · `MUST` — Its placement lists are `floor`, `wall`, `ceiling` and `free`, each a
> list of nodes carrying a size by [WEIGHT.003](05-weights.md#1-one-spelling-of-size).

> **MODEL.072** · `REJECT` (`DIAG.010`) — A `light_socket` declaring no candidate in any of the four
> lists is refused.

> **MODEL.073** · `MUST` — Placement is deferred until the chunk is assembled, and opportunities are
> tried in the fixed order floor, west wall, east wall, north wall, south wall, ceiling, `free`.

> **MODEL.074** · `MUST` — The chosen candidate is oriented toward its support, so one
> `wall_torch[facing=north]` candidate serves all four walls.

> **MODEL.075** · `MUST` — A socket is a kind rather than a trait because it selects the block; the
> [`urbex:light`](01-traits.md#45-urbexlight) trait states that a block already selected is an
> optional light.

> **MODEL.076** · `MUST` — A placement list is a list like any other: its candidates accept `when`,
> and it accepts [`$spread`](03-pointers.md#23-spread).

```json fixture:MODEL.072 reject=DIAG.010
{ "version": 2, "palette": { "T": { "kind": "light_socket" } } }
```

## 5. Completeness

> **MODEL.080** · `MUST` — A node **resolves to a block source** when, after
> [reference resolution](02-references.md), it has a `kind` and that kind's required keys.

> **MODEL.081** · `REJECT` (`DIAG.011`) — A node in a marker position, a `choices` entry, a socket
> candidate or a [block-valued trait field](01-traits.md#3-block-valued-fields) that does not resolve to
> a block source is refused.

> > **Why a satellite is in this list** — it is written at a position, exactly as an alternative is;
> > the only difference is which condition puts it there ([MODEL.031](#3-alternatives-and-satellites)).
> > A satellite with no block leaves a marker whose damaged form, or unlit form, is silently nothing —
> > and by [MODEL.032](#3-alternatives-and-satellites) a satellite may carry `$ref`, so the failure
> > arrives through the same indirection every other completeness failure does.

> **MODEL.082** · `ACCEPT` — A node in `$defs` need not resolve to a block source; see
> [REF.020](02-references.md#4-partial-definitions).

```json fixture:MODEL.081 reject=DIAG.011
{
  "version": 2,
  "$defs": { "rubble": { "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } } },
  "palette": { "X": { "$ref": "rubble" } }
}
```

```json fixture:MODEL.082 accept
{
  "version": 2,
  "$defs": { "rubble": { "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } } },
  "palette": { "X": { "$ref": "rubble", "block": "minecraft:stone_bricks" } }
}
```

## Glossary

- **Alternative** — a node realised instead of its siblings: a `choices` entry, or a socket
  candidate. Defined in §3.
- **Definition** — a named node in `$defs` or in the definitions registry. See
  [02 · References](02-references.md).
- **Marker** — a single character, used as a key in `palette` and in a part's slices. Defined in §1.
- **Node** — the single value type of the format. Defined in §2.
- **Palette** — a mapping from markers to nodes. Defined in §1.
- **Resolves to a block source** — has a kind and that kind's required keys, after reference
  resolution. Defined in §5.
- **Satellite** — a node a trait points at, placed under that trait's own condition. Defined in §3.
- **Trait** — a namespaced statement about a node beyond which block it is. See
  [01 · Traits](01-traits.md).

## Tombstones

*None. This document has not yet left draft.*
