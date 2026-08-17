# The site API

**Experimental.** `dev.krona.urbex.api` may change shape without a deprecation cycle, including
between patch releases. It is published so the design can be used and argued with, not because it is
settled. Pin the Urbex version you build against. Nothing outside that package is API.

## What this is for

Urbex normally decides for itself where cities go. A perlin field over the dimension picks the
centres, the terrain heightmap picks how high each chunk's city sits, and one world style governs the
level. That is the right default, and until now it was the only thing on offer.

A **site** is the same machinery with three of those answers supplied by another mod: *where*, *how
high*, and *how far it may reach*. The case it was built for is a cave bunker — a mod carves a
cavity underground and asks Urbex to fill it, at the cavity's depth, in a different world style from
the ruins on the surface, without a block escaping the cavity.

Everything else about a site is a preset, and a preset is a datapack file. Lighting density, floor
counts, cellar counts, ruin chance, corridor chance, road spacing: a site reaches all of them by
naming a preset, and reaches any of them individually through `presetOverrides`.

## The whole API

```java
public interface SiteField {
    boolean isSite(int chunkX, int chunkZ);
    default int groundY(int chunkX, int chunkZ) { return UrbexApi.DEFAULT_GROUND_Y; }
}

public record SiteSpec(Identifier id, Identifier preset, WorldStyleMix worldStyles,
                       @Nullable String presetOverridesJson, SiteField field,
                       int minY, int maxY, int waterY) {
    public static Builder builder(Identifier id, Identifier preset, SiteField field);
}

public interface UrbexSite {
    boolean fill(WorldGenRegion region, ChunkAccess chunk);
    SiteSpec spec();
}

public final class UrbexApi {
    public static UrbexSite site(ServerLevel level, SiteSpec spec);
    public static boolean isAvailable(ServerLevel level);
}
```

## The one rule that cannot be relaxed

**`SiteField` must be a pure function of the coordinate.**

It is tempting to read it as "the mod tells Urbex about the chunk it is filling". It is not. Urbex
plans a chunk by reading its *neighbours'* plans — whether a street continues across the border,
whether a doorway is cut through a shared wall, which of two competing stairs wins, whether a
multi-chunk building may be accepted here. Those reads reach coordinates you have not asked about and
may never ask about, on threads you do not own, in an order nobody controls.

So an implementation must be:

- **Pure.** The same coordinate answers the same thing forever — not "once the carver has run", not
  "once the cave is known". Reading a block, a chunk, a level, or any state a generation pass writes
  is wrong, and shows up as streets ending in rock and doorways opening into stone.
- **Total.** It answers for every coordinate in the dimension, including ones far from anything you
  care about.
- **Thread-safe.** It is called concurrently from the worldgen worker pool.
- **Cheap.** It is called for a neighbourhood per planned chunk. Hash arithmetic and noise are fine;
  anything that allocates per call is not.

The natural shape is a deterministic field derived from the world seed — a region grid, a noise
threshold, a hashed lattice. Your carver reads the same field, so what gets carved and what gets
built agree by construction rather than by timing. `Urbex-Bunkers`' `BunkerField` is a worked
example.

## Where `fill` can be called from

Anywhere a `WorldGenRegion` exists for the chunk: a `Feature`, a `StructurePiece` that has one, or —
the case this was built for — an injection at the tail of `ChunkGenerator.applyCarvers`, which is the
stage Urbex itself generates at and the point where the terrain is a pure function of the seed.

**Not from a `WorldCarver`.** A carver is handed a `CarvingContext` and a `ChunkAccess` and has no
region to write through. Carve in the carver and fill from the carver tail; both read the same
field, so they need no ordering between them.

Urbex dispatches nothing. A site generates when you call `fill` and at no other time — its own
carver-tail hook knows nothing about sites.

## The vertical window

`window(minY, maxY)` is inclusive at both ends and is enforced twice:

- **Planning.** The `LevelShape` a site plans against is clamped to the window, so floor counts are
  chosen to fit rather than being cut off.
- **Writing.** `ChunkBuffer`, the single point every driver write passes through, refuses everything
  outside it. A pass that believes it may build to the sky writes nothing above the window whatever
  it believes.

Deferred writes bypass the driver and are bounded separately, by anchor position, at the three queues
that accept them. That is exact for the block a todo addresses and approximate for anything its
callback touches around that block — the upper half of a door, the block a light attaches to.

Leave room. The window bounds planning as well as writing, so a window ten blocks tall does not
produce a squashed building, it produces a building with no floors in it.

It is also the only ceiling Urbex knows about. If your sites are chambers of differing heights, a
fixed window can only be tight for one of them, and every site with headroom to spare will plan a
building taller than its own chamber and drive the excess into the rock. Carve your roofs *to the
window* rather than to a fixed clearance above each floor, and the two agree at every depth at once —
`Urbex-Bunkers` does exactly this, and the comment on its `WINDOW_MAX_Y` explains why the obvious
alternative does not work.

One consequence worth knowing: because the window is absolute, a site and the surface city
**commute**. It does not matter which of them runs first, so you need not reason about mixin priority
against Urbex's own hook.

## Ground height is quantised to six blocks

`SiteField.groundY` is snapped down to a multiple of six above the window's bottom. Six blocks is a
storey, and it is the unit Urbex's whole height graph counts in: internally a site's ground becomes
`cityLevel`, the number every height comparison is written against — whether a doorway can be cut
through a shared wall, whether a street may slope, which of two competing stairs wins.

Return multiples of six above `minY` and nothing is lost. Return anything else and it is rounded,
because two parts of a site whose grounds differ by less than a storey have no way to say so to each
other.

## What a site does not get: highways and railways

Neither generates in a site, and this is not a setting.

Both are *networks*. Their height is measured from `groundLevel` rather than from a chunk's own city
ground, which is exactly what makes them networks: they hold one height across hundreds of chunks
while the cities they join ride up and down the terrain above them. A site's `groundLevel` is the
bottom of its window, with the per-chunk height carried in `cityLevel` instead — so the same offset
puts a railway eighteen blocks *below the window*, which for a bunker at y=−60 is outside the world.
That is what station staircases descending into the bedrock were.

Re-datuming them onto the chunk's city ground would land them somewhere legal and no better: a
network needs two ends, and a site is a pocket a few dozen chunks across with nothing to join.

Note that `railwayStationsEnabled` is a separate preset flag from `railwaysEnabled`, so a preset that
turns railways off — `urbex:cavern` does — still generates stations. That is a surprise on the
surface too; for a site it no longer matters, because neither reaches one.

**Connecting a site to the surface, or to a real network above it, is a feature and not this
exclusion.** A bunker with a staircase up to a ruined station would be worth having. It needs the
site to know where the surface is, which nothing here currently tells it.

## Spawning a world's players inside a site

`UrbexApi.spawnIn(factory, SiteSpawn.STREET)` asks that the world's spawn land inside your site.

**Off unless called.** A site changes nothing about where a world puts its players until a mod asks
it to — a mod that silently relocated spawn because it happened to be installed would be a bad guest.

Call it **once, at mod initialisation**: the world's spawn is chosen while the level loads, so a
claim made from the generation path has already missed it. That is also why it takes a
`SiteSpecFactory` rather than a `SiteSpec` — your spec almost certainly needs the world seed, which
does not exist that early. Return the same spec your generation path uses, id included, so the spawn
search and the chunks the player walks out into are the same memoised site.

`SiteSpawn.STREET` lands on a site chunk with no building — clear by construction and lit by the
preset's lighting density. `SiteSpawn.BUILDING` lands on a building's ground floor, which is more
dramatic and has more furniture for the search to reject.

A claim **outranks the dimension's own spawn settings**, and it works in a dimension with no Urbex
preset at all — which the dimension's own rules cannot, since they need one. A vanilla overworld
whose players wake up sealed in a bunker is a supported configuration.

If nothing is found — no site within 512 chunks of the origin, or no chunk matching `where` — the
world keeps the spawn it would have had and a warning names the site. Two mods claiming one world's
spawn is a modpack conflict: first registered wins, and the clash is logged.

## Spawners

`buildings.generateSpawners` is a switch — a world that wants none has none. `buildings.spawnerDensity`
(0–1) is the dial beside it: it thins what survives, position-addressed like `lootDensity`, so which
markers keep their spawner is a property of the seed and the coordinate rather than of the order
parts were generated in. Both must pass, and the density defaults to 1, so a preset that says nothing
about it keeps every marker.

The dial is the useful one for somewhere a player has to survive: "fewer" is not something a switch
can say.

## Two sites, one world

Nothing stops a mod registering several sites in one level, and it is how "this place is different
from that place" is expressed — a site carries one preset and one world style, so two kinds of place
is two sites.

Give them different ids (separate memo keys, separate caches, and separate road-grid addresses) and
**complementary fields**, so no chunk belongs to both. `Urbex-Bunkers` does this for its starter
bunker: one field view covers the bunker nearest the origin, the other covers every other, and
between them they cover exactly what the undivided field covered.

Two things to know. A site's id feeds its road field, so moving a bunker from one site to the other
changes its street layout. And `groundY` must stay the *whole* field's answer in both views — a chunk
on the edge of one reads neighbours belonging to the other, and the contract still says total.

## Cellars

`buildingMaxCellars` means what it says in a site. In a dimension the effective cap is
`buildingMaxCellars + cityLevel` — "how much room is there under this chunk before the datum", where
the datum is the preset's ground level and `cityLevel` is how far the city has climbed above it. A
site's datum is the bottom of your window, so the same term reads as *dig as deep as the window
allows*: a caller asking for 2 cellars at `cityLevel` 8 got up to 10. The term is dropped for sites.

## Water

A site is dry unless you say otherwise, and it ignores its preset's sea level to stay that way.

This is not a detail. A preset names one absolute sea level for a whole dimension — `urbex:cavern`
says 32 — and a bunker forty blocks under that is not underwater, but every rule that fills below the
water line thinks it is. A caller reaching for the obvious underground preset would get a bunker
flooded to the ceiling and no clue why. `waterY(y)` floods a site deliberately; omitting it leaves
the water table below the floor.

## What a site is, underneath

A second `PlanningContext` and `CityGenerator` over the same level, holding its own
`DimensionCaches`. Its plans therefore never collide with the level's own at the same chunk
coordinate, which is what lets one dimension run a heavily ruined world style on the surface and an
intact one underground.

A site *borrows* the world's compiled assets and block-tag epoch, and the level's deferred task
queue. It borrows nothing from the level's preset — so **a site generates in a dimension where Urbex
surface generation is switched off entirely.** A vanilla overworld with bunkers under it is a
supported configuration.

Building one is expensive (a preset resolution, a world-style field, a road field, a set of caches),
so `UrbexApi.site` memoises per `(level, spec.id())` and is cheap enough to call per chunk. Two calls
with one id return one site; registering two different specs under one id logs a warning and keeps
the first.

## Differences from an ordinary Urbex chunk

| | A dimension | A site |
|---|---|---|
| Where the city is | perlin city field vs. threshold | `SiteField.isSite` |
| Ground level | the preset's, one number for the dimension | the window's bottom, one number for the site |
| City height band | 0–8, from the terrain height | `(groundY − minY) / 6`, from `SiteField.groundY` |
| A non-city chunk | rendered: ground cover, terrain fix, scattered buildings | **untouched** |
| Structure avoidance | a village suppresses the city here | not applied; you named the place |
| Water | the preset's sea level, else the dimension's | `waterY`, and dry by default |
| Highways and railways | planned across the world | never generated |
| Cellar cap | `buildingMaxCellars + cityLevel` | `buildingMaxCellars` |
| Building height cap | the dimension's build limit | the window top — **one number for the whole site** |

The "untouched" row is what makes a site sparse, and it is the difference that matters most. Outside
a dimension's cities is still somewhere Urbex has an opinion about; outside a site is somebody else's
world.

## A complete caller

```java
public final class MyMod implements ModInitializer {

    private static final Identifier SITE = Identifier.fromNamespaceAndPath("mymod", "bunkers");
    private static final SiteField FIELD = new BunkerField(/* seeded per world */);

    private static UrbexSite site(ServerLevel level) {
        return UrbexApi.site(level, SiteSpec
                .builder(SITE, Identifier.fromNamespaceAndPath("urbex", "cavern"), FIELD)
                .worldStyle(Identifier.fromNamespaceAndPath("mymod", "bunker_style"))
                .presetOverrides("""
                        { "buildings": { "buildingMaxFloors": 2 },
                          "decoration": { "lightingDensity": 0.95 } }
                        """)
                .window(-60, 24)
                .build());
    }

    // called from an @Inject at the TAIL of ChunkGenerator.applyCarvers
    public static void generate(WorldGenRegion region, ChunkAccess chunk) {
        if (!FIELD.isSite(chunk.getPos().x(), chunk.getPos().z())) {
            return;                                  // the cheap test first
        }
        ServerLevel level = region.getLevel();
        if (!UrbexApi.isAvailable(level)) {
            return;                                  // the world has not compiled its assets yet
        }
        carveCavity(region, chunk);                  // your carver, reading the same FIELD
        site(level).fill(region, chunk);
    }
}
```

`minecraft-mods/Urbex-Bunkers` is this, finished and working.

## Failure modes

| Symptom | Cause |
|---|---|
| `IllegalStateException` from `site()` | called before any level loaded; guard with `isAvailable` |
| Streets end in rock, doorways open into stone | the field is not pure or not total |
| The site is flooded | `waterY` was set, or the window's bottom is under a water table you asked for |
| Buildings have no floors | the window is too short — it bounds planning too |
| Buildings punch through the chamber roof | the window is taller than the chamber; carve to the window |
| Doors open into a wall, or a floor has none | `groundY` steps by less than six, so two heights collapse to one band |
| A warning about two definitions under one id | the spec is not stable across calls; build it once |
| Nothing generates at all | `fill` is never called, or the field answers false everywhere |
