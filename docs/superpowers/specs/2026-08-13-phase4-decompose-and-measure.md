# Phase 4: decompose and measure (#11, #52, #53, #132)

Epic #134's fourth phase. Four issues, and one of them is a prerequisite for the other three: the
epic's own constraint is *"do not optimize deterministic generation paths solely from inspection;
attach measurements"*, and #53 and #52 are both partly performance claims.

## Order, and why it is not the epic's numbering

The epic lists #11, #52, #53, #132. The measurement half of #132 goes first instead, because every
other PR in this phase has to attach before/after numbers to it, and a baseline established after the
changes it is meant to justify measures nothing.

1. **#132a — the baseline.** A repeatable measurement any digest run can emit. *(This PR.)*
2. **#53 — typed palette entries**, and memoizing the per-part compile. The smallest change with a
   measurable claim attached, so it is also the first real test of whether the baseline is any use.
3. **#52 — `ChunkDriver`.** Explicit positions, and the read-marks-a-section-dirty defect.
4. **#11 — `CityGenerator`.** The largest, and the one whose only safety net is the digest suite.
5. **#132b — bounded caches** and the confirmed-dead allocation paths, with the numbers the first
   four produced.

## What "measured" has to mean here

The acceptance criteria ask for throughput, allocations, cache behaviour and tail latency. All four
are properties of one digest run, so they belong on the digest run rather than in a separate harness
nobody runs:

| Wanted | Where it comes from |
| --- | --- |
| Throughput | chunks and driver writes per second — the runner already times the drive |
| Allocations | `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, summed over every thread that generated anything |
| Cache behaviour | hits, misses, duplicate computations, evictions, sweeps and sweep time, per named cache |
| Tail latency | per-chunk generation time, as count/mean/max/p99 |

Off by default and gated on `-Durbex.metrics`, because a counter on the generation path that is
always on is a measurement that changes what it measures. When it is off, the instrumentation is a
static `false` and a branch the JIT removes.

Allocation is measured per *thread*, not per run, because worldgen fans out: a total that took
`Runtime.totalMemory()` deltas would measure the collector's mood. `getThreadAllocatedBytes` is
cumulative and exact per thread, so the run's figure is the sum of the deltas of every thread that
touched generation.

**JFR stays a recipe rather than a task.** `-PurbexDigestVmArgs` already forwards arbitrary JVM
arguments to every digest run configuration, so a profile is
`-PurbexDigestVmArgs=-XX:StartFlightRecording=filename=urbex.jfr,settings=profile`. Wrapping that in
a gradle task would add a second way to say the same thing.

## The counters, precisely

`GenerationMetrics` holds them. Three groups:

- **`chunk(nanos)`** — one call per generated chunk, from `CityGenerator.generate`. Aggregated into
  count, total, max and a coarse log-bucketed histogram for the p99. A histogram rather than a list
  because a list of a million longs is itself an allocation problem, and p99 to within a bucket is
  all a regression test needs.
- **`cache(name)`** — `TimedCache` reports hit, miss, race, evict, sweep(nanos) and a size sampled
  at report time. "Race" is the case `getOrCompute` documents and nothing counted: two threads
  computing the same pure value, which is harmless but is also exactly the waste #132 asks to
  quantify. There is no separate compute count — most of these caches populate through
  get-then-`putIfAbsent`, so it would read zero for them and non-zero for three, which looks like
  "these never compute" rather than "this does not apply". The miss count is the compute count.
- **`queue(depth)`** — the level task queue's high-water mark, for the "deferred queue backlog"
  criterion.

`PERF=` is one line, emitted next to `DRIVERDIGEST=` so a digest run is also a measurement run.

## The baseline, as of `59a381d`

`./gradlew runDigestCheckAvoid -PurbexDigestVmArgs=-Durbex.metrics` — 625 chunks requested, seed
135132278163449878, offset 0, on an 8-core laptop:

```
PERF=on chunks=625 generated=816 ms=24367 chunksPerSec=33.5 meanUs=4679.5 p99Us=32768 maxUs=76573
  allocMiB=9963 allocThreads=15 queueHighWater=598
  biomeInfo=10099/11438(88%)  candidate=67822/69030(98%)  chunkPlan=5616/6675(84%)
  cityLevel=570/1798(32%)     cityStyle=5023/6425(78%)    heightmap=22613/22795(99%)
  multiChunk=617/631(98%)     scatterAreaScan=0/4(0%)     worldStyle=0/0(0%)
```

Four things this says that inspection did not, and that #132b now has to answer:

- **~12 MiB allocated per generated chunk** (9963 MiB over 816). That is the number every allocation
  claim in this phase is measured against.
- **`cityLevel` hits 32%.** A memo table that misses two thirds of the time is either keyed wrong or
  asked about coordinates it will never be asked about again; both are worth knowing before anyone
  bounds it.
- **No sweep runs at all** in a 24-second drive, because the TTL is 300 seconds. The O(n) sweep
  #132 worries about is real but never observed by a digest run — so any claim about it needs the
  soak, not this.
- **`queueHighWater=598`** deferred tasks against a `todoQueueSize` of 20 per tick. The backlog is
  drained after generation in a real world, but the number is what a tick-budget argument starts
  from.

`generated` exceeds `chunks` because the pipeline pulls in neighbours to satisfy the requested
square; it is the honest denominator for a per-chunk figure.

## A blind spot the baseline found

`ChunkDriver` used one field as both the end-of-chunk corrections worklist and the `/urbex digest`
write log, and `putRange` only filled it when the recorder was on. So which positions received
connection properties and neighbour shape updates depended on whether a digest was running.

The interesting part is what happened when it was fixed: **nothing**. All six suites hash the same
chunks whether corrections run over every written position or only over cursor-written ones — about
26M recorded block writes, five worlds, no difference. The measurement is what made the change
decidable at all: correcting bulk fills costs ~6.5% of mean chunk time (3864µs against 4117–4206µs
on `runDigestCheckAvoid`) and no measurable wall clock, since the extra work parallelizes.

So the digests do not cover this class of defect, and a green six-suite run is not evidence about
it. Anything that changes *which* positions the corrections pass visits needs a unit test;
`ChunkDriverCorrectionsTest` is that test.

## What this PR does not do

It sets no cache limits and removes no allocation. Those are #132b, and doing them here would be
optimizing from inspection with a measurement bolted on afterwards — which is the thing the epic
constraint is about.
