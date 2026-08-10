# Hierarchical Street System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Urbex's per-chunk street/park coin-flip with a deterministic global road field of primary, secondary and tertiary roads, rendered with a wide-road asset family, planned bridges over water, and sloped connections across one-level height differences.

**Architecture:** A pure, dependency-free `RoadField` (seam) answers "what road class is at this chunk and how does it connect", implemented by `GridRoadField` — a port of upstream Lost Cities `1.20-7.5.0`'s `HierarchicalStreetPlanner`. A new `ChunkContentResolver` owns the chunk-content precedence order extracted out of `BuildingInfo`. The renderer selects part families by road class. There is exactly one implementation and no legacy mode.

**Tech Stack:** Java 25, Fabric Loader 0.19.3, Minecraft 26.2, Gradle (Loom 1.17.17), JUnit 5, Mojang DFU codecs.

**Spec:** `docs/superpowers/specs/2026-08-10-urbex-hierarchical-streets-design.md`

## Global Constraints

- **Upstream source is read-only reference.** The `lostcities-upstream/1.20` branch shares no history with ours (`git merge-base` is empty). Never merge or cherry-pick. Read files with `git show lostcities-upstream/1.20:<path>`.
- **Package root is `dev.krona.urbex`.** Upstream's is `mcjty.lostcities`. Every ported file needs its package and imports rewritten.
- **API drift from upstream 1.20 (Forge/MC 1.20) to ours (Fabric/MC 26.2):** `net.minecraft.resources.ResourceLocation` → `net.minecraft.resources.Identifier`. Upstream 1.20 code referencing Forge registries, events or `ForgeEventHandlers` has no counterpart here and must not be ported.
- **Randomness:** all addressed randomness goes through `dev.krona.urbex.plan.Hash`. A `new Random(...)`, a shared `RandomSource` field, or `Math.random()` inside generation is a bug. Two logically independent decisions must never share an address and a key.
- **Datapack references are fully namespaced.** Every asset reference is written `urbex:name`, never a bare name. `DatapackReferenceIntegrityTest` enforces this.
- **Profile fields:** every public non-static field on `UrbexProfile` must have a descriptor in `Settings.java` and lang keys in `en_us.json`. `SettingsCompletenessTest` enforces this.
- **`digest.golden` discipline:** Tasks 1, 2, 3, 6 and 7 must leave it byte-identical. Tasks 4 and 5 change it and regenerate it. Regeneration procedure: delete `digest.golden`, run `./gradlew runDigestCheck` twice, confirm the two runs print the same digest, write it to `digest.golden`, commit.
- **Hard replace.** No `StreetGenerationMode`, no `HighwayGenerationMode`, no `LostCityWorldGenData`, no `LEGACY` branch. If a ported upstream file references any of them, that reference is deleted rather than translated.

## Commands

| Purpose | Command |
|---|---|
| Unit tests | `./gradlew test` |
| One test class | `./gradlew test --tests 'dev.krona.urbex.plan.grid.GridRoadFieldTest'` |
| Compile | `./gradlew build -x test` |
| Digest check | `./gradlew prepareDigestCheck runDigestCheck` |
| Client (visual check) | `./gradlew runClient` |

## File Structure

**New pure module** — no Minecraft imports, ever. `./gradlew test` must pass on these without a game.

| File | Responsibility |
|---|---|
| `plan/Hash.java` | splitmix64 mixing, extracted out of `Rng`; the single addressing primitive |
| `plan/RoadType.java` | `NONE`/`TERTIARY`/`SECONDARY`/`PRIMARY` + `strongest()` |
| `plan/RoadDirection.java` | `NORTH`/`SOUTH`/`WEST`/`EAST` with step deltas |
| `plan/TertiarySegment.java` | one access road: origin, direction, length, `contains()` |
| `plan/RoadCell.java` | road class + 4 connections + diagnostic block data |
| `plan/RoadField.java` | the seam interface |
| `plan/EffectiveRoad.java` | pure 4-input city-clipping rule |
| `plan/grid/GridRoadField.java` | the ported planner; implements `RoadField` |
| `plan/grid/GridSettings.java` | validating settings record |
| `plan/grid/GridPurpose.java` | upstream's 14 salts as named keys |

**Minecraft-coupled**

| File | Responsibility |
|---|---|
| `worldgen/lost/ChunkContent.java` (new) | immutable result of the content decision |
| `worldgen/lost/ChunkContentResolver.java` (new) | owns the precedence order, extracted from `BuildingInfo` |
| `worldgen/lost/PrimaryBridgePlanner.java` (new) | planned-bridge span resolution |
| `worldgen/lost/BuildingInfo.java` | becomes a consumer of `ChunkContent` |
| `worldgen/lost/MultiChunk.java` | queries the raw road field |
| `worldgen/CityGenerator.java` | part-family selection, connectors, slopes |
| `worldgen/IDimensionInfo.java` | holds the per-dimension `RoadField` |
| `config/MultiBuildingStreetConflict.java` (new) | conflict policy enum |
| `config/UrbexProfile.java` | 17 new fields, 1 removed |
| `gui/settings/Settings.java` | descriptors for the new fields |
| `gui/preview/CityPreview.java` | `ROADS` preview mode |

---

### Task 1: Road assets, schema and conflict policy

Pure data and schema. No generation behaviour changes; `digest.golden` must not move.

**Files:**
- Create: `src/main/resources/data/urbex/urbex/palettes/street_large.json`
- Create: `src/main/resources/data/urbex/urbex/parts/street_large_{straight,bend,t,all,end,none,full,connector}.json`, `street_stair.json`, `bridge_large_open.json`
- Create: `src/main/java/dev/krona/urbex/config/MultiBuildingStreetConflict.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/StreetParts.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/StreetSettings.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/Selectors.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/CityStyle.java`
- Modify: `src/main/resources/data/urbex/urbex/citystyles/citystyle_common.json`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `MultiBuildingStreetConflict.roadBlocks(RoadType) -> boolean`, `MultiBuildingStreetConflict.byName(String) -> MultiBuildingStreetConflict`
  - `StreetParts.connector() -> List<String>`, `StreetParts.stair() -> List<String>`
  - `CityStyle.getLargeStreetParts() -> StreetParts`, `CityStyle.getTertiaryStreetParts() -> StreetParts`
  - `CityStyle.getRandomLargeBridge(RandomSource, ChunkCoord) -> String`

- [ ] **Step 1: Extract the upstream asset files**

The parts are plain 16x16 char-slice JSON using only vanilla blocks. Copy each one, then namespace it.

```bash
cd /Volumes/Dev/Projects/krona/minecraft-mods/LostCities
mkdir -p /tmp/upstream-street
for n in street_large_straight street_large_bend street_large_t street_large_all \
         street_large_end street_large_none street_large_full street_large_connector \
         street_stair bridge_large_open; do
  git show lostcities-upstream/1.20:src/main/resources/data/lostcities/lostcities/parts/$n.json \
    > /tmp/upstream-street/$n.json
done
git show lostcities-upstream/1.20:src/main/resources/data/lostcities/lostcities/palettes/street_large.json \
  > /tmp/upstream-street/street_large.json
```

- [ ] **Step 2: Copy the assets into the datapack**

Copy all eleven files into `src/main/resources/data/urbex/urbex/parts/` (ten parts) and `src/main/resources/data/urbex/urbex/palettes/` (the palette). Then in each part JSON, change `"refpalette": "street_large"` to `"refpalette": "urbex:street_large"`. The palette file itself needs no change — it references only `minecraft:` blocks.

Verify no bare references remain:

```bash
grep -l '"refpalette": "street_large"' src/main/resources/data/urbex/urbex/parts/*.json && echo "STILL BARE - fix these" || echo "all namespaced"
```

- [ ] **Step 3: Run the datapack integrity test**

Run: `./gradlew test --tests 'dev.krona.urbex.data.DatapackReferenceIntegrityTest'`
Expected: PASS. If it fails naming an unresolvable reference, the `urbex:` prefix is missing somewhere from step 2.

- [ ] **Step 4: Add the conflict policy enum**

Create `src/main/java/dev/krona/urbex/config/MultiBuildingStreetConflict.java`:

```java
package dev.krona.urbex.config;

import dev.krona.urbex.plan.RoadType;

import java.util.Locale;

/** How an accepted random multi-building resolves against a planned road under its footprint. */
public enum MultiBuildingStreetConflict {
    /** Any planned road under the footprint rejects the candidate. */
    BLOCK_ALL,
    /** Only a primary road rejects; accepted complexes suppress secondary and tertiary roads. */
    OVERRIDE_MINOR,
    /** No road rejects; every covered road is suppressed after acceptance. */
    OVERRIDE_ALL;

    public boolean roadBlocks(RoadType roadType) {
        return switch (this) {
            case BLOCK_ALL -> roadType != RoadType.NONE;
            case OVERRIDE_MINOR -> roadType == RoadType.PRIMARY;
            case OVERRIDE_ALL -> false;
        };
    }

    public static MultiBuildingStreetConflict byName(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown multiBuildingStreetConflict '" + name
                    + "'. Valid values: BLOCK_ALL, OVERRIDE_MINOR, OVERRIDE_ALL", e);
        }
    }
}
```

This references `dev.krona.urbex.plan.RoadType`, created in Task 3. To keep this task compilable on its own, also create the enum now — it is four constants and never changes:

```java
package dev.krona.urbex.plan;

/** How major a planned road is. Ordinal order is precedence order: later beats earlier. */
public enum RoadType {
    NONE,
    TERTIARY,
    SECONDARY,
    PRIMARY;

    public static RoadType strongest(RoadType first, RoadType second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }
}
```

- [ ] **Step 5: Write the failing test for the conflict policy**

Create `src/test/java/dev/krona/urbex/config/MultiBuildingStreetConflictTest.java`:

```java
package dev.krona.urbex.config;

import dev.krona.urbex.plan.RoadType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiBuildingStreetConflictTest {

    @Test
    void blockAllRejectsEveryRoadButNone() {
        MultiBuildingStreetConflict p = MultiBuildingStreetConflict.BLOCK_ALL;
        assertFalse(p.roadBlocks(RoadType.NONE));
        assertTrue(p.roadBlocks(RoadType.TERTIARY));
        assertTrue(p.roadBlocks(RoadType.SECONDARY));
        assertTrue(p.roadBlocks(RoadType.PRIMARY));
    }

    @Test
    void overrideMinorRejectsOnlyPrimary() {
        MultiBuildingStreetConflict p = MultiBuildingStreetConflict.OVERRIDE_MINOR;
        assertFalse(p.roadBlocks(RoadType.NONE));
        assertFalse(p.roadBlocks(RoadType.TERTIARY));
        assertFalse(p.roadBlocks(RoadType.SECONDARY));
        assertTrue(p.roadBlocks(RoadType.PRIMARY));
    }

    @Test
    void overrideAllRejectsNothing() {
        MultiBuildingStreetConflict p = MultiBuildingStreetConflict.OVERRIDE_ALL;
        for (RoadType t : RoadType.values()) {
            assertFalse(p.roadBlocks(t), t + " should not block under OVERRIDE_ALL");
        }
    }

    @Test
    void byNameIsCaseInsensitiveAndNamesValidValuesOnFailure() {
        assertEquals(MultiBuildingStreetConflict.OVERRIDE_MINOR,
                MultiBuildingStreetConflict.byName("override_minor"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MultiBuildingStreetConflict.byName("nonsense"));
        assertTrue(e.getMessage().contains("BLOCK_ALL"), "error should list valid values");
    }
}
```

- [ ] **Step 6: Run the test**

Run: `./gradlew test --tests 'dev.krona.urbex.config.MultiBuildingStreetConflictTest'`
Expected: PASS (the enum from step 4 already satisfies it). If `RoadType` does not resolve, step 4's second file was skipped.

- [ ] **Step 7: Extend `StreetParts` with connector and stair**

In `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/StreetParts.java`, add two components to the record, two codec entries and two `DEFAULT` entries. The full record header becomes:

```java
public record StreetParts(List<String> full, List<String> straight, List<String> end, List<String> bend,
                          List<String> t, List<String> none, List<String> all, List<String> connector,
                          List<String> stair) {
```

Add to the codec group, after the `all` line:

```java
            Tools.listOrStringList("connector", "urbex:street_large_connector", StreetParts::connector),
            Tools.listOrStringList("stair", "urbex:street_stair", StreetParts::stair))
```

and extend `DEFAULT` with `List.of("urbex:street_large_connector")` and `List.of("urbex:street_stair")` as its final two arguments. Keep the existing defaults' `urbex:` prefixes consistent with whatever the file already uses — if the current `DEFAULT` uses bare names, namespace all of them in this step so the file is internally consistent.

- [ ] **Step 8: Extend `StreetSettings` with largeparts and tertiaryparts**

In `StreetSettings.java` add two fields, two codec entries, two getters and two constructor parameters:

```java
    private final StreetParts largeParts;
    private final StreetParts tertiaryParts;
```

Codec, appended to the group after the existing `parts` line (note the trailing comma moves):

```java
                    StreetParts.CODEC.optionalFieldOf("parts").forGetter(l -> l.parts.get()),
                    StreetParts.CODEC.optionalFieldOf("largeparts").forGetter(l -> l.largeParts.get()),
                    StreetParts.CODEC.optionalFieldOf("tertiaryparts").forGetter(l -> l.tertiaryParts.get())
```

Getters:

```java
    public StreetParts getLargeParts() {
        return largeParts;
    }

    public StreetParts getTertiaryParts() {
        return tertiaryParts;
    }
```

Constructor: add `Optional<StreetParts> largeParts, Optional<StreetParts> tertiaryParts` as the final two parameters and assign `this.largeParts = largeParts.orElse(StreetParts.DEFAULT);` and the same for `tertiaryParts`.

- [ ] **Step 9: Add the `largebridges` selector**

In `Selectors.java` add a `largeBridgeSelector` field, a codec entry `Codec.list(ObjectSelector.CODEC).optionalFieldOf("largebridges").forGetter(l -> Optional.ofNullable(l.largeBridgeSelector))` placed immediately after the `bridges` entry, a getter `getLargeBridgeSelector()` returning `Optional<List<ObjectSelector>>`, and a matching constructor parameter in the same position.

- [ ] **Step 10: Wire the new schema into `CityStyle`**

In `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/CityStyle.java`:

- add fields `private final List<ObjectSelector> largeBridgeSelector = new ArrayList<>();`, `private StreetParts largeStreetParts = StreetParts.DEFAULT;` and `private StreetParts tertiaryStreetParts = StreetParts.DEFAULT;`
- in the street-settings consumer, alongside `streetParts = s.getParts();`, add `largeStreetParts = s.getLargeParts();` and `tertiaryStreetParts = s.getTertiaryParts();`
- in the selectors consumer, alongside the bridge line, add `s.getLargeBridgeSelector().ifPresent(largeBridgeSelector::addAll);`
- add getters:

```java
    public StreetParts getLargeStreetParts() {
        return largeStreetParts;
    }

    /** Tertiary roads fall back to the secondary-road family when a style does not define their own. */
    public StreetParts getTertiaryStreetParts() {
        return tertiaryStreetParts == StreetParts.DEFAULT ? streetParts : tertiaryStreetParts;
    }
```

- add a `getRandomLargeBridge(RandomSource rand, ChunkCoord coord)` that selects from `largeBridgeSelector` exactly as the existing `getRandomBridge` selects from `bridgeSelector`, and returns the result of `getRandomBridge(rand, coord)` when `largeBridgeSelector.isEmpty()`. Copy the existing bridge method's body and selection helper rather than inventing a new selection path, so weighting and coordinate addressing stay identical.

- [ ] **Step 11: Declare the wiring in the bundled datapack**

In `src/main/resources/data/urbex/urbex/citystyles/citystyle_common.json`, inside the existing `"street"` object add:

```json
    "largeparts": {
      "full": "urbex:street_large_full",
      "straight": "urbex:street_large_straight",
      "end": "urbex:street_large_end",
      "bend": "urbex:street_large_bend",
      "t": "urbex:street_large_t",
      "none": "urbex:street_large_none",
      "all": "urbex:street_large_all",
      "connector": "urbex:street_large_connector",
      "stair": "urbex:street_stair"
    }
```

and inside the existing `"selectors"` object add:

```json
    "largebridges": [
      {
        "factor": 1.0,
        "value": "urbex:bridge_large_open"
      }
    ]
```

Do not add `tertiaryparts` — omitting it exercises the fallback to `parts`, which is the intended default.

- [ ] **Step 12: Add lang keys for the conflict policy**

In `src/main/resources/assets/urbex/lang/en_us.json` add:

```json
  "urbex.enum.multibuildingstreetconflict.block_all": "Block All",
  "urbex.enum.multibuildingstreetconflict.override_minor": "Override Minor Roads",
  "urbex.enum.multibuildingstreetconflict.override_all": "Override All Roads",
```

The profile field and its descriptor arrive in Task 4; these keys are added now so the datapack and lang work land together.

- [ ] **Step 13: Verify the full suite and an unmoved digest**

Run: `./gradlew test`
Expected: PASS, including `DatapackReferenceIntegrityTest`.

Run: `./gradlew prepareDigestCheck runDigestCheck`
Expected: PASS against the existing `digest.golden`. Nothing in this task touches generation, so a changed digest means an asset accidentally replaced an existing one — check for an overwritten `parts/*.json`.

- [ ] **Step 14: Commit**

```bash
git add src/main/resources/data/urbex src/main/resources/assets/urbex/lang/en_us.json \
        src/main/java/dev/krona/urbex/config/MultiBuildingStreetConflict.java \
        src/main/java/dev/krona/urbex/plan/RoadType.java \
        src/main/java/dev/krona/urbex/worldgen/lost/regassets/data \
        src/main/java/dev/krona/urbex/worldgen/lost/cityassets/CityStyle.java \
        src/test/java/dev/krona/urbex/config/MultiBuildingStreetConflictTest.java
git commit -m "feat(streets): wide-road asset family, part schema and conflict policy

Ports the street_large part family, street_stair and bridge_large_open
from upstream 1.20-7.5.0, plus the largeparts/tertiaryparts/connector/
stair schema and the largebridges selector. Nothing consumes them yet.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Extract the chunk-content decision out of `BuildingInfo`

A behaviour-preserving refactor. `digest.golden` must not move — that is the proof.

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/ChunkContent.java`
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/ChunkContentResolver.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/BuildingInfo.java`
- Test: `src/test/java/dev/krona/urbex/worldgen/lost/ChunkContentResolverTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `ChunkContentResolver.resolve(BuildingInfo.Inputs) -> ChunkContent` with fields `hasBuilding`, `streetType`, `buildingName`, `openLot`. Task 4 inserts the planned-road branch into this resolver.

- [ ] **Step 1: Read the current decision site**

Read `BuildingInfo.java` lines 620–830. The decision currently lives inline in the constructor path: predefined-building lookup, highway levels, park chance, street type, fountain, park part, city factor, floor counts. Identify exactly which locals feed the *content* decision (has-building, street type) versus which feed rendering detail (fountain, palette, floors). Only the content decision moves.

- [ ] **Step 2: Define the result record**

Create `src/main/java/dev/krona/urbex/worldgen/lost/ChunkContent.java`:

```java
package dev.krona.urbex.worldgen.lost;

import javax.annotation.Nullable;

/**
 * What occupies one city chunk. The outcome of {@link ChunkContentResolver}'s precedence order,
 * computed once and consumed by {@link BuildingInfo}.
 *
 * @param hasBuilding  true when a building (single or part of a multi) occupies this chunk
 * @param streetType   how a non-building chunk renders; meaningless when {@code hasBuilding}
 * @param buildingName the selected building asset, null when {@code !hasBuilding}
 * @param openLot      true when this is a failed-building-roll lot rather than a nominated park
 */
public record ChunkContent(boolean hasBuilding,
                           BuildingInfo.StreetType streetType,
                           @Nullable String buildingName,
                           boolean openLot) {
}
```

`openLot` is always `false` until Task 4 — it exists now so Task 4 adds a branch rather than changing this record's shape.

- [ ] **Step 3: Move the decision into the resolver, unchanged**

Create `ChunkContentResolver.java` with one public static entry point. Move the *existing* logic verbatim — same order, same random draws, same conditions. Do not tidy, rename or reorder anything: any change of draw order changes the digest and defeats the check.

The resolver's signature takes the values `BuildingInfo` already has at that point in construction, passed explicitly rather than reaching back into a partially-constructed `BuildingInfo`:

```java
package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import net.minecraft.util.RandomSource;

/**
 * Owns the order in which candidate content claims a city chunk. Stated once, here, rather than
 * being implicit in {@link BuildingInfo}'s control flow.
 *
 * <p>Precedence, strongest first: hard exclusions and sphere/infrastructure constraints, predefined
 * buildings and multi-buildings, predefined streets, accepted random multi-buildings, ordinary
 * single-building chance with the lonely-building veto, and finally a non-building fallback.
 */
public final class ChunkContentResolver {

    private ChunkContentResolver() {
    }

    public static ChunkContent resolve(UrbexProfile profile, CityStyle cityStyle, RandomSource rand,
                                       boolean predefinedStreet, float cityFactor) {
        // Body cut from BuildingInfo, unchanged.
    }
}
```

**Cut and paste the body — do not retype it.** The whole value of this task is that the resulting
generation is byte-identical, and a retyped body is where a draw silently moves. Select the decision
code identified in step 1 in `BuildingInfo`, cut it, paste it here, and fix only what the compiler
complains about (field references becoming parameters).

The parameter list above is a starting point: the exact list is whatever step 1 found the decision
actually reads. Add parameters for those values and no others. If the decision reads a neighbour's
`BuildingInfo`, pass a narrow accessor rather than the whole object, so the resolver stays testable
with fakes in step 5.

- [ ] **Step 4: Make `BuildingInfo` a consumer**

At the site where the decision used to run, call the resolver and assign from the result:

```java
        ChunkContent content = ChunkContentResolver.resolve(profile, cs, rand, predefinedStreet, cityFactor);
        hasBuilding = content.hasBuilding();
        streetType = content.streetType();
```

Leave everything else in `BuildingInfo` untouched.

- [ ] **Step 5: Write a characterization test for the precedence order**

Create `src/test/java/dev/krona/urbex/worldgen/lost/ChunkContentResolverTest.java`. Assert the ordering properties that must survive Task 4's insertion — that a predefined street beats an ordinary building roll, and that a chunk with no claim falls through to a non-building result. Use a fixed-seed `XoroshiroRandomSource` so the draws are reproducible:

```java
package dev.krona.urbex.worldgen.lost;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ChunkContentResolverTest {

    @Test
    void predefinedStreetNeverProducesABuilding() {
        for (long seed = 0; seed < 200; seed++) {
            ChunkContent content = ChunkContentResolver.resolve(
                    TestProfiles.dense(), TestProfiles.cityStyle(),
                    new XoroshiroRandomSource(seed), true, 1.0f);
            assertFalse(content.hasBuilding(), "predefined street must beat the building roll, seed " + seed);
        }
    }
}
```

`TestProfiles` is a new small test helper in the same package producing a `UrbexProfile` with `BUILDING_CHANCE` at 1.0 and a minimal `CityStyle`. Write it as part of this step; it is reused by Task 4 and Task 7.

- [ ] **Step 6: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Verify the digest did not move — the actual point of this task**

Run: `./gradlew prepareDigestCheck runDigestCheck`
Expected: PASS against the unchanged `digest.golden`.

A failure here means the extraction changed behaviour, almost always because a random draw moved, was added, or was skipped. Do not regenerate the golden. Diff the moved code against its original and restore the draw order.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost src/test/java/dev/krona/urbex/worldgen/lost
git commit -m "refactor(worldgen): extract the chunk-content decision from BuildingInfo

Behaviour-preserving: the precedence order moves into ChunkContentResolver
verbatim, draws in the same sequence, and the digest is unchanged. This is
the seam the planned-road branch is inserted into next.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: The pure road field

Everything in this task compiles and tests without Minecraft. Nothing is wired yet; `digest.golden` must not move.

**Files:**
- Create: `src/main/java/dev/krona/urbex/plan/Hash.java`
- Create: `src/main/java/dev/krona/urbex/plan/RoadDirection.java`, `TertiarySegment.java`, `RoadCell.java`, `RoadField.java`, `EffectiveRoad.java`
- Create: `src/main/java/dev/krona/urbex/plan/grid/GridRoadField.java`, `GridSettings.java`, `GridPurpose.java`
- Modify: `src/main/java/dev/krona/urbex/varia/Rng.java` (delegate mixing to `Hash`)
- Test: `src/test/java/dev/krona/urbex/plan/grid/GridRoadFieldTest.java`, `src/test/java/dev/krona/urbex/plan/EffectiveRoadTest.java`

**Interfaces:**
- Consumes: `RoadType` from Task 1.
- Produces:
  - `RoadField.at(int chunkX, int chunkZ) -> RoadCell`
  - `RoadCell` components: `type`, `north`, `south`, `west`, `east`, `blockX`, `blockZ`, `westX`, `northZ`, `eastX`, `southZ`, `density`, `secondaryX`, `secondaryZ`, `tertiary`
  - `EffectiveRoad.resolve(RoadType raw, boolean isCity, boolean hasConnectedCityNeighbour, boolean overridden) -> RoadType`
  - `GridSettings.fromProfile(UrbexProfile) -> GridSettings`
  - `new GridRoadField(long seed, String dimensionId, GridSettings settings)`

- [ ] **Step 1: Extract `Hash` out of `Rng`**

Copy the file from the parked P3 branch, which already did this extraction:

```bash
git show feat/p3-road-system:src/main/java/dev/krona/urbex/plan/Hash.java \
  > src/main/java/dev/krona/urbex/plan/Hash.java
```

Then change `Rng` to delegate: replace its private mixing constants and `mix`/address arithmetic with calls to `Hash.at`, `Hash.atPos`, `Hash.atSlot`, `Hash.index`, `Hash.unit` and `Hash.mix`. The public `Rng` API does not change.

- [ ] **Step 2: Prove the extraction is bit-for-bit**

Run: `./gradlew test --tests 'dev.krona.urbex.varia.RngTest'`
Expected: PASS, with `GOLDEN` and `GOLDEN_LAST` unchanged.

A failure means the delegation altered the mixing — most likely a multiplier applied in a different order. Do not regenerate the vectors; fix the delegation.

- [ ] **Step 3: Create the small pure types**

`RoadDirection.java`:

```java
package dev.krona.urbex.plan;

public enum RoadDirection {
    NORTH(0, -1),
    SOUTH(0, 1),
    WEST(-1, 0),
    EAST(1, 0);

    private final int stepX;
    private final int stepZ;

    RoadDirection(int stepX, int stepZ) {
        this.stepX = stepX;
        this.stepZ = stepZ;
    }

    public int stepX() {
        return stepX;
    }

    public int stepZ() {
        return stepZ;
    }
}
```

`TertiarySegment.java`:

```java
package dev.krona.urbex.plan;

/** A short access road. The origin lies on an existing primary or secondary road and is not itself tertiary. */
public record TertiarySegment(long id, int originX, int originZ, RoadDirection direction, int length) {

    public boolean contains(int chunkX, int chunkZ) {
        int dx = chunkX - originX;
        int dz = chunkZ - originZ;
        int distance = dx * direction.stepX() + dz * direction.stepZ();
        return distance >= 1 && distance <= length
                && dx * direction.stepZ() == dz * direction.stepX();
    }
}
```

`RoadCell.java`:

```java
package dev.krona.urbex.plan;

import javax.annotation.Nullable;

import java.util.List;

/**
 * One chunk of a {@link RoadField}.
 *
 * <p>Generation consumes only {@link #type} and the four connection flags. Everything from
 * {@link #blockX} onward is diagnostic - it exists for {@code /urbex debug} and the preview, and no
 * rendering decision may read it. A {@link RoadField} with no notion of primary blocks supplies
 * zeroes and empty lists.
 */
public record RoadCell(RoadType type,
                       boolean north, boolean south, boolean west, boolean east,
                       int blockX, int blockZ,
                       int westX, int northZ, int eastX, int southZ,
                       double density,
                       List<Integer> secondaryX, List<Integer> secondaryZ,
                       @Nullable TertiarySegment tertiary) {

    public RoadCell {
        secondaryX = List.copyOf(secondaryX);
        secondaryZ = List.copyOf(secondaryZ);
    }

    public boolean isRoad() {
        return type != RoadType.NONE;
    }

    public boolean connects(RoadDirection direction) {
        return switch (direction) {
            case NORTH -> north;
            case SOUTH -> south;
            case WEST -> west;
            case EAST -> east;
        };
    }
}
```

`RoadField.java`:

```java
package dev.krona.urbex.plan;

/**
 * Where roads are. The single seam between road planning and everything that renders or reacts to
 * roads.
 *
 * <p>Implementations must be pure functions of their construction parameters and the queried
 * coordinate: no world state, no mutable random source, no dependence on query order. Callers clip
 * the returned field to the city mask via {@link EffectiveRoad}; a field never knows about cities.
 */
public interface RoadField {

    RoadCell at(int chunkX, int chunkZ);
}
```

`EffectiveRoad.java`:

```java
package dev.krona.urbex.plan;

/** The pure rule turning a raw road field into the roads a city actually renders. */
public final class EffectiveRoad {

    private EffectiveRoad() {
    }

    /**
     * @param hasConnectedCityNeighbour whether at least one chunk this road connects to is also raw city;
     *                                  this is what removes isolated one-chunk stubs at city protrusions
     */
    public static RoadType resolve(RoadType raw, boolean isCity,
                                   boolean hasConnectedCityNeighbour, boolean overridden) {
        if (!isCity || !hasConnectedCityNeighbour || overridden) {
            return RoadType.NONE;
        }
        return raw;
    }
}
```

- [ ] **Step 4: Write the `EffectiveRoad` truth table test**

Create `src/test/java/dev/krona/urbex/plan/EffectiveRoadTest.java`:

```java
package dev.krona.urbex.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectiveRoadTest {

    @Test
    void onlyAllThreeConditionsTogetherKeepTheRoad() {
        assertEquals(RoadType.PRIMARY, EffectiveRoad.resolve(RoadType.PRIMARY, true, true, false));
        assertEquals(RoadType.NONE, EffectiveRoad.resolve(RoadType.PRIMARY, false, true, false));
        assertEquals(RoadType.NONE, EffectiveRoad.resolve(RoadType.PRIMARY, true, false, false));
        assertEquals(RoadType.NONE, EffectiveRoad.resolve(RoadType.PRIMARY, true, true, true));
    }

    @Test
    void aNoneFieldStaysNoneUnderEveryCombination() {
        for (boolean city : new boolean[]{false, true}) {
            for (boolean neighbour : new boolean[]{false, true}) {
                for (boolean overridden : new boolean[]{false, true}) {
                    assertEquals(RoadType.NONE,
                            EffectiveRoad.resolve(RoadType.NONE, city, neighbour, overridden));
                }
            }
        }
    }

    @Test
    void roadClassIsPreservedNotPromoted() {
        assertEquals(RoadType.TERTIARY, EffectiveRoad.resolve(RoadType.TERTIARY, true, true, false));
        assertEquals(RoadType.SECONDARY, EffectiveRoad.resolve(RoadType.SECONDARY, true, true, false));
    }
}
```

- [ ] **Step 5: Create the settings record**

`src/main/java/dev/krona/urbex/plan/grid/GridSettings.java`. Validation bounds are upstream's; the error messages name the offending profile field because that is what a user sees:

```java
package dev.krona.urbex.plan.grid;

/**
 * Validated inputs to {@link GridRoadField}. Constructed once per dimension; a profile that cannot
 * produce a valid instance fails at load with a message naming the field.
 */
public record GridSettings(
        int primarySpacingX,
        int primarySpacingZ,
        float primaryOptionalChance,
        int primaryForceEvery,
        int secondaryMinCountX,
        int secondaryMaxCountX,
        int secondaryMinCountZ,
        int secondaryMaxCountZ,
        int minimumRoadSeparation,
        int minimumEdgeDistance,
        float tertiaryChance,
        int tertiaryMinLength,
        int tertiaryMaxLength
) {
    public GridSettings {
        if (primarySpacingX < 8 || primarySpacingX > 128) {
            throw new IllegalArgumentException("primaryRoadSpacingX must be 8..128, was " + primarySpacingX);
        }
        if (primarySpacingZ < 8 || primarySpacingZ > 128) {
            throw new IllegalArgumentException("primaryRoadSpacingZ must be 8..128, was " + primarySpacingZ);
        }
        if (primaryOptionalChance < 0 || primaryOptionalChance > 1) {
            throw new IllegalArgumentException("primaryRoadOptionalChance must be 0..1, was " + primaryOptionalChance);
        }
        if (primaryForceEvery < 1 || primaryForceEvery > 16) {
            throw new IllegalArgumentException("primaryRoadForceEvery must be 1..16, was " + primaryForceEvery);
        }
        if (secondaryMinCountX < 0 || secondaryMaxCountX > 128 || secondaryMinCountX > secondaryMaxCountX) {
            throw new IllegalArgumentException("secondaryRoadMinCountX/MaxCountX must satisfy 0 <= min <= max <= 128, were "
                    + secondaryMinCountX + "/" + secondaryMaxCountX);
        }
        if (secondaryMinCountZ < 0 || secondaryMaxCountZ > 128 || secondaryMinCountZ > secondaryMaxCountZ) {
            throw new IllegalArgumentException("secondaryRoadMinCountZ/MaxCountZ must satisfy 0 <= min <= max <= 128, were "
                    + secondaryMinCountZ + "/" + secondaryMaxCountZ);
        }
        if (minimumRoadSeparation < 2 || minimumRoadSeparation > 32) {
            throw new IllegalArgumentException("minimumRoadSeparation must be 2..32, was " + minimumRoadSeparation);
        }
        if (minimumEdgeDistance < 2 || minimumEdgeDistance > 32) {
            throw new IllegalArgumentException("minimumRoadEdgeDistance must be 2..32, was " + minimumEdgeDistance);
        }
        if (tertiaryChance < 0 || tertiaryChance > 1) {
            throw new IllegalArgumentException("tertiaryRoadChance must be 0..1, was " + tertiaryChance);
        }
        if (tertiaryMinLength < 1 || tertiaryMaxLength > 32 || tertiaryMinLength > tertiaryMaxLength) {
            throw new IllegalArgumentException("tertiaryRoadMinLength/MaxLength must satisfy 1 <= min <= max <= 32, were "
                    + tertiaryMinLength + "/" + tertiaryMaxLength);
        }
    }

    /** Upstream's defaults, used by tests and as the profile field defaults. */
    public static GridSettings defaults() {
        return new GridSettings(8, 8, 0.45f, 4, 0, 2, 0, 2, 4, 3, 0.40f, 2, 5);
    }
}
```

`fromProfile(UrbexProfile)` is added in Task 4, when the profile fields exist. Keeping it out now is what lets this package stay Minecraft-free and unit-testable in this task.

- [ ] **Step 6: Create the purpose keys**

`src/main/java/dev/krona/urbex/plan/grid/GridPurpose.java`:

```java
package dev.krona.urbex.plan.grid;

/**
 * Named keys for the independent hash streams inside {@link GridRoadField}, replacing upstream's
 * loose salt constants.
 *
 * <p>Same discipline as {@code Rng.Purpose}: two logically independent decisions taken at the same
 * address under the same key get the identical stream and silently correlate. Give a new decision a
 * new constant rather than reusing a neighbour's.
 *
 * <p>Keys occupy their own band, clear of {@code Rng.Purpose} (from 0) and of the plan-side band at
 * 2000, so no shipped stream can collide with another module's.
 */
public enum GridPurpose {
    PRIMARY_X_OFFSET,
    PRIMARY_Z_OFFSET,
    PRIMARY_X_ACTIVATION,
    PRIMARY_Z_ACTIVATION,
    DENSITY,
    SECONDARY_X_COUNT,
    SECONDARY_Z_COUNT,
    SECONDARY_X_POSITION,
    SECONDARY_Z_POSITION,
    TERTIARY_CHANCE,
    TERTIARY_SIDE,
    TERTIARY_ORIGIN,
    TERTIARY_LENGTH,
    OPEN_LOT_PARK,
    PLANNED_BRIDGE;

    private static final long OFFSET = 3000L;

    public long key() {
        return OFFSET + ordinal();
    }
}
```

`OPEN_LOT_PARK` is used in Task 4 and `PLANNED_BRIDGE` in Task 5. Both are declared now so no
ordinal shifts when they are consumed — a shifted ordinal silently changes every world.

- [ ] **Step 7: Port the planner**

Read the upstream source, which is 350 lines of pure Java:

```bash
git show lostcities-upstream/1.20:src/main/java/mcjty/lostcities/worldgen/street/HierarchicalStreetPlanner.java
```

Port it to `src/main/java/dev/krona/urbex/plan/grid/GridRoadField.java` implementing `RoadField`, with these mechanical substitutions and no algorithmic changes:

| Upstream | Ours |
|---|---|
| `PlannedRoadType` | `RoadType` |
| `PlannedStreetInfo` | `RoadCell` |
| `TertiaryRoadSegment` | `TertiarySegment` |
| `StreetPlannerSettings` | `GridSettings` |
| its private `mix`/`hash` and 14 `*_SALT` constants | `Hash.at(seed, x, z, GridPurpose.X.key())` etc. |
| its `stableStringHash(dimensionId)` | keep as-is; it must stay a stable FNV over the string, **not** `String.hashCode()` |
| `getRoadType(x, z)` | `at(x, z).type()` |
| `getStreetInfo(x, z)` | `at(x, z)` |

The dimension id is mixed into the seed once in the constructor, exactly as upstream does, so per-query cost is unchanged. Keep `Math.floorDiv`/`Math.floorMod` everywhere upstream uses them — truncating division creates a seam at coordinate zero, which step 9 tests for.

- [ ] **Step 8: Write the four smoke invariants**

These are the only planner tests written before the playable build. Create `src/test/java/dev/krona/urbex/plan/grid/GridRoadFieldTest.java`:

```java
package dev.krona.urbex.plan.grid;

import dev.krona.urbex.plan.RoadCell;
import dev.krona.urbex.plan.RoadType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridRoadFieldTest {

    private static GridRoadField field(long seed) {
        return new GridRoadField(seed, "urbex:test", GridSettings.defaults());
    }

    private static String sample(GridRoadField f, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int x = from; x < to; x++) {
            for (int z = from; z < to; z++) {
                sb.append(f.at(x, z).type().ordinal());
            }
        }
        return sb.toString();
    }

    @Test
    void sameInputsReproduceTheFieldExactly() {
        assertEquals(sample(field(1337L), -40, 40), sample(field(1337L), -40, 40));
    }

    @Test
    void changingTheSeedChangesTheField() {
        assertNotEquals(sample(field(1337L), -40, 40), sample(field(9001L), -40, 40));
    }

    @Test
    void changingTheDimensionChangesTheField() {
        GridRoadField a = new GridRoadField(1337L, "urbex:one", GridSettings.defaults());
        GridRoadField b = new GridRoadField(1337L, "urbex:two", GridSettings.defaults());
        assertNotEquals(sample(a, -40, 40), sample(b, -40, 40));
    }

    @Test
    void queryOrderCannotChangeTheAnswer() {
        GridRoadField f = field(1337L);
        List<int[]> coords = new ArrayList<>();
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                coords.add(new int[]{x, z});
            }
        }
        String rowMajor = sample(f, -40, 40);

        GridRoadField shuffledField = field(1337L);
        Collections.shuffle(coords, new java.util.Random(42));
        for (int[] c : coords) {
            shuffledField.at(c[0], c[1]);
        }
        assertEquals(rowMajor, sample(shuffledField, -40, 40),
                "a shuffled warm-up must not change later answers");
    }

    @Test
    void anActivePrimaryIsStraightAndContinuous() {
        GridRoadField f = field(1337L);
        int found = 0;
        for (int x = -64; x < 64; x++) {
            if (f.at(x, 0).type() != RoadType.PRIMARY) {
                continue;
            }
            boolean verticalEverywhere = true;
            for (int z = -64; z < 64; z++) {
                if (f.at(x, z).type() != RoadType.PRIMARY) {
                    verticalEverywhere = false;
                    break;
                }
            }
            if (verticalEverywhere) {
                found++;
            }
        }
        assertTrue(found > 0, "expected at least one continuous vertical primary corridor");
    }

    @Test
    void thereIsNoSeamAtCoordinateZero() {
        GridRoadField f = field(1337L);
        for (int z = -64; z < 64; z++) {
            RoadCell minusOne = f.at(-1, z);
            RoadCell zero = f.at(0, z);
            assertTrue(minusOne.eastX() >= minusOne.westX(),
                    "primary block bounds inverted at x=-1, z=" + z);
            assertTrue(zero.eastX() >= zero.westX(),
                    "primary block bounds inverted at x=0, z=" + z);
        }
    }
}
```

The straightness test scans z at a fixed x rather than asserting a specific coordinate, because our salts differ from upstream's so the exact corridor positions are ours, not theirs.

- [ ] **Step 9: Run the tests**

Run: `./gradlew test --tests 'dev.krona.urbex.plan.*'`
Expected: PASS.

If `anActivePrimaryIsStraightAndContinuous` finds zero corridors, the activation hash is reading the chunk coordinate instead of the candidate index — activation must depend only on the candidate index, which is what makes corridors straight.

- [ ] **Step 10: Verify nothing is wired yet**

Run: `./gradlew test && ./gradlew prepareDigestCheck runDigestCheck`
Expected: both PASS, `digest.golden` unchanged. Nothing consumes `RoadField` yet.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/dev/krona/urbex/plan src/main/java/dev/krona/urbex/varia/Rng.java src/test/java/dev/krona/urbex/plan
git commit -m "feat(plan): pure hierarchical road field behind a RoadField seam

Ports upstream 1.20-7.5.0's HierarchicalStreetPlanner as GridRoadField, a
pure function of seed, dimension and settings with no Minecraft imports.
Upstream's 14 salt constants become named GridPurpose keys drawn through
plan.Hash, extracted out of Rng - RngTest's golden vectors prove that
extraction is bit-for-bit.

Not wired to generation yet; the digest is unchanged.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Wire the roads — first playable build

This is the milestone. After this task, `./gradlew runClient` shows hierarchical streets in a real world. `digest.golden` changes once and is regenerated.

**Files:**
- Modify: `src/main/java/dev/krona/urbex/config/UrbexProfile.java`
- Modify: `src/main/java/dev/krona/urbex/gui/settings/Settings.java`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json`
- Modify: `src/main/java/dev/krona/urbex/plan/grid/GridSettings.java` (add `fromProfile`)
- Modify: `src/main/java/dev/krona/urbex/worldgen/IDimensionInfo.java`, `DefaultDimensionInfo.java`, `gui/NullDimensionInfo.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/ChunkContentResolver.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/BuildingInfo.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/MultiChunk.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/CityGenerator.java`
- Delete: `src/test/java/dev/krona/urbex/worldgen/lost/StreetTypeTest.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/ParkSettings.java`

**Interfaces:**
- Consumes: `RoadField`, `RoadCell`, `RoadType`, `EffectiveRoad`, `GridSettings` (Task 3); `ChunkContentResolver`, `ChunkContent` (Task 2); `MultiBuildingStreetConflict`, `CityStyle.getLargeStreetParts()`, `getTertiaryStreetParts()` (Task 1).
- Produces: `IDimensionInfo.roadField() -> RoadField`; `BuildingInfo.getEffectiveRoadType() -> RoadType`.

- [ ] **Step 1: Add the profile fields**

In `UrbexProfile.java` add these public fields near the other city fields, and **remove** `public float PARK_CHANCE = .2f;`:

```java
    public int PRIMARY_ROAD_SPACING_X = 8;
    public int PRIMARY_ROAD_SPACING_Z = 8;
    public float PRIMARY_ROAD_OPTIONAL_CHANCE = .45f;
    public int PRIMARY_ROAD_FORCE_EVERY = 4;
    public int SECONDARY_ROAD_MIN_COUNT_X = 0;
    public int SECONDARY_ROAD_MAX_COUNT_X = 2;
    public int SECONDARY_ROAD_MIN_COUNT_Z = 0;
    public int SECONDARY_ROAD_MAX_COUNT_Z = 2;
    public int MINIMUM_ROAD_SEPARATION = 4;
    public int MINIMUM_ROAD_EDGE_DISTANCE = 3;
    public float TERTIARY_ROAD_CHANCE = .40f;
    public int TERTIARY_ROAD_MIN_LENGTH = 2;
    public int TERTIARY_ROAD_MAX_LENGTH = 5;
    public float PLANNED_PRIMARY_BRIDGE_CHANCE = 1.0f;
    public int PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = 12;
    public float OPEN_LOT_PARK_CHANCE = .8f;
    public MultiBuildingStreetConflict MULTI_BUILDING_STREET_CONFLICT = MultiBuildingStreetConflict.OVERRIDE_MINOR;
```

Add matching `cfg.getInt`/`cfg.getFloat` lines in the config-reading method under `CATEGORY_CITY_ID`, using the ranges from `GridSettings`'s validation, and remove the `parkChance` line. For the enum, read it as a string and convert:

```java
        MULTI_BUILDING_STREET_CONFLICT = MultiBuildingStreetConflict.byName(
                cfg.getString("multiBuildingStreetConflict", UrbexProfile.CATEGORY_CITY_ID,
                        MULTI_BUILDING_STREET_CONFLICT.name(),
                        "How an accepted multi-building resolves against a planned road under its footprint"));
```

- [ ] **Step 2: Remove the dead city-style park override**

In `ParkSettings.java` delete the `parkChance` field, its codec entry, its getter and its constructor parameter. In `CityStyle.java` delete the `parkChance` field, its assignment and `getParkChance()`. Leave `parkStreetThreshold` alone — it drives park *elevation*, which open lots still use.

- [ ] **Step 3: Register the GUI descriptors**

In `Settings.java`, replace the `PARK_CHANCE` slider with `OPEN_LOT_PARK_CHANCE` in the same `BUILDINGS` section:

```java
        r.slider("OPEN_LOT_PARK_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.OPEN_LOT_PARK_CHANCE, (p, v) -> p.OPEN_LOT_PARK_CHANCE = ((Double) v).floatValue());
```

Then add four new sub-sections under `TRANSPORT`, before the existing highway section:

```java
        r.section("roads_primary");
        r.slider("PRIMARY_ROAD_SPACING_X", SettingCategory.TRANSPORT, 8, 128, 1,
                p -> (double) p.PRIMARY_ROAD_SPACING_X, (p, v) -> p.PRIMARY_ROAD_SPACING_X = (int) Math.round((Double) v));
        r.slider("PRIMARY_ROAD_SPACING_Z", SettingCategory.TRANSPORT, 8, 128, 1,
                p -> (double) p.PRIMARY_ROAD_SPACING_Z, (p, v) -> p.PRIMARY_ROAD_SPACING_Z = (int) Math.round((Double) v));
        r.slider("PRIMARY_ROAD_OPTIONAL_CHANCE", SettingCategory.TRANSPORT, 0.0, 1.0, 0.01,
                p -> (double) p.PRIMARY_ROAD_OPTIONAL_CHANCE, (p, v) -> p.PRIMARY_ROAD_OPTIONAL_CHANCE = ((Double) v).floatValue());
        r.slider("PRIMARY_ROAD_FORCE_EVERY", SettingCategory.TRANSPORT, 1, 16, 1,
                p -> (double) p.PRIMARY_ROAD_FORCE_EVERY, (p, v) -> p.PRIMARY_ROAD_FORCE_EVERY = (int) Math.round((Double) v));

        r.section("roads_secondary");
        r.slider("SECONDARY_ROAD_MIN_COUNT_X", SettingCategory.TRANSPORT, 0, 128, 1,
                p -> (double) p.SECONDARY_ROAD_MIN_COUNT_X, (p, v) -> p.SECONDARY_ROAD_MIN_COUNT_X = (int) Math.round((Double) v));
        r.slider("SECONDARY_ROAD_MAX_COUNT_X", SettingCategory.TRANSPORT, 0, 128, 1,
                p -> (double) p.SECONDARY_ROAD_MAX_COUNT_X, (p, v) -> p.SECONDARY_ROAD_MAX_COUNT_X = (int) Math.round((Double) v));
        r.slider("SECONDARY_ROAD_MIN_COUNT_Z", SettingCategory.TRANSPORT, 0, 128, 1,
                p -> (double) p.SECONDARY_ROAD_MIN_COUNT_Z, (p, v) -> p.SECONDARY_ROAD_MIN_COUNT_Z = (int) Math.round((Double) v));
        r.slider("SECONDARY_ROAD_MAX_COUNT_Z", SettingCategory.TRANSPORT, 0, 128, 1,
                p -> (double) p.SECONDARY_ROAD_MAX_COUNT_Z, (p, v) -> p.SECONDARY_ROAD_MAX_COUNT_Z = (int) Math.round((Double) v));
        r.slider("MINIMUM_ROAD_SEPARATION", SettingCategory.TRANSPORT, 2, 32, 1,
                p -> (double) p.MINIMUM_ROAD_SEPARATION, (p, v) -> p.MINIMUM_ROAD_SEPARATION = (int) Math.round((Double) v));
        r.slider("MINIMUM_ROAD_EDGE_DISTANCE", SettingCategory.TRANSPORT, 2, 32, 1,
                p -> (double) p.MINIMUM_ROAD_EDGE_DISTANCE, (p, v) -> p.MINIMUM_ROAD_EDGE_DISTANCE = (int) Math.round((Double) v));

        r.section("roads_tertiary");
        r.slider("TERTIARY_ROAD_CHANCE", SettingCategory.TRANSPORT, 0.0, 1.0, 0.01,
                p -> (double) p.TERTIARY_ROAD_CHANCE, (p, v) -> p.TERTIARY_ROAD_CHANCE = ((Double) v).floatValue());
        r.slider("TERTIARY_ROAD_MIN_LENGTH", SettingCategory.TRANSPORT, 1, 32, 1,
                p -> (double) p.TERTIARY_ROAD_MIN_LENGTH, (p, v) -> p.TERTIARY_ROAD_MIN_LENGTH = (int) Math.round((Double) v));
        r.slider("TERTIARY_ROAD_MAX_LENGTH", SettingCategory.TRANSPORT, 1, 32, 1,
                p -> (double) p.TERTIARY_ROAD_MAX_LENGTH, (p, v) -> p.TERTIARY_ROAD_MAX_LENGTH = (int) Math.round((Double) v));

        r.section("roads_bridges");
        r.slider("PLANNED_PRIMARY_BRIDGE_CHANCE", SettingCategory.TRANSPORT, 0.0, 1.0, 0.01,
                p -> (double) p.PLANNED_PRIMARY_BRIDGE_CHANCE, (p, v) -> p.PLANNED_PRIMARY_BRIDGE_CHANCE = ((Double) v).floatValue());
        r.slider("PLANNED_PRIMARY_BRIDGE_MAX_LENGTH", SettingCategory.TRANSPORT, 1, 64, 1,
                p -> (double) p.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH, (p, v) -> p.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = (int) Math.round((Double) v));
        r.cycle("MULTI_BUILDING_STREET_CONFLICT", SettingCategory.TRANSPORT,
                p -> p.MULTI_BUILDING_STREET_CONFLICT, (p, v) -> p.MULTI_BUILDING_STREET_CONFLICT = (MultiBuildingStreetConflict) v);
```

- [ ] **Step 4: Add the lang keys**

In `en_us.json` remove the two `PARK_CHANCE` keys and add, for each of the seventeen new settings, `urbex.setting.<KEY>` and `urbex.setting.<KEY>.tooltip`, plus the four section pairs:

```json
  "urbex.section.transport.roads_primary": "Primary Roads",
  "urbex.section.transport.roads_primary.desc": "The widest roads, running unbroken across the whole world.",
  "urbex.section.transport.roads_secondary": "Secondary Roads",
  "urbex.section.transport.roads_secondary.desc": "Ordinary streets filling the blocks between primary roads.",
  "urbex.section.transport.roads_tertiary": "Access Roads",
  "urbex.section.transport.roads_tertiary.desc": "Short dead-end lanes branching off larger roads.",
  "urbex.section.transport.roads_bridges": "Planned Bridges",
  "urbex.section.transport.roads_bridges.desc": "How primary roads cross open water.",
```

- [ ] **Step 5: Run the completeness test**

Run: `./gradlew test --tests 'dev.krona.urbex.gui.settings.SettingsCompletenessTest'`
Expected: PASS. A failure names the field missing a descriptor or the missing lang key — add exactly that, do not add to the test's `EXCLUDED` set.

- [ ] **Step 6: Add `GridSettings.fromProfile`**

In `GridSettings.java`:

```java
    public static GridSettings fromProfile(dev.krona.urbex.config.UrbexProfile profile) {
        return new GridSettings(
                profile.PRIMARY_ROAD_SPACING_X,
                profile.PRIMARY_ROAD_SPACING_Z,
                profile.PRIMARY_ROAD_OPTIONAL_CHANCE,
                profile.PRIMARY_ROAD_FORCE_EVERY,
                profile.SECONDARY_ROAD_MIN_COUNT_X,
                profile.SECONDARY_ROAD_MAX_COUNT_X,
                profile.SECONDARY_ROAD_MIN_COUNT_Z,
                profile.SECONDARY_ROAD_MAX_COUNT_Z,
                profile.MINIMUM_ROAD_SEPARATION,
                profile.MINIMUM_ROAD_EDGE_DISTANCE,
                profile.TERTIARY_ROAD_CHANCE,
                profile.TERTIARY_ROAD_MIN_LENGTH,
                profile.TERTIARY_ROAD_MAX_LENGTH);
    }
```

This one method is the single Minecraft-adjacent reference in the `plan` package. It imports a config class, not a Minecraft class, so the package stays game-free and its tests keep running headless.

- [ ] **Step 7: Hold the road field per dimension**

Add `RoadField roadField();` to `IDimensionInfo`. In `DefaultDimensionInfo`, build it once in the constructor beside the existing caches:

```java
    private final RoadField roadField;
    // in the constructor, after the profile is available:
    this.roadField = new GridRoadField(world.getSeed(), getType().location().toString(), GridSettings.fromProfile(profile));
```

Implement the same in `gui/NullDimensionInfo` so the world-creation preview can query roads without a server. Use the preview's configured seed there, exactly as `NullDimensionInfo` already supplies a seed to `City`.

- [ ] **Step 8: Insert the road branch into the precedence order**

In `ChunkContentResolver.resolve(...)`, add the effective-road step between accepted multi-buildings and the ordinary building roll. An effective road forces no building and `StreetType.NORMAL`, and can never be a park:

```java
        if (effectiveRoad != RoadType.NONE) {
            return new ChunkContent(false, BuildingInfo.StreetType.NORMAL, null, false);
        }
```

Then replace the legacy park nomination at the end of the order. Delete the `parkChance` roll and the `StreetType.randomNonPark(rand)` call, and return an open lot instead:

```java
        // A city chunk with no road and no building is an open lot, rendered through the park
        // surface. OPEN_LOT_PARK_CHANCE decides whether a weighted park part fills it; it never
        // turns the lot into a road.
        boolean park = Hash.unit(Hash.at(seed, chunkX, chunkZ, GridPurpose.OPEN_LOT_PARK.key()))
                < profile.OPEN_LOT_PARK_CHANCE;
        return new ChunkContent(false, park ? BuildingInfo.StreetType.PARK : BuildingInfo.StreetType.NORMAL,
                null, true);
```

The open-lot roll gets its own `GridPurpose` key, declared in Task 3, rather than reusing a
neighbouring draw. Two decisions sharing an address and a key read the same value, which would make
"is this lot a park" a monotone function of some unrelated decision.

This adds `seed`, `chunkX` and `chunkZ` to the resolver's parameter list.

- [ ] **Step 9: Compute the effective road in `BuildingInfo`**

Add a method that resolves the raw field against city membership and caches the result on the instance:

```java
    /**
     * The road this chunk actually renders. Raw field clipped to the city mask: a road needs its own
     * chunk to be raw city and at least one chunk it connects to to be raw city as well, which is what
     * removes isolated stubs at one-chunk city protrusions.
     */
    public RoadType getEffectiveRoadType() {
        if (effectiveRoad == null) {
            RoadCell cell = provider.roadField().at(coord.chunkX(), coord.chunkZ());
            boolean neighbour = false;
            for (RoadDirection d : RoadDirection.values()) {
                if (cell.connects(d) && isCityRaw(coord.offset(d.stepX(), d.stepZ()), provider)) {
                    neighbour = true;
                    break;
                }
            }
            effectiveRoad = EffectiveRoad.resolve(cell.type(), isCityRaw(coord, provider), neighbour, false);
        }
        return effectiveRoad;
    }
```

`isCityRaw` is the existing lower-level city test — use whichever method `BuildingInfo` already exposes for raw city membership, and do **not** call anything that depends on final building decisions, or the cycle-freedom invariant breaks. `ChunkCoord.offset(int dx, int dz)` already exists.

- [ ] **Step 10: Apply the multi-building conflict policy**

In `MultiChunk.canPlaceBuilding(...)`, before accepting a random candidate, reject it when the policy says any covered chunk's **raw** road blocks:

```java
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < height; dz++) {
                RoadType raw = provider.roadField().at(topLeftX + dx, topLeftZ + dz).type();
                if (profile.MULTI_BUILDING_STREET_CONFLICT.roadBlocks(raw)) {
                    return false;
                }
            }
        }
```

Query the raw field, never `getEffectiveRoadType()`. Effective roads depend on city membership, which is fine, but routing this through `BuildingInfo` would make multi-building acceptance depend on `BuildingInfo`, which depends on multi-building acceptance. Predefined multi-buildings skip this check entirely.

- [ ] **Step 11: Select the part family by road class**

In `CityGenerator`, where the street part is chosen, branch on the effective road type:

```java
        StreetParts parts = switch (info.getEffectiveRoadType()) {
            case PRIMARY -> info.getCityStyle().getLargeStreetParts();
            case TERTIARY -> info.getCityStyle().getTertiaryStreetParts();
            case SECONDARY, NONE -> info.getCityStyle().getStreetParts();
        };
```

Topology (straight/bend/t/all/end/none/full) is selected from the neighbours' final road classifications using the existing selection code. For a `PRIMARY` chunk, count a neighbour as connected **only when that neighbour is also `PRIMARY`** — a touching secondary or tertiary must not turn a primary end or straight into a bend or junction, or the quartz centre marking aims down a minor street.

- [ ] **Step 12: Overlay connectors**

After the main part is placed on a `PRIMARY` chunk, for each of the four edges where the neighbour is an effective road of a *lesser* class, rotate the `connector` part to face that edge and overlay it. An empty `connector` list disables the overlay for that style — check `isEmpty()` and skip rather than warning.

- [ ] **Step 13: Delete the now-unreachable street type**

Planned roads are always `NORMAL` and open lots are `NORMAL` or `PARK`, so nothing produces `StreetType.FULL`. Delete `FULL` from the enum, delete `randomNonPark()` and the `NON_PARK` array, delete `src/test/java/dev/krona/urbex/worldgen/lost/StreetTypeTest.java`, and remove the `case FULL ->` arm and `generateFullStreetSection` if nothing else calls it. Also remove the `randomNonPark` call at the multi-level redraw site in `CityGenerator` — that path now reads `info.streetType` directly, because the content decision is authoritative and no longer needs re-rolling.

- [ ] **Step 14: Compile and run the unit suite**

Run: `./gradlew build -x test` then `./gradlew test`
Expected: both PASS. Compilation errors naming `PARK_CHANCE`, `getParkChance` or `StreetType.FULL` are leftovers from steps 1, 2 and 13.

- [ ] **Step 15: Look at it in a real world**

Run: `./gradlew runClient`

Create a world with the `default` profile on the Cities tab, fly out to a city and check, in order:

1. wide primary roads exist and run straight and unbroken through the city
2. narrower secondary streets connect to them without a gap at the junction
3. short tertiary dead ends branch off
4. the quartz centre line never turns down a minor street
5. buildings sit in the blocks between roads, not on them

Record what looks wrong. This is the feedback that gates Task 7, and tuning the defaults now is cheaper than re-pinning tests later.

- [ ] **Step 16: Regenerate the digest**

Generation changed deliberately, so the golden must move exactly once:

```bash
rm digest.golden
./gradlew prepareDigestCheck runDigestCheck   # note the printed digest
./gradlew prepareDigestCheck runDigestCheck   # must print the same digest
```

If the two runs disagree, generation is not deterministic — that is a bug in this task, not a reason to pick one value. Find it before continuing. When they agree, write the value to `digest.golden`.

- [ ] **Step 17: Commit**

```bash
git add -A
git commit -m "feat(streets): generate hierarchical primary, secondary and tertiary roads

Wires GridRoadField into the chunk-content decision, selects part families
by road class, overlays connectors where minor roads meet primaries, and
applies the multi-building conflict policy against the raw road field.

Replaces the legacy park nomination with open lots: PARK_CHANCE and the
city-style parkchance override are gone, and StreetType.FULL with them.

Digest regenerated deliberately - generation changed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Planned bridges and sloped roads

The two fiddliest rendering rules, split out so they did not block the playable build. `digest.golden` changes and is regenerated.

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/PrimaryBridgePlanner.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/CityGenerator.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/gen/Bridges.java`
- Test: `src/test/java/dev/krona/urbex/worldgen/lost/PrimaryBridgePlannerTest.java`

**Interfaces:**
- Consumes: `RoadField`, `RoadType`, `GridPurpose.PLANNED_BRIDGE`, `Hash` (Task 3); `CityStyle.getRandomLargeBridge` (Task 1); `BuildingInfo.getEffectiveRoadType()` (Task 4).
- Produces: `PrimaryBridgePlanner.spanAt(ChunkCoord, IDimensionInfo) -> Optional<BridgeSpan>` where `BridgeSpan` is a record of `orientation`, `fromX`, `fromZ`, `toX`, `toZ`.

- [ ] **Step 1: Read the upstream implementation**

```bash
git show lostcities-upstream/1.20:src/main/java/mcjty/lostcities/worldgen/street/HierarchicalBridgePlanner.java
```

It is 146 lines. Unlike the planner it touches the world, because "water-like" needs a biome and a base height.

- [ ] **Step 2: Port the span resolver**

Create `PrimaryBridgePlanner.java`. A non-city chunk on a raw primary line is a bridge candidate when water-like — either an existing water biome, or a deterministic base height below sea level, which is what catches inland lakes whose biome is still plains or forest.

Scan both directions along that same primary line up to `PLANNED_PRIMARY_BRIDGE_MAX_LENGTH` and accept the canonical span only when all three hold:

- every intervening chunk is non-city, water-like, and on the raw primary line
- both ends are effective primary-road city chunks at city level zero
- one deterministic roll for the whole span passes

The roll addresses the span, not the chunk, so every chunk in it reconstructs the same answer:

```java
        long h = Hash.atSlot(seed, minX, minZ, ((long) maxX << 32) ^ (maxZ & 0xffffffffL),
                GridPurpose.PLANNED_BRIDGE.key() + orientation.ordinal());
        boolean accepted = Hash.unit(h) < profile.PLANNED_PRIMARY_BRIDGE_CHANCE;
```

Always derive `minX/minZ/maxX/maxZ` by sorting the two endpoints, so scanning from either end yields the same address.

- [ ] **Step 3: Resolve crossings deterministically**

Where a horizontal and a vertical span would both claim a chunk, one orientation must win by a seed- and dimension-stable rule, or two bridge parts fight over the same chunk. Compute the winner from the span addresses alone — never from which was queried first:

```java
    /** At a crossing, the orientation whose span address hashes higher wins. Query order is irrelevant. */
    private static boolean winsCrossing(long horizontalAddress, long verticalAddress) {
        return Long.compareUnsigned(horizontalAddress, verticalAddress) >= 0;
    }
```

- [ ] **Step 4: Write the bridge tests**

Create `PrimaryBridgePlannerTest.java` covering: a span shorter than the minimum is rejected; a span containing a city chunk is rejected; a span whose end is not an effective primary at level zero is rejected; and — the important one — both chunks of a crossing independently agree which orientation wins:

```java
    @Test
    void bothSidesOfACrossingAgreeOnTheWinner() {
        long horizontal = 0x1234_5678_9abc_def0L;
        long vertical = 0x0fed_cba9_8765_4321L;
        assertEquals(PrimaryBridgePlanner.winsCrossing(horizontal, vertical),
                PrimaryBridgePlanner.winsCrossing(horizontal, vertical),
                "the rule must not depend on which side asks");
        assertNotEquals(PrimaryBridgePlanner.winsCrossing(horizontal, vertical),
                PrimaryBridgePlanner.winsCrossing(vertical, horizontal),
                "exactly one orientation wins");
    }
```

Make `winsCrossing` package-private so the test can reach it.

- [ ] **Step 5: Render bridges from `largebridges`**

In `Bridges.java`, when the span comes from `PrimaryBridgePlanner`, select the part via `CityStyle.getRandomLargeBridge(...)`, which already falls back to the ordinary bridge when the style has no `largebridges`. Primary-road endpoint topology treats the bridge as a straight primary continuation, so the road either side renders as `straight`, not `end`.

- [ ] **Step 6: Implement sloped minor roads**

In `CityGenerator`, a lower minor-road chunk becomes the full-chunk `stair` part only when all of these hold:

- it has exactly one minor road neighbour one city level higher
- same-level minor roads continue directly behind the transition and beyond it
- neither end has a same-level side-road branch

This keeps bends and intersections flat and avoids ambiguous slopes. The upper road includes the slope in its topology so it continues to the chunk edge instead of rendering an end, and its retaining wall opens only across the stair part's `z1`/`z2` bounds. Primary roads never slope.

On a sloped chunk, suppress fountains, park parts, random vegetation, building-front overlays and the older narrow stair decoration, so the route stays clear.

- [ ] **Step 7: Compile and test**

Run: `./gradlew build -x test && ./gradlew test`
Expected: both PASS.

- [ ] **Step 8: Second in-game look**

Run: `./gradlew runClient`

Find a city on a coast or lake and check: primary roads bridge open water rather than diving into it; bridge decks match the primary road's width and marking; road slopes read as ramps rather than steps; and no chunk has two bridges fighting over it.

- [ ] **Step 9: Regenerate the digest**

```bash
rm digest.golden
./gradlew prepareDigestCheck runDigestCheck
./gradlew prepareDigestCheck runDigestCheck
```

Both runs must print the same digest; write it to `digest.golden`.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(streets): planned primary bridges and sloped minor roads

Primary roads now bridge open water on deterministic spans addressed by
their endpoints, so every chunk in a span agrees without shared state, and
crossings resolve by a stable rule rather than query order. Minor roads
bridge one-level height differences with a full-chunk stair part.

Digest regenerated deliberately.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Road preview and debug diagnostics

Tooling. No generation change; `digest.golden` must not move.

**Files:**
- Modify: `src/main/java/dev/krona/urbex/gui/preview/CityPreview.java`
- Modify: `src/main/java/dev/krona/urbex/gui/CustomizeScreen.java`
- Modify: `src/main/java/dev/krona/urbex/commands/CommandDebug.java`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json`
- Test: `src/test/java/dev/krona/urbex/gui/preview/RoadPreviewColourTest.java`

**Interfaces:**
- Consumes: `RoadField`, `RoadCell` (Task 3); `IDimensionInfo.roadField()` (Task 4).
- Produces: `CityPreview.Mode.ROADS`.

- [ ] **Step 1: Add the preview mode**

Add `ROADS` to `CityPreview.Mode`, drawing the region map dimmed with the road field over it: one colour per road class, so the grid's structure is visible before any chunk generates. Follow the existing `TRANSPORT` mode's structure — it already dims the map and overlays a network.

Extract the class-to-colour mapping as a package-private static method so it can be tested without a game:

```java
    static int roadColour(RoadType type) {
        return switch (type) {
            case PRIMARY -> 0xFFE8E8E8;
            case SECONDARY -> 0xFFA8A8A8;
            case TERTIARY -> 0xFF6C6C6C;
            case NONE -> 0;
        };
    }
```

- [ ] **Step 2: Test the colour mapping**

Create `RoadPreviewColourTest.java` asserting every `RoadType` maps to a distinct value and that `NONE` maps to fully transparent:

```java
    @Test
    void everyRoadClassIsDistinctAndNoneIsTransparent() {
        Set<Integer> seen = new HashSet<>();
        for (RoadType t : RoadType.values()) {
            assertTrue(seen.add(CityPreview.roadColour(t)), t + " duplicates another class's colour");
        }
        assertEquals(0, CityPreview.roadColour(RoadType.NONE));
    }
```

- [ ] **Step 3: Wire the mode into the screen**

In `CustomizeScreen`, add the `ROADS` case to the mode mapping alongside the existing `TRANSPORT` arm, and add its lang key. Check `PreviewModeMappingTest` still passes and extend it with the new mode if it enumerates modes exhaustively.

- [ ] **Step 4: Report road state in `/urbex debug`**

Extend `CommandDebug` to print, for the player's chunk: raw and effective road class, the four connections, primary-block coordinates and bounds, density, secondary positions, the tertiary segment when present, any planned bridge span, the conflict policy, and — when the chunk is inside one — the containing multi-building's name.

Nothing may be logged during ordinary generation; this output is produced only on command.

- [ ] **Step 5: Verify**

Run: `./gradlew test`
Expected: PASS.

Run: `./gradlew prepareDigestCheck runDigestCheck`
Expected: PASS, `digest.golden` unchanged. This task adds no generation behaviour.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(gui): road preview mode and street debug diagnostics

Adds a ROADS preview mode so a layout can be judged from the world-creation
screen before generating anything, and reports raw/effective road state,
primary-block data and the containing multibuilding from /urbex debug.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: The full property suite

Gated on the in-game feedback from Tasks 4 and 5. Run this task **after** the defaults have been tuned, so the tests pin the shipped behaviour rather than a draft.

**Files:**
- Modify: `src/test/java/dev/krona/urbex/plan/grid/GridRoadFieldTest.java`
- Create: `src/test/java/dev/krona/urbex/plan/grid/GridSettingsTest.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/ChunkContentResolverTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: no production code.

- [ ] **Step 1: Confirm the defaults are settled**

Before writing a line, confirm the in-game observations from Task 4 step 15 and Task 5 step 8 have been acted on and the profile defaults are final. Pinning a layout that is about to be retuned is the waste this task was deferred to avoid.

- [ ] **Step 2: Primary road spacing invariants**

Add to `GridRoadFieldTest`: collect active vertical corridor x-coordinates across a wide range; assert every consecutive gap is a positive multiple of `primarySpacingX` and at most `primarySpacingX * primaryForceEvery`; assert every `primaryForceEvery`-th candidate is active. Repeat for horizontal corridors and z.

- [ ] **Step 3: Secondary road invariants**

Assert each secondary spans its whole primary block and touches both bounding primaries; accepted positions are at least `minimumRoadSeparation` apart and at least `minimumEdgeDistance` from each primary boundary; and a `GridSettings` demanding more roads than physically fit yields fewer rather than throwing — build one with `secondaryMinCountX = secondaryMaxCountX = 128` on a default-width block and assert the field still resolves.

- [ ] **Step 4: Tertiary road invariants**

Assert each segment is contiguous from its origin; the origin lies on a primary or secondary road at least two chunks from that road's intersections; the segment leaves at least one non-road chunk before the opposite road; and a cell whose first-choice side cannot fit falls through to another side in deterministic order rather than silently losing its access road.

- [ ] **Step 5: Settings validation branches**

Create `GridSettingsTest` asserting every branch of the compact constructor throws with a message naming the profile field — one test per branch, checking the field name appears in the message. A user who mistypes a profile value must be told which one.

- [ ] **Step 6: Cycle-freedom**

Add to `ChunkContentResolverTest`: querying the road field first and then multi-building acceptance yields the same result as the reverse order, over a range of chunks. This is the invariant that makes the design sound; assert it rather than trusting it.

- [ ] **Step 7: Run everything**

Run: `./gradlew test`
Expected: PASS.

Run: `./gradlew prepareDigestCheck runDigestCheck`
Expected: PASS, `digest.golden` unchanged — this task adds no production code.

- [ ] **Step 8: Commit**

```bash
git add src/test
git commit -m "test(streets): property suite for the hierarchical road field

Pins the invariants upstream documents: primary spacing and continuity,
secondary spanning and separation, tertiary contiguity and side fall-through,
settings validation messages, and the cycle-freedom of the content decision.

Written after in-game tuning so they pin shipped behaviour, not a draft.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage.** Every numbered spec section maps to a task: §3.1–3.2 seam and packages → Task 3; §3.3–3.4 content decision → Task 2 (extraction) and Task 4 (road branch); §4 data flow → Task 4; §4.1 cycle-freedom → Task 4 step 10, asserted in Task 7 step 6; §5 rendering → Task 4 steps 11–12 and Task 5 steps 5–6; §6.1–6.2 assets and schema → Task 1; §6.3 profile settings → Task 4 steps 1–4; §6.4 conflict policy → Task 1 step 4 and Task 4 step 10; §7 failure handling → Task 3 step 5 and Task 1 step 10; §8.0 smoke set → Task 3 step 8; §8.1–8.2 full suite → Task 7; §9 sequencing → task order.

**Deliberate carry-forwards.** `RoadType` is created in Task 1 rather than Task 3 so Task 1 compiles standalone. `GridPurpose.PLANNED_BRIDGE` and `OPEN_LOT_PARK` are declared before use so no ordinal shifts later — a shifted ordinal silently changes every world.

**Known judgement calls left to the implementer.** Task 2 step 3's parameter list depends on what the current decision code actually reads, which is why step 1 is a reading step. Task 5 step 6's slope rule is described by its conditions rather than as code because it interacts with the existing city-level and retaining-wall logic, which the implementer must read first.
