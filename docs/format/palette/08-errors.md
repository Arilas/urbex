# 06 · Diagnostics

`[DRAFT]` · Area `DIAG` · Palette format version 2

Every rejection this format performs, with the message it produces. A `REJECT` rule cites an entry
here; a test asserts against the entry, never against a literal string in the test.

---

## 1. What a diagnostic must contain

> **DIAG.900** · `MUST` — Every diagnostic names the asset, the marker if one is implicated, what
> was found, and what to write instead.

> **DIAG.901** · `MUST` — Every diagnostic names the reference chain the failing node was reached
> through, when it was reached through one. See [LOAD.051](07-compilation.md#6-reporting).

> **DIAG.902** · `MUST` — A diagnostic names the asset id, and additionally the source file path
> when one is known.

> > **Why** — version 1 named the owning asset id only. For a palette written inline in a part, that
> > was a synthetic `urbex:__local__<path>` in the `urbex` namespace whatever the owner's namespace
> > was — close enough to find, but not a filename.

> **DIAG.903** · `MUST` — Diagnostics are collected and reported together, not thrown at the first
> failure. See [LOAD.004](07-compilation.md#1-the-pipeline).

> **DIAG.904** · `MUST` — A diagnostic is an error or a warning. A warning does not refuse the
> world. There is no third level.

## 2. Message shape

    <asset> [marker '<m>'] [via <chain>]: <what was found>. <what to write instead>.

The bracketed parts appear when they apply. The second sentence is required: a diagnostic that
names a problem without naming a remedy is incomplete.

## 3. Reserved ranges

Identifiers are allocated in blocks so related diagnostics stay adjacent as the catalogue grows.

| Range | Concern |
|---|---|
| 001-019 | file and node shape |
| 020-029 | traits |
| 030-039 | references and merging |
| 040-049 | weights |
| 050-059 | characters |
| 060-069 | versioning |
| 070-079 | references and merging, continued — 030-039 is full |
| 080-899 | unallocated |
| 900-999 | rules about diagnostics themselves |

## 4. The catalogue

### File and shape

| Id | Raised by | Message |
|---|---|---|
| `DIAG.001` | [MODEL.002](00-model.md#1-the-file) | *`<asset>`: declares version `<n>`, which this Urbex does not know. Write `"version": 2`, or omit it for the version 1 format.* |
| `DIAG.002` | [MERGE.007](04-merging.md#1-extends) | *`<asset>`: declares no `palette`, and neither does anything it extends. Add one, or extend a palette that has one.* |
| `DIAG.003` | [MODEL.004](00-model.md#1-the-file) | *`<asset>` marker `'<m>'`: `<key>` is not a key of `<context>`. Version 2 palettes refuse keys they do not define; check the spelling against the schema.* |
| `DIAG.004` | [MODEL.012](00-model.md#2-the-node) | *`<asset>` marker `'<m>'`: kind `<k>` does not exist. The kinds are block, weighted, tag, alias and light_socket.* |
| `DIAG.005` | [MODEL.033](00-model.md#3-alternatives-and-satellites) | *`<asset>` marker `'<m>'`: a `<field>` replacement cannot be a light_socket, because it is written at a position already chosen. Name a block, or a weighted list of them.* |
| `DIAG.006` | [MODEL.043](00-model.md#41-block) | *`<asset>` marker `'<m>'`: `<block>` names a block this game has, with a property it does not have. Installing a mod will not fix this; correct the property expression.* |
| `DIAG.007` | [MODEL.045](00-model.md#42-weighted) | *`<asset>` marker `'<m>'`: a weighted node declares no choices. Give it at least one.* |
| `DIAG.008` | [MODEL.053](00-model.md#43-tag) | *`<asset>` marker `'<m>'`: tag `<tag>` contains no blocks. An empty tag has nothing to place; name a tag with members, or name blocks directly.* |
| `DIAG.009` | [MODEL.062](00-model.md#44-alias) | *`<asset>` marker `'<m>'`: aliases `'<t>'`, which no palette in this context defines. Alias a marker that exists, or give this one a block of its own.* |
| `DIAG.010` | [MODEL.072](00-model.md#45-light_socket) | *`<asset>` marker `'<m>'`: a light_socket declares no candidate in floor, wall, ceiling or free. Give it at least one.* |
| `DIAG.011` | [MODEL.081](00-model.md#5-completeness) | *`<asset>` marker `'<m>'` `<via>`: resolves to no block. `<def>` `<declares only traits` / `declares kind <k> and no <key>>`; give this marker a `block`, `choices`, `tag` or `alias` as well.* |
| `DIAG.012` | [MODEL.051](00-model.md#43-tag) | *`<asset>` marker `'<m>'`: tag `<tag>` has no leading `#`. A block tag reference is written `#namespace:path`.* |

### Traits

| Id | Raised by | Message |
|---|---|---|
| `DIAG.020` | [TRAIT.003](01-traits.md#1-the-mechanism) | *`<asset>` marker `'<m>'`: no trait `<id>` is registered`<, and nothing loaded registers the namespace '<ns>'>`. Check the id, or the mod that provides it.* |
| `DIAG.021` | [TRAIT.021](01-traits.md#42-urbexloot), [TRAIT.031](01-traits.md#43-urbexspawner) | *`<asset>` marker `'<m>'`: `<trait>` names pool `<id>`, which is not a loaded conditions asset. Generation dereferences it, so it must exist.* |
| `DIAG.022` | [TRAIT.041](01-traits.md#44-urbexblock_entity) | *`<asset>` marker `'<m>'`: `<block>` has no block entity, so its `urbex:block_entity` nbt would never be written. Remove the trait, or name a block that has one.* |
| `DIAG.023` | [TRAIT.052](01-traits.md#45-urbexlight) | *`<asset>` marker `'<m>'`: declares `urbex:light`, but none of the blocks it resolves to emit light. It would roll a density and place the same dark block either way.* |
| `DIAG.024` | [TRAIT.053](01-traits.md#45-urbexlight) | *`<asset>` marker `'<m>'` `<via>`: an unlit replacement emits light. Name a block that does not, so the marker looks different when the light is off.* |

| `DIAG.025` | [TRAIT.064](01-traits.md#46-urbexoptional) | *`<asset>` marker `'<m>'`: carries both `urbex:light` and `urbex:optional`. A marker rolls one density; `urbex:light` is the lighting one.* |

### References and merging

| Id | Raised by | Message |
|---|---|---|
| `DIAG.030` | [REF.013](02-references.md#2-where-a-name-resolves) | *`<asset>` marker `'<m>'`: `<operand>` `<name>` names no `<tier>` definition. `<A name with a colon is looked up in the definitions registry; one without, in this file's $defs and those it inherits.>`* |
| `DIAG.031` | [MERGE.009](04-merging.md#1-extends) | *`<owner>`: the inline palette declares `extends` `<id>`, but an inline palette is not a registry entry and nothing can resolve that. Use `refpalette`, or put `extends` on `<owner>` itself.* |
| `DIAG.032` | [REF.032](02-references.md#5-resolution-order-and-cycles) | *`<asset>`: reference cycle `<a → b → c → a>`. One of these must not reference the next.* |
| `DIAG.033` | [REF.015](02-references.md#2-where-a-name-resolves) | *`<asset>`: a definitions asset references `<name>`, which has no namespace. A registry definition has no file to resolve local names against; qualify it.* |
| `DIAG.034` | [REF.045](03-pointers.md#1-pointers) | *`<asset>` marker `'<m>'`: pointer `<p>` names `<no asset '<id>'` / `no node at '<path>' in '<id>'>`. `<The asset exists; the path does not.>`* |
| `DIAG.035` | [REF.053](03-pointers.md#21-only-and-without) | *`<asset>` marker `'<m>'`: carries both `$only` and `$without`. Name the keys to keep, or the keys to drop, not both.* |
| `DIAG.036` | [REF.062](03-pointers.md#22-super) | *`<asset>` marker `'<m>'`: `$super` names what this entry inherits, and `<this file declares no extends` / `nothing in its extends chain declares '<m>'>`. Remove `$super`, or extend something that defines it.* |
| `DIAG.037` | [REF.071](03-pointers.md#23-spread) | *`<asset>` marker `'<m>'`: `$spread` `<p>` names a `<kind>`, not a list. A spread element can only be replaced by list elements.* |
| `DIAG.039` | [REF.083](03-pointers.md#3-imports) | *`<asset>` marker `'<m>'`: `$<alias>` is not an import of this file`<, and the closest declared is '$<near>'>`. Declare it in `$imports`, or write the pointer in full.* |
| `DIAG.038` | [MERGE.010](04-merging.md#1-extends), [VER.005](09-migration.md#1-versioning) | *`<asset>` (version `<n>`) extends `<id>` (version `<m>`). An extends chain cannot cross format versions; convert one of them.* |
| `DIAG.070` | [REF.082](03-pointers.md#3-imports) | *`<asset>`: `$imports` declares `super`, which is a built-in alias naming what this entry inherits and cannot be redeclared. Remove it, or choose another alias name.* |
| `DIAG.071` | [REF.019](02-references.md#2-where-a-name-resolves) | *`<asset>`: a definitions asset `<declares no `version`` / `declares version `<n>`>`. The `definitions` registry is new in palette format version 2 and has no version 1 form, so an absent `version` is not one; write `"version": 2`.* |
| `DIAG.072` | [REF.055](03-pointers.md#21-only-and-without) | *`<asset>` marker `'<m>'`: `<operand>` names `<key>`, which is not a key of a node`<, and the closest is `<near>`>`. The keys a filter may name are kind, block, choices, tag, of, floor, wall, ceiling, free and traits.* |

### Weights

| Id | Raised by | Message |
|---|---|---|
| `DIAG.040` | [WEIGHT.002](05-weights.md#1-one-spelling-of-size) | *`<asset>` marker `'<m>'` choice `<i>`: `<weight `<w>` is not a positive integer` / `share `<f>` is not between 0 and 1` / `declares <none of\|both> `weight`, `share` and `rest`>`. Each choice states its size exactly once.* |
| `DIAG.041` | [WEIGHT.013](05-weights.md#2-share-weight-and-rest) | *`<asset>` marker `'<m>'`: `<`<n>` choices declare `rest`` / ``rest` is declared beside `<n>` weighted choices>`. `rest` is the single choice that takes what the shares leave; weighted choices already divide that between them.* |
| `DIAG.045` | [WEIGHT.014](05-weights.md#2-share-weight-and-rest), [WEIGHT.019](05-weights.md#21-composition) | *`<asset>` marker `'<m>'`: shares total `<n>``< — <a> written here and <b> spread from '<id>'>`. `<Shares must leave something for the weight choices` / `Shares must total exactly 1 when nothing takes the remainder>`.* |
| `DIAG.042` | *retired — see tombstones* | — |
| `DIAG.043` | [WEIGHT.024](05-weights.md#3-when), [WEIGHT.032](05-weights.md#4-absent-blocks) | *`<asset>` marker `'<m>'`: every choice was excluded — `<n>` by `when`, `<n>` by absent blocks. The marker would generate as air; give it a choice that always applies.* |
| `DIAG.044` | [WEIGHT.063](05-weights.md#7-rounding) | *`<asset>` marker `'<m>'`: `<n>` choices exceed the 128 slots available, so some would be dropped. Reduce the list, or nest the rare choices under one weighted choice.* |

### Characters

| Id | Raised by | Message |
|---|---|---|
| `DIAG.050` | [CHAR.003](06-characters.md#2-the-domain) | *`<asset>`: marker `<s>` is `<n>` codepoints. A marker is exactly one.* |
| `DIAG.051` | [CHAR.004](06-characters.md#2-the-domain) | *`<asset>`: marker U+`<hhhh>` is not an assigned Unicode codepoint. It was most likely produced by an exporter walking codepoints in sequence; reassign it.* |
| `DIAG.052` | [CHAR.005](06-characters.md#2-the-domain) | *`<asset>`: marker U+`<hhhh>` is `<category>`, which cannot be a marker. `<A combining mark occupies no position of its own in a slice.>`* |
| `DIAG.053` | [CHAR.011](06-characters.md#3-slices) | *`<part>` slice `<i>` row `<j>`: `<n>` codepoints, but the part declares a width of `<w>`. Correct the row, or the declared width, so the two agree.* |
| `DIAG.054` | [CHAR.022](06-characters.md#4-assignment) | *`<command>`: this part needs `<n>` markers and the assignment alphabet holds `<m>`. Split the part, or reuse markers already in its palette.* |

### Versioning

| Id | Raised by | Message |
|---|---|---|
| `DIAG.060` | [VER.010](09-migration.md#3-retired-keys) | *`<asset>` marker `'<m>'`: `<key>` was retired in version 2. Write `<replacement>` instead.* |
| `DIAG.061` | [VER.011](09-migration.md#3-retired-keys) | *`<asset>`: `<key>` was deleted, not renamed. `<explanation>`* |
| `DIAG.062` | [VER.014](09-migration.md#11-what-version-2-does-not-reach-yet) | *`<owner>`: the inline palette declares version `<n>`, which this Urbex cannot yet read inline. Write it in the version 1 format, or move it to the `palettes` registry and name it with `refpalette`.* |
| `DIAG.063` | [VER.015](09-migration.md#11-what-version-2-does-not-reach-yet) | *`<asset>`: resolves through an entry written in palette format version `<n>`, which this Urbex decodes but does not yet compile. Write it in the version 1 format, or omit `version`, until version 2 compilation lands.* |
| `DIAG.064` | [VER.016](09-migration.md#11-what-version-2-does-not-reach-yet) | *`<asset>` marker `'<m>'`: trait `<id>` holds a `$ref`, and this Urbex cannot yet resolve a reference inside a trait. Write the block, or the weighted list, in full.* |

## 5. Retired identifiers

> **DIAG.910** · `MUST` — A diagnostic identifier is permanent. Retiring a rejection retires its
> identifier with a tombstone; the number is never reused.

> > **Why** — identifiers reach users. They appear in logs, in bug reports and in support threads,
> > and a reused number makes an old report describe a new problem.

## Tombstones

> **DIAG.042** — *retired in draft.* Raised when a list's explicit weights reached 128 before its
> `rest`, under the model where weights carried a `rest` were absolute counts. That model was
> replaced by `share`/`weight`/`rest`
> ([WEIGHT.010](05-weights.md#2-share-weight-and-rest)), which has no total to exceed. The
> over-allocation case that survives is shares totalling 1 or more, which is `DIAG.045`, a different
> message with a different remedy. The number stays retired.
