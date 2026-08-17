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
| `worldstyles` | The top of the tree: which city styles apply in which biomes, the highway and railway wiring, what gets scattered outside cities, and which blocks rotate with their part | `outsidestyle`, `citystyles`, `parts.highways` (all six), `parts.railways` (all sixteen) — `rotatable` is optional |
| `citystyles` | What a city is made of: street materials and street parts, plus weighted selectors for buildings, parks, bridges, fountains, stairs | `streetblocks.parts` (all eight) |
| `buildings` | An ordered stack of parts, with floor and cellar limits | `filler`, `parts` |
| `parts` | A block of geometry, up to 16×16, written as character slices | `xsize`, `zsize`, `slices` |
| `palettes` | What each character in a slice means: a block, a variant, loot, a mob, a light | `palette` |
| `styles` | Groups of palettes a building can be painted from | `randompalettes` |
| `multibuildings` | A grid of buildings spanning several chunks | `dimx`, `dimz`, `buildings` |
| `scattered` | A building placed outside cities, and how it sits on the terrain | `terrainheight`, `terrainfix` |
| `conditions` | A weighted, conditional set of values — loot tables, mob ids | `values` |
| `variants` | A weighted set of blockstates behind one palette character | `blocks` |
| `stuff` | A small decoration pass: cobwebs, chains, rubble | `column`, `mincount`, `maxcount`, `attempts`, `inbuilding` |
| `predefinedcities` | A city pinned to fixed chunk coordinates | `dimension`, `chunkx`, `chunkz`, `radius` — `citystyle` is optional |
| `presets` | Per-dimension worldgen tuning — see [`docs/presets.md`](presets.md) | *(nothing)* |

Every one of them accepts `extends`, and every one of them merges by the same rules. That
uniformity is deliberate: you should never have to look up whether *this* asset type supports
extension.

Three of them — `presets`, `worldstyles` and `citystyles` — also accept an optional `name`. See
[Display names](#display-names) below.

The last column is what the load-time check enforces, and it is not quite the same as "what a
working asset needs". Three city-style fields are needed but unchecked: `style`, which names the
`styles` entry its buildings are painted from, and the `streetblocks` characters `street` and
`border`. A selected city style whose chain declares none of them loads without complaint and then
throws the first time a chunk needs one — `style` when a building asks for its palette, `street`
when the chunk is set up, `border` when room is cleared for a building.

Treat all three as required. Extending `urbex:citystyle_common` covers `street` and `border`; it
leaves `style` to its children, which is why `urbex:citystyle_standard` is little more than an
`extends` and a `style`.

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

### Sharing a world with another pack

A player who turns on `experimentalMultiWorldStyles` can pick **several** world styles at once,
weighted — your pack's cities and another's in one world. That does not change how you author a
world style, but it does change which of its blocks are yours to control, because the blocks do not
all have the same scope:

| Block | Under a mix |
|---|---|
| `citystyles` | **Per city.** Each city centre draws a world style and keeps it, so a city built from your pack is entirely yours. |
| `outsidestyle`, `rotatable` | **Per chunk**, from the nearest city — a chunk on your city's edge takes your outside style and your rotatable tag. |
| `scattered` | **Per scatter area**, so your scattered structures appear alongside the other pack's. |
| `multisettings` | Per multichunk area, except `areasize`, which defines the grid and so comes from the heaviest style. |
| `parts.highways`, `parts.railways`, `settings`, `citybiomemultipliers` | **From the heaviest style only.** A highway runs for hundreds of chunks between cities; one that changed pack partway along its run would not join up. |

The practical consequence: a pack that is not the heaviest in a mix will not see its highway or
railway parts used. Nothing about that is worth designing around — author the world style as if it
were the only one, and it will be, for every city it owns.

## City-style families and optional edges

A `worldstyles[].citystyles` entry selects a **family** for a city. Its `factor` is the
weighted-choice weight among the selector entries whose `biomes` match; it is not the distance into
a city and it does not set the edge boundary. A selector with no `edge` is base-only:

<!-- example: worldstyles -->
```json
{
  "citystyles": [
    {
      "factor": 1.0,
      "citystyle": "urbexmt:downtown"
    }
  ]
}
```

To give that family a sparse edge, nest a complete `edge` object in the selector that owns it:

<!-- example: worldstyles -->
```json
{
  "citystyles": [
    {
      "factor": 1.0,
      "citystyle": "urbexmt:downtown",
      "edge": {
        "citystyle": "urbexmt:downtown_border",
        "threshold": 0.4
      }
    }
  ]
}
```

`edge.threshold` is a spatial city-factor boundary. Urbex uses the edge when
`cityFactor < threshold`; equality stays with the base. In the example, factors below `0.4` use
`urbexmt:downtown_border`, while `0.4` and above use `urbexmt:downtown`. The threshold must be
finite and in `(0, 1]`.

The family is chosen once at its anchor, then its base or edge is chosen from each chunk's local
city factor. With ordinary centred cities, the anchor is the city centre: the world style, weighted
selector draw, and `biomes` match all use the centre's chunk and biome, not the observer chunk's.
With a Perlin-rarity preset (`cityChance < 0`), there is no city centre, so the anchor is the
minimum chunk coordinate of the containing 16-by-16 region (using floor division in every
quadrant). All chunks in that region share the family draw, and its selector `biomes` match is made
against that anchor biome; their local Perlin factors can still independently choose base or edge.

An edge therefore applies with every preset that uses the world style, and omitting `edge` means
base-only with every preset. A predefined city that explicitly supplies `citystyle` is also
base-only: that field names an exact style. Leave `citystyle` out of a predefined city to let the
ordinary world-style family, including its edge, apply there. Future typed districts may add a
sibling field to `edge`; no district syntax exists today.

### Breaking migration from preset alternatives

`cities.cityStyleAlternative` and `cities.cityStyleThreshold` were removed from presets. Move their
meaning to every relevant world-style selector. The removed preset fragment and its world-style
replacement are both valid JSON snippets, but only the latter is supported datapack syntax:

<!-- example: none -->
```json
{
  "cities": {
    "cityStyleAlternative": "urbexmt:downtown_border",
    "cityStyleThreshold": 0.4
  }
}
```

<!-- example: worldstyles -->
```json
{
  "citystyles": [
    {
      "factor": 1.0,
      "citystyle": "urbexmt:downtown",
      "edge": {
        "citystyle": "urbexmt:downtown_border",
        "threshold": 0.4
      }
    }
  ]
}
```

Repeat the nested edge for every family that should have one. This makes the owner explicit: a
selector's edge travels with that selector under every preset instead of one preset replacing all
world styles' city edges.

### Do not place the `urbex:city` feature

Urbex generates each chunk once, at the end of the carver stage, without any feature placement. You
do not need to place anything, and you should not: **placing `urbex:city` in a biome's `features` is
refused**, with one warning per dimension in the log. It used to generate the chunk a second time,
planning against terrain the first pass had already rewritten.

The feature exists for one case: a **custom chunk generator** that is neither
`minecraft:noise` nor `minecraft:flat` (nor a mod generator extending one of those classes). Urbex
has no hook into such a generator, so a world using one gets no cities at all unless the pack places
`urbex:city` itself. That is what the feature is for, and it is the supported way in.

Placing it costs one guarantee. Features run at the decoration stage, where a neighbouring chunk's
own feature pass may or may not have run yet, so what Urbex reads from the terrain depends on how the
server scheduled its worker threads — the same world can generate differently twice. The carver-stage
path has no such problem. If your world runs on a vanilla generator, leave the feature alone.

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

A cycle, or an `extends` naming an id nothing provides, is an error that prints the chain:

```
'extends' cycle: urbexmt:a -> urbexmt:b -> urbexmt:a
Unknown asset 'urbexmt:missing' (referenced from 'urbexmt:downtown')
```

It is raised when the chain is *resolved*, which for most registries is world load — see
[When each asset is resolved](#when-each-asset-is-resolved) below for the three where it is not.

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

### `rotatable`: which blocks turn with their part

A part placed at a rotation only turns the blocks named by a block tag; everything else keeps the
facing its palette entry authored. That tag is `urbex:rotatable` unless the world style names
another:

<!-- example: worldstyles -->

```json
{
  "outsidestyle": "urbex:outside",
  "rotatable": "#mypack:rotatable"
}
```

Written with the leading `#`, like every other tag reference, and fully qualified — `#rotatable` is
a load error, not `minecraft:rotatable`. Like `outsidestyle`, it is a scalar: the last entry in the
chain that declares one wins, and a chain declaring none resolves `urbex:rotatable`.

**Declaring it replaces; it does not merge.** To keep Urbex's own set, name it from your own tag —
this one is an ordinary Minecraft block tag at `data/mypack/tags/block/rotatable.json`, not a
registry asset:

<!-- example: none -->

```json
{
  "values": [
    "#urbex:rotatable",
    "#minecraft:trapdoors",
    "minecraft:ladder"
  ]
}
```

The alternative is shipping `data/urbex/tags/block/rotatable.json`, which merges into
`urbex:rotatable` itself and therefore changes **every** world style including `urbex:standard`,
whether or not the player selected yours. That is why this field exists: a pack that needs banners
or trapdoors to rotate should be able to say so without reaching into Urbex's namespace.

## Display names

`presets`, `worldstyles` and `citystyles` accept an optional top-level `name`: the human label the
game shows in place of the id.

<!-- example: worldstyles -->

```json
{
  "name": "Modern Tweaks",
  "outsidestyle": "urbex:outside"
}
```

Plain text, not an id and not a translation key — anything you'd want a player to read. Without it
the Cities tab and the world-style picker show the fully-qualified id, which is what they showed
before the field existed. That fallback is why `name` is optional rather than required: an unnamed
asset is drab, not broken.

Set one on **everything a player picks from**. The picker shows the name over the id, so two packs
naming a style "Standard" still tell each other apart there — but a preset list shows the name
alone, and two identical rows is a real problem.

**It is inherited, like every other scalar: the last entry in the chain that declares one wins.**
That is the trap. A world style extending `urbex:standard` without a `name` of its own is listed as
**Standard**, because that is the name Urbex's own file declares. Restate it. For the same reason,
leave `name` **off** the abstract bases meant to be extended (`urbex:citystyle_common`,
`urbex:citystyle_config`): a base with no name lets an unnamed child fall back to its own id, which
is wrong but at least unique, instead of borrowing a label that belongs to something else.

### When each asset is resolved

In **eleven** registries the check above runs on every registered asset, whether or not anything
selects it. Loading a world resolves all of `variants`, `palettes`, `conditions`, `styles`, `parts`,
`buildings`, `multibuildings`, `scattered`, `worldstyles`, `predefinedcities` and `stuff` up front in
`AssetCompiler`, so a broken file in your pack fails the world even for a player who never picks your
world style. That is the intended trade: a load error naming the file beats an exception from a
worldgen worker thread three hours into someone's save.

It has one consequence for how you write bases. In those eleven registries an **incomplete** chain root
is not allowed — a "base part" that carries only a `refpalette`, meaning to be completed by
children, fails the load on its own. Bases there have to be complete assets that children
specialise.

The other two registries are each resolved differently, and it is worth knowing which you are in:

- **`citystyles` is resolved by reachability.** Only city styles something can actually *select* are
  resolved: the base and optional edge styles named by a world style's `citystyles` selectors, and
  a predefined city's `citystyle`. A city style nothing names is never resolved and never validated
  — which is what lets the bundled `urbex:citystyle_config` exist as a street width and nothing else,
  complete only through `urbex:citystyle_common`, which extends it. It is the one registry where an
  incomplete chain root is legal.
- **`presets`** have no required fields at all, so there is nothing for this check to do. A preset
  resolves when it is selected.

A predefined city's optional `citystyle` is also added to the reachable city-style roots during
that eager compilation. An explicit style is base-only; omission uses the matching world-style
family instead.

Everything a resolution can raise — a required field nothing declared, a cycle, a dangling
`extends` — surfaces at whichever of those moments applies to the registry.

## The three merge shapes

When a file and its ancestor both declare something, what happens is decided by the **shape of the
field**, not by which registry it belongs to. There are exactly three shapes:

| Shape | Rule | Examples |
|---|---|---|
| Scalar | The child wins when it declares the field; otherwise the ancestor's value stands | `style`, `outsidestyle`, `buildingchance`, `xsize`, `refpalette`, `filler` |
| Mergeable list | The child **replaces** by default; appending is opt-in | `selectors.buildings`, `citystyles`, `streetblocks.parts.straight`, `parts.highways.tunnel`, `randompalettes`, `parts` on a building, `values` on a condition |
| Keyed collection | Merged by key; the child's entry wins for the keys it declares | `palette` (keyed by `char`) |

Three shapes rather than one per asset type is what makes this learnable — but the shape is a
property of the *field*, not of the JSON you can see, and four fields do not follow it. Read the
next section before assuming.

### The exceptions, in full

**Four list fields are plain lists, not mergeable ones.** They replace what the chain inherited and
have no append form at all — `{"replace": false, "values": [...]}` there is a decode failure, not an
append:

| Field | Registry | Behaviour |
|---|---|---|
| `slices` | `parts` | Replaces. A grid is not a list you append rows to |
| `buildings` | `multibuildings` | Replaces. Appending rows would contradict `dimx`/`dimz` |
| `scattered.list` | `worldstyles` | Replaces — and see below, it is rarely reached on its own |
| `stuff_tags` | `citystyles` | **Unions.** See below |

`stuff_tags` is a fourth behaviour rather than a third: a city style's tags are accumulated into a
set, so a child's list is always added to what it inherits. It cannot replace, and it cannot remove
a tag an ancestor declared. (`all` is added to every city style regardless.)

**Three blocks are scalars even though they look like objects to merge into.** Most nested blocks —
`streetblocks`, `buildingsettings`, `parkblocks`, `corridorblocks`, `railblocks`, `generalblocks` on
a city style, and `parts` on a world style — are merged field by field, so overriding one number in
one of them leaves the rest alone. These three are not:

| Block | Registry | Behaviour |
|---|---|---|
| `scattered` | `worldstyles` | Replaced wholesale. A child that touches it must restate `areasize`, `chance`, `weightnone` and the whole `list` — all four are required by the block's own codec |
| `multisettings` | `worldstyles` | Replaced wholesale; `areasize`, `minimum` and `maximum` are required by the block |
| `settings` | `worldstyles` | Replaced wholesale; `railwayavoidance` is required by the block |

Those three are the one place in the format where "state only what you change" does not hold, and
they are the only three settings blocks whose own codec has required fields — every other block is
optional all the way down. That is the tell: a block that refuses to decode when you leave a field
out is a block you have to restate in full.

### Scalars: child wins when present

"When present" is doing real work. A chain entry that omits a scalar leaves whatever an earlier
entry set — it does not blank it out. And "present" is read from the key being there, not from a
sentinel value, so a child *can* set `preferslonely` back to `0.0` or `maxfloors` back to `-1`
against an ancestor that set something else.

### Mergeable lists: replace by default, append on request

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

Every *mergeable* list field takes both forms — the four plain lists in the table above take
neither. The part-wiring fields additionally accept a single string as shorthand for a one-element
list, so these three are the same value:

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

There are two ways a part or building gets a palette, and they are alternatives rather than layers:

- **`refpalette`** names a registered palette. It is a scalar: the nearest ancestor that declares
  one wins.
- **An inline `palette` block** defines characters in the part or building file itself. Inline
  blocks merge by character along the owner's `extends` chain, exactly as a registered palette
  does — so a part that extends another and inlines two characters keeps the rest of its
  ancestor's.

They do not stack. Whichever the later chain entry declares drops the other, so an inline block
anywhere below a `refpalette` discards it and vice versa; and within a single file that declares
both, the inline block wins and the `refpalette` is ignored.

`extends` inside an inline `palette` block is rejected at load. The key decodes — an inline block
uses the same format a registered palette does — but an inline block is not a registry entry, so
nothing can resolve the link, and silently ignoring a key the format accepted is exactly how a
datapack ends up meaning something other than what it says:

```
'urbexmt:tower': the inline palette declares 'extends' 'urbex:common', but an inline palette is not
a registry entry and nothing can resolve that. Use 'refpalette', or put 'extends' on 'urbexmt:tower'
itself.
```

Those two suggestions are the whole answer. To share a palette, register it and name it with
`refpalette`; to build on one asset's palette, put `extends` on the asset.


## Lighting: `lightSource`

A light is a property you state about a block, not a separate kind of palette entry. Any entry can
carry `lightSource`, and doing so puts that entry under the preset's `lightingDensity`: the roll
happens once per marker position, and the marker writes either its light or the **replacement** the
entry names. Nothing is ever filtered out of the output.

This is what makes the setting reach a pack's real lighting. A pack that authors its street lamps as
ordinary lantern entries — which is how most packs author them — used to have every one of them
placed unconditionally, so moving the slider changed nothing visible.

### In place: this block is a light

<!-- example: palettes -->
```json
{
  "palette": [
    { "char": "e", "block": "minecraft:lantern[hanging=false]", "lightSource": true }
  ]
}
```

<!-- example: palettes -->
```json
{
  "palette": [
    {
      "char": "E",
      "block": "minecraft:lantern[hanging=true]",
      "lightSource": { "unlit": "minecraft:iron_chain[axis=y]" }
    }
  ]
}
```

The entry's own `block`, `blocks`, `variant` or `frompalette` is the lit block, written exactly where
and as you wrote it — no support search, no reorientation. When the roll rejects it, `unlit` is
written instead; `unlitBlocks` takes a weighted list in the same shape as `blocks`. Name neither and
the replacement is air, which is what an unlit marker has always left behind.

`"lightSource": true` is shorthand for `{}`. `"lightSource": false` is a load error: omitting the
field is how you say "not a light", and a field that can be present and mean nothing is how a pack
ends up meaning something other than what its author read.

### A socket: let Urbex pick and orient one

<!-- example: palettes -->
```json
{
  "palette": [
    {
      "char": "T",
      "lightSource": {
        "floor": [
          { "weight": 6, "block": "minecraft:lantern[hanging=false]" },
          { "weight": 3, "block": "minecraft:torch", "unlit": "minecraft:candle[candles=1,lit=false]" }
        ],
        "wall":    [ { "weight": 8, "block": "minecraft:wall_torch[facing=north]" } ],
        "ceiling": [ { "weight": 8, "block": "minecraft:lantern[hanging=true]",
                       "unlit": "minecraft:iron_chain[axis=y]" } ],
        "free":    [ { "weight": 1, "block": "minecraft:sea_lantern" } ]
      }
    }
  ]
}
```

Declaring any of `floor`, `wall`, `ceiling` or `free` makes the entry a socket: the pool is its block
source, so it needs no block of its own. Placement is deferred until the chunk is assembled, and the
opportunities are tried in one fixed order — floor, then west, east, north and south wall, then
ceiling, then `free`, which needs no anchor at all. The chosen candidate is oriented toward its
support, so one `wall_torch[facing=north]` covers all four walls.

A candidate's `unlit` is its own, because an unlit torch on a wall and an unlit torch on a floor are
two different blocks — one replacement for the whole socket could be right for at most one of its
placements. A candidate that names none falls back to the source's `unlit`, then to air. An `unlit`
that emits light is a load error.

Both passes draw from one stream at one position, so the fixture a marker would light is the fixture
standing there while it is dark: raising `lightingDensity` lights the candle that was already on that
floor rather than moving the light somewhere else.

### What is not a light

Nothing is a light unless its entry says so. There is no tag, and no list of block ids: a redstone
torch wired into a door, a brewing stand on a workstation, glow lichen on a wall and a campfire that
was authored `lit=false` are all ordinary blocks, and `lightingDensity` cannot add, remove or reroll
them. That was the point of removing `urbex:lights` — a tag cannot tell a lamp from a lit thing.

### Parks: a lamp on generated ground

A park section is not a part. Urbex generates that surface itself, so there are no slices to write a
light into and, until `lamp`, no lighting density that could reach one — a city's parks were dark at
every setting, whatever the pack said.

<!-- example: citystyles -->
```json
{
  "parkblocks": {
    "grass": "g",
    "lamp": "T",
    "lampspacing": 8
  }
}
```

`lamp` names a character in the style's palette; it is placed one block above the grass on a grid of
`lampspacing` blocks (default 8, range 1-16). The grid is keyed on world coordinates, so lamps line
up across a park spanning several chunks rather than restarting at each seam, and nothing is placed
on the border ring, where a lamp would sit half in the street.

The character goes through the same path as a marker in a part: give it `lightSource` and the park
lamps obey `lightingDensity` like every other light, leaving their `unlit` replacement behind when
the roll rejects them. Name a plain block instead and it is simply always placed. A `lamp` character
the palette does not map is a load-time error rather than a park that is dark for no visible reason.

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

- References the load-time sweep reaches — a world style's base and edge `citystyles`, and a
  predefined city's `citystyle` — fail the world load, and the message names what pulled the asset
  in:
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

The fallback is per block, and **requiredness is per component**. What arms it is the *block* being
present anywhere in the chain — one component is enough, and so is an empty `"largeparts": {}`.
Once it is armed all eight components must come from somewhere in the chain:

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

## Two typos that do not fail the load

Nearly everything wrong in a datapack refuses to load and names itself. These two do not, and
between them they account for most of the time you will spend wondering why a change did nothing.

### A misspelled key is ignored

The codecs read the keys they know and **silently ignore every other key in the object**. So
`"styl"` for `"style"`, `"maxfloor"` for `"maxfloors"`, `"varient"` for `"variant"` all load
cleanly and do nothing at all. Nothing is logged, nothing fails, and the asset simply behaves as
though you had not written the line.

This is why the field names in this guide are worth copying exactly, and why a change that appears
to have no effect is worth spell-checking before it is worth debugging. It also cuts the other way,
usefully: a `"_comment"` key — or any other `_`-prefixed note — is ignored everywhere, so you can
annotate files freely.

`presets` are the one exception. Their codec checks its keys and logs one `WARN` naming the bad
key and the section it was in, which is the behaviour [`docs/presets.md`](presets.md) documents.
The other twelve registries have no such check.

**Two keys are the exception to the exception, in all thirteen registries: `inherit` and
`parent`.** Urbex's inheritance key is `extends` and only `extends`; the other two were deleted, not
aliased. Because they would otherwise be ignored like any other unknown key, a file using one would
load as a chain root with no inheritance at all — either quietly generating without what it meant to
inherit, or failing later with a message about a missing field that names neither key. So they are
rejected outright, naming the key and its replacement. This matters mostly if you are converting a
Lost Cities Modern Tweaks pack, where `inherit` *is* the key.

One more key is rejected rather than ignored, in `scattered` only: **`rotatable`**. It was read and
then thrown away — a scattered building always generates unrotated — so a pack that set it got
exactly the world it would have got without it. Remove the key; nothing replaces it yet.

The examples in this guide are machine-checked against the codecs for exactly this: each is decoded
and then re-encoded, and a key that does not survive the round trip fails the build. A field name
you read here is one the mod actually reads.

### An unknown block id becomes air

A block id in a palette that this Minecraft version does not know **resolves to air**, with one
warning per id:

```
Block 'minecraft:chain' (in urbex:common) does not exist in this Minecraft version; it will
generate as air. It was most likely renamed - check the current id and update the asset.
```

A misspelled block id shows up as missing blocks in the world, and only the log says why. That is
how `minecraft:chain` — renamed `minecraft:iron_chain` in 26.x — made the whole `urbex:chains`
decoration invisible without anyone noticing.

Three details about that warning:

- It names the **owning asset id**, not the file it came from, and that owner is always the
  `palettes` or `variants` entry the string was written in — never the asset you noticed was
  broken. In the example above the missing chains belong to a `stuff` entry called `urbex:chains`,
  but the id in the warning is `urbex:common`, the palette that maps the character it places.
- For a palette written inline in a part or building, the id is the synthetic
  `urbex:__local__<path>` the inline block is registered under — for a part `urbexmt:tower` that
  reads `urbex:__local__tower`. Close enough to find; not a filename, and note that the synthetic
  id is always in the `urbex` namespace whatever the owner's is.
- It only covers the plain form. A blockstate string — anything containing `[` — is parsed
  strictly, so `minecraft:nosuchblock[facing=north]` is a hard failure rather than air.

## Common errors

| Message | What happened | Fix |
|---|---|---|
| `Unqualified datapack reference 'x': references must name their namespace, e.g. 'urbex:x'` | A reference with no `:` | Qualify it. There is no default namespace |
| `This citystyle declares 'inherit', which Urbex deleted rather than renamed: use 'extends' instead. Left as it is, the key is ignored and this file loads with no inheritance at all.` | A file uses the retired key `inherit` or `parent` | Rename it to `extends`. Lost Cities Modern Tweaks spells this key `inherit`, so a ported pack hits this first |
| `'extends' cycle: urbexmt:a -> urbexmt:b -> urbexmt:a` | An `extends` chain loops | Break the loop; the whole chain is printed |
| `Unknown asset 'urbexmt:missing' (referenced from 'urbexmt:child')` | `extends` names an id nothing provides | Check the id, the registry directory, and that the providing pack is enabled |
| `'urbexmt:x' declares no 'streetblocks.parts.stair', and neither does anything it extends` | A required field is declared nowhere in the chain | Declare it in this file, or in something it extends |
| `'urbexmt:x' declares no 'parts.railways.railsbend', ...` | A world style's chain never wired railways | Wire all sixteen, or `extends` a world style that does |
| `'urbexmt:x' declares no 'streetblocks.largeparts.connector', ...` | The primary-road family was partly declared | Declare all eight components, or none at all and inherit `parts` |
| `Part 'urbexmt:tower' declares no slices, and neither does anything it extends` | A part with no geometry anywhere in its chain | Add `slices`, or `extends` a part that has them |
| `Part 'urbexmt:tower' declares xsize 8 and zsize 16 but its slices are 16 wide (...)` | A size was redeclared without the matching slices | Declare both together, or neither |
| `'urbexmt:tower': the inline palette declares 'extends' '...'` | `extends` inside an inline `palette` block | Use `refpalette`, or put `extends` on the owning asset |
| `Illegal palette urbex:x!` | A palette entry names no `block`, `variant`, `blocks` or `frompalette` | Give the character something to resolve to |
| `Palette 'urbex:x' entry 'T' declares 'lightSource' but names nothing to place. Give the entry a block, blocks, variant or frompalette to light, or give the light source at least one candidate in floor, wall, ceiling, or free.` | A `lightSource` with neither a block of its own nor any candidate | Do one or the other |
| `Palette 'urbex:x' entry 'L' declares 'lightSource', but none of the blocks it resolves to emit any light. Either name candidates under floor/wall/ceiling/free, or drop 'lightSource' from this entry.` | A light source on a block that is not a light | Drop `lightSource`, or name candidates |
| `Palette 'urbex:x' entry 'T' declares 'torch', which no longer exists. Write "lightSource" instead: either "lightSource": true to make this entry's own block an optional light, or a "lightSource" object with floor/wall/ceiling/free candidates to let Urbex pick and orient one.` | The removed `torch` boolean | Follow the message; see the lighting section above |
| `Palette 'urbex:x' entry 'T' declares 'light', which was renamed. Write the same object under "lightSource", and add "unlit" to it if this marker should leave something behind when the light is off.` | The removed `light` object | Rename the key |
| `Invalid light candidate in palette 'urbex:x', marker 'T', placement 'wall', candidate #1 'minecraft:glowstone': an unlit replacement must emit no light` | A candidate's `unlit` is itself a light | Name a dark block, or omit `unlit` |
| `Palette marker 'ab' must be exactly one character, but is 2 characters long` | A `char`, `filler` or `rubble` that is not one character | Write one character. `""` used to throw with no file named, and `"ab"` quietly meant `"a"` |
| `Style 'urbexmt:downtown' declares a 'randompalettes' group whose factors total 0.0; no palette could ever be drawn from it. At least one palette in each group needs a factor above zero.` | Every palette in one group has a factor of zero or less | Give at least one of them a positive factor |
| `Stuff 'urbexmt:downtown' resolves to mincount 5 above maxcount 2; no count could be drawn between them` | The two came from different links of the chain and contradict | Declare them together, or fix the one that is wrong |
| `Value 5000 outside of range [0:4095]` on a stuff `mincount` or `maxcount`, or `[1:4096]` on `attempts` | Above what the decoration's RNG slot address can hold — two attempts would silently share one stream and draw the same position | Keep counts under 4096. Vanilla data is three orders of magnitude below it |
| `'urbexmt:x' declares no 'inbuilding', and neither does anything it extends` | A `stuff` entry that never says whether it goes inside buildings or outside | Declare it. It used to be optional, and an entry without it matched no chunk at all and placed nothing, silently |
| `Can't find 'urbexmt:p' in urbex:parts!` | A highway or railway part id resolves to nothing | Check the id; this one throws rather than degrading |
| `Cannot find 'urbexmt:p' in urbex:parts!` (WARN) | A street part id resolves to nothing | Same, but generation continues without the part |
| `Block 'minecraft:chain' (in urbex:common) ... it will generate as air` | A renamed or misspelled block id | Update the id; nothing else will tell you. The id in brackets is the palette or variant, not the asset that looks broken |
| `Error getting resource urbexmt:x!` | The wrapper around any of the above, naming the asset being resolved | Read the cause below it |
| *(no message at all)* | A misspelled **key** — the codecs ignore keys they do not know, in every registry but `presets` | Check the field name against this guide |

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

A **preset**, changing the city chance — see
[`docs/presets.md`](presets.md) for the rest of the format:

<!-- example: presets -->
```json
{
  "extends": "urbex:default",
  "description": "Modern cities",
  "cities": {
    "cityChance": 0.2
  }
}
```

## Seeing what a chain actually resolves to

Because a file states only what it changes, a file on its own does not tell you the effective
values. For presets, `/urbex savepreset` writes the fully resolved preset for your current
dimension to disk; [`docs/presets.md`](presets.md) covers it. The other twelve registries have no
equivalent export yet — for those, the load errors are the feedback loop, which is why they are
worded to name the asset and the field rather than the file.

Those errors come all at once. Every registered asset is resolved while the world loads, and
everything that fails is collected and reported together, each line naming the registry, the asset
and what is missing — so a pack with four mistakes takes one world load to diagnose rather than
four.

`/urbex validate` runs the same pass on demand and reports the same list, without changing anything.
On a world that is running it finds nothing, because anything it could find would have refused the
world already; it is there to confirm that after installing a pack, and to give you the whole list
in the server log. It cannot see an edit made since the world opened: the thirteen registries are
dynamic registries, loaded once with the world and frozen, so an edited file needs the world
reopened — exactly as a vanilla worldgen file does.
