# One generation dispatch, and one failure contract (#131)

Phase 3, item 12 of epic #134. Two separate questions that the issue keeps together because they
share a call site: *how many times can Urbex generate one chunk*, and *what happens when doing so
fails*.

## Part 1 — dispatch

### What exists

Two entry points reach `CityFeature.generateFromPipeline`, and nothing coordinates them:

- **The carver tail.** `CarverHookMixin` injects at `TAIL` of `applyCarvers` on
  `NoiseBasedChunkGenerator` and `FlatLevelSource`. This is the real path, and the reason it exists
  is #18: at the decoration stage a neighbouring chunk's feature pass may or may not have bled into
  this one, so anything Urbex read from the terrain depended on worker scheduling. At the carver tail
  the terrain is a pure function of the seed.
- **`CityFeature.place`.** The `urbex:city` feature is registered so a datapack *can* name it. The mod
  does not inject it anywhere and the bundled pack does not use it.

So today:

- a datapack that puts `urbex:city` in a biome's `features` generates the chunk **twice** — once at
  the carver tail and once at decoration, the second pass planning against terrain the first pass
  already rewrote;
- a chunk generator that is neither `NoiseBasedChunkGenerator` nor `FlatLevelSource` gets **no**
  Urbex generation at all, however its dimension is configured, and says nothing about it.

### Decision

Take the issue's second option — **an explicit per-chunk marker** — rather than deprecating the
feature path, and keep the feature path as the deliberate custom-generator integration point.

A `ChunkAccess` mixin carries one `@Unique boolean`, reached through a `GeneratedChunkMark`
interface. `generateFromPipeline` marks the chunk before it generates and refuses a chunk already
marked. The mark dies with the chunk object, so there is no map to grow and nothing to clean up.

This is chosen over the two alternatives on offer:

- *Deprecate the feature path.* It is the only way a custom generator can ever be supported, and
  removing it converts "double generation" into "no generation, silently" for those worlds.
- *Ask whether the carver hook applies* (`generator instanceof NoiseBasedChunkGenerator ||
  instanceof FlatLevelSource`). Stateless and tempting, but wrong for a generator that extends
  `NoiseBasedChunkGenerator` and overrides `applyCarvers` without calling `super`: the predicate says
  the hook ran, the hook did not, and the chunk gets nothing. A marker records what actually
  happened rather than what should have.

Custom-generator support becomes explicit in both directions:

- a generator the carver mixin applies to needs nothing — it is generated at the carver tail, and an
  explicitly placed `urbex:city` feature is refused with one log line per level;
- any other generator gets no carver hook, so placing `urbex:city` in its biomes is how a pack opts
  in, and the marker guarantees that this is once per chunk.

The cost is stated rather than hidden: a chunk generated through the feature path is generated at the
decoration stage, which is exactly the ordering #18 moved away from. That is a documented property of
opting in, not a regression of the supported path.

## Part 2 — failure contract

### What exists

```java
try {
    feature.generate(runtime, region, chunk);
} catch (Exception e) {
    Urbex.getLogger().error(...);
    ErrorLogger.logChunkInfo(...);
    ErrorLogger.report(...);
}
return true;                       // <- success, whatever happened
```

Every failure is one category: log it and report success. The chunk continues through the pipeline
and is saved.

Whether that is even survivable depends on *where* the failure landed, and the driver has a real
commit point that makes the three cases distinguishable:

| When | State of the chunk | What is currently saved |
| --- | --- | --- |
| Before `ChunkDriver.actuallyGenerate` | Nothing written — the driver buffers into its own section cache | Pure vanilla terrain, in the middle of a city |
| During `actuallyGenerate` (`flushToChunk`) | Partially written | Half a city |
| After it (`ChunkFixer.fix`, post-todos, block-entity cleanup) | City written, post-processing incomplete | A city missing its deferred writes |

### Direction

Three categories, as the issue sets out:

- **Configuration/asset validation failure** — already handled: `AssetCompiler` diagnostics refuse
  the world at load, and `DimensionRuntime.create` refuses a level whose city style cannot resolve.
  Nothing to do beyond stating that this is where such failures belong.
- **Retryable generation failure** — the exception propagates out of `generateFromPipeline`. On the
  carver path that fails the chunk task rather than saving a chunk that is quietly wrong; on the
  feature path `place` returns `false`.
- **Non-fatal diagnostic** — reported without changing the result. This is what `ErrorLogger.report`
  is for, and it stays.

Error reporting must be runtime-scoped and safe during shutdown. `ErrorLogger.report` already guards
the shutdown window (a worker finishing after `SERVER_STOPPED` used to NPE inside the error handler
and lose the message it was reporting); what it is not yet is scoped — it reads a static server
reference. That is the same `ServerAccess` coupling the milestone's completion criteria name.

### Verification

Fault injection at the three points above, asserting that the original failure survives and that no
partially-written chunk is reported as a success. This is a unit-testable property of
`generateFromPipeline` once the marker exists, since the marker is what tells a second attempt that
the first one happened.

## Order of work

1. **The marker.** `GeneratedChunkMark` + `ChunkAccess` mixin; `generateFromPipeline` refuses a
   second invocation; `place` documented as the custom-generator integration point. *(p3i.)*
2. **The failure contract.** Exceptions stop becoming success; the three categories are named in
   code; fault-injection coverage. *(p3j.)*

Both must leave the digest goldens alone: no supported path changes what a chunk contains.
