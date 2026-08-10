# Cleanup: Dimension Removal, standard_everywhere Removal, Namespace Prefixing — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the legacy `urbex:city` datapack dimension and the `standard_everywhere` world style, and namespace-prefix every asset cross-reference in the shipped datapack per the LCMT convention.

**Architecture:** Three independent cleanups over the same codebase. The dimension is a pure datapack dimension plus three Java touch points (bed teleport, registry keys, config default) — deletion, no replacement. `standard_everywhere` is a single JSON plus one test fixture. Namespacing is a scripted per-field transformation over the datapack guarded by a new reference-integrity test; name resolution semantics in Java (`DataTools.fromName`/`toName`) are untouched, so behavior is provably identical.

**Tech Stack:** Java 25 / Fabric / Gradle; JUnit 5 (Jupiter); Python 3 for the one-off datapack transformation (not committed).

**Spec:** `docs/superpowers/specs/2026-08-10-cleanup-dimension-worldstyle-namespacing-design.md`

## Global Constraints

- Worldgen behavior must not change: `digest.golden` must be byte-identical after all tasks (`./gradlew runDigestCheck`).
- No changes to `DataTools.fromName`/`toName` or any Java default asset-name constants (`StreetParts`, `HighwayParts`, `RailwayParts`, `MonorailParts`, `UrbexProfile.worldStyle`).
- Bare names remain legal for third-party datapacks (they resolve to `urbex:`); only the shipped datapack adopts full prefixes.
- New default for `dimensionsWithProfiles` is the empty list — no overworld default mapping is added (confirmed product decision).
- Repo root for all commands: `/Volumes/Dev/Projects/krona/minecraft-mods/LostCities`.

---

### Task 1: Remove the `urbex:city` dimension and the bed-teleport mechanic

**Files:**
- Delete: `src/main/resources/data/urbex/dimension/` (whole dir, contains only `city.json`)
- Delete: `src/main/resources/data/urbex/dimension_type/` (whole dir, contains only `city.json`)
- Delete: `src/main/java/dev/krona/urbex/setup/BedTeleport.java`
- Delete: `src/main/java/dev/krona/urbex/varia/CustomTeleporter.java`
- Modify: `src/main/java/dev/krona/urbex/setup/ServerEventHandlers.java:14-17,30`
- Modify: `src/main/java/dev/krona/urbex/setup/Registration.java:9-13,37-40`
- Modify: `src/main/java/dev/krona/urbex/config/UrbexConfig.java:25,40,42,60`
- Modify: `src/main/java/dev/krona/urbex/setup/Config.java:46`
- Modify: `runs/digestcheck/config/urbex/urbex.json` (tracked file)
- Test: `src/test/java/dev/krona/urbex/config/UrbexConfigTest.java:23`
- Test: `src/test/java/dev/krona/urbex/config/LegacyTomlTest.java:32`
- Modify: `README.md` (Usage section), `CHANGELOG.md`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `UrbexConfig` record without the `specialBedBlock` component — new component order is `(List<String> dimensionsWithProfiles, int heightSampleSize, String selectedProfile, String selectedCustomJson, int todoQueueSize, boolean forceSaplingGrowth, int cacheCleanupSeconds, List<String> avoidStructures, boolean avoidStructuresAdjacent, boolean avoidSurfaceStructures, boolean structuresYieldToCities, boolean avoidVillages, boolean avoidVillagesAdjacent, boolean avoidFlattening)`. No later task depends on this, but any future config edit must use this shape.

- [ ] **Step 1: Update the two config tests to the post-removal expectations (failing first)**

In `src/test/java/dev/krona/urbex/config/UrbexConfigTest.java`, change line 23:

```java
        assertEquals(List.of(), cfg.dimensionsWithProfiles());
```

In `src/test/java/dev/krona/urbex/config/LegacyTomlTest.java`, line 32 uses `"urbex:city=biosphere"` purely as parser sample data. Swap for a neutral id so no stale reference survives:

```java
                "\tdimensionsWithProfiles = [\"minecraft:overworld=rare\", \"foo:bar=rare\"]"
```

No assertion change needed: the test (`readsSingleLineArrays`, lines 34-35) only asserts the array size is 2 and that element 1 is `"foo:bar=rare"`, both unaffected.

- [ ] **Step 2: Run the two tests to verify they fail**

Run: `./gradlew test --tests "dev.krona.urbex.config.UrbexConfigTest" --tests "dev.krona.urbex.config.LegacyTomlTest"`
Expected: `UrbexConfigTest.emptyJsonYieldsAllDefaults` FAILS (default is still `["urbex:city=biosphere"]`). `LegacyTomlTest` passes or fails depending only on its own assertion edit being consistent — if it fails, it must fail on the changed strings, nothing else.

- [ ] **Step 3: Delete the dimension resources and the two Java classes**

```bash
git rm -r src/main/resources/data/urbex/dimension src/main/resources/data/urbex/dimension_type
git rm src/main/java/dev/krona/urbex/setup/BedTeleport.java src/main/java/dev/krona/urbex/varia/CustomTeleporter.java
```

- [ ] **Step 4: Unregister the sleep hook in `ServerEventHandlers.java`**

Remove line 30 (`EntitySleepEvents.ALLOW_SLEEPING.register(BedTeleport::onPlayerSleepInBed);`) and line 8 (`import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;`). Replace the class javadoc (lines 14-18) with:

```java
/**
 * The server-side Fabric event wiring. The spawn-placement algorithm lives in
 * {@link SpawnPlacement}; this class only registers it. (Formerly {@code ForgeEventHandlers},
 * a name left over from the port.)
 */
```

- [ ] **Step 5: Strip the dimension keys from `Registration.java`**

Remove lines 37-40 (`CITY_ID`, `DIMENSION_TYPE`, `DIMENSION`) and the now-unused imports on lines 9 (`Registries`), 11 (`ResourceKey`), 12 (`Level`), 13 (`DimensionType`). Keep `init()` exactly as is — it registers the `urbex:city` **Feature** (a different registry that only shares the id string) and `urbex:spheres`.

- [ ] **Step 6: Remove `specialBedBlock` and empty the `dimensionsWithProfiles` default in `UrbexConfig.java`**

- Line 25: delete the `String specialBedBlock,` record component.
- Line 40: `List.of("urbex:city=biosphere"),` → `List.of(),`
- Line 42: delete `"minecraft:diamond_block",` from the `DEFAULT` constructor call.
- Line 60: delete the `specialBedBlock` codec line.

In `src/main/java/dev/krona/urbex/setup/Config.java`, delete line 46 (`SPECIAL_BED_BLOCK` supplier). No other code references it (only the deleted `BedTeleport` did). `LegacyToml` needs no change: it is a generic key parser, and the codec ignores unknown keys, so a legacy `specialBedBlock` TOML entry migrates to a harmless ignored JSON key.

- [ ] **Step 7: Run the config tests to verify they pass**

Run: `./gradlew test --tests "dev.krona.urbex.config.UrbexConfigTest" --tests "dev.krona.urbex.config.LegacyTomlTest"`
Expected: PASS.

- [ ] **Step 8: Clean the tracked digestcheck config and untracked dev leftovers**

Edit `runs/digestcheck/config/urbex/urbex.json`: remove the `"urbex:city=biosphere",` entry from its `dimensionsWithProfiles` array (keep any other entries). Then remove untracked leftovers if present (ignore errors):

```bash
rm -rf runs/server/world/dimensions/urbex runs/digestcheck/world/dimensions/urbex
```

- [ ] **Step 9: Rewrite the README Usage section and add CHANGELOG entries**

In `README.md`, replace the entire Usage section (the paragraph starting "By default Urbex generates cities **only in the `urbex:city` dimension**" through the `dimensionsWithProfiles` paragraph) with:

```markdown
## Usage

Urbex does not generate anything until you opt in. A new world looks completely untouched
unless you pick a profile.

To get cities:

- On the world-creation screen, open the **More** tab and use the **Cities** button to pick a
  profile before creating the world. With no profile selected (the default) the world generates
  normally.
- Server owners can map any dimension to a profile with the `dimensionsWithProfiles` config
  option (entries look like `minecraft:overworld=default`).
```

In `CHANGELOG.md`, add an Unreleased section above `## 0.1.0` (create it; later tasks append to it):

```markdown
## Unreleased

- **Removed the `urbex:city` dimension.** It existed for historical reasons only (it was a plain
  overworld clone). Cities are enabled by picking a profile on the world-creation Cities tab or
  via the `dimensionsWithProfiles` config. The sleep-on-a-special-bed teleport and its
  `specialBedBlock` config option are gone with it, and `dimensionsWithProfiles` now defaults to
  empty.
```

- [ ] **Step 10: Full build and digest check**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

Run: `./gradlew runDigestCheck`
Expected: digest matches `digest.golden` (the digest runs on the overworld with the `default` profile; the dimension never participated).

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat!: remove the urbex:city dimension and bed teleport

The dimension was a plain overworld clone kept for historical reasons.
Cities are opted into via the Cities tab or dimensionsWithProfiles,
which now defaults to empty. Removes BedTeleport, CustomTeleporter,
the specialBedBlock config key, and the dimension datapack files.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Remove the `standard_everywhere` world style

**Files:**
- Delete: `src/main/resources/data/urbex/urbex/worldstyles/standard_everywhere.json`
- Test: `src/test/java/dev/krona/urbex/gui/WorldStyleDialogTest.java:17,22-24`
- Modify: `CHANGELOG.md` (Unreleased section from Task 1)

**Interfaces:**
- Consumes: the `## Unreleased` CHANGELOG section created in Task 1 Step 9.
- Produces: nothing later tasks rely on. After this task exactly one world style (`standard`) ships, so `CitiesTab`'s `worldStyles.size() > 1` guard hides the world-style dropdown (no code change needed — that guard already exists).

- [ ] **Step 1: Delete the world style**

```bash
git rm src/main/resources/data/urbex/urbex/worldstyles/standard_everywhere.json
```

- [ ] **Step 2: Neutralize the test fixture**

`WorldStyleDialog.preselectIndex` is a pure index lookup; the fixture strings are arbitrary. In `src/test/java/dev/krona/urbex/gui/WorldStyleDialogTest.java` replace the `standard_everywhere` string in both places (line 17 fixture, line 23 assertion):

```java
    private static final List<String> CHOICES = List.of("standard", "floating", "lcmt");
```

```java
        assertEquals(1, WorldStyleDialog.preselectIndex(CHOICES, "floating"));
```

- [ ] **Step 3: Run the test**

Run: `./gradlew test --tests "dev.krona.urbex.gui.WorldStyleDialogTest"`
Expected: PASS.

- [ ] **Step 4: Verify nothing else references it**

Run: `grep -rn "standard_everywhere" src/ README.md CHANGELOG.md`
Expected: no matches.

- [ ] **Step 5: Add CHANGELOG entry**

Append to the `## Unreleased` section:

```markdown
- **Removed the `standard_everywhere` world style.** A backward-compatibility leftover that had
  not been kept up to date. `standard` is the only bundled world style; with a single style the
  world-style dropdown on the Cities tab stays hidden.
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat!: remove the standard_everywhere world style

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Namespace-prefix the datapack, add explicit part wiring, guard with an integrity test

This task is TDD at the datapack level: the new integrity test encodes the target convention (every internal reference fully `urbex:`-prefixed and resolving to a real file), fails against the current bare-name datapack, and passes after the scripted transformation.

**Files:**
- Test (create): `src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java`
- Modify: all JSONs under `src/main/resources/data/urbex/urbex/` with cross-references (~100 of 294 files; scripted)
- Modify: `src/main/resources/data/urbex/urbex/worldstyles/standard.json` (add `parts` block)
- Modify: `src/main/resources/data/urbex/urbex/citystyles/citystyle_common.json` (add `streetblocks.parts`)
- Script (scratchpad only, NOT committed): `prefix_datapack.py`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: nothing from earlier tasks (independent of Tasks 1-2; the reference map below already excludes the files they delete).
- Produces: `DatapackReferenceIntegrityTest` — plain JUnit 5 + Gson, no Minecraft bootstrap. Encodes the reference-field map; future datapack work must keep it green.

**Reference-field map** (single source of truth for both the test and the script; target category → the directory under `data/urbex/urbex/` the name must exist in):

| Source category | JSON location | Target category |
|---|---|---|
| buildings | `parts[].part`, `parts2[].part` | parts |
| multibuildings | `buildings[][]` (list of lists of strings) | buildings |
| styles | `randompalettes[][].palette` | palettes |
| citystyles | `inherit` | citystyles |
| citystyles | `style` | styles |
| citystyles | `selectors.buildings[].value` | buildings |
| citystyles | `selectors.multibuildings[].value` | multibuildings |
| citystyles | `selectors.{bridges,parks,fountains,stairs,fronts,raildungeons}[].value` | parts |
| citystyles | `streetblocks.parts.{full,straight,end,bend,t,none,all}[]` | parts |
| parts | `refpalette` | palettes |
| worldstyles | `outsidestyle` | styles |
| worldstyles | `citystyles[].citystyle` | citystyles |
| worldstyles | `scattered.list[].name` | scattered |
| worldstyles | `parts.monorails.{both,vertical,station}` (strings) | parts |
| worldstyles | `parts.highways.{tunnel,open,bridge,tunnel_bi,open_bi,bridge_bi}[]` | parts |
| worldstyles | `parts.railways.{stationunderground,stationopen,stationopenroof,stationundergroundstairs,stationstaircase,stationstaircasesurface,railshorizontal,railshorizontalend,railshorizontalwater,railsvertical,railsverticalwater,rails3split,railsbend,railsflat,railsdown1,railsdown2}[]` | parts |
| scattered | `buildings[]` | buildings |
| scattered | `multibuilding` | multibuildings |
| conditions | `values[].inpart` | parts |
| stuff | `buildings.{if_any,if_all,excluding}[]` | buildings |
| any category | entries of any `palette` array: `variant` | variants |
| any category | entries of any `palette` array: `loot`, `mob` | conditions |

Explicitly NOT references (leave untouched): `minecraft:`-prefixed block/mob ids, `biomes` matchers (biome ids/tags), `conditions` `values[].value` (loot-table paths and mob ids, e.g. `urbex:chests/city_chest` — a loot table, not a city asset), `stuff` `blocks`/`upperblocks` matchers (block ids/tags), `damaged`/`block`/`id` fields.

- [ ] **Step 1: Write the integrity test**

Create `src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java`:

```java
package dev.krona.urbex.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every cross-reference in the bundled datapack must be fully namespaced ("urbex:...") and
 * resolve to an existing asset file. Bare names would still work at runtime (they default to
 * the urbex namespace), but the shipped pack is fully qualified so it stays unambiguous next
 * to third-party datapacks — the same convention LCMT uses. The field map here mirrors
 * docs/superpowers/specs/2026-08-10-cleanup-dimension-worldstyle-namespacing-design.md.
 */
class DatapackReferenceIntegrityTest {

    private static final Path ROOT = Path.of("src/main/resources/data/urbex/urbex");

    /** target category (directory under ROOT) -> collected [sourceFile, reference] pairs */
    private final List<String> problems = new ArrayList<>();

    @Test
    void allDatapackReferencesAreNamespacedAndResolve() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::checkFile);
        }
        assertTrue(problems.isEmpty(),
                () -> problems.size() + " bad datapack references:\n" + String.join("\n", problems));
    }

    private void checkFile(Path file) {
        String category = ROOT.relativize(file).getName(0).toString();
        JsonObject d;
        try {
            d = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException e) {
            problems.add(file + ": unreadable: " + e.getMessage());
            return;
        }
        String src = file.toString();
        switch (category) {
            case "buildings" -> {
                for (String key : List.of("parts", "parts2")) {
                    forEachObject(d.get(key), e -> ref(src, e.get("part"), "parts"));
                }
            }
            case "multibuildings" -> forEachElement(d.get("buildings"),
                    row -> forEachElement(row, cell -> ref(src, cell, "buildings")));
            case "styles" -> forEachElement(d.get("randompalettes"),
                    row -> forEachObject(row, e -> ref(src, e.get("palette"), "palettes")));
            case "citystyles" -> {
                ref(src, d.get("inherit"), "citystyles");
                ref(src, d.get("style"), "styles");
                JsonObject sel = asObject(d.get("selectors"));
                if (sel != null) {
                    for (Map.Entry<String, String> e : Map.of(
                            "buildings", "buildings", "multibuildings", "multibuildings",
                            "bridges", "parts", "parks", "parts", "fountains", "parts",
                            "stairs", "parts", "fronts", "parts", "raildungeons", "parts").entrySet()) {
                        forEachObject(sel.get(e.getKey()), o -> ref(src, o.get("value"), e.getValue()));
                    }
                }
                JsonObject sb = asObject(d.get("streetblocks"));
                JsonObject sbParts = sb == null ? null : asObject(sb.get("parts"));
                if (sbParts != null) {
                    for (Map.Entry<String, JsonElement> e : sbParts.entrySet()) {
                        forEachElement(e.getValue(), v -> ref(src, v, "parts"));
                    }
                }
            }
            case "parts" -> ref(src, d.get("refpalette"), "palettes");
            case "worldstyles" -> {
                ref(src, d.get("outsidestyle"), "styles");
                forEachObject(d.get("citystyles"), e -> ref(src, e.get("citystyle"), "citystyles"));
                JsonObject scattered = asObject(d.get("scattered"));
                if (scattered != null) {
                    forEachObject(scattered.get("list"), e -> ref(src, e.get("name"), "scattered"));
                }
                JsonObject parts = asObject(d.get("parts"));
                if (parts != null) {
                    JsonObject mono = asObject(parts.get("monorails"));
                    if (mono != null) {
                        for (Map.Entry<String, JsonElement> e : mono.entrySet()) {
                            ref(src, e.getValue(), "parts");
                        }
                    }
                    for (String group : List.of("highways", "railways")) {
                        JsonObject g = asObject(parts.get(group));
                        if (g != null) {
                            for (Map.Entry<String, JsonElement> e : g.entrySet()) {
                                forEachElement(e.getValue(), v -> ref(src, v, "parts"));
                            }
                        }
                    }
                }
            }
            case "scattered" -> {
                forEachElement(d.get("buildings"), v -> ref(src, v, "buildings"));
                ref(src, d.get("multibuilding"), "multibuildings");
            }
            case "conditions" -> forEachObject(d.get("values"), e -> ref(src, e.get("inpart"), "parts"));
            case "stuff" -> {
                JsonObject buildings = asObject(d.get("buildings"));
                if (buildings != null) {
                    for (String key : List.of("if_any", "if_all", "excluding")) {
                        forEachElement(buildings.get(key), v -> ref(src, v, "buildings"));
                    }
                }
            }
            default -> { /* variants and future categories: only palette-entry refs below */ }
        }
        walkPaletteEntries(src, d);
    }

    /** Any "palette" array anywhere: entries may reference variants ("variant") and conditions ("loot"/"mob"). */
    private void walkPaletteEntries(String src, JsonElement el) {
        if (el == null) {
            return;
        }
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (e.getKey().equals("palette") && e.getValue().isJsonArray()) {
                    forEachObject(e.getValue(), entry -> {
                        ref(src, entry.get("variant"), "variants");
                        ref(src, entry.get("loot"), "conditions");
                        ref(src, entry.get("mob"), "conditions");
                    });
                } else {
                    walkPaletteEntries(src, e.getValue());
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                walkPaletteEntries(src, e);
            }
        }
    }

    private void ref(String src, JsonElement el, String targetCategory) {
        if (el == null || !el.isJsonPrimitive()) {
            return;
        }
        String name = el.getAsString();
        int colon = name.indexOf(':');
        if (colon < 0) {
            problems.add(src + ": bare reference \"" + name + "\" (expected urbex:" + name + ")");
            return;
        }
        String namespace = name.substring(0, colon);
        if (!namespace.equals("urbex")) {
            return; // foreign namespace: not resolvable from this repo, and none are shipped
        }
        Path target = ROOT.resolve(targetCategory).resolve(name.substring(colon + 1) + ".json");
        if (!Files.isRegularFile(target)) {
            problems.add(src + ": \"" + name + "\" does not resolve to " + target);
        }
    }

    private static JsonObject asObject(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static void forEachElement(JsonElement el, java.util.function.Consumer<JsonElement> fn) {
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                fn.accept(e);
            }
        }
    }

    private static void forEachObject(JsonElement el, java.util.function.Consumer<JsonObject> fn) {
        forEachElement(el, e -> {
            if (e.isJsonObject()) {
                fn.accept(e.getAsJsonObject());
            }
        });
    }
}
```

Note: `checkFile` catches only `IOException`; a JSON syntax error should crash the test loudly, which is correct.

- [ ] **Step 2: Run the test to verify it fails on the current bare names**

Run: `./gradlew test --tests "dev.krona.urbex.data.DatapackReferenceIntegrityTest"`
Expected: FAIL, message listing ~533 bare references ("bare reference \"building1\" ..."). If it passes, the extraction rules are broken — stop and fix the test before touching data.

- [ ] **Step 3: Write and run the prefixer script**

Save as `prefix_datapack.py` in the session scratchpad (do NOT commit it), run from the repo root. It is the same field map as the test, as a transformation. It only rewrites files whose content actually changes (2-space indent, trailing newline — matching the existing style):

```python
#!/usr/bin/env python3
"""One-off: prefix bare asset cross-references in the urbex datapack with 'urbex:'."""
import json
from pathlib import Path

ROOT = Path("src/main/resources/data/urbex/urbex")

def pfx(name):
    return name if ":" in name else "urbex:" + name

def prefix_values(entries):  # [{..., "value": name}]
    for e in entries or []:
        e["value"] = pfx(e["value"])

def walk_palette_entries(obj):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "palette" and isinstance(v, list):
                for e in v:
                    if isinstance(e, dict):
                        for f in ("variant", "loot", "mob"):
                            if f in e:
                                e[f] = pfx(e[f])
            else:
                walk_palette_entries(v)
    elif isinstance(obj, list):
        for v in obj:
            walk_palette_entries(v)

def transform(category, d):
    if category == "buildings":
        for key in ("parts", "parts2"):
            for e in d.get(key) or []:
                if "part" in e:
                    e["part"] = pfx(e["part"])
    elif category == "multibuildings":
        d["buildings"] = [[pfx(b) for b in row] for row in d["buildings"]]
    elif category == "styles":
        for row in d.get("randompalettes") or []:
            for e in row:
                e["palette"] = pfx(e["palette"])
    elif category == "citystyles":
        if "inherit" in d:
            d["inherit"] = pfx(d["inherit"])
        if "style" in d:
            d["style"] = pfx(d["style"])
        sel = d.get("selectors") or {}
        for key in ("buildings", "multibuildings", "bridges", "parks",
                    "fountains", "stairs", "fronts", "raildungeons"):
            prefix_values(sel.get(key))
    elif category == "parts":
        if "refpalette" in d:
            d["refpalette"] = pfx(d["refpalette"])
    elif category == "worldstyles":
        if "outsidestyle" in d:
            d["outsidestyle"] = pfx(d["outsidestyle"])
        for e in d.get("citystyles") or []:
            e["citystyle"] = pfx(e["citystyle"])
        for e in (d.get("scattered") or {}).get("list") or []:
            e["name"] = pfx(e["name"])
    elif category == "scattered":
        if "buildings" in d:
            d["buildings"] = [pfx(b) for b in d["buildings"]]
        if "multibuilding" in d:
            d["multibuilding"] = pfx(d["multibuilding"])
    elif category == "conditions":
        for e in d.get("values") or []:
            if "inpart" in e:
                e["inpart"] = pfx(e["inpart"])
    elif category == "stuff":
        b = d.get("buildings")
        if isinstance(b, dict):
            for key in ("if_any", "if_all", "excluding"):
                if key in b:
                    b[key] = [pfx(x) for x in b[key]]
    walk_palette_entries(d)

changed = 0
for f in sorted(ROOT.rglob("*.json")):
    category = f.relative_to(ROOT).parts[0]
    original = json.loads(f.read_text())
    d = json.loads(f.read_text())
    transform(category, d)
    if d != original:
        f.write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n")
        changed += 1
print(f"rewrote {changed} files")
```

Run: `python3 <scratchpad>/prefix_datapack.py`
Expected: "rewrote ~100 files" (every file containing at least one bare reference). Spot-check the diff:

```bash
git diff --stat src/main/resources/data/urbex/urbex/ | tail -3
git diff src/main/resources/data/urbex/urbex/citystyles/citystyle_common.json | head -40
```

Expected in the spot-check: `"value": "building1"` → `"value": "urbex:building1"` and similar; no `minecraft:` id touched; no key reordering.

- [ ] **Step 4: Add the explicit part wiring (LCMT convention)**

These two edits declare in data what today comes from bare Java codec defaults. The names mirror `MonorailParts.DEFAULT`, `HighwayParts.DEFAULT`, `RailwayParts.DEFAULT`, `StreetParts.DEFAULT` exactly, prefixed. The Java defaults stay untouched as the fallback for third-party packs.

In `src/main/resources/data/urbex/urbex/worldstyles/standard.json`, add this top-level key (after `"multisettings"`):

```json
  "parts": {
    "monorails": {
      "both": "urbex:monorails_both",
      "vertical": "urbex:monorails_vertical",
      "station": "urbex:monorails_station"
    },
    "highways": {
      "tunnel": ["urbex:highway_tunnel"],
      "open": ["urbex:highway_open"],
      "bridge": ["urbex:highway_bridge"],
      "tunnel_bi": ["urbex:highway_tunnel_bi"],
      "open_bi": ["urbex:highway_open_bi"],
      "bridge_bi": ["urbex:highway_bridge_bi"]
    },
    "railways": {
      "stationunderground": ["urbex:station_underground"],
      "stationopen": ["urbex:station_open"],
      "stationopenroof": ["urbex:station_openroof"],
      "stationundergroundstairs": ["urbex:station_underground_stairs"],
      "stationstaircase": ["urbex:station_staircase"],
      "stationstaircasesurface": ["urbex:station_staircase_surface"],
      "railshorizontal": ["urbex:rails_horizontal"],
      "railshorizontalend": ["urbex:rails_horizontal_end"],
      "railshorizontalwater": ["urbex:rails_horizontal_water"],
      "railsvertical": ["urbex:rails_vertical"],
      "railsverticalwater": ["urbex:rails_vertical_water"],
      "rails3split": ["urbex:rails_3split"],
      "railsbend": ["urbex:rails_bend"],
      "railsflat": ["urbex:rails_flat"],
      "railsdown1": ["urbex:rails_down1"],
      "railsdown2": ["urbex:rails_down2"]
    }
  },
```

In `src/main/resources/data/urbex/urbex/citystyles/citystyle_common.json`, extend the existing `"streetblocks"` object with a `"parts"` key (keep the existing `border`/`wall`/`street`/`streetbase`/`streetvariant` keys):

```json
    "parts": {
      "full": ["urbex:street_full"],
      "straight": ["urbex:street_straight"],
      "end": ["urbex:street_end"],
      "bend": ["urbex:street_bend"],
      "t": ["urbex:street_t"],
      "none": ["urbex:street_none"],
      "all": ["urbex:street_all"]
    }
```

(`citystyle_border.json` also has a `streetblocks` block; leave it without `parts` — if citystyle inheritance replaces rather than merges the block, it falls back to the identical Java defaults, so behavior is the same either way. This matches LCMT, which declares street parts only in its `citystyle_common`.)

- [ ] **Step 5: Run the integrity test to verify it passes**

Run: `./gradlew test --tests "dev.krona.urbex.data.DatapackReferenceIntegrityTest"`
Expected: PASS. Every failure at this point is either a genuine pre-existing broken reference (fix the data — but investigate first; the audit found none) or a script/test rule mismatch (fix whichever is wrong).

- [ ] **Step 6: Verify zero bare references remain (independent of the test's rules)**

Sanity cross-check with a different method than the test uses:

```bash
grep -rhoE '"(part|variant|palette|inherit|style|citystyle|refpalette|outsidestyle|name|multibuilding|inpart|loot|mob)": *"[^:"]*"' src/main/resources/data/urbex/urbex --include="*.json" | sort | uniq -c | sort -rn | head -20
```

Expected: only non-reference hits (e.g. `"name"` fields that are not scattered refs, `"style"`-like keys in non-reference positions). Review each surviving line and confirm it is not in the reference map; if one is, the script missed it — fix and re-run.

- [ ] **Step 7: Full test suite and digest check**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (includes `CommonPaletteLightingTest`, which re-parses the rewritten `palettes/common.json`).

Run: `./gradlew runDigestCheck`
Expected: digest matches `digest.golden` **unchanged**. Bare and prefixed names resolve to the same `Identifier`s and the part wiring mirrors the Java defaults, so any drift is a real mistake — do not update `digest.golden`; find the bug.

- [ ] **Step 8: Add CHANGELOG entry**

Append to `## Unreleased`:

```markdown
- **The bundled datapack is now fully namespaced.** Every internal asset reference is written
  `urbex:name` instead of relying on bare-name defaulting, and street/highway/railway/monorail
  part wiring is declared explicitly in `worldstyles/standard` and `citystyles/citystyle_common`
  (previously implicit Java defaults). Bare names in third-party datapacks still work and still
  default to the `urbex` namespace. A new test enforces that every shipped reference is
  namespaced and resolves.
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor(data): fully namespace the bundled datapack

Prefix every internal asset cross-reference with urbex: and declare
street/highway/railway/monorail part wiring explicitly in the
worldstyle/citystyle JSONs (LCMT convention). Resolution semantics
are unchanged - bare names still default to urbex: for third-party
packs - and the worldgen digest is identical. Adds a reference-
integrity test that keeps the shipped pack fully qualified.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Final verification sweep

**Files:** none created; read-only checks over the whole repo.

**Interfaces:**
- Consumes: the committed results of Tasks 1-3.
- Produces: a clean, verified main branch.

- [ ] **Step 1: Full clean build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Digest check from clean state**

Run: `./gradlew runDigestCheck`
Expected: digest matches `digest.golden`; `git status` shows no modification to `digest.golden`.

- [ ] **Step 3: Residual-reference sweep**

```bash
grep -rn "urbex:city\b\|BedTeleport\|CustomTeleporter\|specialBedBlock\|SPECIAL_BED_BLOCK\|standard_everywhere" src/ README.md CHANGELOG.md build.gradle
```

Expected: no matches except the CHANGELOG `## Unreleased` entries describing the removals (and historical `docs/` files, which are intentionally untouched and not in the grep scope).

- [ ] **Step 4: Confirm working tree is clean and log is coherent**

```bash
git status --short && git log --oneline -5
```

Expected: empty status; three implementation commits from Tasks 1-3 on top of the spec/plan commits.
