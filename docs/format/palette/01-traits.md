# 01 · Traits

`[DRAFT]` · Area `TRAIT` · Palette format version 2

A [node](00-model.md#2-the-node)'s `kind` says which block goes somewhere. Its **traits** say what
else is true about that block. Kinds are exclusive; traits compose.

---

## 1. The mechanism

> **TRAIT.001** · `MUST` — `traits` is an object mapping a trait id to that trait's value, which is an
> object unless that trait's schema defines a scalar shorthand.

> > **Why** — [`urbex:rotatable`](#47-urbexrotatable) is the case in point: its whole content is one
> > boolean, and `"urbex:rotatable": false` is what an author writes. Version 1 had the same shorthand
> > for the same reason — `lightSource: true` — and it existed because the common case had nothing else
> > to say. A trait that defines one is declaring, through its schema, that it never will.

> **TRAIT.002** · `MUST` — A trait id is namespaced: `<namespace>:<name>`.

> **TRAIT.003** · `REJECT` (`DIAG.020`) — A trait id this specification or a loaded mod does not
> define is refused.

> **TRAIT.004** · `MUST` — Traits compose. A node may carry any number of them, and carrying one
> never suppresses another.

> > **Why** — version 1 read a marker's four metadata fields as an `if`/`else if` chain at
> > generation time, so an entry declaring both `lightSource` and `mob` placed the light and
> > silently discarded the spawner. Nothing in the format said the fields were exclusive, because
> > they were not meant to be.

```json fixture:TRAIT.003 reject=DIAG.020
{ "version": 2, "palette": { "X": { "block": "minecraft:stone", "traits": { "urbex:damage": {} } } } }
```

```json fixture:TRAIT.004 accept
{
  "version": 2,
  "palette": {
    "1": {
      "block": "minecraft:spawner",
      "traits": {
        "urbex:spawner": { "pool": "urbex:easymobs" },
        "urbex:damaged": { "into": "minecraft:iron_bars" }
      }
    }
  }
}
```

## 2. Inheritance

> **TRAIT.005** · `MUST` — An [alternative](00-model.md#3-alternatives-and-satellites) inherits its
> parent node's traits.

> **TRAIT.006** · `MUST` — An inherited trait is replaced whole, by id, when the child declares the
> same id. Trait objects are never deep-merged.

> > **Why** — a keyed replace has one answer to "what survived"; a deep merge has one answer per
> > field, and the reader has to know the shape of the trait to predict it. This is the rule
> > `palette` itself already follows for markers.

> **TRAIT.007** · `MUST NOT` — A [satellite](00-model.md#3-alternatives-and-satellites) inherits
> nothing. It begins with no traits.

> > **Why** — without this, an `unlit` satellite would inherit `urbex:light` from the node it
> > replaces, and so be an optional light whose own replacement is an optional light, without
> > termination.

```json fixture:TRAIT.005 accept
{
  "version": 2,
  "palette": {
    "#": {
      "kind": "weighted",
      "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } },
      "choices": [
        { "share": 0.6,  "block": "minecraft:stone_bricks" },
        { "share": 0.05, "block": "minecraft:wall_torch[facing=north]",
          "traits": { "urbex:light": { "unlit": "minecraft:air" } } },
        { "rest": true,  "block": "minecraft:cracked_stone_bricks" }
      ]
    }
  }
}
```

In that fixture every choice carries `urbex:damaged`, and only the second additionally carries
`urbex:light`. The `unlit` satellite inside it carries neither.

> **TRAIT.008** · `MUST` — Traits declared beside a [`$ref`](02-references.md) are applied over the
> referenced node's traits by TRAIT.006.

## 3. Block-valued fields

> **TRAIT.009** · `MUST` — Every trait field that names a block holds a
> [node](00-model.md#2-the-node), and so accepts the string shorthand of
> [MODEL.020](00-model.md#21-string-shorthand), a weighted list, a `$ref`, and traits of its own.

> > **Why** — version 1 spelled these as bare strings, which is why an unlit replacement could not
> > be a weighted list without `unlitBlocks` being added beside `unlit` as a second field, and why a
> > damaged block could not be rubble at all.

Every block-valued field defined here is a satellite, and so is governed by TRAIT.007 and
[MODEL.033](00-model.md#3-alternatives-and-satellites).

| Trait | Block-valued field |
|---|---|
| `urbex:damaged` | `into` |
| `urbex:light` | `unlit` |
| `urbex:optional` | `replacement` |

```json fixture:TRAIT.009 accept
{
  "version": 2,
  "palette": {
    "X": {
      "block": "minecraft:stone_bricks",
      "traits": {
        "urbex:damaged": {
          "into": {
            "kind": "weighted",
            "choices": [
              { "share": 0.3, "block": "minecraft:iron_bars" },
              { "rest": true, "block": "minecraft:cobweb" }
            ]
          }
        }
      }
    }
  }
}
```

## 4. The defined traits

### 4.1 `urbex:damaged`

> **TRAIT.010** · `MUST` — `urbex:damaged` states what this node's block becomes where the damage
> pass applies. Its required field is `into`.

> **TRAIT.011** · `MUST` — The mapping is keyed by the marker carrying the trait, not by the block
> state it resolves to.

> > **Why** — version 1 kept one `Map<BlockState, BlockState>` per palette, so two markers resolving
> > to the same block shared one mapping and the last compiled won.

> **TRAIT.012** · `ACCEPT` — An `into` naming a block this game does not have leaves the marker
> undamaged, and the load succeeds.

```json fixture:TRAIT.012 accept
{
  "version": 2,
  "palette": {
    "X": { "block": "minecraft:stone_bricks",
           "traits": { "urbex:damaged": { "into": "create:andesite_casing" } } }
  }
}
```

### 4.2 `urbex:loot`

> **TRAIT.020** · `MUST` — `urbex:loot` makes this node's block a loot container. Its required field
> is `pool`, naming a `conditions` asset whose values are loot tables.

> **TRAIT.021** · `REJECT` (`DIAG.021`) — A `pool` naming no loaded `conditions` asset is refused.

> **TRAIT.022** · `MUST` — The trait declares `pool` as a reference into the `conditions` registry,
> and that declaration is what reference validation reads.

> > **Why** — version 1 recorded which string fields were asset references nowhere, so an addon's
> > importer and its validator each kept a hand-written 48-name table. They drifted, and 35–55% of
> > real references went unchecked in both without either failing.

```json fixture:TRAIT.021 reject=DIAG.021
{
  "version": 2,
  "palette": {
    "C": { "block": "minecraft:chest[facing=north]",
           "traits": { "urbex:loot": { "pool": "urbex:no_such_condition" } } }
  }
}
```

### 4.3 `urbex:spawner`

> **TRAIT.030** · `MUST` — `urbex:spawner` initialises this node's block as a mob spawner. Its
> required field is `pool`, naming a `conditions` asset whose values are entity ids.

> **TRAIT.031** · `REJECT` (`DIAG.021`) — A `pool` naming no loaded `conditions` asset is refused.

> **TRAIT.032** · `MUST` — A node carrying `urbex:spawner` and rejected by spawner policy is written
> as air.

```json fixture:TRAIT.031 reject=DIAG.021
{
  "version": 2,
  "palette": {
    "1": { "block": "minecraft:spawner",
           "traits": { "urbex:spawner": { "pool": "urbex:no_such_condition" } } }
  }
}
```

### 4.4 `urbex:block_entity`

> **TRAIT.040** · `MUST` — `urbex:block_entity` supplies the NBT a block entity is initialised with.
> Its required field is `nbt`.

> **TRAIT.041** · `REJECT` (`DIAG.022`) — `urbex:block_entity` on a node **none** of whose resolved
> states has a block entity is refused.

> > **Why** — version 1 accepted it, scanned the block-entity registry for a type accepting the
> > state, found none, and wrote nothing. The NBT the author supplied simply never appeared.

> > **Why none rather than any** — a node resolves to as many states as it has alternatives, and a
> > weighted marker of a chest, a barrel and one decorative block is a real shape. Refusing it because
> > one of the three cannot hold the NBT is the over-rejection [`ACCEPT`](../README.md#32-classes)
> > exists as a class to prevent; refusing only when *nothing* can hold it keeps the version 1 silence
> > closed without inventing a new noise.

> **TRAIT.042** · `WARN` (`DIAG.026`) — `nbt` carrying the positional keys `x`, `y`, `z` or the type key
> `id` is accepted, those keys are dropped, and the drop is reported; the loader supplies all four.

> > **Why it is reported rather than refused, and rather than silent** — the four keys cannot be
> > honoured, because the loader knows the position and the type and the file does not. Dropping them
> > silently is the class of failure this format version exists to remove —
> > [MODEL.004](00-model.md#1-the-file)'s `> Why` measures what silence costs, and *"(no message at
> > all)"* was version 1's documented symptom. Refusing is the other overcorrection: the pack works, the
> > block entity is written correctly, and the author has written something redundant rather than
> > something wrong. That is the middle [DIAG.904](08-errors.md#1-what-a-diagnostic-must-contain)
> > allows, and the only one it allows.

> **TRAIT.043** · `ACCEPT` — Where only some of a node's resolved states have a block entity, the `nbt`
> is written to those that do and the load succeeds.

```json fixture:TRAIT.041 reject=DIAG.022
{
  "version": 2,
  "palette": {
    "X": { "block": "minecraft:stone_bricks",
           "traits": { "urbex:block_entity": { "nbt": { "Items": [] } } } }
  }
}
```

```json fixture:TRAIT.043 accept
{
  "version": 2,
  "palette": {
    "C": {
      "kind": "weighted",
      "traits": { "urbex:block_entity": { "nbt": { "Items": [] } } },
      "choices": [
        { "weight": 1, "block": "minecraft:chest[facing=north]" },
        { "weight": 1, "block": "minecraft:stone_bricks" }
      ]
    }
  }
}
```

### 4.5 `urbex:light`

> **TRAIT.050** · `MUST` — `urbex:light` states that this node's block is an optional light: it is
> subject to the preset's lighting density, and when the roll rejects it the trait's `unlit`
> satellite is written in its place.

> **TRAIT.051** · `DEFAULT` — An absent `unlit` is air.

> **TRAIT.052** · `REJECT` (`DIAG.023`) — `urbex:light` is refused on any node that carries it and
> none of whose resolved states emits light, whether that node declared the trait or inherited it by
> TRAIT.005.

> > **Why** — the marker would roll a density and then place the same dark block either way, so the
> > author has marked something optional that can never look different.

> > **Why per slot, and not only where the trait is written** — traits are a property of the slot by
> > [LOAD.021](07-compilation.md#3-the-compiled-shape), because two alternatives of one marker can
> > differ. A marker declaring `urbex:light` over choices of a lantern and a stone block passes any
> > check asked only of the declaring node, and the stone slot is then precisely what this rule forbids:
> > an optional light that can never look different. The mixed case is already sayable, and TRAIT.005's
> > own fixture is how — it declares `urbex:damaged` for every choice and adds `urbex:light` only to
> > the one that lights. Declaring it over a mixed list is an authoring mistake rather than a pattern to
> > protect. `DIAG.023` still names the node that *declared* the trait, and the alternative that cannot
> > light, so the author is pointed at the line they wrote rather than at a slot they did not.

> **TRAIT.053** · `REJECT` (`DIAG.024`) — An `unlit` satellite that emits light is refused.

> **TRAIT.054** · `MUST` — A light source is never filtered out of the output; the roll chooses
> between the lit block and the replacement, and both occupy the marker.

> **TRAIT.055** · `MUST` — On a [`light_socket`](00-model.md#45-light_socket) node, a candidate's own
> `urbex:light.unlit` takes precedence over the socket's.

> > **Why** — a floor torch and a hanging lantern go dark as different blocks, so one replacement for
> > the whole socket could be right for at most one of its placements.

```json fixture:TRAIT.053 reject=DIAG.024
{
  "version": 2,
  "palette": {
    "e": { "block": "minecraft:lantern",
           "traits": { "urbex:light": { "unlit": "minecraft:glowstone" } } }
  }
}
```

```json fixture:TRAIT.051 equiv=absent-unlit
{ "version": 2, "palette": { "e": { "block": "minecraft:lantern", "traits": { "urbex:light": {} } } } }
```

```json fixture:TRAIT.051 equiv=absent-unlit
{ "version": 2, "palette": { "e": { "block": "minecraft:lantern",
    "traits": { "urbex:light": { "unlit": "minecraft:air" } } } } }
```

```json fixture:TRAIT.052 reject=DIAG.023
{
  "version": 2,
  "palette": {
    "L": { "block": "minecraft:stone", "traits": { "urbex:light": {} } }
  }
}
```

### 4.6 `urbex:optional`

> **TRAIT.060** · `MUST` — `urbex:optional` states that this node is placed only when a named
> density roll accepts it, and that its `replacement` satellite is written when the roll rejects it.

> **TRAIT.061** · `MUST` — Its required field `density` names a density in the preset's decoration
> settings.

> **TRAIT.062** · `DEFAULT` — An absent `replacement` is air.

> **TRAIT.063** · `MUST` — `urbex:light` behaves exactly as `urbex:optional` with `density` fixed to
> the preset's lighting density, plus the emission rules TRAIT.052 and TRAIT.053.

> **TRAIT.064** · `REJECT` (`DIAG.025`) — A node carrying both `urbex:light` and `urbex:optional` is
> refused.

> > **Why** — two densities would roll against one position, and which replacement is written would
> > depend on which trait was consulted first. TRAIT.092 forbids traits that depend on application
> > order, so the pair has to be refused rather than ordered.

> **TRAIT.065** · `MUST` — The roll is addressed by position, so a marker's outcome does not depend
> on how many other markers the chunk resolved first.

```json fixture:TRAIT.064 reject=DIAG.025
{
  "version": 2,
  "palette": {
    "e": {
      "block": "minecraft:lantern",
      "traits": {
        "urbex:light":    { "unlit": "minecraft:air" },
        "urbex:optional": { "density": "stuff" }
      }
    }
  }
}
```

```json fixture:TRAIT.062 equiv=absent-replacement
{ "version": 2, "palette": { "v": { "block": "minecraft:cobweb",
    "traits": { "urbex:optional": { "density": "stuff" } } } } }
```

```json fixture:TRAIT.062 equiv=absent-replacement
{ "version": 2, "palette": { "v": { "block": "minecraft:cobweb",
    "traits": { "urbex:optional": { "density": "stuff", "replacement": "minecraft:air" } } } } }
```

### 4.7 `urbex:rotatable`

> **TRAIT.070** · `MUST` — `urbex:rotatable` states whether this node's block follows the rotation
> and mirroring applied to the part that places it.

> **TRAIT.071** · `DEFAULT` — Absent, a node is rotatable. `"urbex:rotatable": false` opts out.

> > **Why** — version 1 answered this from a hand-maintained block tag on the world style, holding
> > 16 tag-includes and 27 block ids and excluding nothing; a test existed solely to catch the list
> > falling behind the shipped palettes. The predicate it approximated is one the platform computes
> > exactly, since rotating a state with no directional property is already a no-op. Opting out is
> > the rare case, and a pack that forgets an opt-*in* gets silently mis-facing blocks on every
> > rotated part — which is the defect the tag was introduced to fix.

> **TRAIT.072** · `MUST` — `false` is accepted here and is meaningful, unlike a `false` that merely
> restates a default.

> **TRAIT.073** · `MUST` — Rotation follows the part; deriving a facing from surroundings is a
> different behaviour and is not this trait. `[PROPOSED]` `urbex:oriented` would be that trait; the
> only orientation behaviour defined today is a socket candidate's, [MODEL.074](00-model.md#45-light_socket).

```json fixture:TRAIT.071 accept
{
  "version": 2,
  "palette": {
    "F": {
      "block": "minecraft:furnace[facing=north]",
      "traits": { "urbex:rotatable": false }
    }
  }
}
```

## 5. Defining a trait

Traits are the format's extension point. A mod may register its own.

> **TRAIT.090** · `MUST` — A registered trait declares its id, its schema, which of its fields are
> [block-valued](#3-block-valued-fields), and which of its fields are references into which registry.

> **TRAIT.094** · `MUST` — Traits are registered at mod initialisation into a registry that never
> changes afterwards: the compiler is handed it rather than fetching one, and a decoder reads it
> directly.

> > **Why immutability is the property, and not who holds the reference** — the rule this is drawn
> > from is [LOAD.031](07-compilation.md#4-sharing-and-identity), and what it forbids is *retained
> > mutable* state: its `> Why` is two static pools "unsynchronised while being written from a decoding
> > worker pool, [that] nothing emptied". A registry fixed before any document is read has neither
> > failure — nothing writes it, and there is nothing to empty. Handing it to the compiler is worth
> > doing anyway, for [LOAD.003](07-compilation.md#1-the-pipeline)'s measured reason: when a registry
> > was fetched from a static reference, "which registry answered depended on whether the server field
> > was populated yet". That question has an answer here whoever asks it.

> > **Why a decoder reads it directly, said plainly rather than aspirationally** — a
> > [codec](07-compilation.md#1-the-pipeline) is handed a document and nothing else, which is the same
> > limitation [DIAG.902](08-errors.md#1-what-a-diagnostic-must-contain) is unmet under: a decode does
> > not know which asset it is decoding either. So [TRAIT.003](#1-the-mechanism)'s refusal of an
> > unregistered id happens against the registry as a static lookup, and this rule says so rather than
> > describing a hand-off that stage 1 has nowhere to put. It is safe for exactly one reason, and the
> > rule states it: the registry cannot change after initialisation.

> **TRAIT.091** · `MUST` — A trait id in a namespace no loaded mod registers is refused by
> TRAIT.003, and the diagnostic names the namespace.

> **TRAIT.092** · `MUST NOT` — A trait may not depend on the order traits are applied in. Two traits
> on one node whose outcomes conflict is a bug in one of them, not a resolution rule.

> **TRAIT.093** · `MUST` — A trait's validation runs at load, against the compiling world's
> registries, and produces a `DIAG` identifier from the catalogue.

## Tombstones

*None. This document has not yet left draft.*
