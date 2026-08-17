# Experimental site API: letting another mod ask Urbex to build somewhere

**Status:** design approved 2026-08-16; implemented the same day. Two things changed during
implementation, both because a real world showed them — see "What building it changed" at the end.

## The problem

Urbex decides for itself where cities go. A perlin city field over the whole dimension picks the
centres, the terrain heightmap picks how high each chunk's city sits, and one world style governs the
whole level. That is the right default and it is the only thing on offer.

A mod that wants to put Urbex content somewhere Urbex would never choose has nowhere to stand. The
motivating case is a cave bunker: a third-party mod carves a cavity underground and wants Urbex to
fill it — at the cavity's Y rather than the surface's, in a different world style from the one
generating the ruins overhead, and without a single block escaping the cavity.

Everything needed is already in the mod. It is reachable only through decisions Urbex makes on its
own behalf.

## What we are building

An experimental Java API — `dev.krona.urbex.api` — through which another mod defines a **site**: a
patch of Urbex city with its own preset, its own world style, its own ground level and a hard
vertical window. The caller drives. Urbex dispatches nothing; a site generates when, and only when,
the caller says `fill`.

Plus `minecraft-mods/Urbex-Bunkers`, a working mod that carves cavities and fills them, to prove the
API is usable from outside.

### Non-goals

- No datapack surface. A site is defined in Java, by the mod that owns it.
- No new dispatch. Urbex's own carver-tail hook is untouched and knows nothing about sites.
- No change to any output Urbex generates today. Every seam below is inert when no site is involved.

## The public surface

Four types in `dev.krona.urbex.api`, marked experimental. Nothing else becomes public.

```java
/** Where this mod's sites are, and how high each one sits. */
public interface SiteField {
    boolean isSite(int chunkX, int chunkZ);
    int groundY(int chunkX, int chunkZ);
}

/** What a site is built from. A value; its identity is `id`. */
public record SiteSpec(Identifier id, Identifier preset, List<StyleWeight> worldStyles,
                       @Nullable String presetOverridesJson, SiteField field,
                       int minY, int maxY) { /* + builder */ }

/** A live handle onto one site in one level. */
public interface UrbexSite {
    boolean fill(WorldGenRegion region, ChunkAccess chunk);
}

public final class UrbexApi {
    public static UrbexSite site(ServerLevel level, SiteSpec spec);
    public static boolean isAvailable(ServerLevel level);
}
```

`presetOverridesJson` is the existing `PresetDefinition` overlay that dimension selection already
applies. Lighting density, floor counts, ruin chance, corridor chance and every other preset field
therefore reach a site with no new API at all.

### The purity contract, and why it is not negotiable

`SiteField` must be a pure, thread-safe function of the coordinate, and must answer for **any**
coordinate rather than only the one being filled.

Urbex plans a chunk by reading its neighbours' plans: whether a street continues, whether a doorway
is cut through a shared wall, whether a stair wins against a competing one, whether a multi-chunk
building may be accepted. Those reads reach coordinates the caller has not asked about and may never
ask about. A per-call `boolean bunkerIsHere` cannot answer them, and the visible result is streets
that stop in rock and doorways that open into nothing.

Putting the field in the spec keeps dispatch entirely in the caller's hands while making the edges
correct. The caller's carver reads the same field, so what gets carved and what gets built agree by
construction rather than by timing.

### Cost model

Building a site is expensive: a preset resolution, a world-style field, a road field and a fresh set
of per-dimension caches. `UrbexApi.site` therefore memoises per `(level, spec.id())` and is cheap to
call per chunk. Two specs sharing an id in one level are a programming error and are reported as one.

## The internal seam

`PlanningContext` gains a tenth component, `@Nullable SiteBinding site`, carrying the field and the
window. Six call sites consult it, and every one of them is a no-op when it is null:

| Where | Today | With a site |
|---|---|---|
| `CityField.isCityRaw` | perlin city factor vs. threshold | `field.isSite(x, z)` |
| `CityField.getCityLevel` / `cityLevelUncached` | terrain height band 0..7 | `0` — there are no surface bands underground |
| `ChunkPlan` constructor | `groundLevel = profile.groundLevel()` | `groundLevel = field.groundY(x, z)` |
| `CityGenerator.generateOrThrow` | a non-city chunk runs `doNormalChunk` | a non-site chunk returns having written nothing |
| planning assembly | `LevelTerrain`, level's `LevelShape` | flat `SiteTerrain`, `LevelShape` clamped to the window |
| `ChunkBuffer` | writes bounded by the level | writes bounded by the window |

A site is a second `PlanningContext` and a second `CityGenerator` over the same level, holding its
own `DimensionCaches`. Surface plans and site plans therefore never collide in a cache keyed by
`ChunkCoord`, and one dimension can run a heavily ruined world style at the surface and an intact one
underground.

A site borrows the level's `LevelTaskQueue` and the world's `TagEpoch` from the published
`DimensionRuntime` and `GenerationSession`. It does not borrow the level's preset or planning, so
**a site generates in a dimension where Urbex surface generation is switched off entirely** — a
vanilla overworld with bunkers under it and nothing above.

### Skipping the non-site chunk

A site is sparse. Where `isSite` is false, `fill` must write nothing at all: no ground cover, no
terrain correction, no scattered buildings, no bridge scan. That is one early return in
`generateOrThrow`, before `doNormalChunk`, and it is what stops a bunker layer repainting the world
it lives inside.

Where `isSite` is true, the ordinary city path runs unchanged, terrain correction included — that
correction is what gives the bunker a floor.

## The vertical window is a guarantee

Two mechanisms, deliberately:

**Planning** sees a `LevelShape` clamped to `[minY, maxY]`. Floor counts already clamp against
`shape().maxY()`, so a site plans buildings that fit rather than buildings that are cut off.

**Writing** is bounded at `ChunkBuffer`, the single choke point every driver write passes through.
`set`, `fill`, `fillWhere` and `remember` drop positions outside the window — one comparison each, on
a path that already indexes by Y. Nothing outside the window can reach a flushed section, whatever
any pass believes it is doing.

Deferred writes bypass the driver and are bounded separately, by anchor position, at the three
queues that accept them: post-todos, light todos and level tasks. This is exact for the block a todo
addresses and approximate for anything it touches around that block; the javadoc says so rather than
implying a guarantee the mechanism does not give.

A consequence worth stating: because the window is absolute, a site and the surface city **commute**.
It does not matter which of them runs first, so a caller need not reason about mixin priority
against Urbex's own carver-tail hook.

## The example mod: `minecraft-mods/Urbex-Bunkers`

A standalone Fabric mod, depending on Urbex.

- `BunkerField implements SiteField` — a coarse region grid; each region hashes to a centre, a
  radius and a depth. Pure, allocation-free, no state.
- A cavity pass that hollows rock around the site, driven off the same field.
- The `UrbexApi` call that fills it.

Both run from one `applyCarvers` TAIL hook, the stage Urbex itself uses, because that is where a
`WorldGenRegion` exists. A raw `WorldCarver` receives a `CarvingContext` and cannot write through a
region, so it cannot call `fill`; the API javadoc states this rather than leaving a caller to find it
by exception.

The mod configures the site with a different world style from the surface and a window well below
sea level, which is the whole scenario the API exists for.

## Verification

Everything above is unreachable when `site == null`, so the five digest goldens must not move:
`digest`, `digest-features`, `digest-avoid`, `digest-avoid-modes`, `digest-rail`. Run at the default
worker pool size and again at `-Dmax.bg.threads=2`, and report the hashes.

New unit tests:

- `ChunkBuffer` drops writes outside a window and keeps writes inside it, including a run that
  straddles a boundary and a section that is partly in and partly out.
- A site's `DimensionCaches` and the level's are distinct — the same `ChunkCoord` plans differently
  in each.
- `SiteField` answers drive `isCity` and `groundLevel`, and a site's `cityLevel` is 0.
- A spec re-registered under the same id in the same level returns the same handle.

## What building it changed

Both of these were found by generating a world with `Urbex-Bunkers` loaded and diffing the driver's
per-chunk write log against a run without it. Neither was visible from the design.

**A site is not subject to structure avoidance.** The design put the sparse early return before the
structure probe, which left a path around it: the probe can turn `doCity` off *after* that point, and
a site reaching the `else` branch ran `doNormalChunk` — exactly the world-repainting this was
supposed to prevent. In the probe world it cost ten of a bunker's eleven chunks, which generated four
layers of terrain correction and nothing else.

The fix is not another guard in the same place. Avoidance exists to stop Urbex's own city noise
bulldozing a village it happened to roll on top of; a caller naming a place has already decided, and
a bunker suppressed by a village forty blocks overhead is a hole in the middle of itself with streets
running into it — its neighbours' plans still say the middle is there. So a site skips the probe
entirely, along with the floating-dimension void probe, which asks a question `isCityRaw` has already
answered for a site. Two region reads saved per chunk as well.

**A site ignores its preset's sea level, and gains `waterY`.** A preset names one absolute sea level
for a whole dimension. `urbex:cavern` — the obvious preset for anything underground, and the one the
example mod reaches for — says 32, so the first working bunker came out flooded to the ceiling with
21,868 blocks of water. Nothing in the design was wrong; the interaction simply does not survive
contact with a site forty blocks below a dimension's surface.

Making the preset's sea level apply anyway would leave a trap every caller walks into once. So a site
takes its water level from `SiteSpec.waterY`, which defaults to "there is none", and a caller who
wants a flooded site asks for one.
