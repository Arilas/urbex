# World-Creation GUI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the injected-button GUI with a real Cities tab (preset list + detail + live preview) and a metadata-driven Customize editor exposing all profile settings, per `docs/superpowers/specs/2026-08-10-gui-redesign-design.md`.

**Architecture:** Phase 1 adds the tab, preset list, selection state and a fixed/cached preview engine while still opening the old editor. Phase 2 adds a `SettingDescriptor` framework that generates the editor (sidebar categories, search, log/linear sliders, pinned preview) and deletes the old screen. All state publishing reuses `Config.profileFromClient`/`jsonFromClient`.

**Tech Stack:** Fabric 26.2 mappings, vanilla widgets only (`ObjectSelectionList`, `AbstractSliderButton`, `GridLayout`/`LinearLayout`, `TabNavigationBar`), one new client mixin, JUnit 5.

## Global Constraints

- Java 25, MC 26.2 named mappings; method is `markPosForPostProcessing`-style casing — verify names with `javap` against `./.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-7bbd2dae7e/26.2/minecraft-merged-7bbd2dae7e-26.2.jar` before mixing in.
- Every mixin `require = 1` (already the json default).
- No absolute pixel layouts; must be usable at GUI scale 4 on 1920×1080.
- No display string used as an identity key; all new strings in `assets/urbex/lang/en_us.json`.
- The worldgen digest (`./gradlew runDigestCheck`) must pass **unchanged** after every task — this is all client-side work.
- One PR per phase. Phase 1 closes #66, #67, #68. Phase 2 closes #64, #65.
- Branch: `feat/gui-redesign` (spec already committed there).

---

## Phase 1 — Cities tab, presets, preview

### Task 1: Preview engine — correct and cached

**Files:**
- Create: `src/main/java/dev/krona/urbex/gui/preview/CityPreview.java`
- Modify: `src/main/java/dev/krona/urbex/gui/NullDimensionInfo.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/City.java` (the two `provider.getWorld() != null` guards)
- Test: `src/test/java/dev/krona/urbex/gui/preview/CityPreviewKeyTest.java`

**Interfaces:**
- Produces: `CityPreview.render(GuiGraphics g, int x, int y, int w, int h)`, `CityPreview.update(UrbexProfile profile, String worldStyle, long seed)` (no-op when the (profileHash, worldStyle, seed) key is unchanged), `CityPreview.close()` (frees the texture), `static long seedFromUi(String seedField, long fallbackRandom)`.
- Consumes: existing `NullDimensionInfo`, `ChunkCharacteristics` via `BuildingInfo.getChunkCharacteristics`.

- [ ] **Step 1: Failing test for the cache key and seed source.** `CityPreviewKeyTest`: `update` with equal (profile JSON hash, worldstyle, seed) must report `false` (no recompute) from a package-visible `boolean needsRecompute(...)`; `seedFromUi("1337", r)` returns `1337`'s `String.hashCode`-free parse — exactly vanilla's rule: numeric string → `Long.parseLong`, non-numeric → `String.hashCode()`, blank → fallback. Three asserts. Run; expect compile failure.
- [ ] **Step 2: Implement `CityPreview`.** Owns: key record `(int profileJsonHash, String worldStyle, long seed)`; a `NullDimensionInfo` built once per key change; an `int[] colors = new int[W*H]` (W=62, H=58 as today) filled from `getChunkCharacteristics` (city → 0x995555, building → 0xffffff, else terrain green, water blue for `isOcean`-style biomes as the old `renderPreviewMap` did); a `DynamicTexture` uploaded on recompute; `render` blits the texture and a 3-swatch legend (city/building/water) using lang keys `urbex.preview.legend.*`. Recompute is synchronous but only on key change; callers debounce (Task 6).
- [ ] **Step 3: Fix `NullDimensionInfo` (#67).** `dimension()` returns `Level.OVERWORLD` (agreeing with `getType()`); `getBiome` returns a plains fallback instead of dereferencing the null `biomeRegistry`; `loadPredefinedStuff` path must not latch `AssetRegistries.loadedPredefined` when the level is null (guard in `AssetRegistries.java:79`); give `NullDimensionInfo` the client's `RegistryAccess` (from `Minecraft.getInstance().getConnection()` being null at world-creation, use `CreateWorldScreen`'s `WorldCreationContext.worldgenLoadContext()` passed into the constructor) so `City.java`'s two `provider.getWorld() != null` guards can be relaxed to "has registry access": worldstyle city-chance multiplier and `CITY_MINHEIGHT/MAXHEIGHT` gating then run in previews.
- [ ] **Step 4: Verify.** `./gradlew test` green (new + existing); `./gradlew runDigestCheck` → `URBEX-DIGEST-CHECK: OK` with the current golden (relaxed guards must behave identically in-world where `getWorld() != null` already held).
- [ ] **Step 5: Commit** `feat(gui): cached, honest city preview engine`.

### Task 2: Preset selection state

**Files:**
- Create: `src/main/java/dev/krona/urbex/gui/PresetSelection.java`
- Modify: `src/main/java/dev/krona/urbex/gui/RecreateProfileRestore.java` (retarget), `src/main/java/dev/krona/urbex/setup/ClientEventHandlers.java` (reset on disconnect), `src/main/java/dev/krona/urbex/gui/ClientProfileSetup.java` (fix `toggleWorldStyle` for #66; the class otherwise stays until Phase 2)
- Test: `src/test/java/dev/krona/urbex/gui/PresetSelectionTest.java`

**Interfaces:**
- Produces: `PresetSelection.CLIENT` singleton with `List<Entry> entries()` (`Entry(String id, Component name, boolean custom, String basedOn, Optional<UrbexProfile> profile)`; first entry always `disabled`), `select(String id)`, `selected()`, `publish()` (writes `Config.profileFromClient`/`jsonFromClient` + dirty counter + `Config.resetProfileCache()`, exactly like the old `selectProfile`), `applyCustomized(UrbexProfile copy, String basedOn)`, `reset()`, `restore(String profileName, String json)` (Re-Create path).
- Consumes: `ProfileSetup.STANDARD_PROFILES` (`isPublic()` filter), `Config` statics.

- [ ] **Step 1: Failing tests.** Ordering (`disabled` first, publics sorted with `default` first as today, customs last); `select("nope")` is a no-op; `restore` with unknown profile leaves selection untouched; `restore` with JSON produces a custom entry `basedOn="customized"`-free label. Run, expect compile failure.
- [ ] **Step 2: Implement `PresetSelection`.** Pure state + the `publish()` bridge; `entries()` rebuilt from `STANDARD_PROFILES` on demand; nothing here touches widgets, so it is unit-testable headless. `customize()`-style mutation of `STANDARD_PROFILES` is NOT copied here — custom configs live only in the selection (Phase 2's editor supplies them via `applyCustomized`).
- [ ] **Step 3: Retarget Re-Create.** `RecreateProfileRestore.consumePending()` → `PresetSelection.CLIENT.restore(...)`; `ClientEventHandlers` disconnect → `PresetSelection.CLIENT.reset()` alongside the existing resets.
- [ ] **Step 4: Fix `toggleWorldStyle` (#66)** in place: try-with-resources on the `MultiPackResourceManager`, source packs from the `CreateWorldScreen`'s `WorldCreationContext` datapack repository instead of the client resource-pack repository, guard the empty-styles list.
- [ ] **Step 5: Verify + commit** (`./gradlew test`, digest unchanged) `feat(gui): preset selection state, Re-Create retarget, worldstyle toggle fixes`.

### Task 3: The Cities tab

**Files:**
- Create: `src/main/java/dev/krona/urbex/mixin/CreateWorldScreenTabMixin.java`, `src/main/java/dev/krona/urbex/gui/CitiesTab.java`, `src/main/java/dev/krona/urbex/gui/PresetListWidget.java`, `src/main/resources/assets/urbex/lang/en_us.json`
- Modify: `src/main/resources/urbex.mixins.json` (client list), `src/main/java/dev/krona/urbex/setup/ClientEventHandlers.java` (delete the More-tab button injection; keep the Re-Create consume hook)
- Test: manual checklist (widgets) + existing suites

**Interfaces:**
- Consumes: `PresetSelection.CLIENT`, `CityPreview`, `CreateWorldScreen.getUiState().getSeed()`.
- Produces: `CitiesTab implements Tab` (GridLayout-based like vanilla's `CreateWorldScreen.MoreTab`), registered by the mixin.

- [ ] **Step 1: Mixin.** Target `TabNavigationBar$Builder#addTabs(Tab...)` from `CreateWorldScreen`'s init: `@ModifyArgs` (or `@ModifyArg` on the array) appending a `new CitiesTab(createWorldScreen)` when the builder call site is `CreateWorldScreen`. Alternative anchor if the varargs shape fights back: inject at `CreateWorldScreen` init `@At("TAIL")` is NOT acceptable (tab bar already built); instead `@Redirect` the `addTabs` call. Verify the chosen target with `javap` first; `require=1` guards the result. Add to `"client"` in `urbex.mixins.json`.
- [ ] **Step 2: `PresetListWidget`** extends `ObjectSelectionList<PresetListWidget.Row>`; rows render icon (from `UrbexProfile.getIcon()` — the 18 shipped textures; fallback plain tile), name, and for customs a `✎` prefix + "based on X" suffix (lang: `urbex.preset.custom_suffix`). Selection calls `PresetSelection.CLIENT.select(id)` + `publish()`.
- [ ] **Step 3: `CitiesTab`.** Two-column `GridLayout`: left the list; right a detail panel — name (bold), description/extra/warning (`MultiLineTextWidget`, colors as today's `getProfileInfo`), the `CityPreview` (updates on selection change and on tab switch using `seedFromUi(uiState.getSeed(), randomFallback)`), "⟳ New preview seed" button (disabled + tooltip `urbex.preview.seed_locked` when the seed field is non-blank), and "Customize this preset…" button → opens the **old** `UrbexConfigScreen` (Phase 2 swaps this) — disabled for the `disabled` entry. All strings via lang keys (`urbex.tab.cities`, `urbex.preset.disabled`, …).
- [ ] **Step 4: Delete the More-tab button injection** from `ClientEventHandlers` (keep screen-identity Re-Create consumption, now keyed on the CreateWorldScreen as before).
- [ ] **Step 5: Verify.** `./gradlew build` green; `./gradlew runDigestCheck` unchanged; **manual**: `./gradlew runClient` → Create New World shows the Cities tab at GUI scales 2 and 4; selecting Rare shows description+preview; fixed seed `1337` disables reroll; Re-Create of a world with a saved profile pre-selects it. Screenshot for the PR.
- [ ] **Step 6: Commit + PR (Phase 1).** `feat(gui): Cities tab with preset list, detail panel and live preview` — PR body claims closes #66, closes #67, closes #68, includes screenshots and the unchanged digest line.

---

## Phase 2 — the Customize editor

### Task 4: SettingDescriptor framework

**Files:**
- Create: `src/main/java/dev/krona/urbex/gui/settings/SettingDescriptor.java`, `src/main/java/dev/krona/urbex/gui/settings/SettingCategory.java`, `src/main/java/dev/krona/urbex/gui/settings/Settings.java` (the full descriptor registry)
- Modify: `src/main/resources/assets/urbex/lang/en_us.json` (setting names + tooltips)
- Test: `src/test/java/dev/krona/urbex/gui/settings/SettingsCompletenessTest.java`

**Interfaces:**
- Produces: `record SettingDescriptor(String key, SettingCategory category, boolean general, ControlKind kind, double min, double max, double step, boolean logScale, Function<UrbexProfile,Object> getter, BiConsumer<UrbexProfile,Object> setter)`; `enum ControlKind { SLIDER, TOGGLE, CYCLE, TEXT }`; `enum SettingCategory { GENERAL, CITIES, BUILDINGS, DAMAGE, TRANSPORT, SPHERES, TERRAIN, SPAWN, ADVANCED }` (labels via `urbex.category.<lowercase>`); `Settings.ALL : List<SettingDescriptor>`; `Settings.byCategory(SettingCategory)`; `Settings.search(String localizedQuery)`.
- Consumes: `UrbexProfile` public fields directly (getter/setter lambdas) — deliberately NOT the `Configuration` bridge, so #75 part 2 can delete it without touching this framework.

- [ ] **Step 1: Failing completeness test.** Reflection over `UrbexProfile`'s public non-static fields: every field name appears in exactly one non-`general` descriptor key, and every descriptor key matches a field. Excluded fields (e.g. `EDITMODE`-adjacent internals) live in an explicit `EXCLUDED` set in the test with a justification comment each. Also: every descriptor's lang keys (`urbex.setting.<key>`, `.tooltip`) exist in `en_us.json` (load the json from resources in the test).
- [ ] **Step 2: Write the registry.** All ~131 descriptors, category assignments per the spec (★ General = the curated 15: `CITY_CHANCE` (logScale), `CITY_MINRADIUS`, `CITY_MAXRADIUS`, `BUILDING_MINFLOORS`, `BUILDING_MAXFLOORS`, `RUIN_CHANCE`-family, `EXPLOSION_CHANCE` (logScale), `MINI_EXPLOSION_CHANCE` (logScale), loot/lighting density, landscape type, world style). `general=true` entries are the same descriptor instances flagged, not duplicates. Lang entries generated alongside (names Title Case from field names, tooltips from the comments currently in `UrbexProfile.init`).
- [ ] **Step 3: Green + commit** `feat(gui): setting descriptor registry covering the full profile`.

### Task 5: Editor controls

**Files:**
- Create: `src/main/java/dev/krona/urbex/gui/settings/LogValueMapper.java`, `src/main/java/dev/krona/urbex/gui/settings/SettingControls.java` (factory: descriptor → AbstractWidget)
- Test: `src/test/java/dev/krona/urbex/gui/settings/LogValueMapperTest.java`

**Interfaces:**
- Produces: `LogValueMapper(double min, double max)` with `double toSlider(double value)` / `double fromSlider(double t)` (t∈[0,1], `fromSlider(toSlider(v)) ≈ v`), `String format(double value)` (decimals adapt: ≥3 significant digits, so 0.0001 and 0.001 are distinct); `SettingControls.create(SettingDescriptor d, UrbexProfile target, Runnable onChanged) : AbstractWidget` — sliders (`AbstractSliderButton` subclass), toggles (`CycleButton.booleanBuilder`), enums (`CycleButton`), text (`EditBox`), each with `Tooltip.create` from the descriptor tooltip key.
- Consumes: Task 4's descriptors.

- [ ] **Step 1: Failing `LogValueMapperTest`.** Round-trip within 1e-9 relative at min, max, and 7 midpoints for (1e-4, 1); endpoints map to 0 and 1; `format(0.0001)` ≠ `format(0.001)`; monotonicity across 100 samples.
- [ ] **Step 2: Implement** `toSlider(v) = (ln v − ln min) / (ln max − ln min)`, clamp; format via `BigDecimal.round(new MathContext(3))` stripped of trailing zeros.
- [ ] **Step 3: Implement `SettingControls`** (thin, widget-side; no test beyond compile — behavior is exercised in-game and by the completeness test's kind/range sanity assertions: every SLIDER has min<max, every logScale has min>0 — add those asserts to the Task 4 test now).
- [ ] **Step 4: Green + commit** `feat(gui): editor controls with logarithmic sliders`.

### Task 6: The Customize editor screen

**Files:**
- Create: `src/main/java/dev/krona/urbex/gui/CustomizeScreen.java`, `src/main/java/dev/krona/urbex/gui/SaveAsDialog.java`
- Modify: `src/main/java/dev/krona/urbex/gui/CitiesTab.java` (Customize opens `CustomizeScreen`), `src/main/resources/assets/urbex/lang/en_us.json`
- Test: `src/test/java/dev/krona/urbex/gui/SaveAsValidationTest.java`

**Interfaces:**
- Consumes: `Settings.ALL`, `SettingControls`, `CityPreview`, `PresetSelection.CLIENT.applyCustomized(copy, basedOn)`.
- Produces: `CustomizeScreen(Screen parent, UrbexProfile base, String baseName)` — works on `new UrbexProfile(baseName+"-copy", false).copyFrom(base)`; `SaveAsDialog.validateName(String, Set<String> taken) : Optional<Component>` (error or empty).

- [ ] **Step 1: Failing `SaveAsValidationTest`.** Rejects: empty, names in `taken` (built-ins + existing customs), non `[a-z0-9_]+` after lowercasing; accepts `my_wasteland`. Error components are lang-keyed (`urbex.saveas.err.*`).
- [ ] **Step 2: Screen layout.** Left `ObjectSelectionList` of categories (★ General pinned first, Advanced last); middle scrollable widget column rebuilt on category/search change (search `EditBox` on top; filter via `Settings.search`); right the pinned `CityPreview` + reroll button; bottom bar `[Save as…] [Reset to preset] [Done] [Cancel]`; title `Customize: <base>` gaining `*` once any `onChanged` fires. Preview `update` calls debounced 150 ms (a `long nextUpdateAt` checked in `tick()`).
- [ ] **Step 3: Flows.** Done → `applyCustomized(copy, baseName)` + `publish()` + back to parent. Cancel → back, no publish (the copy is discarded — nothing global was touched, which is what fixes #65's cancel/dup/ESC class; also `onClose()` = Cancel). Reset → re-copy from base, rebuild controls. Save as → dialog; on accept: write `config/urbex/profiles/<name>.json` = `profile.toJson(false)` + `"basedOn": baseName`, register in `STANDARD_PROFILES` as non-public, `PresetSelection` refresh + select it, back to tab. IO errors surface in the dialog (lang `urbex.saveas.err.io`).
- [ ] **Step 4: Verify.** Tests green; digest unchanged; **manual** at GUI scale 4: search "chance" filters across categories; city-chance slider shows 0.0001↔0.02 usable range; Save as creates the file and the list entry survives a game restart.
- [ ] **Step 5: Commit** `feat(gui): metadata-driven customize editor`.

### Task 7: Deletions and Phase 2 PR

**Files:**
- Delete: `src/main/java/dev/krona/urbex/gui/UrbexConfigScreen.java`, `src/main/java/dev/krona/urbex/gui/elements/*` except the slider-math helper class backing `PercentageSliderElementTest` (move it to `gui/settings/` if package-private access demands), `src/main/java/dev/krona/urbex/gui/ClientProfileSetup.java` (state now fully in `PresetSelection`)
- Modify: whatever still imports them (`RecreateProfileRestore` javadoc references, `ClientEventHandlers`), `docs/superpowers/specs/2026-08-10-gui-redesign-design.md` status line → implemented
- Test: full suite + digest

- [ ] **Step 1: Delete and fix references.** `grep -rn "UrbexConfigScreen\|ClientProfileSetup\|gui.elements" src/main/java` must return nothing afterwards (except the kept slider-math file).
- [ ] **Step 2: Verify everything.** `./gradlew build` (all tests), `./gradlew runDigestCheck` unchanged, `runClient` smoke: full flow tab → preset → customize → save-as → create world → cities generate with the customized values (check one obvious setting like city chance visually).
- [ ] **Step 3: Commit + PR (Phase 2).** `feat(gui): replace the old config screen` — closes #64, closes #65; screenshots of tab, editor, search, and log slider.

## Self-review notes

- Spec coverage: entry/tab (T3), preset model incl. Save-as/basedOn (T2/T6), editor structure+search+log sliders (T4-6), preview seed/correctness/caching/legend (T1/T3), help+lang (T3/T4/T6), deletions (T7), phasing (PR steps in T3/T7). Error handling: greyed invalid configs — covered by `PresetSelection.entries()` skipping unparseable profile JSONs with a greyed row (add in T2 Step 2); preview failure fallback — `CityPreview.render` catches and shows `urbex.preview.unavailable` (T1 Step 2).
- Types named identically across tasks (`PresetSelection.CLIENT`, `Settings.ALL`, `CityPreview.update/render`).
- No TBDs; widget-assembly steps carry exact vanilla classes instead of full listings by design (executor model is capable; the novel logic — mappers, validation, cache keys, mixin targets — is spelled out).
