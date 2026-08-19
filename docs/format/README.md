# The Urbex format specification system

This directory holds the normative specification of the Urbex datapack format, version 2. It is
not a guide and not a design record. It is the document a test suite is written against.

The palette registry is the first to be specified this way. Everything in this file — the rule
identifiers, the rule classes, the fixture syntax, the conformance index — is meant to be reused
unchanged by every registry that follows.

---

## 1. Why this exists

Version 1 of the format was never written down as rules. It was written down as prose in a guide,
and as behaviour in a codec. The two disagreed, and nothing detected that they disagreed:

- The guide documented 7 of the 11 keys the palette codec accepted. `mob`, `loot` and `tag` were
  accepted by the code and described nowhere.
- Three shipped palettes wrote `damaged` inside `blocks[]` elements. The codec ignored it. Nothing
  failed, nothing warned, and the packs shipped that way for their whole lifetime.
- An addon's importer maintained a hand-written table of which JSON fields were asset references,
  because the format did not say. It drifted out of sync with the validator that used the same
  table, and 35–55% of real references went unchecked in both places without either exiting
  non-zero.

Each of these is the same failure: **a claim about the format existed in exactly one place, and
nothing compared it to anything else.** This system exists so that every claim exists in three
places that are checked against each other — the rule, the fixture, and the test.

## 2. What a specification document contains

Nothing but rules, fixtures, and the prose needed to make them unambiguous.

Rationale goes in a `> Why` block attached to the rule it explains, so that deleting a rule deletes
its justification with it. Discussion, alternatives considered, and measurements belong in a design
record under `docs/superpowers/specs/`, which this directory links to but never duplicates.

A specification document may not contain:

- an example that is not a fixture (an unmarked example cannot be checked, and will rot)
- a statement of behaviour that is not a rule (it cannot be tested and will not be maintained)
- a "usually", "generally", "should normally", or "in most cases"

## 3. Rules

Every normative statement is a rule. A rule looks like this:

> **DEMO.032** · `REJECT` (`DIAG.032`) — A reference cycle is refused. The diagnostic names every
> node in the cycle, in declaration order, beginning with the node the loader reached first.
>
> > **Why** — a cycle otherwise surfaces as a `StackOverflowError` from inside a worker thread,
> > naming no file.

### 3.1 Identifiers

    <AREA>.<NNN>

`AREA` is a short uppercase token naming the concern. `NNN` is a zero-padded ordinal.

**A rule identifier is permanent.** It is assigned once, never renumbered, never reused, and never
recycled after deletion. A deleted rule leaves its number retired, with a tombstone (§3.4). This is
the property that lets a test cite `REF.032` and still mean the same thing in two years.

Identifiers are assigned in blocks so that related rules stay near each other as the specification
grows. Per-trait rules, for example, start at a multiple of ten:

| Area | Covers | Document |
|---|---|---|
| `MODEL` | the node type, the kinds, the shape of a palette file | [`palette/00-model.md`](palette/00-model.md) |
| `TRAIT` | the trait mechanism, and each defined trait | [`palette/01-traits.md`](palette/01-traits.md) |
| `REF` | `$defs`, pointers, and the `$` operands | [`palette/02-references.md`](palette/02-references.md) |
| `MERGE` | `extends`, and what replaces what | [`palette/02-references.md`](palette/02-references.md) |
| `WEIGHT` | weights, `rest`, `when`, and how a choice is drawn | [`palette/05-weights.md`](palette/05-weights.md) |
| `CHAR` | which characters may be palette markers | [`palette/06-characters.md`](palette/06-characters.md) |
| `LOAD` | the compilation pipeline and its invariants | [`palette/07-compilation.md`](palette/07-compilation.md) |
| `DIAG` | the diagnostic catalogue | [`palette/08-errors.md`](palette/08-errors.md) |
| `VER` | format versioning and v1 coexistence | [`palette/09-migration.md`](palette/09-migration.md) |

New areas are added by adding a row. An area is never renamed.

`DEMO` is reserved. It names no real rule, and exists so that this document can show the shape of a
rule without defining one — every example above and below uses it. The tooling ignores `DEMO`
wherever it appears here, and treats it as a build failure in a specification document, a fixture or
a test.

### 3.2 Classes

A rule's class states **how it is falsifiable**, which is what makes the test writable without
rereading the prose. Eight classes, and every rule has exactly one:

| Class | The rule says | A test proves it by |
|---|---|---|
| `MUST` | a conforming loader does this | exercising it and asserting the observable result |
| `MUST NOT` | a conforming loader never does this | exercising the situation the rule forbids and asserting the behaviour does not occur — the negative of `MUST`, and no weaker: the situation has to be reachable, or the test asserts nothing |
| `REJECT` | this input is refused at load | feeding the input and asserting the load fails with the cited `DIAG` |
| `ACCEPT` | this input is *not* refused | feeding the input and asserting the load succeeds |
| `WARN` | this input is accepted, and reported | feeding the input, asserting the load succeeds, and asserting the cited `DIAG` is recorded at warning level |
| `DEFAULT` | an absent field takes this value | comparing the compiled output of the absent and explicit forms |
| `EQUIV` | two spellings compile identically | compiling both and asserting the compiled forms are equal |
| `INVARIANT` | a property holds of every compiled palette | asserting it over the shipped corpus and over generated inputs |

`MUST NOT` is not `REJECT`. A `REJECT` rule refuses a document and names the diagnostic that refuses
it; a `MUST NOT` rule says a behaviour never happens, and the input that would provoke it is usually
accepted. `MODEL.021` — a string is never a reference — accepts the string and asserts it did not
resolve; `VER.004` — version 1 does not become stricter — accepts a version 1 file with an unknown key
and asserts it still loads. Neither has a diagnostic, because neither is a rejection, which is why
`MUST NOT` carries no `DIAG` and the fixture-completeness check in §4.2 does not reach it.

A `REJECT` or `WARN` rule always cites a `DIAG` identifier. A rejection whose message is not specified
is a rejection that cannot be tested without pinning an implementation detail, and the same is true of
a report that does not refuse.

`WARN` is neither `ACCEPT` nor `REJECT`, and that is why it is a class rather than a `MUST` with a
diagnostic bolted on. `ACCEPT` asserts the load succeeds and says nothing about what was reported;
`REJECT` asserts it fails. A `WARN` rule asserts both halves at once — the document loads *and* the
author is told something — and a test that checks only one of them passes while the other is broken.
[DIAG.904](palette/08-errors.md#1-what-a-diagnostic-must-contain) fixes error and warning as the only
two levels, so this class is what the catalogue already assumed existed. It carries no `reject=`
fixture, because there is nothing to refuse; §4.2's fixture-completeness check therefore does not reach
it and §4.3's citing-test requirement does.

`ACCEPT` exists because the expensive mistakes in version 1 were over-rejection as often as
under-rejection: a validator that reported 45 warnings about a correct pack is a validator nobody
reads. When a rule refuses something, the neighbouring case that must *not* be refused is worth
stating as its own rule.

`INVARIANT` covers performance claims. "Resolution allocates nothing per chunk" is a testable
statement and belongs in the specification, not in a comment.

### 3.3 Status

A rule with no status marker is normative and current. Three markers exist:

- `[PROPOSED]` — written, not yet implemented. Tests may exist and be `@Disabled` with the rule id
  as the reason. A release may not ship with a `[PROPOSED]` rule in a document not itself marked
  `[DRAFT]`.
- `[DEPRECATED → X.NNN]` — still enforced, superseded by another rule, scheduled for removal.
- `[NOT-YET-REACHED: <issue>]` — normative, current, and implemented as far as it can be, but no code
  path reaches the situation it describes yet, so a citing test can cover how the rule is *written*
  and not what it *does*. The reason names the issue that will reach it:

      > **DEMO.011** · `MUST` `[NOT-YET-REACHED: issue #216]` — …

  It is not `[PROPOSED]`, which is a rule nothing implements; a release may ship with one. A rule
  carrying it still needs a citing test, on the same terms as any other rule — and the marker is what
  keeps that test from reading as coverage it is not. `SpecDocuments` parses it, and the
  [conformance index](palette/conformance.md) lists every rule that carries it beside the rule table,
  because the table shows citing tests and cannot show what they were able to assert.

### 3.4 Tombstones

A removed rule leaves a line in its document's tombstone section:

> **DEMO.007** — *retired 2026-09-01.* Partial definitions were required to declare
> `partial: true`. Superseded by DEMO.008, which infers it. No replacement identifier; tests citing
> DEMO.007 were deleted.

A tombstone is not optional. Without it, a reader who finds a retired identifier in an old test, an old pack,
or an old error message has no way to learn what happened to it.

## 4. Fixtures

Every example in a specification document is machine-extractable and is checked on every build. An
example that is not a fixture is a documentation bug.

A fixture is a fenced code block whose info string carries the rule it demonstrates and the
expected outcome:

    ```json fixture:MODEL.020 accept
    { "version": 2, "palette": { "X": "minecraft:stone_bricks" } }
    ```

    ```json fixture:MODEL.004 reject=DIAG.003
    { "version": 2, "palette": { "X": { "block": "minecraft:stone", "blcok": "typo" } } }
    ```

### 4.1 Grammar

    fixture:<RULE-ID> <outcome> [name=<slug>]

    <outcome> ::= accept
                | reject=<DIAG-ID>
                | equiv=<slug>
                | fragment

- `accept` — the document loads. If the fixture is a whole palette file, it is loaded as one; if it
  is a fragment, it is embedded in a minimal valid file first.
- `reject=<DIAG-ID>` — the load fails, and the diagnostic identifier matches. The message text is
  compared against the catalogue entry in [`palette/08-errors.md`](palette/08-errors.md), not
  against a literal in the test.
- `equiv=<slug>` — this fixture and every other fixture with the same slug compile to equal output.
  This is how an `EQUIV` rule is checked, and it is how shorthand forms are pinned to their long
  forms.
- `fragment` — not a complete file; shown for illustration and checked only for well-formedness and
  for the keys it uses being keys the schema defines.

`name=<slug>` labels a fixture so a test can request it directly. Unnamed fixtures are addressed by
rule and ordinal.

### 4.2 What the harness does

`FormatFixtureTest` walks every `.md` file under `docs/format/`, extracts every fixture, and:

1. fails if a fixture cites a rule identifier that no document defines
2. fails if a fixture cites a `DIAG` identifier that the catalogue does not define
3. runs each fixture against its declared outcome
4. fails if any rule of class `ACCEPT`, `REJECT`, `DEFAULT` or `EQUIV` has no fixture

Rule 4 is the one that keeps the specification honest as it grows. A new rule that can be
demonstrated by an example and is not is a build failure.

It is suspended for a document marked `[DRAFT]`, where rules are still being written and their
fixtures follow. The [conformance index](palette/conformance.md) lists every rule currently relying
on that suspension, so leaving draft is a matter of emptying that list rather than discovering it.
Regenerate it with `./gradlew regenerateConformance` after editing a specification document.

### 4.3 When a fixture is impossible

Some rules take an input that is not a document: a command invocation, a multi-file arrangement, a
generated input too large to read. Those carry a marker naming the reason, and rule 4 does not apply
to them:

> **DEMO.022** · `REJECT` (`DIAG.054`) `[NO-FIXTURE: a command invocation]` — …

`[NO-FIXTURE]` does not excuse the rule from being tested. It moves the burden entirely onto a
citing test, and `ConformanceIndexTest` fails if a `[NO-FIXTURE]` rule has none — a stricter
requirement than the one it replaces, since a fixture alone would otherwise have satisfied it.

The marker is for rules whose *input* cannot be a fixture. It is not for rules whose fixture is
merely inconvenient to write.

## 5. The conformance index

[`palette/conformance.md`](palette/conformance.md) is generated, not written. It lists every rule,
its class, its fixtures, and the tests that cite it. Regenerate it with
`./gradlew regenerateConformance`.

`ConformanceIndexTest` regenerates it and fails if the checked-in copy differs — the same
drift-guard the preset schema already uses, applied to the rule set. It additionally fails if:

- any rule has no citing test and no fixture (an unenforced claim)
- any test cites a rule identifier that does not exist (a stale test)
- any `REJECT` rule cites a `DIAG` that no rule produces
- any rule carrying a `reject=` fixture is not class `REJECT`, or cites a different `DIAG` than that
  fixture expects (the converse of the line above, and the one that catches a misclassified rule
  before the line above skips it)

**What the index cannot see is whether a citing test could fail,** and neither does anything else here.
A `@Rule` annotation binds a test to a rule; nothing checks that the test's assertions are reachable, so
an assertion that reduces to `assertSame(EMPTY, EMPTY)` counts here exactly as a real one does — and
nothing at all watches the production seams no rule is written about. Both have been found repeatedly
and only ever by hand, by someone breaking the code and looking at what stayed green: three `LOAD`
guards that could not fail, a guard whose regex matched an overload that no longer exists, a javadoc
claiming to cover a seam it never touched, and an entire compile path — `AssetCompiler` through
`V2Palettes` to a version 2 palette — that no test reached while the suite reported 1,250 passes. Making
that mechanical is [issue #217](https://github.com/Arilas/urbex/issues/217), and it is the highest-value
open item against this specification's tooling.

### 5.1 How a test cites a rule

    @Rule("REF.032")
    @Test
    void aReferenceCycleIsRefusedNamingEveryNodeInIt() { … }

`@Rule` is repeatable. A test may cite several rules; a rule may be cited by several tests. The
annotation is the only binding — there is no naming convention to remember, and renaming a test
does not break the index.

## 6. Writing style

The specification is read by someone debugging a pack that does not do what they wrote. Terseness
serves them; completeness serves them more.

- **One rule, one sentence.** If a rule needs two sentences, it is two rules.
- **State the behaviour, not the implementation.** "The remaining weights are redistributed", not
  "`distributeSlots` is called again".
- **Name the failing case.** A rule about rejection says what is rejected, not what is allowed.
- **No forward references without a link.** A term used before it is defined is linked to its
  definition.
- **Prefer a fixture to a sentence.** Two lines of JSON showing the accepted and rejected forms
  replace a paragraph, and unlike the paragraph they are checked.

Every defined term appears in the glossary in [`palette/00-model.md`](palette/00-model.md#glossary)
exactly once, in bold, at its definition site.

## 7. Relationship to other documents

| Document | Holds | Checked by |
|---|---|---|
| `docs/format/**` | the rules | `FormatFixtureTest`, `ConformanceIndexTest` (regenerated by `./gradlew regenerateConformance`) |
| `docs/schema/palette.v2.schema.json` | the machine-readable shape | `PaletteSchemaTest`, against the codec's key sets |
| `docs/datapacks.md` | the authoring guide — task-shaped, non-normative | links to rule ids; no rule stated only here |
| `docs/superpowers/specs/**` | design records — why, alternatives, measurements | not checked; dated and immutable once approved |

A conflict between the guide and the specification is a bug in the guide. A conflict between the
specification and the implementation is a bug in whichever the design record says is wrong.

## 8. Documents

| # | Document | Area | Status |
|---|---|---|---|
| 00 | [The node model](palette/00-model.md) | `MODEL` | `[DRAFT]` |
| 01 | [Traits](palette/01-traits.md) | `TRAIT` | `[DRAFT]` |
| 02 | [References and merging](palette/02-references.md) | `REF`, `MERGE` | `[DRAFT]` |
| 03 | [Weights and selection](palette/05-weights.md) | `WEIGHT` | `[DRAFT]` |
| 04 | [The character domain](palette/06-characters.md) | `CHAR` | `[DRAFT]` |
| 05 | [Compilation](palette/07-compilation.md) | `LOAD` | `[DRAFT]` |
| 06 | [Diagnostics](palette/08-errors.md) | `DIAG` | `[DRAFT]` |
| 07 | [Versioning and migration](palette/09-migration.md) | `VER` | `[DRAFT]` |
| — | [Conformance index](palette/conformance.md) | — | generated |

All documents are `[DRAFT]` until the design record for palette v2 is approved and the fixture
harness is in place. A `[DRAFT]` document's rules are numbered and may be cited, but may still be
renumbered — the permanence guarantee in §3.1 begins when the document leaves draft.

## 9. What is not yet specified

Recorded here rather than discovered later. Each of these is a known hole, not an oversight.

**A version 2 palette generates, the bundled pack is written in version 2, and one thing is not
finished.** The pipeline has a production caller as of `VER.015`'s retirement: a registered or inline
version 2 palette is compiled at world load through all of
[LOAD.001](palette/07-compilation.md#1-the-pipeline), merges with version 1 palettes into one lookup,
and places blocks. All 30 bundled palettes and all 13 bundled `definitions` assets declare
`"version": 2`; `V2Palettes` builds the `definitions` index off the world being loaded and 14 of the
30 palettes point through it, so [REF.043](palette/02-references.md) and
[REF.045](palette/03-pointers.md#1-pointers) resolve against real assets rather than being refused by
name. What is not finished:

- **[TRAIT.011](palette/01-traits.md#41-urbexdamaged) is not reached.** The damage pass has no marker to
  key on, so a version 2 palette's damage mapping collapses exactly as version 1's does. The rule carries
  §3.3's `[NOT-YET-REACHED]` marker naming the issue that fixes it, and the
  [conformance index](palette/conformance.md) lists it separately from the rules whose citing tests
  cover what they do.

**The authoring guide is half converted, and the half that is done was forced.**
[`docs/datapacks.md`](../datapacks.md) is the task-shaped guide §7's table points at, and it described
the version 1 palette throughout. `VER.018` made that untenable for its *examples*: a version 1 palette
no longer decodes, and `DatapackGuideExamplesTest` runs every example through the registry codec, so
the seven palette examples are version 2 now and the prose immediately around them moved with them.

What has not moved is everything else — the narrative order, the worked examples that are not palettes,
and the framing that still teaches a marker as a thing with a `block` beside it rather than a node.
Nothing fails while that is true, which is exactly why it is written down here: the examples are
checked and the prose is not.

**Version 1 cannot be removed, and the shipped pack is no longer why.** It was: six files — three parts
and three buildings — carried inline palettes written in version 1, tracked as
[issue #219](https://github.com/Arilas/urbex/issues/219). Those six are converted, and `VER.017` then
removed the `variants` registry, which was the only version 1 the bundled pack had left. The pack now
writes none of it.

That cost the thing this section predicted it would: the key-name half of `ShippedBlockRefs` walked
those twelve assets and nothing else, so it now covers no bundled file at all. It is asserted at zero
rather than deleted — the branch still reads the registries that are not palettes, none of which spells
a block today, and an assertion of zero fails the moment one does.

Both of those rules are retired, and no pack is left waiting on them. Modern Tweaks converted — 98
palettes, 58 variants, 60 inline palettes — and `VER.018` then removed version 1 from the loader
outright. Zombie Apocalypse Essentials converted after it: the 41 inline palettes that could not be
translated were the ones naming markers on unassigned codepoints, and reassigning those 1,111 marker
occurrences was a `sed` over its own data rather than anything this format could do for it. Nothing can
produce such a marker again — `CHAR.020`'s alphabet is what `/exportpart` draws from now.

Each pack keeps a frozen `reference/v1-snapshot/`, and those two snapshots are the whole version 1
corpus `VER.021` is verified against. That is deliberate: the property is about the converter, not
about what any pack ships today, so the corpus must not shrink each time a pack migrates. Pointed at
the live packs it would now be empty, and empty would not have failed.

What survives of version 1 is the compile path, and only in the sense that the converter needs it:
`VER.021` is verified by compiling every shipped version 1 palette both ways and comparing marker by
marker, which requires a version 1 implementation to exist for as long as the converter does.
`PaletteDefinition` is unregistered and excluded by name from the retired-key sweep for that reason.
It is not loadable from any datapack, and moving it to test scope is the next thing owed here.

**Not every fixture runs.** `FormatFixtureTest` runs every fixture whose outcome decoding alone
decides. One is listed in that class instead: `MODEL.062#1`, whose rule is decided where a style's
palette groups are merged rather than against one document, so a one-document harness has no outcome
to assert — the rule is covered by three citing tests instead. The list is checked, so a fixture
cannot fall out of coverage or stay listed after it becomes runnable.

**Two `[NO-FIXTURE]` rules have no citing test yet.** Of the 15 rules carrying the marker, 13 gained
one as the stages landed; `CHAR.011` needs a part file's slice rows and `CHAR.022` needs a
marker-assigning command, and `ConformanceIndexTest` carries those two as enumerated exemptions until
they do. That field fails the moment either gains a test, so it cannot outlive the work.

**The design record exists**, at
[`docs/superpowers/specs/2026-08-18-palette-v2-design-record.md`](../superpowers/specs/2026-08-18-palette-v2-design-record.md).
§2 forbids these documents from holding discussion, alternatives considered and measurements on the
grounds that a design record holds them, and it now does: the corpus measurements, the four
whole-format shapes costed before this one was chosen, and what implementing the rules taught about
them. A `> Why` block remains the right place for a reason and the wrong place for an argument.

**Four guards were latently vacuous, one was born so, and one has since come true.** Recorded rather
than fixed, because each is either outside this format's scope or becomes wrong on a change nobody has
made yet — until somebody makes it:

- `NoAssetReferenceDefaultsTest`'s regex matches a three-argument `listOrStringList` overload that was
  deleted in the same commit that added the test. The only text it can match is text that does not
  compile, so it has never been able to fail. Pre-existing and unrelated to version 2.
- ~~`ShippedBlockRefs.Ref.version2` records the *document's* top-level version while `collect()`
  dispatches per nested node. The 43/18 split its guard pins therefore misdescribes coverage the moment
  one of the six inline version 1 palettes converts.~~ **Fixed**, by the change this paragraph predicted:
  converting the six made the flag wrong for every block string in them, so it is now recorded per string
  by the branch that produced it, and the split is 49/12. Kept struck through rather than deleted because
  a latent vacuity that was written down and then came true is the evidence that writing them down works.
- `DatapackGuideExamplesTest.codecs()` hardcodes 13 registries where there are 14, so its "an example for
  every registry" check cannot notice that `definitions` has no example in the guide — which is the same
  hole the paragraph above this one is about, one directory over.
- `V1ToV2Test`'s two loops over the bundled `palettes/` compare the converter's output against files that
  are now version 2, so idempotence makes those 30 iterations assert nothing about the converter. The
  reference packs, which are still version 1, are what those tests actually measure.

**`LOAD` has no fixtures, by construction.** Every rule in it is `MUST` or `INVARIANT`, so §4.2's
completeness check does not reach it. The compilation guarantees — including every performance
invariant — are therefore the least externally-checked part of the specification, and the easiest to
let rot. Three of its citing tests have already been found asserting something that could not fail;
that they were found by hand rather than by tooling is §5's own hole, and
[issue #217](https://github.com/Arilas/urbex/issues/217).

**One behaviour is `[PROPOSED]`, not specified**: `urbex:oriented`, which would derive a facing from a
block's surroundings. The `$super` alias's interaction with a future kind that introduces a list this
document does not define is noted where it would arise ([REF.040](palette/03-pointers.md#1-pointers),
[WEIGHT.001](palette/05-weights.md#1-one-spelling-of-size)) and is not written as a rule at all, which
is the weaker of the two states and is deliberate: there is no such kind to specify against.

**Registries other than `palettes` and `definitions` have no version 2.** `VER.040` and `VER.041` say
how one adopts this pattern; `definitions` is the only registry that has, and it is a special case —
`REF.019` gives it no version 1 form to coexist with. `conditions`, `variants`, `styles`, `parts`,
`buildings` and the rest remain version 1 only, which `VER.013` makes safe but does not make finished.

**The Python generator was scaffolding, and is gone.** `docs/format/conformance.py` produced
`palette/conformance.md` before there was a Java tree to parse these documents in. `SpecDocuments`
is now the only parser; the script is deleted, so there is no longer a second implementation of the
grammar this document defines to drift out of sync with the first.
