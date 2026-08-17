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
rereading the prose. Seven classes, and every rule has exactly one:

| Class | The rule says | A test proves it by |
|---|---|---|
| `MUST` | a conforming loader does this | exercising it and asserting the observable result |
| `MUST NOT` | a conforming loader never does this | exercising the situation the rule forbids and asserting the behaviour does not occur — the negative of `MUST`, and no weaker: the situation has to be reachable, or the test asserts nothing |
| `REJECT` | this input is refused at load | feeding the input and asserting the load fails with the cited `DIAG` |
| `ACCEPT` | this input is *not* refused | feeding the input and asserting the load succeeds |
| `DEFAULT` | an absent field takes this value | comparing the compiled output of the absent and explicit forms |
| `EQUIV` | two spellings compile identically | compiling both and asserting the compiled forms are equal |
| `INVARIANT` | a property holds of every compiled palette | asserting it over the shipped corpus and over generated inputs |

`MUST NOT` is not `REJECT`. A `REJECT` rule refuses a document and names the diagnostic that refuses
it; a `MUST NOT` rule says a behaviour never happens, and the input that would provoke it is usually
accepted. `MODEL.021` — a string is never a reference — accepts the string and asserts it did not
resolve; `VER.004` — version 1 does not become stricter — accepts a version 1 file with an unknown key
and asserts it still loads. Neither has a diagnostic, because neither is a rejection, which is why
`MUST NOT` carries no `DIAG` and the fixture-completeness check in §4.2 does not reach it.

`REJECT` rules always cite a `DIAG` identifier. A rejection whose message is not specified is a
rejection that cannot be tested without pinning an implementation detail.

`ACCEPT` exists because the expensive mistakes in version 1 were over-rejection as often as
under-rejection: a validator that reported 45 warnings about a correct pack is a validator nobody
reads. When a rule refuses something, the neighbouring case that must *not* be refused is worth
stating as its own rule.

`INVARIANT` covers performance claims. "Resolution allocates nothing per chunk" is a testable
statement and belongs in the specification, not in a comment.

### 3.3 Status

A rule with no status marker is normative and current. Two markers exist:

- `[PROPOSED]` — written, not yet implemented. Tests may exist and be `@Disabled` with the rule id
  as the reason. A release may not ship with a `[PROPOSED]` rule in a document not itself marked
  `[DRAFT]`.
- `[DEPRECATED → X.NNN]` — still enforced, superseded by another rule, scheduled for removal.

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

**Only decoding is implemented.** A version 2 palette file decodes to a raw node tree or is refused
with the diagnostic the catalogue names. Nothing resolves a `$ref`, merges an `extends` chain, reads a
trait, expands a tag or compiles a palette, so every rule about those is written and unenforced. The
[conformance index](palette/conformance.md) is the list of which.

**Not every fixture runs.** `FormatFixtureTest` runs every fixture whose outcome decoding alone
decides; the rest are listed in that class, each naming the task it waits for, and the list is checked
so that a fixture cannot fall out of coverage or stay listed after it becomes runnable.

**No `[NO-FIXTURE]` rule has a citing test yet.** All thirteen need something a decoder does not have -
a second asset, a resolved chain, a part file, a command invocation, a generated input - and
`ConformanceIndexTest` carries the enumerated exemptions until they do.

**`docs/schema/palette.v2.schema.json` does not exist.** §7 names it as the machine-readable shape
and as the thing `PaletteSchemaTest` drift-guards against the codec's key sets. Until it is written,
`DIAG.003` tells an author to "check the spelling against the schema" and there is no schema to
check against.

**The design record does not exist.** §2 forbids these documents from holding discussion,
alternatives considered and measurements, on the grounds that a design record holds them. That record
has not been written, so the rejected alternatives currently survive only in `> Why` blocks — which
is the right place for the reason but the wrong place for the argument.

**`LOAD` has no fixtures, by construction.** Every rule in it is `MUST` or `INVARIANT`, so §4.2's
completeness check does not reach it. The compilation guarantees — including every performance
invariant — are therefore the least externally-checked part of the specification, and the easiest to
let rot.

**Two behaviours are `[PROPOSED]`, not specified**: `urbex:oriented`, which would derive a facing
from a block's surroundings, and the `$super` alias's interaction with a future kind that introduces
a list this document does not define.

**Registries other than `palettes` have no version 2.** `VER.040` and `VER.041` say how one adopts
this pattern; nothing has. `conditions`, `variants`, `styles` and the rest remain version 1 only,
which `VER.013` makes safe but does not make finished.

**The Python generator was scaffolding, and is gone.** `docs/format/conformance.py` produced
`palette/conformance.md` before there was a Java tree to parse these documents in. `SpecDocuments`
is now the only parser; the script is deleted, so there is no longer a second implementation of the
grammar this document defines to drift out of sync with the first.
