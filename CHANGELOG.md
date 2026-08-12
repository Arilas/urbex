# Changelog

## Unreleased

- **`/reload` refreshes block tags without rebuilding everything a level generates with.** Block tags
  are the one piece of Urbex's compiled state a reload genuinely changes, and they now live in their
  own immutable `TagSnapshot` that a reload swaps in a single write (issue #128).
  - *A reload used to republish every loaded level's runtime.* `CityGenerator` expanded
    `urbex:lights`, `urbex:needspoi` and the vanilla plant tags into `BlockState` sets in its
    constructor, so a fresh generator was the only way to see an edited tag — and it took the
    level's road field, its heightmaps and every chunk plan it had cached with it. None of that is
    derived from a tag; all of it was rebuilt to exactly what it already was.
  - *A chunk sees one tag epoch, start to finish.* The snapshot is captured once, when a chunk's
    generation begins, so a reload landing halfway through a building cannot put one slice of it on
    the old tag membership and the next slice on the new one.
  - *Block tags are read in exactly one place now*: while an epoch is being captured, on the thread
    capturing it. `Tools.hasTag`, which the driver loop called per block, is gone.
  - *No worldgen change*: both digest goldens are unchanged, verified by running `runDigestCheck`
    and `runDigestCheckFeatures`.

- **Compiled assets are one immutable snapshot, finished before any chunk generates.** Twelve
  `static` registries, a readiness latch and a `reset()` are gone. Every asset is now compiled once
  per world load into an `AssetSnapshot` the level's runtime holds, and every lookup goes through it
  (issue #128).
  - *Nothing is compiled on demand any more.* A lookup either finds a finished asset or finds
    nothing. A style's `randompalettes`, and a part's or building's `refpalette`, used to be resolved
    by the first chunk that needed them — which is why they took a level argument, and why a typo in
    one surfaced from a worldgen worker mid-chunk instead of at load. They resolve at compile time,
    and those level arguments are gone.
  - *Compilation runs in dependency order*, so a stage can read what earlier stages built:
    variants → palettes → styles/parts/buildings → … → stuff. A stage above its dependency would not
    fail; it would read an empty index and compile assets referencing nothing.
  - *The preview compiles its own snapshot* from the client's registries and owns it, rather than
    reaching for a server's.
  - *A broken pack still refuses the world, naming everything at once* — except for city styles
    nothing can select, which are allowed to be incomplete because a style may exist only to be
    extended.
  - *No worldgen change*: both digest goldens are unchanged, verified by running `runDigestCheck`
    and `runDigestCheckFeatures`.

- **A datapack's mistakes are reported all at once, and `/urbex validate` asks for them on demand.**
  Load-time asset resolution stopped at the first broken file. That is the right outcome — the world
  must not load — but the wrong report: an author with four typos fixed one, reloaded the world,
  found the second, and went round again. Every registered asset is now resolved before anything is
  reported, and the world refuses to load with one message listing every problem, each line naming
  the registry, the asset and what is missing (issue #56).
  - *`/urbex validate`* runs the same pass on demand and changes nothing — it does not populate,
    replace or clear the compiled assets the running world's chunks are generating against. On a
    world that is running it finds nothing, which is the answer worth being able to confirm after
    installing a pack. The full list goes to the server log; chat gets the first ten.
  - *`ErrorLogger.report` no longer dereferences a null server.* It is called from the handler
    around chunk generation, and the window it most needs to survive is shutdown — a worker
    finishing a chunk after the static server reference has been cleared. The error *handler* threw
    an exception of its own there, turning a reported chunk failure into a dead worldgen worker and
    losing the message being reported. The report now always reaches the log, whether or not anyone
    is listening.
  - *No worldgen change*: both digest goldens are unchanged.

- **A palette's `variant` resolves against its own dimension's registries.** `Palette.compile`
  looked a variant up through `ServerAccess.getServer().getLevel(Level.OVERWORLD)` — the
  process-wide server reference, and then unconditionally that server's overworld — ignoring the
  registries the palette had just been read from. A palette compiled for any other dimension took
  the overworld's variants, and one compiled on a worldgen worker before the static server
  reference was populated threw a `NullPointerException` out of asset compilation (issue #60). The
  registry access now travels with the chain being compiled, which is what
  `RegistryAssetRegistry` had in hand at every one of its three compile sites all along. A palette
  compiled with no registry access and a `variant` entry to resolve now says exactly that, naming
  the variant. *No worldgen change*: both digest goldens are unchanged.

- **Deferred level work belongs to the level, and says whether it actually ran.** `GlobalTodo` was a
  `static` map keyed by dimension id, and every one of its problems followed from that (issue #127,
  part b). Nothing ever removed a dimension's bucket, so work queued in one single-player world was
  still queued when the next world with the same dimension id loaded, and ran against it. Its
  bucket removal raced its enqueue, so a task queued at the wrong moment went into a bucket that was
  about to be dropped. And it allocated a `HashMap` copy and a `HashSet` **on every level tick of
  every dimension** — twenty times a second, empty or not — to discover it had nothing to do.
  - *A task that could not run is no longer counted as done.* The one task there has ever been —
    force-grown saplings — checked whether its chunk was available, found it was not, and returned;
    the queue retired it. That tree never grew. Tasks now answer `DONE` or `RETRY`, and a task that
    keeps retrying ages out with a log line naming its position rather than being dropped on the
    first attempt or accumulating forever behind a chunk nobody will load again.
  - *The queue is drained under a task-count budget and a time budget*, whichever runs out first,
    and visits each task at most once per tick.
  - *It dies with its level.* The queue is a component of that level's runtime, retired on unload
    and at server stop, which reports how many tasks were still waiting instead of losing them
    quietly.
  - *No worldgen change*: both digest goldens are unchanged, verified by running `runDigestCheck`
    and `runDigestCheckFeatures`.

- **Generation state belongs to the server and the level that own it.** `CityFeature` kept the
  dimension state for the whole process in one map keyed by dimension id, and kept it honest with a
  `static volatile int` that the client bumped on disconnect, the world-creation screen bumped on
  publish, and `/reload` bumped on the server thread. Reconciliation ran *from the generation path*,
  so a bump could reset the asset registries while a worker was midway through a chunk — and that
  chunk was written, saved and never revisited with everything the emptied stuff index would have
  placed missing from it. A `GenerationSession` per running server now owns a `DimensionRuntime` per
  loaded level, published when the level loads and retired when it unloads (issue #125).
  - *Leaving a single-player world no longer touches the server's generation state.* The disconnect
    hook fires on the client thread while the integrated server is still draining in-flight
    generation; it now clears client state only, and the server's own state is retired at
    `SERVER_STOPPING` with nothing generating.
  - *`/reload` republishes each level's runtime instead of clearing the registries.* Block tags do
    reload and `CityGenerator` caches several of them, so a fresh runtime per level is what makes an
    edited tag take effect. The thirteen asset registries are frozen at world load and cannot change
    on a reload whatever is done to them (issue #61), so clearing them bought nothing and was how a
    running worker got an emptied index. A chunk already generating finishes against the epoch it
    captured.
  - *Two worlds in one session cannot share state.* Each server start opens a new session; the
    previous one is closed rather than inherited, and a stopping server can only close its own.
  - *Where the "no chunk generates against unloaded assets" rule now lives.* The level-load handler
    resolves the asset registries before it builds the level's runtime, and generation does nothing
    at all without a published runtime — so there is no longer a path that generates first and loads
    afterwards, which is what the load on the generation path was compensating for. Verified on a
    real server: all three dimensions publish before "Preparing spawn area".
  - *No worldgen change*: both digest goldens are unchanged, verified by running `runDigestCheck`
    and `runDigestCheckFeatures`.

- **Post-generation block writes belong to the generation that queued them.** The deferred writes a
  chunk queues for after its driver has run — POI blocks, loot chests, command blocks, saplings, the
  place-twice light refresh — were stored on the cached `BuildingInfo` for that chunk, and the
  drain re-fetched that cache entry to find them. Three things could go wrong with that and now
  cannot: the entry could be evicted between the write and the drain, taking the callbacks with it;
  a second generation of the same chunk found the first one's callbacks still queued and applied
  them to its own region; and a callback added on a worker thread while another cleared the map was
  simply lost. They are now owned by the `ChunkGenContext` that queued them, which refuses both a
  late enqueue and a second drain (issue #127, part a). *No worldgen change*: both digest goldens
  are unchanged, verified by running `runDigestCheck` and `runDigestCheckFeatures`.

- **Presets, world styles and city styles can name themselves again.** Making every asset reference
  fully qualified (0.2.0) also made the Cities tab and the world-style picker show those ids: the
  preset list read `urbex:default`, `urbex:tallbuildings`, `urbex:wasteland`, and the selector read
  *World Style: urbex:standard*. All three registries now take an optional top-level **`name`** — a
  plain human label, not an id and not a translation key — and it is what the UI shows.
  - *Nothing is required to have one.* A file that declares no `name` is labelled by its
    fully-qualified id, which is exactly what it read as before, so an unnamed third-party pack is
    drab rather than blank. An empty string counts as "no name" for the same reason.
  - *It is inherited, like every other scalar*, which is a trap worth stating: a world style
    extending `urbex:standard` without a `name` of its own is now listed as **Standard**. The
    bundled pack therefore names all twelve presets, its world style and its three *selectable*
    city styles, and deliberately leaves the two abstract bases (`urbex:citystyle_common`,
    `urbex:citystyle_config`) unnamed so an unnamed child of theirs keeps its own id.
    `ShippedPresetsTest` fails the build if a shipped preset omits its name or shares one.
  - *The id is still reachable where it matters.* The world-style dropdown shows the name over the
    id in grey — two packs may pick the same label, and the id is what an author has to type — and
    a preset row narrates as "*name* (*id*)". `/urbex debug` prints the city style as
    `name (id)`; before this it printed the id alone and city styles had no label at all.
  - *No worldgen change*: both digest goldens are unchanged, verified by running `runDigestCheck`.
    `PresetRE` did have to fold its six metadata keys behind a `MapCodec` to get past
    `RecordCodecBuilder`'s sixteen-field limit; the keys stay top-level in the JSON and no preset
    file changes shape.

- **A world can be generated from several world styles at once, balanced by weight.** Turn on
  `experimentalMultiWorldStyles` in `config/urbex/urbex.json` and the Cities tab's **World Style**
  picker grows a **Mix** mode: tick several styles, give each a weight, read back the normalized
  percentages. Each city draws its own style and keeps it, so one world can hold cities from several
  datapacks instead of one pack replacing another. Server owners get the same in
  `dimensionsWithPresets`, with `+` between entries and `*` before a weight:
  `minecraft:overworld=urbex:default@urbex:standard*0.1+urbexmt:moderntweaks*0.9`.
  - *A world style was never one scope, and mixing forces that to become explicit.*
    `IDimensionInfo.getWorldStyle()` is replaced by `worldStyles()`, returning a `WorldStyleField`
    that answers per scope rather than per dimension — a call site now has to say which it means,
    and the compiler makes it. `citystyles` come from the city's own centre; `outsidestyle` and
    `rotatable` from the nearest city; `scattered` from the scatter area's anchor; the rest of
    `multisettings` from the multichunk anchor. Highway and railway `parts`, `settings`,
    `citybiomemultipliers` and the two grid sizes come from the heaviest style — a highway that
    changed pack partway along its run would not join up, and a per-area `areasize` would have to be
    read out of a grid it has not defined yet.
  - *Off by default, and the flag gates the value rather than only the UI.* A save or a config line
    hand-edited to carry a mix is reduced to its heaviest style, with the reduction logged, on an
    install that never opted in.
  - *Both goldens unchanged*, verified by running both digest checks. A single-entry mix draws no
    randomness at all — every accessor short-circuits to the one style — so a world created before
    mixing existed, or on an install with the flag off, generates exactly what it did. The one
    deliberate layout change is `Rng.Purpose.WORLD_STYLE`, **appended** after `LARGE_BRIDGE`;
    appending leaves every existing ordinal alone, which is why the digests hold, and `RngTest`'s
    `PURPOSE_COUNT`, `LAST_PURPOSE`, `GOLDEN_LAST` and `PURPOSE_ORDER` are repinned in the same
    commit.
  - *Verified against a real second pack, not just unit tests.* `runMixCheck` is a headless census
    of what a mix actually produces, sibling to the digest checks — a digest proves generation did
    not change, this proves it did, in the way the feature claims. Against Urbex-ModernTweaks at
    `0.1`/`0.9` over a radius-40 square: 6 of 65 city centres and 11 of 121 scatter areas on the
    `0.1` style (9.2% and 9.1% against a nominal 10%), both packs' city styles reached
    (`urbex:citystyle_standard`, `urbex:citystyle_desert`, `urbexmt:citystyle_standard`,
    `urbexmt:citystyle_desert`, `urbexmt:citystyle_jungle`) and both packs' scattered structures
    reached (`urbex:radiotower`, `urbex:oilrig`, `urbex:cabin`, `urbexmt:cabin`). The same run with
    no pack reports one style and the same 65 centres, so mixing moves no city — only its
    attribution.
- **The README's usage instructions were describing 0.1.0.** They named a **More** tab and a
  **Cities** button (it is a Cities tab now), the `dimensionsWithProfiles` config option (the key is
  `dimensionsWithPresets`), and gave `minecraft:overworld=default` as the example — an unqualified
  reference, which that option has rejected since references were made to name their namespace. Two
  of the three would have failed for anyone following them. The same stale key was in the 0.2.0
  entry that removed the `urbex:city` dimension - written before the preset rework renamed
  `dimensionsWithProfiles` to `dimensionsWithPresets` - and is corrected there too.

## 0.2.0 — 2026-08-12 (beta)

- **`urbex:rotatable` covers what the generator actually rotates.** The tag named
  `#minecraft:stairs` and nothing else, while `CityGenerator.transformBlockState` applies the part's
  mirror/rotation to any block in it - so every rotated or mirrored copy of a part placed the
  ladders, iron trapdoor, doors, barrels, levers, wall torches and rails of Urbex's own palettes
  facing the way the *unrotated* part was authored. A ladder or wall torch that survives that is
  attached to nothing. The tag now names the vanilla tags for stairs, doors, trapdoors, banners,
  signs, rails, beds, buttons, fence gates, fences, walls, bars, anvils, shulker boxes, glazed
  terracotta and campfires, plus the individual directional blocks that have no tag of their own.
  - *Guarded mechanically, not by a list.* `RotatableTagCoversShippedBlocksTest` asks each shipped
    block state whether `rotate`/`mirror` returns something different, and fails the build if one
    that turns is outside the tag - so a new palette entry for a directional block cannot ship the
    same defect. It expands `#tag` references out of the vanilla data on the classpath, so it checks
    what the game resolves rather than the file's literal contents.
  - *Both goldens unchanged*, verified by running both digest checks: nothing in the two sampled
    windows (49 and 361 chunks) places a newly-covered block under a transform, so this is latent
    for the bundled pack today and load-bearing for any pack whose rotated parts use one. The one
    entry with a behavioural edge is `#minecraft:rails`: `Railways` places some parts with
    `MIRROR_X`, and a mirrored `ascending_east` rail now becomes `ascending_west` rather than
    ascending into the mirrored wall.

- **Datapack mistakes that used to be silent or late are load errors naming the file.**
  - `stuff` now requires `inbuilding` of the resolved chain. It was optional with no default, and
    `Stuff.generateStuff` matches on `inbuilding == hasBuilding`, so an entry without it matched no
    chunk at all: registered, indexed under its tags, walked on every city chunk, placing nothing.
  - `mincount`/`maxcount` are bounded to `[0, 4095]` and `attempts` to `[1, 4096]`, the width of the
    fields `Stuff.slot` packs them into. Above that they carry into the next field and two distinct
    placement attempts share one RNG stream, drawing the same position - a silent wrong answer
    rather than a failure. A fold-level check also rejects `mincount` above `maxcount`.
  - A palette marker must be exactly one character. `char`, `filler` and `rubble` were read with a
    raw `charAt(0)`, so `""` threw `StringIndexOutOfBoundsException` out of the decode with no file
    named and `"ab"` quietly meant `"a"`.
  - A `randompalettes` group whose factors total zero is refused at the fold. It used to leave the
    weighted draw with no winner and `NullPointerException` out of `Palette.merge` on a worldgen
    worker; the draw now also falls back to the last entry rather than null, for float drift.
  - `scattered` rejects `rotatable` instead of parsing and discarding it: nothing ever read it, and
    a scattered building always generates unrotated.

- **A world creation the player backs out of no longer rewrites another world's preset.**
  `PresetSelection.publish()` writes the choice into three process-global `Config` fields, and
  nothing took them back - so abandoning the create screen and then loading a *different* existing
  world made that world generate with the leftovers and, worse, persisted them into its own
  `UrbexData`, overwriting the selection it was created with. Cleared now from `CreateWorldScreen
  .onClose`, which is the abandon path and not the create path (`createWorldAndCleanup` calls
  `popScreen()` directly), so the published values still survive exactly as long as they are needed.
  `Config.buildPresetCache` additionally ignores a client selection for a world that already
  recorded one, which makes the overwrite unreachable rather than merely unlikely.

- **`/reload` no longer generates against pre-reload block tags.** `CityGenerator` expands
  `urbex:lights` and `urbex:needspoi` into `BlockState` sets once and holds them for the lifetime of
  the dimension info, so an edited tag kept generating against the old membership until the world
  was reopened. `END_DATA_PACK_RELOAD` now bumps the dimension-info dirty counter. It does *not*
  make the thirteen asset registries reloadable - those go through Fabric's `DynamicRegistries` into
  `RegistryDataLoader.WORLDGEN_REGISTRIES`, which is loaded once at world load and frozen, exactly
  like a vanilla worldgen file.

- **`PaletteEntry`'s dedup pools no longer leak or race.** `LIST_POOL`/`TAG_POOL` were
  unsynchronized `ObjectOpenHashSet`s mutated from registry decode (which runs on a worker pool) and
  never cleared, so they retained every palette of every world loaded in the process. Now
  `ConcurrentHashMap`s, cleared by `AssetRegistries.reset()`.

- **Command batch.** `/urbex debug` and `/urbex map` write to the player who ran them instead of the
  server's stdout, where the person who asked could not see it (and `map` was `LEVEL_ALL`, so any
  player could print 41x41 characters into the console on demand; it is `LEVEL_GAMEMASTERS` now).
  `locate` and `locatepart` take an optional radius, say so when they find nothing, and stop the
  spiral once they have six hits rather than only the inner loop. Every command returns a real
  Brigadier success count, so `/execute if` and command blocks can branch on one. `digest` suggests
  its three legal orders. The unused `CommandDispatcher` parameter is gone from twelve `register`
  methods.

- **Build and packaging.** The jar is `urbex-fabric-<version>.jar`, not
  `urbex-fabric-26.2-<version>.jar` - `archivesName` carried a Minecraft version that `version`
  already starts with. `src/generated/resources` is gone - it held six hand-written
  tag files under a directory named "generated", with no datagen entry point anywhere - and those
  files now live in `src/main/resources` where they are written. `fabric.mod.json` pins
  `fabric-api` to `>=` the version this compiles against rather than `*`, which turned an old API
  into `NoClassDefFoundError` instead of a dependency error. Deleted: the inherited probot
  `.github/stale.yml` (`daysUntilStale: 100000`) and the Fuzs maven repository, left over from
  `forgeconfigapiport`. CI uploads the JUnit reports, including on failure.

- **A mod icon, and releases that build themselves.** `assets/urbex/icon.png` (128x128) fills what
  was a blank tile in Mod Menu; `art/icon-master.png` keeps the editable original out of the jar. A
  `v*` tag now builds like any other push - full suite, both worldgen digest checks - and only then
  attaches that build's jar to a draft GitHub release, so a release cannot be cut from an unverified
  run and the notes stay hand-written. The tag names the mod version alone (`v0.2.0`) and the job
  refuses one that disagrees with what the build produced. Modrinth and CurseForge are uploaded by
  hand from the same jar; `README.md` records the sequence.

- **Dead code.** `ConditionContext.parseTest(JsonElement)`, a 75-line hand-rolled Gson duplicate of
  the codec-driven overload that had silently diverged from it; `TerrainHeight.byName`/
  `TerrainFix.byName` and their maps, superseded by the `StringRepresentable` codec.
  `ObjectSelector`'s encoder had constant getters (`v -> 0`) for `minSpawnDistance`,
  `maxSpawnDistance` and `feather`, so any round trip through `PresetRE.CODEC` - `urbex savepreset`,
  or the create-world screen's overrides overlay - silently reset all three.

- **Palette weights are absolute slot counts again, filled in declaration order.** A weighted
  palette or variant entry's `random` is how many of the 128 slots it takes, and the list stops once
  the array is full - Lost Cities' rule (`CompiledPalette.addEntries`), which every pack in existence
  is authored against.
  - *What was wrong.* Issue #58 made the weights proportional. That fixed a real crash (a list
    summing under 128 threw) but inverted the idiom packs actually use: a trailing huge weight
    meaning **fill whatever is left**. `stone_building`'s `#` in a Lost Cities pack is 113 slots of
    varied rubble followed by `moss_block 1000`, so moss takes the last 15 slots - a mossy stone
    wall. Read as proportions it is 91% moss block: a solid green cube. Reported from a live world
    as "almost 90% moss".
  - *Urbex's own assets were affected too*, which is how far this reached: `urbex:blackstone` is
    `[32, 32, 1000]`, meant as half accents and half base, and it generated as 94% base. Every
    bundled variant uses the same shape.
  - *Truncation is the mechanism, not the bug.* Over-full lists truncate, as upstream does. The
    under-128 case keeps #58's leniency and scales up rather than throwing, because Lost Cities threw
    there (`"factor should go up to 128"`) so nothing can depend on the old behaviour.
  - *Both goldens moved; deliberate regeneration.* `digest.golden` `eb6253f3f5363937` ->
    `688914e862e938fb`, `digest-features.golden` `203d8a44769da0c7` -> `7b5348f28e0a6d23`. Block and
    chunk counts are unchanged (849,092 / 4,591,882 blocks), so geometry is identical and only which
    block fills each palette slot differs - the expected signature of this change.

- **A world style can name its own `rotatable` block tag.** New optional `rotatable` field on
  `worldstyles`, holding a `#`-prefixed block tag id; `CityGenerator.transformBlockState` reads the
  resolved tag from the active world style instead of the hardcoded `UrbexTags.ROTATABLE_TAG`. A
  chain that declares none resolves `urbex:rotatable`, so nothing that already exists changes
  meaning.
  - *Why.* Previously the only way for a pack to widen the rotatable set was to ship
    `data/urbex/tags/block/rotatable.json`, and a tag file in Urbex's namespace merges into
    `urbex:rotatable` itself — so it took effect in **every** world style, including
    `urbex:standard`, whether or not the player selected that pack's style. A pack whose palettes
    place directional banners, trapdoors or ladders had to choose between shipping mis-facing blocks
    and changing stock generation for everyone.
  - *Replaces rather than merges.* A pack that wants Urbex's own set writes `"#urbex:rotatable"` into
    its own tag's `values`. Tags reference tags across namespaces, so nothing is lost and the
    composition is visible in data rather than implied by a merge rule.
  - *Not `TagKey.hashedCodec`.* It decodes the same `#ns:path` shape, but parses the remainder with
    `Identifier.read`, so `"#rotatable"` would silently become `minecraft:rotatable`. The new
    `DataTools.BLOCK_TAG_CODEC` goes through `DataTools.fromName`, making an unqualified tag
    reference the same load error as any other unqualified reference.
  - *Both goldens unchanged.* `digest.golden` (`eb6253f3f5363937`) and `digest-features.golden`
    (`203d8a44769da0c7`) are byte-identical after the change. The styles those runs exercise declare
    no `rotatable`, so an identical digest across 849k and 4.6M placed blocks is the evidence that
    the default path is untouched.

- **Open lots generate at street level, not one block above it.** `urbex:default` now sets
  `roads.parkElevation: false`. Reported from a live world: a raised lot next to a building stands a
  one-block lip directly across the doorway, so the door opens into a wall of dirt. The same
  behaviour was confirmed on upstream Lost Cities, so this is a tuning mismatch inherited with the
  algorithm rather than a regression here - which is why the fix is a shipped default and not a code
  change. Lost Cities rolled for parks per chunk, so raised beds were sparse and read as nature
  pushing up through the ruins; Urbex makes *every* chunk that is neither road nor building an open
  lot, so the same rule fires almost everywhere. `BuildingInfo.isElevatedParkSection` counts how many
  of the eight neighbours are open (`isStreetOrParkSection`, i.e. `isCity && !hasBuilding`) and
  elevates at `PARK_STREET_THRESHOLD` or more, default 3. A neighbouring building does not *veto*
  elevation - it only fails to increment that counter - so a lot with three open neighbours elevates
  on their strength no matter what it is standing against. With the toggle off, `CityGenerator` still
  writes the elevation layer at `height` but no longer increments `height`, so `generateParkSection`
  overwrites that row: the lot replaces the street surface underneath instead of resting on top of
  it.
  - *Scope.* The key is set once, on `urbex:default`; the other eleven shipped presets extend it and
    declare no `roads` of their own, so all twelve inherit it. `Preset.PARK_ELEVATION` stays `true`
    as the code default, so this changes what Urbex ships, not what the engine does - a pack that
    wants raised beds sets `roads.parkElevation: true`, and `ParkSettings.parkelevation` still
    overrides per city style.
  - *Both goldens moved; third deliberate regeneration.* `digest.golden`
    `88af6b69e7762fbc` -> `eb6253f3f5363937`, `digest-features.golden` `7c297c1e4ec1ce38` ->
    `203d8a44769da0c7`, each reproduced by two runs. Attributed rather than accepted: block-state
    dumps before and after differ in 5135 of 850353 final cells (0.6%), of which 3478 are literal
    one-block downward shifts (`after[y] == before[y+1]`). The two largest transitions are
    `stone_bricks -> grass_block` (1831), the surface landing on the row the elevation block used to
    hold, and `grass_block -> air` (654), the row above it emptying. Changes sit in three narrow Y
    bands - the sample's three street levels - so nothing below the surface or inside a building
    moved, and 2257 distinct columns are touched, which is the "open lots are everywhere" premise
    showing up as a measurement. The features window still holds both features it guards
    (`bridgeChunks=3`, `slopeChunks=2`), so it did not need relocating; `unsafeReads=0` on both.

- **`inherit` and `parent` are refused by name, in all thirteen registries.** The spec said from the
  start that they were "deleted, not aliased" and that a file using either would fail to load naming
  the replacement key - and nothing implemented it, so the promise was the one thing standing between
  a pack author and the worst version of this failure. DFU codecs ignore unknown map keys, so a city
  style declaring `"inherit": "urbex:citystyle_common"` decoded perfectly cleanly and loaded as a
  **chain root with no inheritance and no diagnostic**; `presets` alone would have logged a `WARN`,
  via `UnknownKeys`, which is a line in a log rather than a refusal. What the author saw next
  depended on what else the file said, and neither outcome named the key: a file that also spelled
  out complete wiring loaded and generated silently without anything it meant to inherit, and one
  that leaned on its parent failed with `declares no 'streetblocks.parts'`, which mentions neither
  `inherit` nor `extends`. That is a bad failure for anyone, and it is the *expected* failure for
  this branch's stated first consumer: a port of Lost Cities Modern Tweaks, a format in which
  `inherit` **is** the key, so a mechanically converted pack hits it on every file. `RetiredKeys`
  now pre-checks the top-level keys of every registry entry and fails the decode with a message
  naming the offending key, its replacement, and what leaving it in place would have done. It is a
  pre-pass rather than a field on each record, so it fires on the key's presence whatever its value's
  type. Encode is delegated untouched - `PaletteRE`, `BuildingPartRE` and `PresetRE` encode on live
  command and GUI paths that read the `DataResult` themselves, and no encoder can produce these keys
  anyway.
  - *The coverage claim is made by enumeration, not by a list to keep up to date.*
    `RetiredKeysRejectedTest` reflects the `CODEC` field off every `*RE` class in the registry
    package, requires all thirteen to reject both keys with a message naming the key and `extends`,
    and separately requires that count to equal the number of `_REGISTRY_KEY` fields on
    `CustomRegistries` - so a fourteenth registry cannot arrive uncovered. A file carrying both keys
    reports the same one every run (declaration order, not map order), and a non-map input is left to
    the wrapped codec rather than being described as a retired key.
  - *Inert, and checked rather than assumed.* No file in `src/main/resources/data` uses either key -
    asserted by the same test, not grepped once by hand - so nothing that loads today changes shape
    and neither golden can move. Both are unchanged (`88af6b69e7762fbc`, `7c297c1e4ec1ce38`,
    `unsafeReads=0`). `docs/datapacks.md` gains the row in its common-errors table, and the carve-out
    it needs in "a misspelled key is ignored": these two keys are the exception to that rule in every
    registry, which is precisely why they had to stop being ignorable.

- **Datapack authoring has a guide: `docs/datapacks.md`.** Everything below changes what a
  third-party pack must write down, and until now the only way to learn the rules was to read the
  codecs. The guide covers the thirteen registries and where their files go, `extends` and its
  root-first order, the three merge shapes and the `{"replace": false}` append opt-in, palettes
  merging per character, `extends` composing with `refpalette` on a part, and the wiring every world
  style and city style has to declare - with a working example of every registry and a table pairing
  each load error with its fix. It also writes down what only the source said: that **ten** of the
  thirteen registries resolve every registered asset at world load whether or not anything selects
  it, so an *incomplete* chain root is legal only in `citystyles` - while `predefinedcities` is
  resolved on the generation path instead, and only its `citystyle` reference is seen at load; that
  an empty part list is a real opt-out for the three street families but a generation crash for
  highways and railways; that **three** city-style fields are needed but not checked at load -
  `style`, and the `streetblocks` characters `street` and `border`, each of which throws at a
  different moment in generation; that a
  misspelled **key** is silently ignored in every registry but `presets`; and that an unknown block id
  resolves to air with a warning naming the palette it was written in, not the asset that looks
  broken. `README.md` and `docs/presets.md` link to it.
  - *Spec section 4's merge-shape table was wrong, and is corrected in place.* It cited
    `scattered.list` and `slices` as ordered lists that take the append form. Neither is mergeable at
    all, and two more fields the rule mispredicts turned up with them: `multibuildings.buildings` is
    also a plain list, and `citystyles.stuff_tags` is a fourth behaviour - `CityStyle.applyFrom`
    unions it into a set, so a child can neither replace nor remove an inherited tag. The `scattered`
    block is additionally a scalar, replaced wholesale alongside `multisettings` and `settings`, and
    those three are the only settings blocks with fields required by their own codec - so restating
    one means restating all of it. Three shapes is still the design; what was wrong was the claim
    that it covered every field, and the guide now enumerates the exceptions rather than promising
    the shape can be read off the JSON. No code changed.
  - *The examples and the quoted errors are checked, not proofread.* `DatapackGuideExamplesTest`
    decodes every JSON block in the guide through the codec of the registry it is marked as belonging
    to, and re-encodes the result - walking objects and arrays alike - to catch keys the codec
    silently ignored, so a doc example that would fail to load, or load and quietly do nothing, fails
    the build. It also fails if a registry has no example, and a second test provokes nine of the
    guide's quoted error messages from the code that raises them and requires the guide to contain
    each verbatim. Both needed `docs/datapacks.md` declared as an input to `:test`, along with
    `docs/schema/` for `PresetSchemaTest`: without them a documentation-only edit left the task
    `UP-TO-DATE`, so neither ran on the change it exists for. Nothing reads `docs/presets.md` - its
    prose is unasserted, only the schema beside it is checked - so it is not an input.

- **Street, highway and railway part wiring must be declared by the datapack; there is no code-side
  default left.** Thirty `Tools.listOrStringList` call sites carried a bare asset name as a fallback,
  so a world style that never mentioned primary roads still generated Urbex's own
  `urbex:street_large_*` parts, and a city style that never mentioned railways still got
  `urbex:rails_*`. That is the leak this whole change set exists to close: a reference no datapack
  file wrote is a reference nobody can grep for when it misbehaves, and a third-party pack inherited
  a road class it never asked for. The `defaultVal` parameter is gone, and `StreetParts.DEFAULT`,
  `HighwayParts.DEFAULT`, `RailwayParts.DEFAULT` and `PartSelector.DEFAULT` with it. A world style
  whose chain declares no `parts` now refuses the world load naming the field
  (`'urbex:x' declares no 'parts.railways.railsbend', and neither does anything it extends`), in the
  same sentence every other required field uses. `MultiSettings.DEFAULT` and `WorldSettings.DEFAULT`
  stay: they hold numbers and enums, not asset references.
  - *Requiredness is still a property of the chain, not of the file.* A child that overrides one
    street family must not have to restate the other seven, so each field decodes as absent and the
    check runs after the `extends` chain is applied, exactly as in the rest of this work. The
    families are folded **component by component** now, where a whole `StreetParts` used to be
    swapped in or out - which is what delivers the append opt-in spec section 4 promised for ordered
    part lists: `"straight": {"replace": false, "values": ["urbex:street_straight_alt"]}` adds one
    variant after the ones the parent declared, while a bare array replaces them.
    `largeparts` and `tertiaryparts` remain optional *as blocks* and fall back to
    `parts` when nothing in the chain declares them - a fallback to parts the pack itself wrote, not
    to a name written in Java - but a family that is declared at all must be complete, or half of it
    reaches generation as a null list. The `s.getParts() != StreetParts.DEFAULT` sentinel goes with
    the constant, though **it was not itself producing a wrong result**, and this entry should not
    imply it was: `StreetParts` is a record, so `!=` compared references, and the `DEFAULT` *instance*
    could only ever arise from `StreetSettings`' `orElse` on an absent key - a declared block always
    decoded to a fresh object, whatever its contents. It was an opaque way of asking "did this file
    contain the key", not a broken one. What replaces it asks that directly, and per component.
  - *City styles are now validated at world load too, by reachability.* Making the wiring required
    created a failure that could only be raised too late: `CITYSTYLES` was not in
    `AssetRegistries.load`'s eager sweep, so a third-party city style with no `parts` would have
    thrown from a worldgen worker mid-generation, wrapped in a `RuntimeException`, rather than
    refusing the world. Sweeping *every* registered city style is not the fix - it would forbid a
    style that exists only to be extended, and the bundled `citystyle_config` is exactly that, a
    street width and nothing else, complete only through `citystyle_common`. `load` now resolves
    every city style anything can *select*: the ones a world style's `citystyles` selectors name,
    every preset's `cities.cityStyleAlternative`, and every predefined city's `citystyle`. Roots
    nothing names drop out for free. That third route matters for the bundled pack itself -
    `citystyle_border` is named by no world style at all, only by `presets/largecities.json`, and it
    generates real cities. A fourth route cannot be swept from a registry at all: the same
    `cityStyleAlternative` field also arrives as per-world *override* JSON, since it is a free-text
    box in the customization GUI that rides into the world through `UrbexData`. A player typing an
    incomplete style there - a third-party extend-only base, or bundled `urbex:citystyle_config`
    itself - would still have crashed from a worker. `CityFeature.getDimensionInfo` now checks that
    one where it builds the preset, once per dimension and before any chunk work.
    `WorldStyleCompletenessTest` follows routes 1-3; route 4 exists in no file in the repository, so
    it has no build-time equivalent, and `CityStyleLookupSitesTest` fails the build if a new
    city-style lookup site appears without registering with either sweep.
  - *Two consequences of validating earlier, both intended and neither only about wiring.* A name
    that does not resolve is now a load failure **in an asset nobody selects**: a world style
    selector naming a style from an optional datapack the player did not install, or an unselected
    preset naming a missing style, refuses a world that loaded before. A preset can even name an
    alternative it could never reach, since `CITY_STYLE_THRESHOLD` defaults to `-1f` and the test at
    `City.java:241` is `factor < threshold`. That is the same trade `AssetRegistries.load` already
    documents for the other ten registries - a broken third-party asset fails the world even when
    nobody selects it - now extended to city styles. And because `getDimensionInfo` is called at
    `CityFeature.java:67`, outside the per-chunk try/catch below it, a city-style failure on the
    generation path takes the pipeline down rather than being logged per chunk. That has been true
    of the other ten registries since the load-timing change above; city styles join them here.
  - *Two ids moved out of Java and into the pack, and nothing moved in the world.*
    `citystyle_common`'s `parts` block never declared `connector` or `stair`, so both came from the
    Java defaults - `urbex:street_large_connector` and `urbex:street_stair` - and `stair` was in
    active use, since secondary streets are what ramp. Both are now written in the file, with the
    same values. Both goldens are unchanged (`88af6b69e7762fbc`, `7c297c1e4ec1ce38`, `unsafeReads=0`),
    which is the check that no default silently in use was replaced by a different value.
  - *Tests.* `NoAssetReferenceDefaultsTest` fails if any wiring field regains a literal default.
    `WorldStyleCompletenessTest` walks every world style, every city style anything can select and
    all their `extends` chains, resolves each through the constructors the game uses, and requires
    every wiring field to hold at least one part id - asserting on the union over a chain, so
    `citystyle_border` declaring no `parts` and taking `citystyle_common`'s is correct rather than a
    failure, and failing in `Resolved.require`'s own wording so an author meets one convention rather
    than two. Note what "at least one" buys: for the three street families an empty list is a genuine
    runtime opt-out that `CityGenerator` guards, so the test is stricter than the rule there on
    purpose; for highways and railways `Highways` and `Railways` hand the list straight to
    `getRandomPart`, so `"tunnel": []` crashes generation and this test is the only thing checking it.
    What the load-time guard promises is non-null, not usable. `DatapackReferenceIntegrityTest` also
    reads the `{"replace": false, "values": [...]}` form now, in both its list helpers: it used to
    fall through on an object, so a bare or dangling name inside a `values` list was invisible to the
    whole reference sweep on all thirty wiring fields and on every mergeable selector list.
    `WiringRequiredTest` covers the three codec arms in both directions, the append opt-in on a
    string part list, and the load errors for a missing and for a half-declared family.

- **Decoration now generates in the spawn area, where it previously did not.** `AssetRegistries.load()`
  was called only from `ServerTickEvents.END_LEVEL_TICK`, but `prepareLevels()` - "Preparing spawn
  area" - generates its chunks inside `initServer()`, before any tick fires. Every chunk it wrote had
  no cobwebs and no chains, and chunks are saved, so nothing ever healed them: a new world had a
  bare-spawn city and a decorated one a few hundred blocks out, with a visible boundary between.
  Where that boundary fell depended on how far spawn preparation and tick 1's chunk work happened to
  get, so two players on one seed got different worlds - which is the opposite of what this mod
  claims. Loading now happens in two places and neither is a tick. `ServerLevelEvents.LOAD` runs the
  eager validation while the world is still loading: Fabric raises it from a `@WrapOperation` on the
  `levels.put` inside `MinecraftServer.createLevels`, and `loadLevel` calls `createLevels()` before
  `prepareLevels()`, so a broken pack refuses the world instead of failing under a player. That alone
  would only swap one ordering assumption for another, so the guarantee itself lives on the
  generation path: `CityFeature.getDimensionInfo` - which `CarverHookMixin` reaches before any city
  is generated - loads the registries before it touches the level, which also covers the case a
  lifecycle event cannot, namely `CityFeature.cleanUp()` resetting the registries mid-session from
  that same path. `AssetRegistries.load` is now called from worker threads, so it takes a lock and
  publishes the stuff-by-tag index with a single volatile write of a finished map; the old `putAll`
  into a shared `ConcurrentHashMap` let a worker see some tags present and others still missing, and
  a missing tag places nothing and says nothing. `CityFeature`'s dirty-counter reconcile is atomic
  now rather than a check-then-act on two volatile ints, so the first reconcile of a session cannot
  reset the registries underneath a thread that is already generating.
  - **Both goldens move, and this is not a routine regeneration - it changes what they are evidence
    for.** `digest.golden` goes from `414cb71424d5e53f` to `88af6b69e7762fbc` and
    `digest-features.golden` from `8a3215441fb9f46d` to `7c297c1e4ec1ce38`, because decoration exists
    in the sampled windows for the first time. **The previous goldens did not cover the `Stuff`
    subsystem at all** - not `generateStuff`, not the `stuffOrdinal` RNG addressing, not a single
    `StuffSettingsRE` filter, not `columnResolves`, and `Rng.Purpose.STUFF` had never appeared in a
    draw sequence - because `DigestCheck` runs on `SERVER_STARTED` and halts the server before a tick
    ever fires. The same gap meant the eager load-time validation added for all ten registries had
    never run in a digest either. Both windows were dumped position-by-position on the pre-change and
    post-change trees, so the movement is measured rather than argued. The primary window moves 38
    driver positions out of 850,049: 21 that were air are cobweb, 16 are iron chains, and one blue
    stained-glass pane changed its `west` connection because a chain is now a real block beside it.
    The features window moves 825 out of 4,605,750, and nothing is lost: 761 are decoration itself
    (485 cobweb, 268 chain, 8 at positions the driver had never written), 62 are connection flips on
    iron bars and glass panes that now attach to a chain - `ChunkDriver`'s corrections pass runs
    against the finished chunk, so it sees decoration - and 2 are blocks the explosion-damage pass
    now breaks that it did not before. That last coupling is the collection gate in
    `breakBlocksForDamageNew`: it only accumulates damage for a cell that is not air, so a cobweb or
    chain in a previously-air cell makes its column collect damage it used to skip, and the
    accumulator carries *upward* through the ascending section loop. (Not the air count feeding the
    damage factor, which is what an earlier draft of this entry said: decoration can only lower that
    count, the factor only ratchets up, and less damage cannot break more blocks. Confirmed by
    instrumenting both trees - the damage factor is identical at every sampled layer, while the
    column accumulator diverges exactly where the gate flips.) One step of that account is inference
    rather than measurement, and is worth stating in an entry whose justification is "measured": for
    one of the two blocks, the cell that opened the gate records air in *both* runs, so the decoration
    that opened it was evidently broken by the same pass in the same layer - consistent with the
    arithmetic and with decoration being the pass's only changed input, but not watched directly. So
    everything that moved is decoration or a consequence of decoration; "only decoration moved" would
    have been the wrong claim.
  - *Two shipped datapack defects, both found by that validation running for the first time.*
    `palettes/bricks_desert_redsand.json` carried `minecraft:red_sandstone@2`, a 1.12 `name@meta`
    string predating flattening that is not a legal `Identifier`; `Identifier.parse` threw on it
    inside `Palette`'s constructor, which now takes the whole world load with it, so the world did
    not start at all until it was fixed. It is `minecraft:cut_red_sandstone` - taken from vanilla's
    own flattening table for legacy id 179 meta 2, not guessed. `palettes/common.json` mapped `{` to
    `minecraft:chain`, renamed `minecraft:iron_chain` in 26.x, so the entire `urbex:chains`
    decoration had been placing air. **The two were separated on the digest rather than reasoned
    about together:** with the load fix in place but before the chain fix, the primary digest was
    `a993b976a935e2eb`, with 21 cobwebs and zero chains in the window; the chain fix alone took it
    to the shipped `88af6b69e7762fbc` by turning 16 of those placements from air into chains. That
    intermediate value is recorded here because it is the measurement the "measured, not inferred"
    claim above rests on, and until now it existed only in a scratch workspace.
    The mechanism: `Tools.stringToState` ends at
    `BuiltInRegistries.BLOCK.getValue(id)`, which hands back the registry's *default* value -
    `minecraft:air` - for an unknown id, so its `value == null` guard never fires and a renamed block
    becomes air with no exception and no log line. `ShippedBlockIdsResolveTest` now checks every
    `block` and `damaged` id in the bundled pack against the block registry; the air fallback in
    `Tools.stringToState` is a wider contract change and is left for its own task.
  - *The one reset that is still reachable now says so instead of costing you a chunk.* A bump of
    `globalDimensionInfoDirtyCounter` arriving while generation is in flight still resets the
    registries underneath it, and the consequence is not the mild one: `AssetRegistries.reset()`
    empties the stuff-by-tag index, and that index - alone among the registries, which all re-resolve
    lazily on the next lookup - has no rebuild, so the chunks in flight are written and **saved** with
    no decoration. That is this entry's own bug, one chunk at a time. It is reachable rather than
    theoretical: `ClientPlayConnectionEvents.DISCONNECT` bumps the counter on the client thread, which
    in single-player fires while the integrated server is still draining generation. It is not closed
    here - closing it means not tearing the registries down from a path generation shares - but
    `Stuff.generateStuff` now takes the index as a single snapshot - so a reset landing while it runs
    can no longer half-decorate a chunk - and logs an error naming the chunk and the consequence when
    that snapshot is empty and the registries are unloaded, once per occurrence rather than once per
    chunk. The index and its loaded flag are one record behind one volatile field, because two
    volatile fields cannot be read together: a reader could legally see the emptied index and the
    stale "loaded" and wave through the very chunk the check exists to catch. Reversing the write
    order only narrows that window; one field removes it. It logs rather than throws because a throw would unwind past
    `ctx.driver.actuallyGenerate(chunk)` and lose the chunk's whole cached write set, costing the
    chunk rather than its decoration, and because `ErrorLogger.report` dereferences the server with no
    null check exactly when the server is going away. `CityFeature.cleanUp()` is private and
    synchronized, since the design depends on it being reached only under the instance monitor.
  - *An unknown block id still generates as air, but no longer in silence.* `Tools.stringToState`
    ends at `BuiltInRegistries.BLOCK.getValue`, which returns the registry's default - air - for an
    id it does not know, so its `value == null` guard has never fired. It now warns once per id,
    naming the id and the asset it came from. **The state returned is unchanged, so no world output
    moves**; making it a load error is a contract change for every third-party pack and is tracked
    separately. The legacy `name@meta` fallback below it is documented as dead in both directions:
    `Identifier.parse` rejects such a string on the line above, and `BlockStateData.upgradeBlock`
    does not handle that form anyway (measured).
  - *Tests.* `AssetsLoadedBeforeGenerationTest` drives `CityFeature.getDimensionInfo` through a level
    that throws the moment anything past `registryAccess()` is asked of it, and requires the stuff
    index to be populated by then - so the load moving back off the generation path fails a test
    rather than silently costing a player their spawn-area decoration. It also pins the index's
    single-write publication, and pins the `ServerLevelEvents.LOAD` registration itself: deleting
    that line would otherwise revert the eager validation to a mid-generation failure with the whole
    suite still green, because generation would go on loading the registries by itself.
- **No worldgen decision rides on an asset's name as a string any more - not on where its id lands in
  a hash bucket, and not on a bare-versus-qualified comparison.** A systematic sweep found four
  places, all pre-existing, none introduced by the qualification pass below; they land together
  because they are one class of bug. `digest-features.golden` moves from `c8267f7b4abfd44e` to
  `8a3215441fb9f46d` as a result. This is the **first of the two** deliberate golden regenerations in
  this work, not the only one: the decoration entry above moves both goldens again, on to
  `88af6b69e7762fbc` and `7c297c1e4ec1ce38`, which are the values that ship.
  `digest.golden` is unchanged *by this entry*, at `414cb71424d5e53f`. Each change was measured on
  its own so every movement is attributable; where a digest did **not** move, the reason is stated
  rather than assumed.
  - *The stuff ordinal no longer comes from a hash.* `Stuff.generateStuff` walks each tag's list
    assigning a running `stuffOrdinal`, and that ordinal is the RNG slot address every placement
    attempt of that decoration draws from. The list came from `STUFF.getIterable()`, a
    `ConcurrentHashMap`'s values - `Identifier` hash-bucket order - and the tag loop came from a
    `HashSet<String>` on `CityStyle`. So which of `stuff/chains.json` and `stuff/cobweb.json` (both
    tagged `rubble`) was ordinal 0 was decided by `hash("urbex:chains")` versus
    `hash("urbex:cobweb")`, and renaming either file would have relocated every chain and cobweb in
    the world. Measured, not assumed: that map hands the three bundled entries over as `example,
    cobweb, chains`, so cobweb held ordinal 0 and chains ordinal 1; sorted by `Identifier` they swap.
    `AssetRegistries.groupStuffByTag` now sorts each tag's list by `Identifier` and publishes it
    immutable (a `List.copyOf`, which is also safely published to the worker threads by the map write
    alone - the former `CopyOnWriteArrayList` was buying thread safety for an `add` that no longer
    happens after publication), and `CityStyle.stuffTags` is a `TreeSet`. **Both digests are
    unchanged by this, and not because the ordering was already right.** At the time of this change
    `AssetRegistries.load()` was
    called only from `ServerEventHandlers.onWorldTick`, while `DigestCheck` runs from
    `SERVER_STARTED` - before any tick - so `STUFF_BY_TAG` was empty for the whole of both digest
    runs and no decoration was placed in either window. The digests had, up to here, never covered
    the `Stuff`
    subsystem at all. **That was a separate and larger shipped defect, and it was player-visible.**
    `prepareLevels()` - "Preparing spawn area" - runs inside `initServer()`, before `SERVER_STARTED`
    and before any tick, and never calls `tickServer`, so `END_LEVEL_TICK` does not fire during it.
    Every chunk generated there is generated with `STUFF_BY_TAG` empty, and generated chunks are
    saved, so nothing heals them later: a newly created world has spawn cities with no decoration at
    all and cities a few hundred blocks out with it, and a visible boundary between. Where that
    boundary falls depends on how far spawn preparation and tick 1's chunk work happened to get -
    and `ServerLevel.tick()` does its chunk work before Fabric fires `END_LEVEL_TICK` at the tail, so
    those chunks land on the empty side too - which means two players on the same seed can get
    different boundaries. It was tracked on its own and deliberately not fixed here, where filling the
    maps earlier would have put decoration into both digest windows and swamped the per-change
    attribution this entry is built on - **it is closed by the decoration entry above**, which moves
    the load off the tick and regenerates both goldens so that the sampled windows cover `Stuff` for
    the first time. The ordering fix above is therefore pinned by unit tests
    rather than by a digest: `RegistryChainResolutionTest` feeds `groupStuffByTag` the exact order
    the hash map produces and requires the sorted one back.
  - *`inpart`, `belowpart` and `inbuilding` have one convention: fully qualified, everywhere.* They
    used to need opposite conventions in different files. `parts2[].inpart` and `parts[].belowpart`
    in `buildings/*.json` matched a qualified id, because `getRandomPart` hands back the raw string a
    part entry wrote; `inbuilding`, and `values[].inpart` in `conditions/*.json`, matched a *bare*
    name, because `ConditionContext.legacyMatchKey` stripped the `urbex:` namespace on the way in -
    while `DatapackReferenceIntegrityTest` requires those same files to write a qualified one. The
    consequence was concrete and shipped: `chestloot.json`'s two rail-dungeon entries compared
    `"urbex:rail_dungeon1"` against `"rail_dungeon1"` and had never once fired. `legacyMatchKey` is
    deleted and every producer now passes `getName()`, so **chest loot changes**: a chest in a rail
    dungeon now rolls `urbex:chests/raildungeonchest` at factor 20 alongside the general table
    instead of only the general table, which is what the pack has always said it should do. That is
    the entire cause of the `digest-features` movement - measured on its own, before any other change
    in this entry, and it produced exactly `8a3215441fb9f46d`. The bare-name comparison had one
    remaining producer and now has none. `"<none>"` (the one non-id value those slots take, for
    "there is no such thing here") is a named constant, `ConditionContext.NO_PART`, rather than nine
    scattered literals.
  - *One asset kind, one order.* `BuildingInfo`'s majority-cityStyle vote broke ties on the raw id
    string, which orders namespace-first, while `MultiChunk`'s city-style sort uses
    `Identifier.compareTo`, which orders path-first. Both were deterministic; they disagree the
    moment a second namespace ships a city style. The vote now counts `Identifier`s and breaks ties
    on `Identifier`'s own order, so the two agree by construction. `Counter.getMostOccuring` takes a
    `Comparator<? super T>` instead of a `Function<T, String>` for this - no single string key
    reproduces path-then-namespace, so the string form was what forced the two orders apart in the
    first place; the tie-break stays a mandatory parameter for the reason it was made one.
    **Confirmed inert**, as expected: every bundled city style is `urbex`-namespaced, so both digests
    were byte-identical across this change alone.
  - *A `parts2[]` entry's `belowpart` was an exact duplicate of its `inpart`.* `BuildingInfo`
    advanced `belowPart = randomPart` *before* building the context that selects `parts2[]`, so that
    selection saw `getBelowPart()` equal to `getPart()` - the same defect issue #58 fixed on the
    reading side in `ConditionContext.parseTest`, still alive on the writing side, in both copies of
    the floor loop. `Scattered` had the third variant of the same confusion - it reused the `parts[]`
    context for `parts2[]` outright, so a scattered building's `parts2[].inpart` was matched against
    `"<none>"` and could never fire while a city building's matched normally. Rather than fix the
    ordering three times, the `parts2` context is now *derived*: `ConditionContext.withPart` copies
    the floor's context and replaces only the current part, and `Building.getRandomPart2` takes the
    chosen part and applies it internally. That buys one invariant, stated no wider than it is: the
    `parts2` context's `belowPart` is always the `parts[]` context's, so a caller mutating its own
    local after building the floor context cannot reach it - which is precisely the defect that was
    shipping, and it is gone at all three sites, each of which also lost its duplicated second
    context. It is *not* a proof that `getPart()` can never equal `getBelowPart()`: the
    `ConditionContext` constructor is public, the `parts[]` contexts are still hand-written, and
    `getRandomPart2(rand, ctx, ctx.getBelowPart())` still compiles. Nothing rejects it on purpose -
    a building that repeats a part on consecutive floors legitimately has the two equal, and
    `library00` (one non-top part entry) does. Inert for the bundled pack (nothing in it writes
    `belowpart`, and no scattered-reachable building declares `parts2`), and measured to confirm it -
    which is also why no golden could catch a revert, so `BelowPartConditionTest` now covers the
    writing side alongside the reading side it already had, on the production `getRandomPart2` path.
  - *`parts[].inpart` never matching is correct, and is now documented as such rather than "fixed".*
    The context that selects `parts[i]` passes `NO_PART` as the current part because at that moment
    the floor genuinely has none - it is what is being chosen. What a `parts[]` entry can usefully
    condition on is the part *below*, which is `belowpart`, and that is passed correctly. Inventing a
    value here would have made `inpart` and `belowpart` synonyms on `parts[]`, which is the bug above.
  - *Things that are correct only by construction now say so - including one that is not fully
    correct.* `MultiChunk` keys a `Counter` and an `Objects.equals` on `CityStyle` identity - no
    `cityassets` class overrides `equals`/`hashCode`, so this works only because
    `RegistryAssetRegistry` canonicalises instances through `putIfAbsent`; if
    `AssetRegistries.reset()` ran mid-generation, one id could exist as two instances, splitting a
    style's votes and making the `getId()` sort stop being a total order. `reset()` is *not* confined
    to server start/stop, which is the part worth writing down: `CityFeature.cleanUp()` calls it and
    is invoked lazily from `CityFeature.getDimensionInfo()`, firing once per session because
    `dimensionInfoDirtyCounter` starts at `-1` - and not necessarily from generation, since that
    method also has callers in `DigestRunner`, `SpawnPlacement`, `StructureSuppressor` and the
    commands. What bounds it is narrow and is not a guarantee: `globalDimensionInfoDirtyCounter` is
    bumped only from client-side paths (`ClientEventHandlers`, `PresetSelection`), none of which run
    while a server generates, so in a *settled* session no reset lands between two chunks' style
    lookups. The first reconcile is not guaranteed to be a single call - `getDimensionInfo`'s
    check-then-act on two volatile ints is not atomic, `cleanUp()` is unsynchronized, and generation
    runs on the parallel worker pool - so one thread can be inside the style loop while another
    resets the registries underneath it. That window is pre-existing, is documented rather than
    closed here, and closing it means keying on ids or making the reconcile atomic.
    `City`'s five predefined-content maps are filled in name-hash order, so two predefined cities
    claiming one chunk resolve last-writer-wins by hash - left alone deliberately, since that is a
    pack authoring conflict rather than a silent reordering of a working configuration, and any rule
    for it (first-wins, or a load error naming both) is a validation decision.
  - *Tests.* `DatapackReferenceIntegrityTest` now checks `inpart`, `belowpart` and `inbuilding` on
    both `buildings` part entries and `conditions` values, in either their single-string or their
    array form - previously only `conditions`' `inpart` was checked, so the two fields whose
    convention this entry fixes were the two nothing was watching. `CounterTest` gains the
    path-first-versus-namespace-first case directly. `ConditionContextLegacyMatchKeyTest` is deleted
    with the function it pinned.
- **An unqualified datapack reference is now a load error instead of an implicit `urbex:` default.**
  `DataTools.fromName` is the single choke point every reference resolves through -
  `RegistryAssetRegistry`'s four lookup methods, `IdentifierMatcher`, and `Config`'s preset/worldstyle
  parsing all call it - so making it throw `IllegalArgumentException` for a string without a `:`
  closes off the implicit resolution this plan's `extends` work otherwise left standing: a bare name
  like `"radiotower"` used to silently resolve to `urbex:radiotower`, which could point at whatever
  happened to be registered there rather than at anything a file actually wrote, so a wrong reference
  was unfindable the moment it misbehaved. The message names both the offending string and the fix,
  e.g. `Unqualified datapack reference 'radiotower': references must name their namespace, e.g.
  'urbex:radiotower'`. Every registry's `extends` field used to decode through plain `Identifier.CODEC`,
  under which a bare name resolves against the `minecraft` namespace instead of erroring - a third,
  silent defaulting rule, inconsistent both with the strict rule above and with itself from one registry
  to the next. All thirteen registries now share one `DataTools.STRICT_IDENTIFIER_CODEC`, which wraps
  `fromName` and catches `RuntimeException` rather than just `IllegalArgumentException` -
  `Identifier.parse` throws `net.minecraft.IdentifierException` (a `RuntimeException`) for a
  qualified-but-malformed id, e.g. an uppercase letter, and that must fail as a clean per-file
  `DataResult.error` too, not escape the codec as a thrown exception. `DatapackReferenceIntegrityTest`'s
  `presets` case, previously checking only `cities.cityStyleAlternative`, now also checks
  `spawn.spawnCity` (an unqualified value is a hard throw at spawn placement, through the same strict
  `fromName`), `spawn.forceSpawnBuildings`/`spawn.forceSpawnParts` (compared against a resolved asset's
  own qualified id, so an unqualified authored value would otherwise never match anything, silently),
  and `icon` (its own check rather than the shared reference helper - it is a texture path under
  `assets/urbex`, not a `data/` registry reference, so this only confirms the file exists). The same
  widened test caught that `palettes` had no case at all (30 files, only `extends` and `palette`
  entries, both already checked elsewhere - closed with the same no-op case `variants` already had) and
  that `largecities.json`'s `cityStyleAlternative: "citystyle_border"` was the bundled pack's last
  unqualified reference, now `"urbex:citystyle_border"`. The test's former silent fall-through category
  is now itself a failure naming the uncovered category, so a fourteenth registry cannot go uncovered
  the way `presets` and `palettes` did.
- **`DataTools.toName`/`fromDisplayName` and the "two names per asset" they existed to bridge are gone;
  every asset is one qualified id everywhere, including on screen.** Twelve `cityassets` classes
  (`Building`, `BuildingPart`, `CityStyle`, `Condition`, `MultiBuilding`, `Palette`, `PredefinedCity`,
  `ScatteredBuilding`, `Style`, `StuffObject`, `Variant`, `WorldStyle`) plus the `IBuildingPart`
  interface `BuildingPart` implements had a `getName()` that stripped the `urbex:` namespace for
  display, alongside a `getId()` that never did - a split that made sense while bare names were legal
  in a datapack (a Lost Cities convention this mod inherited), but not once the entry above makes them
  illegal: a stripped name no longer matches anything a datapack or config file is allowed to write.
  `getName()` on all twelve (thirteen counting the interface) now returns the same fully-qualified id
  as `getId().toString()`. The Cities tab, the Customize editor and the world-creation preview - which
  held worldStyle ids as `toName()`-shortened strings because `Preset` carries no worldStyle field of
  its own - now show and round-trip the qualified id throughout; labels are longer as a direct result,
  which is the intended outcome: once a second datapack is installed, a bare `"standard"` next to a
  bare `"moderntweaks"` no longer says who owns which. `EditModeData`'s persisted part name (read back
  by the `editpart`/`resumeedit`/`exportpart` edit-mode commands) changes format the same way - this
  fork's clean-break policy applies here too, and there are no released worlds to protect. One latent
  bug is fixed as a direct consequence, not a deliberate change: `/urbex locatepart` compared a stored
  (bare) name against a fully-qualified command argument and could never find anything; both sides are
  qualified now, so it works.
- **`Counter.getMostOccuring()`'s tie-break is now a stated rule instead of `HashMap` iteration
  order, and the tie-break is a required parameter instead of an implicit `String.valueOf`.**
  `BuildingInfo`'s majority-cityStyle vote among a chunk's neighbors produces an even split at every
  style boundary (ten votes: 3x3 neighbors plus the center counted twice), and the old tie-break fell
  through to whichever key `HashMap` happened to visit first, which depends on each key's hash bucket:
  an unrelated rename of a city style could silently move which side of a boundary a tied chunk falls
  on. Ties now break on the smallest key under an order the caller supplies, and supplying it is
  mandatory rather than the method defaulting to `String.valueOf` - a key type with
  no meaningful `toString()` (e.g. `CityStyle`, whose default embeds an identity hash) would otherwise
  make the tie-break *look* deterministic while it still varied run to run, the same bug moved one
  layer down rather than fixed. (This entry originally described that parameter as a
  `Function<T, String>` tie-break *key* and the rule as "the lexicographically lowest
  `tieBreakKey.apply(key)`". A later task in this same work replaced it with a
  `Comparator<? super T>` - see the "One asset kind, one order" bullet above for why no single string
  key reproduces `Identifier`'s path-then-namespace order - and this entry is corrected to match the
  code that ships. There is no `tieBreakKey` API.) That was not hypothetical: `MultiChunk`'s own city-style sort
  (`styleList.sort(Comparator.comparing(...))`, which feeds the weighted pick that decides which
  multibuilding gets placed) used `CityStyle::getName` and is a second-order dependency on the very
  accessor the entry above just requalified. The bundled pack is entirely `urbex`-namespaced, so the
  sort order is unchanged and both digests stay clean - but with a third-party city style in play, a
  bare `citystyle_x` used to sort before `lostcities:citystyle_a` (`c` < `l`) and `urbex:citystyle_x`
  now sorts after it (`u` > `l`): a silent reorder, exactly the class of accident this pass exists to
  close off. Switched to `CityStyle::getId`, which cannot change meaning under a future `getName()`
  edit. **Neither determinism fix is verified worldgen-inert by the shipped digests** - the sampled
  windows contain no tied city-style boundary and no second namespace to reorder against, only one
  candidate reachable in generation at all (`urbex:standard`) - so a real tie, or a second datapack's
  city style, could lay out differently elsewhere than either used to. `CounterTest` pins the tie-break
  rule directly: a unique winner is unaffected, a tie's winner does not depend on insertion order, and
  the order comes from wherever the caller says, not from the key object's own `toString()`.
- **A separate, pre-existing bug the entry above could have fixed by accident, and deliberately did
  not:** `conditions/chestloot.json`'s `inpart` entries (`"urbex:rail_dungeon1"`/`"urbex:rail_dungeon2"`)
  are required to be fully qualified by `DatapackReferenceIntegrityTest`, but the runtime side of that
  comparison (`ConditionContext.getPart()`/`getBuilding()`) used to be fed by the very `getName()` two
  entries up just qualified - so qualifying it everywhere would have made a chest-loot condition that
  has never once fired since the qualified-`inpart` convention was introduced start firing, silently,
  as a side effect of an unrelated change. Confirmed by hand: reverting just that one path reverted
  `runDigestCheckFeatures` to the golden current at the time (`c8267f7b4abfd44e`), proving it was the
  entire cause of the digest shift this entry was written against. (It was *not* the only digest shift
  this work produced: two later regenerations follow it, and both goldens end at `88af6b69e7762fbc`
  and `7c297c1e4ec1ce38` - see the two entries above that move them.) The new
  `ConditionContext.legacyMatchKey(Identifier)` preserves
  the exact old (bare-for-`urbex`) comparison every `inpart`/`inbuilding` match used, pinned by
  `ConditionContextLegacyMatchKeyTest`, so this pass changes nothing about which chest-loot conditions
  fire - that mismatch stays a separate, tracked follow-up (worded so that *deleting* `legacyMatchKey`
  is the definition of done, not "fix `inpart` matching" - the bug now has a comfortable name, and a
  comfortable name is how it survives). **That follow-up landed inside this same work:** the
  "one convention: fully qualified, everywhere" bullet above deletes `legacyMatchKey` and its test,
  and the chest-loot condition fires from then on - so the deferral described here is history, not
  the shipped state. **Confirmed inert for everything the digest window reaches:**
  both digests regenerate unchanged (`414cb71424d5e53f` and `c8267f7b4abfd44e`, both `unsafeReads=0`).
  That is direct evidence for this `legacyMatchKey` decision, where every path it touches lies inside
  the window - it is *not* evidence for the two tie-break determinism fixes in the entry just above,
  whose own gap in digest coverage is stated there and stands on its own.
- **A datapack file now only has to state what it changes, on every registry rather than only on
  parts.** Twenty-two fields that the codec still demanded of every file are optional now:
  `predefinedcities.dimension`/`chunkx`/`chunkz`/`radius`/`citystyle`, `stuff.column`/`mincount`/
  `maxcount`/`attempts`, `multibuildings.dimx`/`dimz`/`buildings`, `buildings.filler`/`parts`,
  `scattered.terrainheight`/`terrainfix`, `worldstyles.outsidestyle`/`citystyles`,
  `conditions.values`, `palettes.palette`, `styles.randompalettes` and `variants.blocks`. Absent
  means "read the parent": each takes the value of the last file in the chain that declares one, so
  `{"extends": "urbex:oilrig", "buildings": ["urbex:oilrig_burnt"]}` is now a complete `scattered`
  file, and a second predefined city can be the first one moved by declaring nothing but its own
  `chunkx` and `chunkz`. This is the rule `parts` already got for `xsize`/`zsize`/`slices`, applied
  everywhere - on `multibuildings` every single field was required, which made `extends` there
  purely decorative. Requiredness has not gone away, it has moved: a field that *nothing* in the
  whole chain declares is a load error naming the asset and the field ("`'urbex:oilrig_burnt'`
  declares no `'terrainfix'`, and neither does anything it extends"), raised while the pack loads
  rather than surfacing as a null somewhere in generation. Keeping that promise meant resolving
  every registered asset up front: `worldstyles`, `multibuildings`, `styles`, `palettes`,
  `variants`, `conditions` and `scattered` used to be built lazily on first lookup from a worldgen
  worker, so a broken file in one of them would have failed mid-generation, and one nothing
  referenced would never have been checked at all. They are resolved at load now alongside `parts`,
  `buildings` and `stuff`, which does mean a broken third-party asset fails the world even when the
  player never selects it - the intended trade, and the same rule the rest of the format follows.
  (`scattered` was also missing from the registry reset, so a datapack reload kept serving stale
  objects; it is reset with the others now.) Along the way, `buildings` stopped
  encoding "undeclared" as a sentinel: `minfloors`/`maxfloors`/`mincellars`/`maxcellars` no longer
  use `-1` and `preferslonely` no longer uses `0.0` to mean "absent", so a child can now set an
  inherited `preferslonely` of `0.8` back down to `0.0`, or an inherited floor limit back to `-1`
  ("take the level's limit"), which under the old sentinels was impossible to say. Nothing in an
  existing datapack needs to change: a file that declares everything decodes exactly as it did
  before, and this only widens what is accepted. A third-party pack that relied on a file failing
  to load when it omitted one of these keys now gets that error from the resolved chain instead of
  from the codec, with the asset and the field named. Confirmed worldgen-inert: both digests
  regenerate unchanged (`414cb71424d5e53f` and `c8267f7b4abfd44e`, both `unsafeReads=0`).
- **All thirteen datapack registries now support `extends`, so an author never has to look up which
  asset types can build on another.** `buildings`, `parts`, `palettes`, `styles`, `multibuildings`,
  `scattered`, `conditions`, `variants`, `stuff`, `predefinedcities` and `worldstyles` join
  `citystyles` and `presets`: each takes one fully-qualified id of an asset in its own registry,
  chains are applied root-first, and a cycle or a dangling id is a load error naming the chain. Two
  of the eleven are more than a rename. **Palettes merge per character**, not per position: a child
  that repaints two markers out of thirty overwrites exactly those two and keeps the other
  twenty-eight, and an overridden entry takes its `damaged` mapping with it rather than leaving it
  keyed on a block the palette no longer places. That applies to the inline `palette` block a part
  or building can carry as well as to a registered `palettes` entry - a part that extends another
  and repaints two markers inline keeps the rest, rather than silently starting from nothing. An
  `extends` written *inside* an inline `palette` block is now a load error naming the owning asset:
  the codec accepts the key wherever a palette is embedded, but an inline block is not a registry
  entry, so nothing can resolve it and silently dropping it would let a file mean something other
  than what it says. Use `refpalette`, or put `extends` on the part or building itself.
  **Parts inherit their ancestor's geometry**:
  `xsize`, `zsize` and `slices` each come from the last file in the chain that declares one, so
  `{"extends": "urbex:radiotower", "refpalette": "urbexmt:radiotower_rusted"}` is a complete part
  file. Those three keys are therefore no longer required on a part; a part whose whole chain
  declares no `slices` (or no `xsize`/`zsize`) is a load error naming the part, and a part
  declaring a size that contradicts the slices actually in force is a load error naming the part,
  the declared size and the real width - not the silent truncation it would have been. Ordered list
  fields across these registries (`buildings.parts`/`parts2`, `parts.meta`,
  `styles.randompalettes`, `scattered.buildings`, `conditions.values`, `variants.blocks`,
  `stuff.tags`, `predefinedcities.buildings`/`streets`, `worldstyles.citystyles`/
  `citybiomemultipliers`) now decode through `Mergeable`, so a bare array still replaces what the
  chain inherited and `{"replace": false, "values": [...]}` appends to it instead. Two shapes stay
  wholesale replacements on purpose: `multibuildings.buildings` and `parts.slices` are grids, and a
  half-inherited grid would contradict its own declared dimensions. Nothing in a datapack has to
  change: `extends` is optional everywhere, every previously required key is still accepted, and
  the only keys *this* change made optional are the three on `parts` - the entry above then applies
  the same rule to twenty-two more, across ten registries. Third-party packs that relied on a
  part failing to load when it omitted `xsize`, `zsize` or `slices` now get that error from the
  resolved chain instead of from the codec, with the part named. `DatapackReferenceIntegrityTest`
  checks `extends` in every category rather than only in `citystyles`, so a fourteenth registry
  cannot skip the check. Confirmed worldgen-inert: both digests regenerate unchanged
  (`414cb71424d5e53f` and `c8267f7b4abfd44e`, both `unsafeReads=0`), so this is a load-path and
  format change only.
- **A city style's selector lists can now opt into appending to what they inherit, instead of
  always replacing it.** `CityStyle`'s nine selector fields (`buildings`, `bridges`,
  `largebridges`, `parks`, `fountains`, `stairs`, `fronts`, `raildungeons`, `multibuildings`) now
  decode through the new `Mergeable<E>` codec: a bare JSON array still replaces the inherited list,
  which remains the default and needs nothing extra from a datapack author, but the object form
  `{"replace": false, "values": [...]}` - the same shape vanilla tag files use - appends the file's
  own entries after the inherited ones instead, so a style can widen a selection without retyping
  its parent's. `Selectors`' nine fields changed type from `Optional<List<ObjectSelector>>` to
  `Optional<Mergeable<ObjectSelector>>` to carry the choice; the bundled datapack still ships only
  bare arrays, so no shipped file needs a change. A third-party city style that wants the old
  always-append behavior back for a given list should switch that list to the object form with
  `"replace": false`. Confirmed worldgen-inert: both digests regenerate unchanged
  (`414cb71424d5e53f` and `c8267f7b4abfd44e`, both `unsafeReads=0`).
- **Presets now declare `extends` instead of `parent`, resolved through the same chain walker city
  styles use.** `PresetRE`'s `parent` field is gone; it implements the `Extendable` interface and
  reads an `extends` field instead, like every other cross-reference. `Presets.resolve` no longer
  walks its own leaf-to-root loop and reverses it by hand - it delegates straight to
  `ExtendsChain.resolve`, which already returns the chain root-first, so the application loop is a
  plain forward loop now instead of a reversed one. A cycle or a dangling `extends` is an
  `IllegalStateException` naming the full chain (or both the missing id and the referrer), exactly
  like a bad city style `extends` - presets and city styles now fail identically instead of each
  keeping their own ad hoc parent-walking code. This is datapack-visible: any preset file, bundled
  or third-party, using `"parent"` must rename that key to `"extends"` - the value is unchanged,
  still a fully-qualified id such as `"urbex:default"`. `docs/presets.md` and
  `docs/schema/preset.schema.json` are updated to match, and `PresetSchemaTest` keeps them pinned to
  `PresetRE.KEYS`. Confirmed worldgen-inert: both digests regenerate unchanged
  (`414cb71424d5e53f` and `c8267f7b4abfd44e`, both `unsafeReads=0`), so this is a load-path and
  format change only.
- **City styles now declare `extends` instead of `inherit`, and their whole ancestor chain resolves
  once at load time instead of lazily on first use.** `CityStyleRE`'s `inherit` field (a bare
  string) is gone; it implements the new `Extendable` interface and reads an `extends` field
  instead, typed as a fully-qualified `Identifier` like every other cross-reference. `CityStyle` is
  now built once from the whole chain, root-first, via the new pure `ExtendsChain.resolve` (testable
  without a level - see `ExtendsChainTest`), and never mutates afterward: `CityStyle.init()`, its
  `volatile initialized` flag, its per-style monitor, and its per-thread `RESOLVING` cycle guard are
  all gone. A dangling `extends` or a cycle is now a load-time `IllegalStateException` naming every
  id in the chain, instead of the old guard's silent no-op that left a style half-resolved with no
  diagnostic. `RegistryAssetRegistry` now builds each asset from `Function<List<R>, T>` (the
  resolved chain) rather than `Function<R, T>` (the bare leaf), so all twelve dynamic registries
  route through the same chain resolution; eleven of the twelve still collapse it to just the leaf
  entry until a later task gives them `extends` support of their own. This is datapack-visible: any
  city style file, bundled or third-party, using `"inherit"` must rename that key to `"extends"` -
  the value is unchanged, still a fully-qualified id such as `"urbex:citystyle_common"`. Confirmed
  worldgen-inert: both digests regenerate unchanged (`414cb71424d5e53f` and `c8267f7b4abfd44e`, both
  `unsafeReads=0`), so this is a load-path and format change only.
- **A city style's own selector lists now replace the ones it inherits, instead of being appended
  to them.** `CityStyle.init()` unconditionally `addAll`'d the parent's nine selector lists on top
  of the child's, with no deduplication, so a style could only ever widen a selection - never
  narrow one, and never empty one. The bundled `urbex:citystyle_border` is the case that surfaced
  it: it lists 5 buildings and no multibuildings, but generated with 13 building entries
  (`building1`-`building5` at double weight plus all three of `citystyle_common`'s that it
  deliberately omits) and all 12 of the parent's multibuildings. An explicitly empty list was
  indistinguishable from an absent one, so "none here" was inexpressible. A list the child does not
  mention still inherits whole, so no style needs to restate what it wants unchanged. This is
  datapack-visible: a third-party city style that declared a selector list expecting it to add to
  its parent's now replaces it, and should list what it actually wants. Reachable in the bundled
  pack only through the `urbex:largecities` preset, which is the one place `cityStyleAlternative`
  names the border style - so both worldgen digests are unchanged (`414cb71424d5e53f` and
  `c8267f7b4abfd44e`, `unsafeReads=0`), and the fix is covered by `CityStyleInheritSelectorsTest`
  instead.
- **Presets are now datapack-driven.** The old `UrbexProfile`/`Configuration` machinery, its
  `config/urbex/profiles/*.json` files, and the legacy key migrations (`generateLighting`,
  `generateLoot`, `buildingWithoutLootChance`, `chestWithoutLootChance`, `basedOn`) are gone with
  no replacement parser. Presets are now a thirteenth dynamic registry (`urbex:presets`, registry
  path `urbex/presets`) contributed at `data/<namespace>/urbex/presets/<name>.json`; the 12
  built-ins ship in the mod's bundled datapack and resolve through an optional `parent` chain, so a
  preset file only needs to state what differs from its parent. This is a clean, unsupported break:
  no released Urbex worlds exist to protect, there is no migration for saved data that references
  the old format, and old worlds/configs are not read — testers should recreate worlds rather than
  reuse existing ones. This closes [#112](https://github.com/Arilas/urbex/issues/112): the stale
  `config/urbex/profiles/` file the issue was tracking can no longer exist, because nothing under
  `config/urbex/` is profile-shaped anymore. Confirmed by wiping `runs/digestcheck` and
  `runs/digestcheckfeatures` and regenerating both digest goldens from clean run directories — the
  only Urbex-owned files either run creates are `config/urbex/urbex.json` (empty `{}`) and
  `world/serverconfig/urbex.json` (`{ "selectedPreset": "urbex:default" }`); no `profiles/`
  directory or file appears anywhere under either run.
- **`useAvgHeightmap` now defaults to `true`.** Previously the code default was `false`, so terrain
  smoothing only applied where a profile explicitly opted in; every shipped preset now inherits the
  smoothed default unless a datapack opts out, and `urbex savepreset` on an unmodified preset shows
  `useAvgHeightmap: true`. The mod-config gate (`heightSampleSize > 2`, default 3) is unchanged.
- **Lighting density no longer silently resolves to zero.** Every shipped preset now carries an
  explicit, non-zero `decoration.lightingDensity` (values in the 0.05–1.0 range, zero only where a
  preset deliberately wants darkness), closing a gap where a preset that never set the field could
  end up dark with no lights placed via `CityGenerator.handleLightMarker` and no indication why.
- **New `urbex savepreset` command** writes the fully resolved preset (after walking the `parent`
  chain and applying any Customize-screen overrides) to disk as plain JSON, for inspecting or
  sharing exactly what a world is running.
- **Worldgen digests regenerated.** Both defaults changes above shift worldgen output, so
  `digest.golden` and `digest-features.golden` are regenerated against the new defaults. As always,
  the mod makes no promise that an existing world regenerates identically after an update.
- **Removed city spheres and their supporting system.** The `space`, `spheres`, and `cavernspheres`
  landscape types and the `space`, `biosphere`, and `biosphere_caves` presets are removed, along
  with predefined spheres, sphere profile settings, sphere asset fields, and sphere spawn targeting.
  The old monorail implementation is removed because it only connected spheres. A world config
  that still references one of the removed landscape types (for example a stale
  `config/urbex/profiles/space.json`) will crash on startup with `Bad landscape type: space!`;
  there is no fallback. Delete or edit any such profile file before upgrading.
- **Removed the `urbex:city` dimension.** It existed for historical reasons only (it was a plain
  overworld clone). Cities are enabled by picking a preset on the world-creation Cities tab or
  via the `dimensionsWithPresets` config. The sleep-on-a-special-bed teleport and its
  `specialBedBlock` config option are gone with it, and `dimensionsWithPresets` now defaults to
  empty. If an existing world has this dimension generated, leave it (return to the overworld)
  before upgrading — the dimension disappears and players still inside it will be relocated by
  vanilla.
- **Removed the `standard_everywhere` world style.** A backward-compatibility leftover that had
  not been kept up to date. `standard` is the only bundled world style; with a single style the
  world-style dropdown on the Cities tab stays hidden.
- **The bundled datapack is now fully namespaced.** Every internal asset reference is written
  `urbex:name` instead of relying on bare-name defaulting, and street/highway/railway part wiring
  is declared explicitly in `worldstyles/standard` and `citystyles/citystyle_common`
  (previously implicit Java defaults). Bare names in third-party datapacks still worked and still
  defaulted to the `urbex` namespace at the time this change landed - a later entry above ("An
  unqualified datapack reference is now a load error") removes that default entirely. A new test
  enforces that every shipped reference is namespaced and resolves.
- **Hierarchical streets replace the per-chunk street/park coin flip.** Every dimension now builds
  one deterministic road field of primary, secondary and tertiary roads, computed once from (seed,
  dimension id, road settings) rather than decided chunk by chunk. Primaries render through a new
  wide-road asset family (`urbex:street_large_*`, `urbex:street_stair`) at roughly double the width
  of an ordinary street, with a `connector` part overlaying every edge where an 8-wide minor road
  meets a 14-wide primary so the two surfaces meet without a gap. A primary planned across open
  water is carried on a planned bridge (the city style's `largebridges`, falling back to its
  ordinary bridge) when a deterministic per-span roll passes; a minor road one level below a
  same-level neighbour slopes up to meet it (`urbex:street_stair`) instead of stepping. A city
  chunk with neither a road nor a building is now an open lot - always grass - and is furnished
  with a weighted park part according to the chance below. All of this is configurable from a new
  **Roads** tab (spacing, activation and force-interval for primaries; count, separation and edge
  distance for secondaries; chance and length for tertiaries; chance, max length and the
  multibuilding/road conflict policy for bridges), with its own preview mode colouring each road
  class and `/urbex debug` street diagnostics alongside it.
- **Datapack- and config-breaking removals that come with hierarchical streets.** The city-style
  `parkchance` override (in `ParkSettings`) is gone with the legacy park-nomination path it tuned -
  a third-party datapack that sets it should remove the field. The `parkChance` profile setting
  (`PARK_CHANCE`) is removed; its replacement, `openLotParkChance` (`OPEN_LOT_PARK_CHANCE`), means a
  different thing - the chance that an open lot is furnished with a park part, not the chance a
  chunk becomes a park at all - so a config carrying the old key will simply stop taking effect.
  `StreetType.FULL` and `StreetType.randomNonPark()` are deleted; a planned road is always
  `StreetType.NORMAL`.
- **Removed the `full` street part.** `full` was a street *style* — a chunk paved corner to corner
  with no verge — not a topology; `all` is the four-way. It has been unreachable from generation for
  years upstream, was briefly revived by accident during this fork's Fabric port, and was removed
  again in the hierarchical-streets backport (#100). This finishes the job: the
  `street_full`/`street_large_full` assets, their declarations in `citystyle_common`'s `parts` and
  `largeparts` blocks, and the `full` component of `StreetParts` (record field, codec entry and
  `DEFAULT`) are gone. This is not a breaking change for third-party datapacks: `StreetParts` is
  decoded with `RecordCodecBuilder`, which reads only the field names it's built from and never
  validates an input object's key set, so a datapack that still declares `"full"` under `parts` or
  `largeparts` has that key silently ignored rather than rejected.
- **Removed the vine subsystem.** `ChunkFixer.generateVines` guarded all four of its wall passes on
  the neighbouring chunk having already reached `ChunkStatus.FEATURES` — a status city generation,
  now running at the carver stage, cannot reach there. Instrumented over the digest window: 179
  guard evaluations, 0 passes. The subsystem has been dead code since city generation moved to the
  carver stage (`15dba5f2`); with chunk-sized buildings most vine surface sits at exactly the border
  this guard blocked, so what still worked before that move was already a small fraction of the
  intent. It was also the order-dependence tracked as issue #20 (now closed): `createVineStrip`
  wrote straight to the world instead of through the driver, so whichever neighbouring chunk
  generated first silently decided the outcome, invisibly to `/urbex digest`. Removed rather than
  repaired: the generation code (`generateVines`, `createVineStrip`, `vineRoll`,
  `vineContinueRoll`), the `VINE_CHANCE` profile setting and its GUI slider, and the datapack's
  `vinewest`/`vineeast`/`vinesouth`/`vinenorth` `WorldSettings` fields are all gone. This is not a
  breaking change for third-party datapacks: `WorldSettings` is decoded with `RecordCodecBuilder`,
  the same construction confirmed above for `StreetParts`, which reads only the field names it's
  built from and never validates an input object's key set — a datapack that still declares
  `vinewest` (or its siblings) under `settings` has that key silently ignored rather than rejected.
  A building asset can already paint vines directly into its own design through the ordinary block
  palette, which accepts any block state at any position in the footprint including the border
  columns, so an asset can reserve a margin inside itself to hang them in — strictly more expressive
  than the system it replaces, which offered a single chance for the whole world and only four fixed
  vine-facing states. Because the guard never fired within the profiled generation window, this
  removal does not itself move block placement in already-generated chunks; as always, the mod makes
  no promise that a world regenerates identically after an update.
- **Every city layout moved.** `Rng.Purpose` no longer carries a single dead constant. Alongside the
  five vine constants above it dropped `STREET` and `HIGHWAY`, dead since long before this release,
  and `SPHERE`, `SPHERE_BLOCKS` and `SPHERE_CITY_LEVEL`, dead as of the sphere removal at the top of
  this section — ten constants gone, taking the enum from 51 entries to 41, every one of which now
  has a live caller. The alternative, keeping a dead constant as a reserved slot so that the ordinals
  below it never move, exists to protect *released* worlds; this fork has none to protect and already
  promises nothing about cross-version world stability, so the slots were deleted outright rather
  than renamed and kept. Every consumer addresses its stream as `purpose.ordinal() + 1`, so every
  constant after `BUILDING` shifted address, and with it every random stream downstream of one —
  which is effectively all of city generation. This is the largest user-visible effect in this
  section: every city in every world lays out differently past the point these changes land, not only
  at chunk borders. As always, the mod makes no promise that an existing world regenerates
  identically after an update.
- **Border fences, walls and stairs resolve their connections one step later.** `ChunkDriver` used
  to read — and, when the neighbour happened to already be FULL, write into — the neighbouring chunk
  to compute a border block's connection state; which of two adjacent chunks a worker thread
  generated first could change the result. Border positions are now marked for vanilla's own
  postprocessing pass instead, the same mechanism vanilla structures use across chunk borders, so
  the connection is computed once from every neighbour's final state rather than mid-generation.
- **Worldgen no longer logs cross-chunk read errors.** The two fixes above were the entire source of
  the `Detected unsafe terrain read during worldgen` spam in the log: a fixed digest window that
  logged 88 such warnings before these two fixes logs zero after them. A permanent gate
  (`UnsafeReadGateMixin`, enforced on every digest run via `-Durbex.digestCheck.failOnUnsafeRead`)
  now fails the check if a future change reintroduces a cross-chunk read or write anywhere in city
  generation.
- **Worlds generate differently past the already-generated chunk border, again.** As with `0.1.0`,
  this is expected and permitted: the mod makes no promise that an existing world regenerates
  identically after an update, and the road field changes what every city chunk resolves to.

## 0.1.0 — 2026-08-03 (alpha preview)

First preview release. Forked from Lost Cities 9.4.2 (Fabric/26.2 port) by McJty. See
`docs/history/CHANGELOG-lostcities.txt` for the upstream history.

**Alpha.** The mod generates and runs, but this release exists to get it in front of people, not
because it is finished. Worlds created with it are not guaranteed to generate identically on a
later version.

### The headline

Worldgen is now reproducible from the world seed. Previously it was not, in three separate ways:

- The generation feature held one `RandomSource` shared across every chunk in a dimension, and
  reseeded it *after* city generation had already drawn from it — so ruins and part selection
  consumed the previous chunk's leftover state.
- Rubble and leaf placement ran off a `static int` that no world seed ever touched.
- City placement itself ignored the seed entirely. Every world on a given profile put its cities in
  identical chunks at identical radii. Changing the seed did not move a single city.

All three are fixed. Randomness is now *addressed*: a stream is a pure function of the world seed,
a coordinate and a named purpose, so generation order cannot influence output and adding a consumer
cannot perturb an existing one.

### Also in this release

- **Parallel worldgen restored.** Generation was serialized behind a per-dimension lock to work
  around shared mutable state. That state is gone — generation state is now per-invocation, caches
  are owned by the dimension and concurrent, and lazily-initialized fields on shared assets are
  eager. The lock is removed.
- **World height.** Six sites assumed a 0–255 world. They now read the level's real bounds.
- **Profiles.** Editing a standard profile used to be impossible: the mod rewrote every profile
  JSON on launch and *then* read the directory, so your changes were overwritten before they were
  seen. Built-in profiles now go to `profiles/defaults/` as read-only reference, and files in
  `profiles/` are only created when absent.
- **Startup.** An unknown `selectedProfile` now fails at server start with the valid names listed,
  instead of throwing a `NullPointerException` during world initialization.
- Renamed throughout from Lost Cities to Urbex: mod id `urbex`, namespace `urbex:`, command root
  `/urbex` (alias `/ubx`), dimension `urbex:city`, config in `config/urbex/`.
- The NeoForge-shaped public API package was removed rather than ported. A Fabric-native API will be
  designed against the new generator's shapes.

### Not compatible with Lost Cities

Different mod id, namespace, saved-data ids and dimension. Lost Cities worlds, datapacks and configs
will not load. This is deliberate — see the design doc under `docs/superpowers/specs/`.

### Known limitations

Tracked, not hidden:

- [#18](https://github.com/Arilas/urbex/issues/18) — a small residual order-dependence (~11 block
  positions per 169 chunks) remains, because the mod reads neighbouring vanilla state during the
  decoration step. Architectural; the fix is structure-based placement.
- [#20](https://github.com/Arilas/urbex/issues/20) — vine generation is order-dependent *and*
  invisible to the verification harness, because those writes bypass the block driver.
- [#19](https://github.com/Arilas/urbex/issues/19) — the explosion-height scan is still bounded to
  Y 0–255 even though the surrounding bounds were widened.
- [#29](https://github.com/Arilas/urbex/issues/29) — the east-west and north-south highway networks
  are diagonal reflections of one another. Inherited from upstream, not introduced here.
- [#21](https://github.com/Arilas/urbex/issues/21)–[#28](https://github.com/Arilas/urbex/issues/28),
  [#30](https://github.com/Arilas/urbex/issues/30) — smaller items.

### Worldgen output changes

Output differs from Lost Cities, deliberately and in more than one way. Do not expect a Lost Cities
seed to reproduce here.

- Fixing the seed-independence bugs above necessarily moved everything that depended on them.
- **`PerlinNoiseGenerator14` now seeds `SimplexNoise` from `XoroshiroRandomSource` instead of
  `LegacyRandomSource`.** The two produce different permutation tables from the same seed, so the
  noise field differs and highway placement and city rarity move with it. The class was already
  seed-deterministic, so this was *not* required by the reproducibility work — it was taken because
  every other randomness source in generation was modernised in the same pass. Kept rather than
  reverted, because reverting would move output a second time.
- **Palette variant placement changed late in development.** Weighted palette characters were
  resolving to the same variant index at a given block, so minority variants (mossy, cracked) landed
  at identical offsets for every character. Each character is now addressed independently.
