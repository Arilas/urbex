# 03 · Weights and selection

`[DRAFT]` · Area `WEIGHT` · Palette format version 2

How a [`weighted`](00-model.md#42-weighted) node and a
[`light_socket`](00-model.md#45-light_socket) placement list choose among their
[alternatives](00-model.md#3-alternatives-and-satellites).

---

## 1. One spelling of size

> **WEIGHT.001** · `MUST` — A choice's size is stated by `share`, `weight` or `rest`. Each has one
> spelling, one meaning, and one reading wherever it appears — in a `weighted` node's `choices`, in a
> `light_socket` placement list, and in any list a future kind introduces.

> > **Why** — version 1 spelled it `random` in `blocks` and in `unlitBlocks`, and `weight` in light
> > candidates. The two appeared in adjacent entries of the same file, on different scales, and were
> > selected by different algorithms: `random` was distributed over 128 slots, `weight` by a ticket
> > walk. `random: 0` was legal and `weight: 0` was refused.

> **WEIGHT.002** · `REJECT` (`DIAG.040`) — A `weight` that is absent, zero or negative is refused.

> **WEIGHT.003** · `MUST` — A choice carries exactly one of `weight`, `share` or `rest`.

> **WEIGHT.004** · `MUST` — `share` is a JSON number; `weight` is a positive integer. Neither is a
> count of anything, and no palette file states a denominator. A `share`'s exact value is the decimal
> the file writes, and the [exact arithmetic](#6-nesting) is performed on that.

> > **Why the exact value has to be stated** — WEIGHT.052 requires exact rational arithmetic, and a
> > JSON number reaches every implementation as a binary floating-point value, so "exact" has to say
> > exact to *what*. The two available answers differ: `0.2` written in a file is `1/5`, and the
> > `double` nearest to it is
> > `0.200000000000000011102230246251565404236316680908203125`. The first is a statement about the
> > file and the second about IEEE 754, and only the first is a number the author can be held to. In
> > practice: read the shortest decimal that round-trips to the decoded value, not the value's exact
> > binary expansion.

> **WEIGHT.005** · `MUST` — Every rule about a list's sizes is evaluated on the list as it stands
> after [`$spread`](03-pointers.md#23-spread) expansion, and **before**
> [exclusion](#3-when), never on the choices as written. Exclusion then removes what its conditions
> exclude and the survivors' sizes follow from WEIGHT.021, with no size rule evaluated a second time.

> > **Why** — a file may write one `share` and take the rest of its list from a spread, so the
> > written text is not the list. Checking what was written would refuse a correct file whose
> > remaining size arrives from somewhere else, which is the same class of mistake as reporting 45
> > warnings about a pack that was right.

> > **Why before exclusion and not after** — this rule used to say "after exclusion", and that made
> > WEIGHT.014 and WEIGHT.021 contradict each other. A list of three shares totalling 1, one of them
> > carrying a `when` that does not hold, totals 0.9 once the condition is evaluated and has nothing
> > to take the remainder — so WEIGHT.014 refuses precisely the list WEIGHT.021 says to scale back up.
> > The only reading that keeps both alive is this one: a size rule is about what the *author*
> > assembled, and exclusion is a fact about the installed environment, which WEIGHT.021 already
> > governs. Validating coherence after exclusion refuses a file that was written correctly, which is
> > the over-rejection `ACCEPT` exists as a class to prevent.

```json fixture:WEIGHT.002 reject=DIAG.040
{ "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
  { "weight": 0, "block": "minecraft:stone" },
  { "weight": 4, "block": "minecraft:cobweb" } ] } } }
```

## 2. `share`, `weight` and `rest`

A choice's size is stated in one of three ways. None of them refers to the 128 slots a weighted node
compiles to: that number is an implementation detail of
[materialisation](#5-selection) and never appears in a palette file.

> **WEIGHT.010** · `MUST` — `share` is a fraction strictly between 0 and 1, and the choice carrying
> it takes exactly that fraction of the node.

> **WEIGHT.011** · `MUST` — `weight` choices divide what the shares leave, in proportion to their
> weights. A list with no `share` therefore divides the whole node by weight.

> **WEIGHT.012** · `MUST` — `"rest": true` is the sole `weight` choice written without a number: it
> takes everything the shares leave.

> **WEIGHT.013** · `REJECT` (`DIAG.041`) — More than one `rest` in a list is refused, and so is a
> `rest` in a list that also carries a `weight`.

> > **Why** — `weight` choices already divide the remainder between them, so a `rest` beside one is
> > asking for the same fraction twice. `rest` is exactly the case where that division has one
> > participant, which is why it needs no number.

> **WEIGHT.014** · `REJECT` (`DIAG.045`) — Shares summing to 1 or more are refused when the list has
> any `weight` or `rest` choice, and shares not summing to 1 are refused when it has none.

> **WEIGHT.015** · `INVARIANT` — The distribution of a list does not depend on the order its choices
> are declared in, except for the single-slot tie break WEIGHT.064 allows.

> > **Why** — version 1 read weights as absolute counts filled in declaration order until 128 slots
> > were full, so order decided what got truncated, and an earlier draft of this document kept that
> > for lists carrying a `rest`. Both make a list something you cannot add to: the shipped
> > workstation list totals 65 before its sentinel, so a seventh choice weighted 30 would have
> > received 8 slots and a list already at 128 would have received none. Relative sizes compose;
> > absolute ones do not.

```json fixture:WEIGHT.014 reject=DIAG.045
{ "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
  { "share": 0.7, "block": "minecraft:stone" },
  { "share": 0.4, "block": "minecraft:andesite" },
  { "rest": true, "block": "minecraft:cobweb" } ] } } }
```

```json fixture:WEIGHT.013 reject=DIAG.041
{ "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
  { "weight": 3, "block": "minecraft:stone" },
  { "rest": true, "block": "minecraft:cobweb" } ] } } }
```

Sizes stated directly, which is what the shipped list meant all along:

```json fixture:WEIGHT.010 accept name=workstations-by-share
{
  "version": 2,
  "palette": {
    "F": {
      "kind": "weighted",
      "choices": [
        { "share": 0.20, "block": "minecraft:furnace[facing=north]" },
        { "share": 0.16, "block": "minecraft:crafting_table" },
        { "share": 0.05, "block": "minecraft:brewing_stand" },
        { "share": 0.05, "block": "minecraft:anvil[facing=north]" },
        { "share": 0.04, "block": "minecraft:cauldron" },
        { "share": 0.02, "block": "minecraft:enchanting_table" },
        { "rest": true,  "block": "minecraft:cobweb" }
      ]
    }
  }
}
```

The same node as pure ratios, which is the form to reach for when a descendant may add to it:

```json fixture:WEIGHT.011 accept name=workstations-by-weight
{
  "version": 2,
  "palette": {
    "F": {
      "kind": "weighted",
      "choices": [
        { "weight": 25, "block": "minecraft:furnace[facing=north]" },
        { "weight": 20, "block": "minecraft:crafting_table" },
        { "weight": 7,  "block": "minecraft:brewing_stand" },
        { "weight": 6,  "block": "minecraft:anvil[facing=north]" },
        { "weight": 5,  "block": "minecraft:cauldron" },
        { "weight": 2,  "block": "minecraft:enchanting_table" },
        { "weight": 63, "block": "minecraft:cobweb" }
      ]
    }
  }
}
```

### 2.1 Composition

This is what the three sizes exist for. A descendant
[spreads](03-pointers.md#23-spread) an inherited list and adds to it:

> **WEIGHT.016** · `MUST` — A `weight` added to a spread list of weights takes its proportional part
> of the combined total, whatever that total is.

> **WEIGHT.017** · `MUST` — A `share` added to a spread list takes exactly its fraction, and the
> spread choices divide what is left in their existing proportions.

> **WEIGHT.018** · `MUST` — A `share` in a spread list keeps its fraction; the spread does not
> rescale it.

> **WEIGHT.019** · `REJECT` (`DIAG.045`) `[NO-FIXTURE: a parent palette to spread from]` — A spread that brings the shares of a list to 1 or more is
> refused, naming the incoming and inherited totals separately.

> > **Why** — the failure has to name both halves. "Shares total 1.15" sends an author looking
> > through their own four lines for a number that came from a file they did not write.

```json fixture:WEIGHT.017 accept name=spread-then-add
{
  "version": 2,
  "extends": "urbex:bricks_standard",
  "palette": {
    "#": {
      "$ref": "$super",
      "choices": [
        { "$spread": "$super#/choices" },
        { "share": 0.25, "block": "minecraft:cracked_deepslate_bricks" }
      ]
    }
  }
}
```

Whatever `urbex:bricks_standard` says about `#`, and however many choices it has, the added block is
a quarter of the result and the inherited choices keep their ratios across the other three quarters.

## 3. `when`

> **WEIGHT.020** · `MUST` — `when` on a choice states a load-time condition. A choice whose
> condition does not hold is removed from the list before any share is computed.

> **WEIGHT.021** · `MUST` — A removed choice's size goes to the survivors: a removed `weight` leaves
> the remaining weights to divide the same fraction, and a removed `share` is redistributed to the
> `weight` choices, or in proportion to the remaining shares if there are none. When what is removed
> is the *last* `weight`, or the `rest` itself, nothing is left to take the remainder, and the
> surviving shares divide the whole node in proportion to each other by the same rule.

> > **Why the last one is stated separately** — because it is the one case where removal changes which
> > clause of this rule applies rather than only what it applies to. `{share 0.25, share 0.25, weight
> > 1}` on an installation without the weight's block is `[0.5, 0.5]`, and `{share 0.25, rest}` without
> > the `rest`'s block is `[1]`. Both are the last sentence, and without it WEIGHT.005's promise that
> > the survivors' sizes "follow from WEIGHT.021" would be false for exactly the two shapes an author
> > is most likely to reach by writing one optional cross-mod entry.

> **WEIGHT.022** · `MUST` — `when` is evaluated exactly once, at load. Every position resolving this
> node sees the same reduced list.

> **WEIGHT.023** · `MUST` — `when` accepts `mod`, naming a mod id that must be loaded, and `pack`,
> naming a namespace that must register assets.

> > **Why** — both already have implementations. Widening this to configuration or dimension makes
> > "load time" depend on state that can change without a reload, and is deliberately not in scope.

> **WEIGHT.024** · `REJECT` (`DIAG.043`) — A `weighted` node or a `light_socket` all of whose
> alternatives are removed is *itself* removed from the list it is a choice of, cascading upward; a
> marker's own node left with nothing is refused.

> > **Why** — the alternative is a marker that silently becomes air, which is the failure mode a
> > pack notices only by looking at a chunk.

> > **Why a nested node cascades rather than refusing** — because the `> Why` above is a statement
> > about a *root*. Only at a marker's own node is there nothing left to take the share; a nested one
> > has a parent that divides the remainder between the choices that are left, which is WEIGHT.021
> > with no new mechanism, and DIAG.043's message says as much in its own words ("the marker would
> > generate as air"), which is false of a nested node. Refusing there would also refuse a shape this
> > format positively invites: grouping the rare, optional, cross-mod entries under one choice is the
> > natural way to write them, and DIAG.044's remedy recommended it in so many words until
> > WEIGHT.063 was rescoped to the flattened tree.

> > **What is lost, and what replaces it** — a nested node leaving the tree is a structural change a
> > `when` makes with nothing refusing it, which is the one thing refusing bought. WEIGHT.026 is what
> > replaces it: the cascade is reported, as a warning, so the change leaves a trace without refusing
> > a pack that is working as written.

> > **Why a `light_socket` is named here** — its placement lists are lists like any other, whose
> > candidates accept `when` by MODEL.076, and by MODEL.070 the candidates are its only block source.
> > So a socket with none generates as air exactly as an emptied `weighted` node does, and DIAG.043's
> > message is true of it word for word. MODEL.072 refuses a socket that *declares* no candidate;
> > this is the same absence arriving from the installed environment instead.

> **WEIGHT.026** · `WARN` (`DIAG.046`) — A node removed by WEIGHT.024's cascade is reported as a
> warning, naming how many of its alternatives went each way. It is a warning and not a rejection: by
> DIAG.904 a warning does not refuse the world, and this one does not.

> > **Why** — the cascade is the only structural change a load-time condition can make to a palette
> > that would otherwise leave no trace anywhere. Dropping a *choice* is visible in what generates;
> > dropping the node the choices were nested under changes the shape of the tree, and a pack that
> > loses a whole group of alternatives on a vanilla install would look, from the inside, exactly like
> > a pack that never had them. WEIGHT.030's leniency is about not refusing such a pack, not about
> > saying nothing to its author.

> > **Why it is reported where the node was and not where the marker is** — the remedy is per node:
> > either that choice should not have been written for this installation, or the mod it names should
> > be installed, and both are decisions about the one nested list. The warning is not raised at all
> > when the list that absorbed the node is itself empty, because "the choices around it divide its
> > share" would then be false and something further up is about to report the real failure.

> > **One case reports nothing, deliberately** — a `light_socket` whose `floor` is emptied by the same
> > exclusion that absorbed a node *inside* `floor`, while its `ceiling` survives. The socket lives, so
> > the warning would be true, but the emptied list cannot see that its owner survived and withholds it
> > by the rule above. This is stated here rather than only in a task report because closing it means
> > threading the owner's fate back through the recursion, which is a signature change for a shape no
> > file in the measured corpus has: whoever makes that change should know this is what it buys.

```json fixture:WEIGHT.024 reject=DIAG.043
{ "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
  { "weight": 1, "block": "create:andesite_casing", "when": { "mod": "create" } },
  { "weight": 1, "block": "ae2:sky_stone_block",    "when": { "mod": "ae2" } } ] } } }
```

```json fixture:WEIGHT.021 accept name=when-redistributes
{ "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
  { "weight": 6, "block": "create:andesite_casing", "when": { "mod": "create" } },
  { "weight": 4, "block": "minecraft:stone_bricks" } ] } } }
```

```json fixture:WEIGHT.020 accept
{
  "version": 2,
  "palette": {
    "c": {
      "kind": "weighted",
      "choices": [
        { "share": 0.1, "block": "create:andesite_casing", "when": { "mod": "create" } },
        { "rest": true, "block": "minecraft:stone_bricks" }
      ]
    }
  }
}
```

### 3.1 `when` is not `urbex:optional`

They are near-neighbours and behave completely differently. This table is normative.

| | `when` | [`urbex:optional`](01-traits.md#46-urbexoptional) |
|---|---|---|
| evaluated | once, at load | per position, at generation |
| effect | the choice leaves the list | the choice stays at full weight |
| the share | redistributed to survivors | unchanged |
| when it does not apply | nothing is written for it, ever | its `replacement` is written |
| varies by position | no | yes |

> **WEIGHT.025** · `MUST NOT` — A choice may not use `when` to express per-position optionality, and
> `urbex:optional` may not be used to express a structural exclusion. They are not interchangeable.

## 4. Absent blocks

> **WEIGHT.030** · `ACCEPT` — A choice whose block names an id no installed mod provides is dropped
> from the list, and the survivors share its weight.

> **WEIGHT.031** · `MUST` — Dropping happens after `when` and before the share is computed, so the
> two compose without ordering surprises.

> **WEIGHT.032** · `REJECT` (`DIAG.043`) — A `weighted` node or a `light_socket` all of whose
> alternatives are dropped is removed from its parent, and refused when it is the marker's own node,
> by the same rule as WEIGHT.024.

> > **Why** — this is the one place the format is deliberately lenient about a missing block, and it
> > is lenient because a pack naming optional cross-mod content should generate the rest of itself.
> > A pack naming *only* absent blocks for a marker is not that case.

```json fixture:WEIGHT.032 reject=DIAG.043
{ "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
  { "weight": 1, "block": "nosuchmod:a" },
  { "weight": 1, "block": "nosuchmod:b" } ] } } }
```

```json fixture:WEIGHT.030 accept
{ "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
  { "weight": 6, "block": "nosuchmod:nosuchblock" },
  { "weight": 4, "block": "minecraft:stone_bricks" } ] } } }
```

## 5. Selection

> **WEIGHT.040** · `MUST` — A `weighted` node compiles to exactly 128 slots. Which slot a position
> takes is a pure function of the world seed, the marker, and the block position.

> **WEIGHT.041** · `MUST` — The marker is part of the address. Two markers with weighted nodes place
> their minority choices at different offsets.

> > **Why** — without it every weighted marker resolves to the same slot index at a given block, so
> > a mossy-cobble wall and a cracked-brick floor put their variants at identical offsets: one
> > spatial pattern shared by the whole palette instead of one per marker.

> **WEIGHT.042** · `INVARIANT` — Selection draws from no sequential stream, so the result at a
> position does not depend on how many other positions the chunk resolved first, or in what order.

> **WEIGHT.043** · `MUST` `[NO-FIXTURE: a placed socket, which needs a chunk]` — A `light_socket`
> placement list is selected by the same rules, addressed by the same position: apportioned to the same
> 128 slots, and the slot read from the position rather than drawn from a stream.

> > **Why it is spelled out, having been stated and unimplemented** — "the same rules" was read as
> > "weighted, somehow" for as long as nothing checked it. Version 1 placed a socket by allocating a
> > `RandomSource` at the marker and drawing `nextInt(total)` over the *authored* weights, so a
> > placement list of `6, 3, 1` drew a ticket below ten; version 2 apportions every list to 128 slots,
> > and the two cannot agree on which candidate a position takes. That made
> > [VER.021](09-migration.md#4-the-migration-tool) false for every pack with a socket — the converted
> > file relit the city — and no converter output could avoid it, because 6/10 is not a number of
> > 128ths. Reading the slot at the position instead makes this rule true, makes WEIGHT.042 true of a
> > socket for the first time, and makes the two formats place the same light.

> > **What else it fixed, which is the WEIGHT.042 half** — a sequential ticket meant an opportunity
> > tried earlier at the same position changed the one taken later. An unsupported opportunity never
> > drew, but a *supported* one whose candidate the world refused did, so which light stood in a doorway
> > depended on whether the floor beneath it had been rejected first. That is precisely what "draws from
> > no sequential stream" forbids, one position rather than one chunk over.

> > **A version 1 socket weight is a ticket share and not a slot count, which decides *which*
> > apportionment.** [WEIGHT.060](#7-rounding) to [WEIGHT.062](#7-rounding) is the one this rule means,
> > and it is not the function version 1's `blocks` list uses: that one reads a weight as an absolute
> > count and *clips* a list totalling more than 128, so `[1000, 1]` becomes `[128, 0]` and a candidate
> > version 1 placed one time in a thousand can never be placed. Refusing such a list instead is the
> > same mistake with a diagnostic on it, and both break
> > [VER.004](09-migration.md#1-versioning) — "version 1 does not become stricter" — retroactively, on
> > packs that load today. WEIGHT.062 gives that candidate one slot of 128: rarer than it was, because a
> > socket has 128 slots and not 1001, and present, which is the property a pack author can see.

> > **The marker is not in the address, and WEIGHT.041 does not need it to be.** That rule exists so two
> > weighted markers at one block do not share a draw. A socket *is* the marker at its position and no
> > second socket can occupy it, so the address is the block under the lighting purpose — which is the
> > address version 1 already seeded its stream from. What changed is that the slot is read there rather
> > than a stream allocated there.

## 6. Nesting

> **WEIGHT.050** · `MUST` — A choice that is itself a `weighted` node contributes its own
> distribution, scaled by its share of its parent.

> **WEIGHT.051** · `MUST` — `rest` is resolved against the list it appears in, at any depth.

> **WEIGHT.052** · `MUST` — The distribution of a nested tree is computed as exact rational
> arithmetic over the whole tree, and materialised into slots exactly once, at the root.

> > **Why** — distributing at each level and folding the results compounds a rounding step per
> > level. One rounding step at the end also makes `rest` mean the same thing at any depth, rather
> > than meaning "the remainder of an already-rounded parent share".

> **WEIGHT.053** · `INVARIANT` — For any nested tree, the compiled slot distribution equals the
> distribution of the flattened equivalent list to within one slot per choice.

## 7. Rounding

> **WEIGHT.060** · `MUST` — Slots are apportioned by largest remainder. Ties go to the lowest index
> in declaration order.

> **WEIGHT.061** · `INVARIANT` — Every one of the 128 slots is assigned.

> **WEIGHT.062** · `MUST` — A choice whose exact share rounds below one slot still receives one
> slot, and the deficit is taken from the largest share.

> > **Why** — a choice an author wrote and weighted is a choice they want to see. Silently rounding
> > it out of existence makes a weight of 1 in a long list mean nothing, with no diagnostic.

> **WEIGHT.063** · `REJECT` (`DIAG.044`) `[NO-FIXTURE: a generated 129-choice list]` — A node whose
> alternatives, flattened by WEIGHT.052, number more than 128 after exclusion is refused, since
> WEIGHT.062 cannot be satisfied.

> > **Why the flattened count and not one list's length** — this rule used to say "a list with more
> > than 128 choices", which is not the condition it names after "since". WEIGHT.062 owes a slot to
> > every alternative the tree resolves to, so three nested lists of fifty are 150 claims on 128
> > slots with no list in the tree anywhere near the limit. The flattened count subsumes the older
> > wording, because a single list of more than 128 choices flattens to at least that many.

> **WEIGHT.064** · `MUST` — Rounding is the only place declaration order is read, and only to break
> ties, by WEIGHT.060. It cannot change a choice's share by more than one slot.

## Tombstones

*None. This document has not yet left draft.*
