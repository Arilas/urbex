# 05 · Compilation

`[DRAFT]` · Area `LOAD` · Palette format version 2

What the loader does between reading a palette file and generation asking it a question, and the
properties that must hold afterwards.

The rules here are as normative as the syntax rules. The brief for version 2 was that reading the
format prepares everything, so "no allocation per chunk" is a claim the format makes, not an
implementation detail it happens to have.

---

## 1. The pipeline

> **LOAD.001** · `MUST` — Compilation runs in this order, and each stage completes for every palette
> before the next begins.

| # | Stage | Produces | Rules |
|---|---|---|---|
| 1 | decode | one node tree per file, keys validated | [MODEL.004](00-model.md#1-the-file) |
| 2 | chain | the `extends` chain, root-first | [MERGE.001](04-merging.md#1-extends) |
| 3 | link | `$ref`, `$super`, `$spread` and every pointer resolved; the graph topologically sorted | [REF.031](02-references.md#5-resolution-order-and-cycles), [REF.040](03-pointers.md#1-pointers) |
| 4 | exclude | `when` evaluated, absent blocks dropped | [WEIGHT.020](05-weights.md#3-when), [WEIGHT.030](05-weights.md#4-absent-blocks) |
| 5 | expand | tags expanded against the tag epoch | [MODEL.052](00-model.md#43-tag) |
| 6 | apportion | exact rational shares over each tree | [WEIGHT.052](05-weights.md#6-nesting) |
| 7 | materialise | 128 slots per weighted node, once | [WEIGHT.040](05-weights.md#5-selection) |
| 8 | index | markers remapped to a dense integer range | [CHAR.030](06-characters.md#5-performance) |

> **LOAD.002** · `MUST` — Compilation happens once per world load, on the loading thread, against
> the registries of the world being loaded.

> > **Why** — palettes were once compiled lazily by the first chunk that needed one, from a worldgen
> > worker, resolving block strings against whichever registry a static server reference happened to
> > point at. Which registry answered depended on whether the server field was populated yet.

> **LOAD.003** · `MUST` — Every block string resolves against a block registry handed to the
> compiler by its caller, never one it fetches.

> **LOAD.004** · `MUST` — A palette that fails to compile produces a diagnostic naming the asset and
> does not abort the compilation of other assets; all diagnostics are reported together.

> > **Why** — an author fixing one palette at a time, one world load at a time, is the cost of
> > failing on the first error.

## 2. Diagnostics are load-time

> **LOAD.010** · `MUST` — Every `REJECT` rule in this specification is enforced at load. None is
> deferred to generation.

> **LOAD.011** · `INVARIANT` — No compiled palette can raise a diagnostic during generation. A
> question generation can ask has an answer, or the world did not load.

> > **Why** — version 1 could fail on the first chunk that used a marker, hours after the pack
> > loaded clean, as an exception from a worldgen worker that killed the chunk.

> **LOAD.012** · `MUST` — A marker used by a part that no palette in that part's context defines is
> a load error, and a marker that only some of a style's palette draws define is a load warning.

> **LOAD.013** · `ACCEPT` `[NO-FIXTURE: a style with several palette groups]` — A marker defined by every draw of every group is reported neither as an
> error nor as a warning.

> > **Why** — over-reporting is as costly as under-reporting. The first implementation of this check
> > produced 45 warnings about a pack that was correct, which is a check nobody reads.

> **LOAD.014** · `INVARIANT` — The check in LOAD.012 computes its witnesses per part, not per
> marker.

> > **Why** — measured: hoisting witnesses per marker was 96% of a compile and 890 MB of allocation.

## 3. The compiled shape

> **LOAD.020** · `MUST` — A compiled palette maps each marker to a **compiled entry** holding both
> the block states it may place and the traits that apply to them.

> **LOAD.021** · `MUST` — Traits are per slot, not per marker.

> > **Why** — [TRAIT.005](01-traits.md#2-inheritance) lets two choices of one marker carry different
> > traits, so a per-marker trait table cannot represent them. Version 1 kept traits in a separate
> > map keyed by marker, which is both the wrong granularity and a second lookup.

> **LOAD.022** · `INVARIANT` — Resolving a marker to a state and to its traits is one lookup, not
> two.

> **LOAD.023** · `MUST` — Trait sets are interned, so slots sharing a trait set share one object.

> **LOAD.024** · `INVARIANT` — No compiled palette holds a reference to the parsed JSON, to a
> definition name, to a pointer, or to any string used only during compilation.

> **LOAD.025** · `MUST` — A pointer into another asset is resolved against that asset's decoded
> document, which means stage 2 runs for every palette before stage 3 runs for any.

## 4. Sharing and identity

> **LOAD.030** · `INVARIANT` — Two markers that reference the same definition share one compiled
> representation, established at compile time rather than recovered afterwards.

> > **Why** — version 1 held two static interning pools, cleared by hand from the generation
> > session, whose only job was recovering identity the format had discarded. A named definition
> > makes sharing structural.

> **LOAD.031** · `MUST NOT` — Compilation may not retain state in static fields that outlive the
> compiled palette.

> > **Why** — those two pools were unsynchronised while being written from a decoding worker pool,
> > and nothing emptied them, so every palette of every world loaded in a process lifetime stayed
> > reachable through them.

## 5. Generation-time cost

> **LOAD.040** · `INVARIANT` — Resolving a marker at a position allocates nothing.

> **LOAD.041** · `INVARIANT` — Resolving a marker at a position performs no hash lookup, no boxing,
> and no string comparison.

> **LOAD.042** · `INVARIANT` — Resolving a marker at a position reads no registry and no tag.

> **LOAD.043** · `INVARIANT` — The result of resolving a marker at a position depends only on the
> world seed, the marker, the position, and the compiled palette — not on the order positions are
> resolved in, nor on which chunk resolved first.

> **LOAD.044** · `MUST` — Merging a palette over another is memoised per distinct pair, not
> recomputed per chunk.

## 6. Reporting

> **LOAD.050** · `MUST` — The loader can print the fully resolved form of any marker: its kind, its
> slots with exact shares, every trait with the node it was inherited from, and the reference chain
> each came through.

> > **Why** — indirection is the cost this format pays for reuse, and the two questions it creates —
> > what does this resolve to, and where did that come from — are answerable for free, because
> > everything is already resolved in memory. Without this the format is harder to work with than
> > the one it replaces.

> **LOAD.051** · `MUST` — Every diagnostic names the reference chain it was reached through, not
> only the node that failed.

## Tombstones

*None. This document has not yet left draft.*
