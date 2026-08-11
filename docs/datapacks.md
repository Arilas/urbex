# Authoring Urbex datapacks

Everything Urbex generates comes out of thirteen datapack registries: the buildings, the palettes
they are painted from, the street parts, the world styles that assemble them. None of it is
compiled into the mod. A pack that adds a city style, repaints an existing one, or replaces the
road network wholesale is JSON files in the right directories and nothing else — no code, no
rebuild, no jar changes.

This guide covers what all thirteen share: where files go, how one asset builds on another with
`extends`, what happens when a file and its ancestor both declare the same field, and what has to
be written down somewhere before a world will load. The preset format — the per-dimension worldgen
tuning the Cities tab picks from — has its own document at [`docs/presets.md`](presets.md), but
presets are one of the thirteen and follow every rule here.

Two rules underpin the rest. Both exist because the alternative, in each case, was a reference that
resolved even though no file wrote it:

1. **Every reference names its namespace.** `urbex:street_straight`, never `street_straight`.
2. **No asset reference has a code-side default.** If your chain never says which part a T-junction
   places, the world refuses to load. It does not quietly place Urbex's.

Numbers and enums still default — `multisettings`, the preset format's field-level optionality, and
so on are untouched by rule 2. The rule is about *references*, not about every field.

## Where files go

```
data/<namespace>/urbex/<registry>/<name>.json
```

The file's registry id is `<namespace>:<name>`. A file at
`data/urbexmt/urbex/citystyles/downtown.json` is the asset `urbexmt:downtown`, and that is the
string every other file uses to name it. Directory names are the registry names below, exactly.

| Registry | What one file is | Required after the chain resolves |
|---|---|---|
| `worldstyles` | The top of the tree: which city styles apply in which biomes, the highway and railway wiring, and what gets scattered outside cities | `outsidestyle`, `citystyles`, `parts.highways` (all six), `parts.railways` (all sixteen) |
| `citystyles` | What a city is made of: street materials and street parts, plus weighted selectors for buildings, parks, bridges, fountains, stairs | `streetblocks.parts` (all eight) |
| `buildings` | An ordered stack of parts, with floor and cellar limits | `filler`, `parts` |
| `parts` | A block of geometry, up to 16×16, written as character slices | `xsize`, `zsize`, `slices` |
| `palettes` | What each character in a slice means: a block, a variant, loot, a mob, a light | `palette` |
| `styles` | Groups of palettes a building can be painted from | `randompalettes` |
| `multibuildings` | A grid of buildings spanning several chunks | `dimx`, `dimz`, `buildings` |
| `scattered` | A building placed outside cities, and how it sits on the terrain | `terrainheight`, `terrainfix` |
| `conditions` | A weighted, conditional set of values — loot tables, mob ids | `values` |
| `variants` | A weighted set of blockstates behind one palette character | `blocks` |
| `stuff` | A small decoration pass: cobwebs, chains, rubble | `column`, `mincount`, `maxcount`, `attempts` |
| `predefinedcities` | A city pinned to fixed chunk coordinates | `dimension`, `chunkx`, `chunkz`, `radius`, `citystyle` |
| `presets` | Per-dimension worldgen tuning — see [`docs/presets.md`](presets.md) | *(nothing)* |

Every one of them accepts `extends`, and every one of them applies the same three merge rules. That
uniformity is deliberate: you should never have to look up whether *this* asset type supports
extension.

The last column is what the load-time check enforces, and it is not quite the same as "what a
working asset needs". The gap you are most likely to hit is a city style's `style`, which names the
`styles` entry its buildings are painted from: it is not checked at load, so a selected city style
with no `style` anywhere in its chain throws the first time a chunk asks for a palette. Treat it as
required.

A pack needs the usual `pack.mcmeta` alongside `data/`. `pack_format` must match your target
Minecraft version's data pack format — this repository currently targets `107`, for Minecraft
`26.2`:

<!-- example: none -->
```json
{
  "pack": {
    "pack_format": 107,
    "description": "Urbex Modern Tweaks"
  }
}
```

A world style shows up on its own, without a tag: the Cities tab grows a world-style selector as
soon as more than one is registered, listing every one the world's datapacks provide. Presets are
the exception — they are listed from the `#urbex:presets` tag, which
[`docs/presets.md`](presets.md) explains.

## `extends`

`extends` takes **one fully-qualified id, in the same registry as the file declaring it**:

<!-- example: citystyles -->
```json
{
  "extends": "urbex:citystyle_common",
  "style": "urbexmt:standard"
}
```

That is a complete, loadable city style. It is everything `urbex:citystyle_common` is, painted with
a different style. It restates nothing else, and it must not: a file states what it *changes*.

Crossing namespaces like that is the primary use. Building on the bundled pack rather than copying
it means you inherit its fixes, and it means a reader of your file can see at a glance which parts
are yours.

### Chains apply root-first

`extends` chains are arbitrarily deep. The bundled pack already runs three links:

```
urbex:citystyle_border  →  urbex:citystyle_common  →  urbex:citystyle_config
```

Resolution starts at the root — the end of the chain with no `extends` — and walks back down.
`citystyle_config`'s values land first, then `citystyle_common`'s declared fields on top, then
`citystyle_border`'s. The file you asked for is applied last, so it always wins.

A cycle, or an `extends` naming an id nothing provides, is a load error that prints the chain:

```
'extends' cycle: urbexmt:a -> urbexmt:b -> urbexmt:a
Unknown asset 'urbexmt:missing' (referenced from 'urbexmt:downtown')
```

### Absence means inherit

**No field is required by the file format.** Every field of every registry decodes as optional, and
requiredness is checked *after* the chain has been applied. That is what makes the example above
legal: `citystyle_common` declares the street parts, `citystyle_border` declares none, and the
resolved style has them.

A field nothing in the whole chain declares fails the load, naming the asset and the field:

```
'urbexmt:downtown' declares no 'streetblocks.parts.stair', and neither does anything it extends
```

Note "declares no", not "is missing from this file". The check is a property of the chain, and the
error is worded that way on purpose — the fix might be in any file in it.

One consequence worth knowing: a value that is present but malformed is still a decode failure, not
an absence. `"maxfloors": "three"` fails the file; it does not read as "unset" and silently inherit
an ancestor's number.

### Every asset is resolved at world load — not just the ones in use

The check above runs on **every registered asset**, whether or not anything selects it. Loading a
world resolves all of `variants`, `palettes`, `conditions`, `styles`, `parts`, `buildings`,
`multibuildings`, `scattered`, `worldstyles`, `stuff` and `predefinedcities` up front, so a broken
file in your pack fails the world even for a player who never picks your world style. That is the
intended trade: a load error naming the file beats an exception from a worldgen worker thread three
hours into someone's save.

It has one consequence for how you write bases. In those eleven registries an **incomplete** chain
root is not allowed — a "base part" that carries only a `refpalette`, meaning to be completed by
children, fails the load on its own. Bases in those registries have to be complete assets that
children specialise.

`citystyles` is the exception, and deliberately so: only city styles something can actually
*select* are resolved. That is a world style's `citystyles` selectors, a preset's
`cities.cityStyleAlternative`, and a predefined city's `citystyle`. A city style nothing names is
never resolved and never validated — which is what lets the bundled `urbex:citystyle_config` exist
as a street width and nothing else, complete only through `urbex:citystyle_common`, which extends
it. `presets` are the other exception, for the simpler reason that nothing in a preset is required.

## The three merge shapes

When a file and its ancestor both declare something, what happens is decided by the **shape of the
field**, not by which registry it belongs to. There are exactly three shapes:

| Shape | Rule | Examples |
|---|---|---|
| Scalar | The child wins when it declares the field; otherwise the ancestor's value stands | `style`, `outsidestyle`, `buildingchance`, `xsize`, `refpalette`, `filler` |
| Ordered list | The child **replaces** by default; appending is opt-in | `selectors.buildings`, `citystyles`, `scattered.list`, `streetblocks.parts.straight`, `slices` |
| Keyed collection | Merged by key; the child's entry wins for the keys it declares | `palette` (keyed by `char`) |

Three shapes rather than one per asset type is what makes this learnable. If you can see the shape
of a field in the JSON, you know how it merges.

### Scalars: child wins when present

"When present" is doing real work. A chain entry that omits a scalar leaves whatever an earlier
entry set — it does not blank it out. And "present" is read from the key being there, not from a
sentinel value, so a child *can* set `preferslonely` back to `0.0` or `maxfloors` back to `-1`
against an ancestor that set something else.

### Ordered lists: replace by default, append on request

A declared list means exactly that list. An explicitly empty list means empty. Neither used to be
expressible, and the bundled `citystyle_border` was the proof: its `"multibuildings": []` used to
be *added to* its parent's twelve, so a style that said it wanted no multibuildings generated all
twelve of them.

To append instead, use the object form — the same shape vanilla tag files use:

<!-- example: citystyles -->
```json
{
  "extends": "urbex:citystyle_common",
  "selectors": {
    "parks": {
      "replace": false,
      "values": [
        { "factor": 0.5, "value": "urbexmt:park_cherry_blossom" }
      ]
    }
  }
}
```

That city style has the eight parks `urbex:citystyle_common` declares plus one more. **Appended
entries follow the parent's**, so adding children never reorders what the parent wrote. A bare
array is equivalent to `"replace": true`.

Every ordered-list field takes both forms, including the part-wiring fields, which additionally
accept a single string as shorthand for a one-element list. These three are the same value:

<!-- example: none -->
```json
"straight": "urbexmt:street_straight"
"straight": ["urbexmt:street_straight"]
"straight": { "replace": true, "values": ["urbexmt:street_straight"] }
```

### Keyed collections: palettes merge per character

A palette is addressed by character, so it merges by character. Overriding two markers out of
thirty leaves the other twenty-eight exactly as they were:

<!-- example: palettes -->
```json
{
  "extends": "urbex:bricks_standard",
  "palette": [
    { "char": "#", "variant": "urbexmt:concrete" },
    { "char": "X", "block": "minecraft:cracked_stone_bricks", "damaged": "minecraft:iron_bars" }
  ]
}
```

`urbex:bricks_standard` declares four characters — `X`, `$`, `#` and `}`. This palette redefines
two of them and keeps `$` and `}` untouched. Replacing wholesale would have silently dropped
everything the child did not restate; appending would have registered `#` twice.

An overridden entry takes its `damaged` mapping with it, so a character you repaint does not keep
its ancestor's rubble block.

## Palettes, `refpalette`, and inline palettes

There are three ways a part or building gets a palette, and they are not layers of the same thing:

- **`refpalette`** names a registered palette. It is a scalar: the nearest ancestor that declares
  one wins.
- **An inline `palette` block** defines characters in the part or building file itself. Inline
  blocks merge by character along the owner's `extends` chain, exactly as a registered palette
  does — so a part that extends another and inlines two characters keeps the rest of its
  ancestor's.
- Naming a `refpalette` and inlining a `palette` are a *choice*, not a stack. Whichever the later
  chain entry declares drops the other; within a single file that declares both, the inline block
  wins and the `refpalette` is ignored.

`extends` inside an inline `palette` block is rejected at load. The key decodes — an inline block
uses the same format a registered palette does — but an inline block is not a registry entry, so
nothing can resolve the link, and silently ignoring a key the format accepted is exactly how a
datapack ends up meaning something other than what it says:

```
The inline palette in 'urbexmt:tower' declares extends 'urbex:common', but an inline palette is not
a registry entry and nothing can resolve that. Use 'refpalette' to build on a registered palette, or
put 'extends' on 'urbexmt:tower' itself.
```

Those two suggestions are the whole answer. To share a palette, register it and name it with
`refpalette`; to build on one asset's palette, put `extends` on the asset.

## Parts: geometry inherited, paint overridden

`refpalette` says what a part is *painted with*. `extends` says what a part *is*. They compose, and
the combination is the reason `extends` is on parts at all:

<!-- example: parts -->
```json
{
  "extends": "urbex:radiotower",
  "refpalette": "urbexmt:radiotower_rusted"
}
```

That is a complete part file. It is the radio tower — every slice, both dimensions — repainted.
Neither key alone expresses it, and neither does copying the tower's 437 lines of slices.

`xsize`, `zsize` and `slices` are each inherited from the nearest ancestor that declares them,
independently. Declaring `slices` replaces the inherited ones wholesale; a grid is not a list you
append rows to.

Because the three are independent, redeclaring a size without redeclaring the slices is a
contradiction, and it is a load error rather than a silent truncation:

```
Part 'urbexmt:tower' declares xsize 8 and zsize 16 but its slices are 16 wide
(level 0 holds 256 of 128 characters)
```

A part written from scratch declares all three. Each level of `slices` is an array of `zsize`
strings of `xsize` characters:

<!-- example: parts -->
```json
{
  "xsize": 4,
  "zsize": 4,
  "refpalette": "urbexmt:concrete",
  "slices": [
    ["####", "#  #", "#  #", "####"],
    ["####", "#  #", "#  #", "####"]
  ]
}
```

## Every reference is fully qualified

A reference without a namespace is an error, in every field of every registry, `extends` included:

```
Unqualified datapack reference 'street_straight': references must name their namespace,
e.g. 'urbex:street_straight'
```

There is no default namespace. There used to be two of them — most fields assumed `urbex:`, while
a preset's `parent` went through vanilla's identifier codec and assumed `minecraft:` — which is
two silent ways to resolve a name the author never wrote.

**When the error fires depends on the field.** `extends` is checked when the file is decoded, so a
bare `extends` refuses the datapack immediately. Every other reference is checked when it is
resolved:

- References the load-time sweep reaches — a world style's `citystyles`, a preset's
  `cities.cityStyleAlternative`, a predefined city's `citystyle` — fail the world load, and the
  message names what pulled the asset in:
  `City style 'downtown', selected by 'urbexmt:modern', cannot be used: Unqualified datapack
  reference 'downtown': ...`
- References generation resolves lazily — a part's `refpalette`, the part ids inside street,
  highway and railway wiring, the building ids inside a city style's selectors — are checked the
  first time a chunk needs them. A bare name there is an exception during generation, not at load.

The bundled pack is fully qualified and a test keeps it that way. Qualify everything in yours from
the start; the failure mode of not doing so is late.

A **qualified but missing** id is a different error, and the two part-lookup paths differ. Street
parts warn and skip — `Cannot find 'urbexmt:street_t' in urbex:parts!`, and that chunk generates
without a street part. Highway and railway parts throw: `Can't find 'urbexmt:highway_open' in
urbex:parts!`.

## Wiring must be declared somewhere in the chain

Thirty part-wiring fields used to carry a bare asset name as a fallback in Java. A world style that
never mentioned primary roads still generated Urbex's own wide-road parts; a pack that defined its
own city silently got Urbex's railways running through it. All thirty defaults are gone. The fields
are required — of the chain, not of the file.

For a **world style**, that means all six highway components and all sixteen railway components:

<!-- example: worldstyles -->
```json
{
  "extends": "urbex:standard",
  "citystyles": [
    { "factor": 1.0, "citystyle": "urbexmt:downtown" }
  ],
  "parts": {
    "highways": {
      "bridge": ["urbexmt:highway_bridge"],
      "bridge_bi": ["urbexmt:highway_bridge_bi"]
    }
  }
}
```

That world style replaces the city-style selectors and two of the six highway components, and
inherits the other four plus all sixteen railway components from `urbex:standard`. Highway and
railway wiring merges **per component**, so overriding one does not oblige you to restate the rest.

For a **city style**, `streetblocks.parts` — the secondary-road family — is required in full: all
eight of `straight`, `end`, `bend`, `t`, `none`, `all`, `connector`, `stair`. Here it is written out
in a chain root — a file that exists to be extended, the way the bundled `urbex:citystyle_config`
does — so that the styles which extend it need say nothing about roads at all:

<!-- example: citystyles -->
```json
{
  "streetblocks": {
    "width": 8,
    "street": "S",
    "streetbase": "b",
    "border": "y",
    "parts": {
      "straight": ["urbexmt:street_straight"],
      "end": ["urbexmt:street_end"],
      "bend": ["urbexmt:street_bend"],
      "t": ["urbexmt:street_t"],
      "none": ["urbexmt:street_none"],
      "all": ["urbexmt:street_all"],
      "connector": [],
      "stair": []
    }
  }
}
```

### `largeparts` and `tertiaryparts`: all or nothing

`largeparts` (primary roads) and `tertiaryparts` (tertiary roads) are optional **as blocks**. A
city style whose chain declares neither draws primary and tertiary roads from `parts`, the
secondary family. That is a fallback to parts *the pack itself wrote*, not to a name written in
Java, which is why it survived the deletion of the other thirty.

The fallback is per block, and **requiredness is per component**. So the moment anything in your
chain declares one component of `largeparts`, the whole family is on, and all eight components must
come from somewhere in the chain:

```
'urbexmt:downtown' declares no 'streetblocks.largeparts.connector', and neither does anything it
extends
```

This is deliberate, and the reason is worth stating: a per-component fallback would mean
`largeparts.connector` silently resolving to `parts.connector` — a cross-field implicit resolution,
which is precisely the class of behaviour the rest of this document exists to remove. Half a
declared family reaching generation as a null list is the failure it prevents.

If restating the other seven is genuinely painful, the escape is explicit, not implicit: declare
the full family once in a chain root and `extends` it from both styles that need it. Nothing here
forbids sharing the block — it forbids *inferring* it.

### An empty list is an opt-out — for streets only

The three street families guard emptiness at generation time. `"stair": []` is a city style saying
it has no slope part, and slopes are simply not placed; `"connector": []` opts out of the overlay
where a minor street meets a primary. Both are choices, and both are how the example above is
legal.

**Highway and railway lists have no such guard.** An empty `"tunnel": []` satisfies the load-time
check — the field is declared, so it is not null — and then crashes the moment generation reaches a
highway tunnel. If you do not want a component, you still have to give it a part.

## Blocks that do not exist become air

A block id in a palette that this Minecraft version does not know **resolves to air**, with one
warning per id:

```
Block 'minecraft:chain' (in urbex:chains) does not exist in this Minecraft version; it will
generate as air. It was most likely renamed - check the current id and update the asset.
```

This is worth saying plainly because it is the one place a typo does *not* fail the load. A
misspelled block id shows up as missing blocks in the world, and only the log says why. That is how
`minecraft:chain` — renamed `minecraft:iron_chain` in 26.x — made a whole decoration invisible
without anyone noticing.

Two details about that warning:

- It names the **owning asset id**, not the file it came from. For a palette written inline in a
  part or building, the id it names is the synthetic `urbex:__local__<path>` the inline block is
  registered under — for a part `urbexmt:tower` that reads `urbex:__local__tower`. Close enough to
  find; not a filename, and note that the synthetic id is always in the `urbex` namespace whatever
  the owner's is.
- It only covers the plain form. A blockstate string — anything containing `[` — is parsed
  strictly, so `minecraft:nosuchblock[facing=north]` is a hard failure rather than air.

## Common errors

| Message | What happened | Fix |
|---|---|---|
| `Unqualified datapack reference 'x': references must name their namespace, e.g. 'urbex:x'` | A reference with no `:` | Qualify it. There is no default namespace |
| `'extends' cycle: urbexmt:a -> urbexmt:b -> urbexmt:a` | An `extends` chain loops | Break the loop; the whole chain is printed |
| `Unknown asset 'urbexmt:missing' (referenced from 'urbexmt:child')` | `extends` names an id nothing provides | Check the id, the registry directory, and that the providing pack is enabled |
| `'urbexmt:x' declares no 'streetblocks.parts.stair', and neither does anything it extends` | A required field is declared nowhere in the chain | Declare it in this file, or in something it extends |
| `'urbexmt:x' declares no 'parts.railways.railsbend', ...` | A world style's chain never wired railways | Wire all sixteen, or `extends` a world style that does |
| `'urbexmt:x' declares no 'streetblocks.largeparts.connector', ...` | The primary-road family was partly declared | Declare all eight components, or none at all and inherit `parts` |
| `Part 'urbexmt:tower' declares no slices, and neither does anything it extends` | A part with no geometry anywhere in its chain | Add `slices`, or `extends` a part that has them |
| `Part 'urbexmt:tower' declares xsize 8 and zsize 16 but its slices are 16 wide (...)` | A size was redeclared without the matching slices | Declare both together, or neither |
| `The inline palette in 'urbexmt:tower' declares extends '...'` | `extends` inside an inline `palette` block | Use `refpalette`, or put `extends` on the owning asset |
| `Illegal palette urbex:x!` | A palette entry names no `block`, `variant`, `blocks`, `frompalette` or `light` | Give the character something to resolve to |
| `Can't find 'urbexmt:p' in urbex:parts!` | A highway or railway part id resolves to nothing | Check the id; this one throws rather than degrading |
| `Cannot find 'urbexmt:p' in urbex:parts!` (WARN) | A street part id resolves to nothing | Same, but generation continues without the part |
| `Block 'minecraft:chain' (in ...) does not exist ...; it will generate as air` | A renamed or misspelled block id | Update the id; nothing else will tell you |
| `Error getting resource urbexmt:x!` | The wrapper around any of the above, naming the asset being resolved | Read the cause below it |

## A working example of each registry

Each of these is a complete, loadable file. Together they are a sketch of a pack that builds on the
bundled one throughout rather than forking it.

A **world style** — the entry point, and the only asset a player selects directly:

<!-- example: worldstyles -->
```json
{
  "extends": "urbex:standard",
  "citystyles": [
    { "factor": 1.0, "citystyle": "urbexmt:downtown" }
  ]
}
```

A **city style**, appending one building to what it inherits rather than restating the list:

<!-- example: citystyles -->
```json
{
  "extends": "urbex:citystyle_common",
  "style": "urbexmt:modern",
  "buildingsettings": {
    "maxfloors": 12,
    "buildingchance": 0.8
  },
  "selectors": {
    "buildings": {
      "replace": false,
      "values": [
        { "factor": 0.4, "value": "urbexmt:office_tower" }
      ]
    }
  }
}
```

A **style**, adding a palette group to the ones a building can be painted from:

<!-- example: styles -->
```json
{
  "extends": "urbex:standard",
  "randompalettes": {
    "replace": false,
    "values": [
      [
        { "factor": 1.0, "palette": "urbexmt:concrete_gray" },
        { "factor": 1.0, "palette": "urbexmt:concrete_white" }
      ]
    ]
  }
}
```

A **palette**, redefining two characters of a registered one:

<!-- example: palettes -->
```json
{
  "extends": "urbex:bricks_standard",
  "palette": [
    { "char": "#", "variant": "urbexmt:concrete" },
    { "char": "}", "variant": "urbexmt:concrete_rubble" }
  ]
}
```

A **variant** — the weighted blockstates one palette character can land on:

<!-- example: variants -->
```json
{
  "blocks": [
    { "random": 1000, "block": "minecraft:smooth_stone" },
    { "random": 40, "block": "minecraft:cracked_stone_bricks" },
    { "random": 10, "block": "minecraft:mossy_stone_bricks" }
  ]
}
```

A **part**, repainted from an existing one:

<!-- example: parts -->
```json
{
  "extends": "urbex:building1_1",
  "refpalette": "urbexmt:concrete"
}
```

A **building**, extending an existing stack and swapping its top:

<!-- example: buildings -->
```json
{
  "extends": "urbex:building1",
  "refpalette": "urbexmt:concrete",
  "maxfloors": 12,
  "parts": {
    "replace": false,
    "values": [
      { "part": "urbexmt:office_roof", "top": true }
    ]
  }
}
```

A **multibuilding** — a 2×2 grid, replacing the one it inherits:

<!-- example: multibuildings -->
```json
{
  "extends": "urbex:multi1",
  "buildings": [
    ["urbexmt:office_tower", "urbexmt:office_tower"],
    ["urbexmt:office_tower", "urbexmt:plaza"]
  ]
}
```

A **scattered** building, swapping what an existing one places while keeping how it sits on the
terrain:

<!-- example: scattered -->
```json
{
  "extends": "urbex:radiotower",
  "buildings": ["urbexmt:radiotower_modern"]
}
```

A **condition**, adding one loot table to the ones a chest can roll:

<!-- example: conditions -->
```json
{
  "extends": "urbex:chestloot",
  "values": {
    "replace": false,
    "values": [
      { "factor": 4, "value": "urbexmt:chests/office" }
    ]
  }
}
```

A **stuff** entry — the same decoration as an existing one, rarer:

<!-- example: stuff -->
```json
{
  "extends": "urbex:cobweb",
  "mincount": 1,
  "maxcount": 3
}
```

A **predefined city** — the same city as another, somewhere else. This is what makes every scalar
optional worth having: the whole file is `extends` plus two coordinates.

<!-- example: predefinedcities -->
```json
{
  "extends": "urbexmt:capital",
  "chunkx": 400,
  "chunkz": -120
}
```

A **preset**, naming a city style for the sparse edges of a city — see
[`docs/presets.md`](presets.md) for the rest of the format:

<!-- example: presets -->
```json
{
  "extends": "urbex:default",
  "description": "Modern cities",
  "cities": {
    "cityStyleAlternative": "urbexmt:downtown_border"
  }
}
```

## Seeing what a chain actually resolves to

Because a file states only what it changes, a file on its own does not tell you the effective
values. For presets, `/urbex savepreset` writes the fully resolved preset for your current
dimension to disk; [`docs/presets.md`](presets.md) covers it. The other twelve registries have no
equivalent export yet — for those, the load errors are the feedback loop, which is why they are
worded to name the asset and the field rather than the file.
