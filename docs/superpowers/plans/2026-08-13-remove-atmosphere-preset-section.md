# Remove the `atmosphere` preset section — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the dead `atmosphere` preset section (`HORIZON`, `FOG_RED`, `FOG_GREEN`, `FOG_BLUE`, `FOG_DENSITY`) end-to-end, and collapse the `PresetDefinition.Meta` workaround that the freed codec slot makes unnecessary.

**Architecture:** The five fields form a closed declare → copy → codec → GUI cycle with no reader; deleting the cycle changes no generation path. Existing datapacks that still declare `atmosphere` fall through to the preset registry's `UnknownKeys` WARN and keep loading, so no migration code is added. Removing the section drops `PresetDefinition`'s flat codec from seventeen fields to sixteen — `RecordCodecBuilder.group`'s cap — so the `Meta` record that existed only to buy that field back is deleted too.

**Tech Stack:** Java 21, Minecraft/Fabric, Mojang DataFixerUpper codecs, Gradle, JUnit 5, networknt json-schema-validator.

**Spec:** [`docs/superpowers/specs/2026-08-13-remove-atmosphere-preset-section-design.md`](../specs/2026-08-13-remove-atmosphere-preset-section-design.md)

## Global Constraints

- **No generation behaviour may change.** None of the five fields ever reached generation. Both digest goldens (`digest.golden`, `digest-features.golden`, `digest-rail.golden`, `digest-avoid.golden`, `digest-avoid-modes.golden`) must remain untouched; if any diff appears, stop and report rather than regenerating.
- **No datapack format change beyond the removal itself.** The six metadata keys (`extends`, `name`, `description`, `extraDescription`, `warning`, `icon`) stay top-level keys in the JSON with unchanged names and types.
- **No new migration code.** A pack still declaring `atmosphere` must decode successfully and be reported by the existing `UnknownKeys` path. `RetiredKeys` is explicitly *not* used.
- **Epic #134 PR rules apply:** one boundary per PR, link the child issue and the epic, run unit tests on every PR.
- **Do not use a GitHub auto-closing keyword for #73.** Its static-handoff half remains open (epic #134 phase 3 item 11).
- Run the suite with `./gradlew test` from the repo root. Gradle declares `docs/schema` as a test input, so schema edits do re-run `:test`.

---

### Task 1: Delete the `atmosphere` section end-to-end

**Files:**
- Delete: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/preset/AtmosphereSettings.java`
- Modify: `src/main/java/dev/krona/urbex/config/Preset.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/PresetDefinition.java`
- Modify: `src/main/java/dev/krona/urbex/gui/settings/Settings.java:463-496`
- Modify: `src/main/resources/assets/urbex/lang/en_us.json:322-331`
- Modify: `src/main/resources/data/urbex/urbex/presets/cavern.json:13-19`
- Modify: `src/main/resources/data/urbex/urbex/presets/floating.json:23-25`
- Modify: `docs/schema/preset.schema.json:761-800`
- Modify: `docs/presets.md:31-33`
- Modify: `CHANGELOG.md`
- Test: `src/test/java/dev/krona/urbex/config/PresetCodecTest.java`
- Test: `src/test/java/dev/krona/urbex/config/PresetSchemaTest.java`
- Test: `src/test/java/dev/krona/urbex/config/PresetRoundTripTest.java`
- Test: `src/test/java/dev/krona/urbex/config/PresetResolutionTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `PresetDefinition.KEYS` loses `"atmosphere"` (16 entries remain). `PresetDefinition`'s public constructor loses its `Optional<AtmosphereSettings>` parameter, going from 17 to 16 parameters in the order `(extendsId, displayName, description, extraDescription, warning, icon, terrain, cities, buildings, roads, highways, railways, destruction, decoration, spawn, misc)`. `PresetDefinition.atmosphere()` is gone. `Preset` loses the five `public float` fields `HORIZON`, `FOG_RED`, `FOG_GREEN`, `FOG_BLUE`, `FOG_DENSITY`. Task 2 rewrites this same constructor's body.

- [ ] **Step 1: Write the failing test**

The new behaviour worth pinning is the migration contract: a pack that still declares `atmosphere` must decode without error and be reported as an unknown key. Today `atmosphere` is a *known* key, so this test fails — it is the red test for the whole removal.

Add to `src/test/java/dev/krona/urbex/config/PresetCodecTest.java`, after `unknownTopLevelKeyParsesButWarns`:

```java
    /**
     * The removed {@code atmosphere} section is not a hard error. It never had a reader (issue #73),
     * so a pack that still declares it generates exactly what it did before - it is reported through
     * the ordinary unknown-key WARN, and the file still loads.
     */
    @Test
    void removedAtmosphereSectionParsesButWarns() {
        String json = "{\"description\":\"x\",\"atmosphere\":{\"horizon\":128,\"fogDensity\":0.02}}";

        PresetDefinition re = assertDoesNotThrow(() -> decode(json));
        assertEquals("x", re.description().orElseThrow());

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Dynamic<JsonElement> dyn = new Dynamic<>(JsonOps.INSTANCE, root);
        assertEquals(List.of("atmosphere"), UnknownKeys.check(dyn, PresetDefinition.KEYS));
    }
```

No new imports are needed — `JsonObject`, `JsonParser`, `Dynamic`, `JsonOps`, `List`, `UnknownKeys`, `assertDoesNotThrow`, `assertEquals` are all already imported by this file.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'dev.krona.urbex.config.PresetCodecTest'
```

Expected: `removedAtmosphereSectionParsesButWarns` FAILS on the last assertion — `expected: <[atmosphere]> but was: <[]>`, because `"atmosphere"` is still in `PresetDefinition.KEYS`. The other tests in the class pass.

- [ ] **Step 3: Delete the section from the Java model**

**3a.** Delete the file `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/preset/AtmosphereSettings.java`.

```bash
git rm src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/preset/AtmosphereSettings.java
```

**3b.** In `src/main/java/dev/krona/urbex/config/Preset.java`, delete the five field declarations (`:168-172`):

```java
    public float HORIZON = -1f;
    public float FOG_RED = -1.0f;
    public float FOG_GREEN = -1.0f;
    public float FOG_BLUE = -1.0f;
    public float FOG_DENSITY = -1.0f;
```

Delete the five matching lines from the copy (`:424-428`):

```java
        p.HORIZON = HORIZON;
        p.FOG_RED = FOG_RED;
        p.FOG_GREEN = FOG_GREEN;
        p.FOG_BLUE = FOG_BLUE;
        p.FOG_DENSITY = FOG_DENSITY;
```

In `toDefinition()`, delete the local (`:574-580`):

```java
        AtmosphereSettings atmosphere =
                new AtmosphereSettings(
                Optional.of(HORIZON),
                Optional.of(FOG_RED),
                Optional.of(FOG_GREEN),
                Optional.of(FOG_BLUE),
                Optional.of(FOG_DENSITY));
```

and its argument from the `new PresetDefinition(...)` call (`:602`):

```java
                Optional.of(atmosphere),
```

Finally remove the now-unused import:

```java
import dev.krona.urbex.worldgen.lost.regassets.data.preset.AtmosphereSettings;
```

**3c.** In `src/main/java/dev/krona/urbex/worldgen/lost/regassets/PresetDefinition.java`, make five deletions.

The `KEYS` constant loses `"atmosphere"`:

```java
    public static final Set<String> KEYS = Set.of("extends", "name", "description", "extraDescription", "warning",
            "icon", "terrain", "cities", "buildings", "roads", "highways", "railways", "destruction", "decoration",
            "spawn", "misc");
```

Delete the codec group entry:

```java
                    AtmosphereSettings.CODEC.optionalFieldOf("atmosphere").forGetter(PresetDefinition::atmosphere),
```

Delete the field declaration, the parameter in **both** constructors, the assignment in the private constructor, and the pass-through in the public constructor's `this(...)` call:

```java
    private final Optional<AtmosphereSettings> atmosphere;
```
```java
                     Optional<AtmosphereSettings> atmosphere,
```
```java
        this.atmosphere = atmosphere;
```

The public constructor's delegating call becomes:

```java
        this(new Meta(extendsId, displayName, description, extraDescription, warning, icon),
                terrain, cities, buildings, roads, highways, railways, destruction, decoration,
                spawn, misc);
```

Delete the accessor:

```java
    public Optional<AtmosphereSettings> atmosphere() {
        return atmosphere;
    }
```

Delete the `applyTo` line:

```java
        atmosphere.ifPresent(s -> s.apply(p));
```

And remove the import:

```java
import dev.krona.urbex.worldgen.lost.regassets.data.preset.AtmosphereSettings;
```

**3d.** In `src/main/java/dev/krona/urbex/gui/settings/Settings.java`, delete the five slider registrations (`:487-496`):

```java
        r.slider("HORIZON", SettingCategory.ADVANCED, -1, 256, 1,
                p -> (double) p.HORIZON, (p, v) -> p.HORIZON = ((Double) v).floatValue());
        r.slider("FOG_RED", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_RED, (p, v) -> p.FOG_RED = ((Double) v).floatValue());
        r.slider("FOG_GREEN", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_GREEN, (p, v) -> p.FOG_GREEN = ((Double) v).floatValue());
        r.slider("FOG_BLUE", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_BLUE, (p, v) -> p.FOG_BLUE = ((Double) v).floatValue());
        r.slider("FOG_DENSITY", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_DENSITY, (p, v) -> p.FOG_DENSITY = ((Double) v).floatValue());
```

and trim the section comment at `:464` so it no longer advertises them:

```java
        // Identifier/list TEXT fields and low-level generation switches.
```

- [ ] **Step 4: Update the three remaining test files**

These will not compile until Step 3 has landed, and Step 3 will not compile without them — do both before running the suite.

In `src/test/java/dev/krona/urbex/config/PresetCodecTest.java`, delete the assertion inside `minimalFileParses`:

```java
        assertTrue(re.atmosphere().isEmpty());
```

In `src/test/java/dev/krona/urbex/config/PresetSchemaTest.java`, delete the import of `AtmosphereSettings` (`:10`) and its `EXPECTED_SECTION_KEYS` entry (`:62`):

```java
        EXPECTED_SECTION_KEYS.put("atmosphere", AtmosphereSettings.KEYS);
```

In `src/test/java/dev/krona/urbex/config/PresetRoundTripTest.java`, delete the import of `AtmosphereSettings` (`:7`), its `EXPECTED_SECTION_KEYS` entry (`:50`), and the `HORIZON` pair in `roundTripPreservesValues` (`:82` and `:100`):

```java
        p.HORIZON = 100f;
```
```java
        assertEquals(100f, resolved.HORIZON);
```

Every other section keeps its representative field in that test, so nothing replaces `HORIZON`.

In `src/test/java/dev/krona/urbex/config/PresetResolutionTest.java`, `presetWithExtends` drops one `Optional.empty()` — seventeen arguments become sixteen:

```java
    /** Builds a {@code PresetDefinition} with only the {@code extends} field set. */
    private static PresetDefinition presetWithExtends(Identifier extendsId) {
        return new PresetDefinition(Optional.of(extendsId), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }
```

- [ ] **Step 5: Remove the section from resources, schema and docs**

**5a.** In `src/main/resources/assets/urbex/lang/en_us.json`, delete these ten lines (`:322-331`) exactly:

```json
  "urbex.setting.HORIZON": "Horizon",
  "urbex.setting.HORIZON.tooltip": "This is used client-side (but only if the client has this mod) to set the height of the horizon",
  "urbex.setting.FOG_RED": "Fog Red",
  "urbex.setting.FOG_RED.tooltip": "This is used client-side (but only if the client has this mod) for the fog color",
  "urbex.setting.FOG_GREEN": "Fog Green",
  "urbex.setting.FOG_GREEN.tooltip": "This is used client-side (but only if the client has this mod) for the fog color",
  "urbex.setting.FOG_BLUE": "Fog Blue",
  "urbex.setting.FOG_BLUE.tooltip": "This is used client-side (but only if the client has this mod) for the fog color",
  "urbex.setting.FOG_DENSITY": "Fog Density",
  "urbex.setting.FOG_DENSITY.tooltip": "This is used client-side (but only if the client has this mod) for the fog density",
```

They sit between `urbex.setting.BEDROCK_LAYER.tooltip` and `urbex.setting.SPAWN_BIOME`, so no trailing-comma fixup is needed — every neighbouring line keeps its comma.

**5b.** In `src/main/resources/data/urbex/urbex/presets/cavern.json`, delete `:13-19`:

```json
  "atmosphere": {
    "horizon": 128,
    "fogRed": 0,
    "fogGreen": 0,
    "fogBlue": 0,
    "fogDensity": 0.02
  },
```

**5c.** In `src/main/resources/data/urbex/urbex/presets/floating.json`, delete `:23-25`:

```json
  "atmosphere": {
    "horizon": 0
  },
```

**5d.** In `docs/schema/preset.schema.json`, delete the whole `"atmosphere"` property — from the `"atmosphere": {` line at `:761` through its closing `},`, immediately before `"misc": {`. Leave the `"spawn"` block that precedes it and the `"misc"` block that follows it untouched, and keep the root's trailing `"additionalProperties": false` / `"patternProperties"` intact.

**5e.** In `docs/presets.md:31-33`, change the section count and drop `atmosphere` from the list:

```markdown
top-level object has six plain metadata fields (`extends`, `name`, `description`, `extraDescription`,
`warning`, `icon`) plus ten **sections**, each grouping related settings: `terrain`, `cities`,
`buildings`, `roads`, `highways`, `railways`, `destruction`, `decoration`, `spawn`, `misc`.
```

**5f.** In `CHANGELOG.md`, add this as the first bullet under `## Unreleased`, matching the surrounding entries' style:

```markdown
- **The `atmosphere` preset section is gone, along with its five settings sliders.** `horizon`,
  `fogRed`, `fogGreen`, `fogBlue` and `fogDensity` were read by nothing on either side (issue #73).
  - *They were dead in singleplayer too.* The tooltips promised "used client-side (but only if the
    client has this mod)", but no renderer ever read them and the network package that would have
    carried them to a client was already deleted — so a player could move five ADVANCED sliders and
    nothing at all happened.
  - *An existing pack that still writes the section keeps loading.* It is reported by the ordinary
    unknown-key WARN and generates exactly what it generated before, because nothing ever consumed
    the values. The JSON Schema flags it while you type.
  - *No worldgen change*: every digest golden is unchanged.
```

- [ ] **Step 6: Run the full suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. In particular `PresetCodecTest.removedAtmosphereSectionParsesButWarns` now passes; `PresetSchemaTest.schemaCoversExactlyTheCodecKeys` passes because the schema's top-level properties again equal `PresetDefinition.KEYS`; and `PresetSchemaTest.everyShippedPresetValidatesAgainstSchema` passes because `cavern.json` and `floating.json` no longer carry the removed section.

If `everyShippedPresetValidatesAgainstSchema` fails naming `atmosphere`, a shipped preset still declares it — re-check 5b/5c. If `schemaCoversExactlyTheCodecKeys` fails on the top-level comparison, the schema still has the property — re-check 5d.

- [ ] **Step 7: Confirm no digest golden moved**

```bash
git status --porcelain -- '*.golden'
```

Expected: empty output. Any modified golden means something reached generation and this task's premise is wrong — stop and report rather than regenerating.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
refactor: remove the dead atmosphere preset section

HORIZON and the four FOG_* fields were declared, copied, round-tripped
through a codec and exposed as five ADVANCED sliders, and read by nothing.
The network package that would have carried them to a client is already
gone, so they were dead in singleplayer as well as multiplayer.

A pack that still declares the section keeps loading: atmosphere now falls
into the preset registry's existing unknown-key WARN, and since nothing
ever consumed the values its generation output is byte-identical. No
digest golden moves.

Refs #73, #134

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Collapse `PresetDefinition.Meta`

`Meta` exists only because `RecordCodecBuilder.group` caps at sixteen fields and the flat form needed seventeen. Task 1 freed the slot: six metadata fields plus ten sections is exactly sixteen. This is a pure refactor — no JSON key moves, no key is renamed, and the six metadata keys were always top-level.

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/PresetDefinition.java`
- Test: `src/test/java/dev/krona/urbex/config/PresetCodecTest.java`

**Interfaces:**
- Consumes: `PresetDefinition`'s 16-parameter public constructor and 16-entry `KEYS`, both from Task 1.
- Produces: no signature change. The public constructor keeps the same 16 parameters in the same order and becomes the codec's own constructor. `Meta`, `META` and `meta()` are private and have no callers outside the class, so nothing downstream moves.

- [ ] **Step 1: Write the characterization test**

`minimalFileParses` only asserts the metadata accessors are *empty*. Nothing currently pins all six decoding with values, which is exactly what this refactor rewires. Add this first: it must pass **before** the refactor as well as after — that is what makes it a safety net rather than a new requirement.

Add to `src/test/java/dev/krona/urbex/config/PresetCodecTest.java`, after `minimalFileParses`:

```java
    /**
     * All six metadata keys are top-level keys of the preset object, decoded alongside the sections.
     * Pinned because they have been routed through the codec two different ways - see the flat
     * {@code RecordCodecBuilder.group} in {@code PresetDefinition}, which they were briefly lifted
     * out of to buy back a field slot.
     */
    @Test
    void everyMetadataKeyDecodes() {
        PresetDefinition re = decode("{\"extends\":\"urbex:default\",\"name\":\"Tall Buildings\","
                + "\"description\":\"d\",\"extraDescription\":\"e\",\"warning\":\"w\","
                + "\"icon\":\"i.png\",\"cities\":{\"cityChance\":0.25}}");

        assertEquals(Identifier.fromNamespaceAndPath("urbex", "default"), re.getExtends().orElseThrow());
        assertEquals("Tall Buildings", re.displayName().orElseThrow());
        assertEquals("d", re.description().orElseThrow());
        assertEquals("e", re.extraDescription().orElseThrow());
        assertEquals("w", re.warning().orElseThrow());
        assertEquals("i.png", re.icon().orElseThrow());
        assertEquals(0.25, re.cities().orElseThrow().cityChance().orElseThrow());
    }
```

Add the one import this needs, alongside the file's existing imports:

```java
import net.minecraft.resources.Identifier;
```

- [ ] **Step 2: Run it to verify it passes on the unrefactored code**

```bash
./gradlew test --tests 'dev.krona.urbex.config.PresetCodecTest'
```

Expected: PASS. A failure here means the test itself is wrong, not the code — fix the test before touching `PresetDefinition`.

- [ ] **Step 3: Commit the safety net**

```bash
git add src/test/java/dev/krona/urbex/config/PresetCodecTest.java
git commit -m "$(cat <<'EOF'
test: pin all six preset metadata keys decoding

They are about to be routed through the codec a second way. Nothing
asserted them with values - minimalFileParses only checks they are empty.

Refs #134

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Inline the metadata fields and delete `Meta`**

In `src/main/java/dev/krona/urbex/worldgen/lost/regassets/PresetDefinition.java`:

**4a.** Delete the `Meta` record and its javadoc (the block beginning *"The six non-section keys, as one {@link MapCodec} inlined into..."*) and the `META` map codec beneath it.

**4b.** Replace the `RAW` codec with the flat sixteen-field group:

```java
    private static final Codec<PresetDefinition> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(PresetDefinition::getExtends),
                    Codec.STRING.optionalFieldOf("name").forGetter(PresetDefinition::displayName),
                    Codec.STRING.optionalFieldOf("description").forGetter(PresetDefinition::description),
                    Codec.STRING.optionalFieldOf("extraDescription").forGetter(PresetDefinition::extraDescription),
                    Codec.STRING.optionalFieldOf("warning").forGetter(PresetDefinition::warning),
                    Codec.STRING.optionalFieldOf("icon").forGetter(PresetDefinition::icon),
                    TerrainSettings.CODEC.optionalFieldOf("terrain").forGetter(PresetDefinition::terrain),
                    CitySettings.CODEC.optionalFieldOf("cities").forGetter(PresetDefinition::cities),
                    BuildingSettings.CODEC.optionalFieldOf("buildings").forGetter(PresetDefinition::buildings),
                    RoadSettings.CODEC.optionalFieldOf("roads").forGetter(PresetDefinition::roads),
                    HighwaySettings.CODEC.optionalFieldOf("highways").forGetter(PresetDefinition::highways),
                    RailwaySettings.CODEC.optionalFieldOf("railways").forGetter(PresetDefinition::railways),
                    DestructionSettings.CODEC.optionalFieldOf("destruction").forGetter(PresetDefinition::destruction),
                    DecorationSettings.CODEC.optionalFieldOf("decoration").forGetter(PresetDefinition::decoration),
                    SpawnSettings.CODEC.optionalFieldOf("spawn").forGetter(PresetDefinition::spawn),
                    MiscSettings.CODEC.optionalFieldOf("misc").forGetter(PresetDefinition::misc)
            ).apply(instance, PresetDefinition::new));
```

**4c.** Delete the private delegating constructor entirely, and make the surviving public constructor assign the fields directly:

```java
    public PresetDefinition(Optional<Identifier> extendsId,
                     Optional<String> displayName,
                     Optional<String> description,
                     Optional<String> extraDescription,
                     Optional<String> warning,
                     Optional<String> icon,
                     Optional<TerrainSettings> terrain,
                     Optional<CitySettings> cities,
                     Optional<BuildingSettings> buildings,
                     Optional<RoadSettings> roads,
                     Optional<HighwaySettings> highways,
                     Optional<RailwaySettings> railways,
                     Optional<DestructionSettings> destruction,
                     Optional<DecorationSettings> decoration,
                     Optional<SpawnSettings> spawn,
                     Optional<MiscSettings> misc) {
        this.extendsId = extendsId;
        this.displayName = displayName;
        this.description = description;
        this.extraDescription = extraDescription;
        this.warning = warning;
        this.icon = icon;
        this.terrain = terrain;
        this.cities = cities;
        this.buildings = buildings;
        this.roads = roads;
        this.highways = highways;
        this.railways = railways;
        this.destruction = destruction;
        this.decoration = decoration;
        this.spawn = spawn;
        this.misc = misc;
    }
```

**4d.** Delete the now-unused private `meta()` getter:

```java
    private Meta meta() {
        return new Meta(extendsId, displayName, description, extraDescription, warning, icon);
    }
```

**4e.** Remove the now-unused `MapCodec` import:

```java
import com.mojang.serialization.MapCodec;
```

Leave `getExtends()`, every public accessor, `applyTo`, `KEYS` and the `CODEC` wrapping (`RetiredKeys.reject(UnknownKeys.warning(RAW, KEYS, "preset"), "preset")`) exactly as they are.

- [ ] **Step 5: Run the full suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL, with `everyMetadataKeyDecodes` still passing and `PresetRoundTripTest.toReEncodesEveryKey` unchanged.

If the build fails with *"no suitable method found for group"* or a `Function17`/arity error, the group has more than sixteen entries — Task 1's removal of the `atmosphere` entry did not land, or a metadata entry was duplicated.

- [ ] **Step 6: Confirm no digest golden moved**

```bash
git status --porcelain -- '*.golden'
```

Expected: empty output.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
refactor: fold PresetDefinition.Meta back into the flat codec

Meta existed only because RecordCodecBuilder.group caps at sixteen fields
and the flat form needed seventeen - its own javadoc called it a shape
nobody asked for, existing purely to be flattened again. Removing the
atmosphere section freed the slot: six metadata fields plus ten sections
is exactly sixteen.

No format change. Those six were always top-level keys.

Refs #134

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Open the pull request

**Files:** none — this task produces the PR only.

**Interfaces:**
- Consumes: the commits from Tasks 1 and 2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Verify the branch is clean and the suite is green**

```bash
git status --porcelain && ./gradlew test
```

Expected: no uncommitted changes, BUILD SUCCESSFUL.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin HEAD
```

- [ ] **Step 3: Open the PR**

The body must **not** contain a GitHub auto-closing keyword for #73 — write `#73` bare, never `Closes #73` or `Fixes #73`. #73's static-handoff half is still open and belongs to epic #134 phase 3 item 11.

```bash
gh pr create --title "Remove the dead atmosphere preset section" --body "$(cat <<'EOF'
## What

Deletes the `atmosphere` preset section — `horizon`, `fogRed`, `fogGreen`, `fogBlue`, `fogDensity` —
end-to-end: the five `Preset` fields, `AtmosphereSettings`, the `PresetDefinition` codec entry, the
five ADVANCED sliders, the ten lang keys, the JSON Schema block, and the sections in the two shipped
presets that declared them.

Then collapses `PresetDefinition.Meta`, which existed only because `RecordCodecBuilder.group` caps at
sixteen fields and the flat form needed seventeen. Removing the section freed the slot.

## Why

Nothing read the five values on either side. The only other mention of fog anywhere in `src/main` was
a comment. They were dead in singleplayer as well as multiplayer, because the `network` package that
would have carried them to a client was already deleted and no client-side renderer ever consumed
them — so a player could move five sliders and get nothing.

This answers the **Decide:** in #73 — *"implement a configuration-phase handshake, or delete the
network package and the client fog/horizon settings"* — by taking the deletion branch, whose
network-package half already landed.

## What this does not do

It does **not** close #73. The static `Config.profileFromClient` / `Config.jsonFromClient` handoff
that the issue's title is actually about is untouched; that half is epic #134 phase 3 item 11,
*"Replace the static client-to-integrated-server selection handoff with an explicit, versioned
boundary."*

## Datapack impact

A pack that still declares `atmosphere` keeps loading. The section falls into the preset registry's
existing unknown-key WARN and, because nothing ever consumed the values, generation output is
byte-identical. `RetiredKeys` was considered and rejected: its contract is *"deleted, not aliased:
use X instead"*, and there is no replacement key here — hard-failing the decode would turn a
provably inert section into a preset that refuses to load.

The `urbex:presets` registry and every preset ID in it are unchanged, so epic #134's *"Preserve
external datapack registry IDs"* constraint is not engaged; the removal is a section key inside the
format, and was approved by the repo owner.

## Testing

`./gradlew test` passes. `PresetCodecTest.removedAtmosphereSectionParsesButWarns` pins the migration
contract; `everyMetadataKeyDecodes` pins the six metadata keys across the `Meta` collapse.

Digest suites were not run and are not implicated: epic #134's PR rules require them for planning,
RNG, palette, block-write or ordering changes, and none of the five fields ever reached generation.
All five goldens are unmodified in this branch.

Refs #73, #134

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Report the PR URL to the user**

---

## Notes for the implementer

- **Task 1 Steps 3 and 4 must land together.** Deleting `AtmosphereSettings` breaks the three test files that import it, and shrinking the constructor breaks `PresetResolutionTest` — neither side compiles alone. Make all the edits, then compile once.
- **The digest goldens are the tripwire.** Both tasks check them. This change is only correct if they stay byte-identical; a moved golden falsifies the whole premise and is a stop-and-report, never a regenerate.
- **`CHANGELOG.md` is an addition to the spec's file list.** The spec's scope table did not name it, but the repo keeps a maintained `## Unreleased` section with prose entries for datapack-facing changes, and this is one.
