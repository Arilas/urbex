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

> **REF.005** · `MUST` — A `$ref` value is a [pointer](03-pointers.md#1-pointers): a bare definition
> name, a bare definition name with a JSON Pointer fragment, an asset in the `definitions` registry, an
> asset id with a fragment, or `$super`. The four forms [REF.040](03-pointers.md#1-pointers) lists may be
> written through an [alias](03-pointers.md#3-imports); `$super` may not, because
> [REF.082](03-pointers.md#3-imports) makes it a built-in that no `$imports` entry can stand for.

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

> **REF.019** · `REJECT` (`DIAG.071`) — A definitions asset declares `"version": 2`. An absent `version`
> is refused rather than read as version 1, and so is any other value.

> > **Why** — [VER.001](09-migration.md#1-versioning) makes an absent `version` mean version 1, and that
> > rule is about a palette file: every existing pack has one, and none of them keeps loading unless the
> > absence is honoured. The `definitions` registry is new in version 2 and has no version 1 form at all,
> > so there is nothing for the absence to select. Reading it as version 1 would hand the file to a codec
> > that does not exist, and `DIAG.001`'s remedy — "omit it for the version 1 format" — would send the
> > author looking for a format this registry has never had.

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

> **REF.022** · `REJECT` (`DIAG.074`) — An [operand](03-pointers.md#2-operands) written on a trait
> object is refused: a trait's value is data, not a node.

> > **Why** — a `$ref` inside a trait object would be a second way to share the same thing, and the
> > only case it uniquely serves — one trait value under two different trait ids — is not wanted by
> > anything in the corpus. A satellite node inside a trait *may* carry `$ref`; it is a node, and
> > [TRAIT.009](01-traits.md#3-block-valued-fields) already says so.

> > **Why a row of its own** — the refusal is reachable from
> > [MODEL.004](00-model.md#1-the-file) alone, because no trait's declared key set
> > ([TRAIT.090](01-traits.md#5-defining-a-trait)) contains an operand — but `DIAG.003`'s remedy is
> > "check the spelling against the schema", which is the wrong advice here. Nothing is misspelt; the
> > author wanted to share something, and the two ways to do that are to put the reference on a
> > block-valued field of the trait, or to share the whole trait with a partial definition and `$ref`
> > that. A rejection whose remedy names neither costs the author the search this specification exists
> > to remove.

> > **What it replaced** — [VER.016](09-migration.md#tombstones) refused every operand anywhere inside
> > a `traits` value while a trait payload was opaque. This is the half that was never transitional, and
> > it is narrower in exactly the way the two rules differ: it refuses an operand on the trait *object*
> > and says nothing about a satellite, which is a node and may carry one.

```json fixture:REF.022 reject=DIAG.074
{
  "version": 2,
  "$defs": { "rubble": { "block": "minecraft:iron_bars" } },
  "palette": {
    "X": { "block": "minecraft:stone_bricks", "traits": { "urbex:damaged": { "$ref": "rubble" } } }
  }
}
```

In the shipped Urbex palettes `damaged` had exactly one distinct value across all sixty uses, and a
single partial definition replaces most of them. It is written as one now — `urbex:damageable`, in the
`definitions` registry rather than in one file's `$defs`, because §2's tier table puts a definition used
by more than one file there and these were used by fourteen — and **45 of the 60** point at it. The
other fifteen are the two limits worth knowing about: nine markers already carry a `$ref` into a
converted variant and a node has one `$ref`, so the trait stays written out beside it unless the shared
definition is respelled to carry both (`{ "$ref": "urbex:bricks", "traits": { … } }`, one asset per
variant); and six are in inline palettes still written in version 1, which have no `$defs` and no `$ref`
at all. Those six are why version 1 cannot be removed —
[issue #219](https://github.com/Arilas/urbex/issues/219) is the work of converting them, and until it
lands `VER.004`'s promise is load-bearing for the shipped pack and not only for other people's.

Stated with its arithmetic rather than as "replaces every one of them", which this pack disproved the
first time anyone tried it. [VER.031](09-migration.md#5-what-the-converter-cannot-do) carries the same
figures from the migration side.

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

A cycle *through* both is the case this rule is about, and it is the one the resolver finds: two files
that are each acyclic can reference each other's entries once [MERGE.003](04-merging.md#1-extends) has
made their `$defs` one map, and an inherited value can reference the entry that replaced it. Both are
named by REF.032's diagnostic.

A cycle in the **chain's own shape** — `a` extends `b` extends `a` — is refused earlier, before any of
those files is decoded, by the shared chain walker every registry uses. It says so in prose rather than
as `DIAG.032`, and it is left that way deliberately: the walker is generic over all thirteen registries
and is handed no registry identity, so it cannot know whether this palette's catalogue applies; it runs
before a document exists, so there is no diagnostic collector to record into and the two refusals are not
interchangeable; and its message is already deterministic and names every link. Stating it here so the
gap is findable: when the loader stage owns chain resolution for version 2 palettes, that refusal should
become `DIAG.032` for this registry and stay prose for the other twelve.

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
