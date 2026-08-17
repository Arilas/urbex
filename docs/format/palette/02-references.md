# 02 · Definitions and references

`[DRAFT]` · Area `REF` · Palette format version 2

How a [node](00-model.md#2-the-node) is named once and used many times. Addressing a node in
another file is [03 · Pointers](03-pointers.md); what happens when two files declare the same thing
is [04 · Merging](04-merging.md).

---

## 1. `$defs` and `$ref`

> **REF.001** · `MUST` — `$defs` is an object at the top level of a palette file, mapping a
> **definition name** to a node.

> **REF.002** · `MUST` — Any node may carry `$ref`, naming a definition. The referenced node is the
> base; the referring node's own keys are applied over it.

> **REF.003** · `MUST` — A key present beside `$ref` replaces the referenced node's value for that
> key.

> **REF.004** · `MUST` — `traits` beside a `$ref` are merged into the referenced node's traits by
> [TRAIT.006](01-traits.md#2-inheritance): by id, replacing whole.

```json fixture:REF.003 accept
{
  "version": 2,
  "$defs": {
    "sconce": {
      "block": "minecraft:wall_torch[facing=north]",
      "traits": { "urbex:light": { "unlit": "minecraft:air" } }
    }
  },
  "palette": {
    "t": { "$ref": "sconce" },
    "u": { "$ref": "sconce", "block": "minecraft:wall_torch[facing=south]" }
  }
}
```

> **REF.005** · `MUST` — A `$ref` value is a [pointer](03-pointers.md#1-pointers): a bare definition name, an
> asset id with a JSON Pointer fragment, or `$super`.

> > **Why** — the keys are borrowed from JSON Schema because readers already know they mean
> > indirection, and because JSON Schema 2019-09 defines `$ref` with siblings as combining rather
> > than replacing, which is the semantics REF.003 needs. The fragment grammar is borrowed too:
> > diverging from a notation while keeping its spelling is worse than either following it or
> > choosing a different word.

## 2. Where a name resolves

**A leading `$` means an alias. A `#` means a path into an asset. A colon means the registry.
Nothing means this file.**

The first two forms are [pointers](03-pointers.md) and have their own document. This section defines
the last two, which are the forms a `$ref` takes when it names a definition directly.

> **REF.010** · `MUST` — A `$ref` value containing `:` names a **definitions** asset:
> `data/<namespace>/urbex/definitions/<path>.json`.

> **REF.011** · `MUST` — A `$ref` value containing no `:` names a definition in this file's `$defs`,
> including definitions inherited through [`extends`](04-merging.md#1-extends).

> **REF.012** · `MUST NOT` — There is no search order between the two. A name resolves in exactly
> one tier, decided by the presence of a colon, and a failure in that tier is not retried in the
> other.

> > **Why** — the format already requires every cross-asset reference to carry a namespace, so the
> > colon is a signal authors have. A search order would make `"rubble"` resolve differently
> > depending on what else happened to be loaded.

> **REF.013** · `REJECT` (`DIAG.030`) — A `$ref` naming no definition in its tier is refused, and
> the diagnostic names the tier that was searched.

> **REF.014** · `MUST` — A definitions asset is a single node, with the same file-level keys
> `version` and `extends`, and no `palette`.

> **REF.015** · `MUST NOT` — A definitions asset may not `$ref` an unqualified name.

> > **Why** — it has no file whose `$defs` to resolve against, and resolving against the referring
> > file's would make a shared definition mean different things to different callers.

```json fixture:REF.013 reject=DIAG.030
{ "version": 2, "$defs": { "sconce": "minecraft:torch" },
  "palette": { "t": { "$ref": "scone" } } }
```

The three tiers, and when each is right:

| Tier | Written here | Written elsewhere | Use when |
|---|---|---|---|
| inline | — | — | used once, and short |
| `$defs` | `{ "$ref": "sconce" }` | `{ "$ref": "urbex:common#/$defs/sconce" }` | used more than once in this file |
| registry | `{ "$ref": "urbex:sconce" }` | `{ "$ref": "urbex:sconce" }` | used by more than one file |

The two named tiers differ in where the definition lives and in how far the short spelling reaches,
not in who may point at it: by REF.016 both are addressable from anywhere.

> **REF.016** · `MUST` — Every named definition is addressable from another file by
> [pointer](03-pointers.md#1-pointers), whichever tier it is in.

> **REF.017** · `MUST` — Every named definition is therefore API. Renaming or removing one is a
> breaking change to anything that points at it, and `$defs` is no more private than the registry.

> > **Why** — this is a deliberate loss. An earlier draft made `$defs` file-private, so that a name
> > could be changed freely; the cost was that an addon wanting a base pack's definition had no way
> > to reach it, and the base pack had to promote it to the registry first for no reason but
> > ceremony. Datapacks are public, and pretending otherwise buys a rename that nobody was blocked
> > on. The tiers now differ in *where the file lives*, not in who may see it.

> **REF.018** · `MUST` — A definitions asset may carry `$imports`, and may not carry `$defs`.

> > **Why** — it is one node, so it has nothing to put names on; a definition it wants to share is
> > another asset in the same registry. It still needs `$imports`, because a shared definition is
> > exactly the thing most likely to point somewhere else.

## 4. Partial definitions

> **REF.020** · `ACCEPT` — A definition need not
> [resolve to a block source](00-model.md#5-completeness). A definition carrying only `traits` is
> valid.

> **REF.021** · `MUST` — Completeness is checked where a definition is used, not where it is
> declared. See [MODEL.081](00-model.md#5-completeness).

A partial definition is how a trait is shared without a second mechanism for sharing traits:

```json fixture:REF.020 accept
{
  "version": 2,
  "$defs": {
    "rubble":   { "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } },
    "lootable": { "traits": { "urbex:loot": { "pool": "urbex:chestloot" } } }
  },
  "palette": {
    "X": { "$ref": "rubble",   "block": "minecraft:stone_bricks" },
    "C": { "$ref": "lootable", "block": "minecraft:chest[facing=north]" }
  }
}
```

> **REF.022** · `MUST NOT` — A trait object may not carry `$ref`. Sharing a trait is done with a
> partial definition.

> > **Why** — a `$ref` inside a trait object would be a second way to share the same thing, and the
> > only case it uniquely serves — one trait value under two different trait ids — is not wanted by
> > anything in the corpus. A satellite node inside a trait *may* carry `$ref`; it is a node, and
> > [TRAIT.009](01-traits.md#3-block-valued-fields) already says so.

In the shipped Urbex palettes `damaged` has exactly one distinct value across all sixty uses. A
single `rubble` definition replaces every one of them.

## 5. Resolution order and cycles

> **REF.030** · `MUST` — Every `$ref`, every `$defs` name and every `extends` chain is resolved at
> load. None survives into generation.

> **REF.031** · `MUST` — Resolution is a topological sort over the reference graph, performed once.

> > **Why** — version 1 resolved its one indirection, `frompalette`, with a `while (dirty)` fixpoint
> > loop at merge time, because a reference's target might not have been defined yet.

> **REF.032** · `REJECT` (`DIAG.032`) — A reference cycle is refused. The diagnostic names every
> node in the cycle, in declaration order, beginning with the node the loader reached first.

> **REF.033** · `MUST` — Cycle detection covers `$ref` and `extends` together; a cycle through both
> is one cycle.

```json fixture:REF.032 reject=DIAG.032
{
  "version": 2,
  "$defs": {
    "a": { "$ref": "b" },
    "b": { "$ref": "a" }
  },
  "palette": { "X": { "$ref": "a" } }
}
```

> **REF.034** · `INVARIANT` — After compilation, no compiled palette holds a reference, a definition
> name, or an unresolved marker alias.

> **REF.035** · `INVARIANT` — Resolving a marker during generation performs no map lookup keyed by
> name and allocates nothing.


## Tombstones

*None. This document has not yet left draft.*
