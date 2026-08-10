# World-Creation GUI Redesign

Date: 2026-08-10
Status: approved; Phase 2 (Task 7 deletions) implemented (brainstorm with maintainer; mockups in `.superpowers/brainstorm/94488-1786320857/content/`)
Supersedes: issue #64 (epic); absorbs #65, #66, #67, #68

## Goal

Replace the injected-button + `UrbexConfigScreen` world-creation GUI with a first-class
**Cities tab** in Create New World, a **preset list** with a detail panel, and a
**metadata-driven Customize editor** exposing all profile settings. Selected state must be
visible without clicking anything; presets must be extendable into named, persistent custom
configs; the preview must show what the player will actually get.

## Non-goals

- The in-game part editor (#69/#70) — untouched.
- The `UrbexProfile`/`Configuration` codec rewrite (#75 part 2). The editor framework reads
  the existing `Configuration` metadata; when part 2 lands, the framework's setting
  declarations swap their backing without UI changes.
- Dedicated-server profile sync (#73).
- Translations other than `en_us` (the lang file makes them possible; providing them is not
  in scope).

## 1. Entry: the Cities tab

- A real tab ("Cities") in `CreateWorldScreen`'s tab bar, added by one client mixin that
  appends to the tab list the screen builds. `tabManager` and `MoreTab` access-wideners
  already exist; a new widener/mixin target for tab construction is expected.
- The tab contains the preset list (section 2) and detail panel — it *is* the state display:
  selected preset name, icon, description, warning, mini-preview, and an "✎ based on X"
  marker for custom configs are always visible on the tab.
- The injected "Cities" button on the More tab is deleted.
- "Disabled" is an explicit first entry in the preset list (profile = none), not an implicit
  default.

## 1a. Preset vs worldStyle (orthogonal selectors)

A **preset** carries generation *options* (city chance, radii, damage, …). A **worldStyle**
is a *style* — the block palettes and building sets a city is built from. Upstream bound the
two together (changing style meant a whole new profile). Urbex separates them:

- The Cities tab shows a **worldStyle dropdown** beside the preset list, **only when more than
  one worldStyle is registered** (`AssetRegistries.WORLDSTYLES`); with just the built-in
  `standard` it stays hidden, so the common case is unchanged.
- Choosing a worldStyle applies it over the selected preset (sets `worldStyle` on the published
  profile copy and republishes) — so a player picks a preset and freely experiments across
  Default / LCMT / any datapack-provided style without editing or cloning a profile.
- Consequently `worldStyle` is **not** an editor setting and not a General descriptor; the
  editor covers only the preset's generation options. This resolves the Task 4 parked finding.

## 2. Preset model and extension

**List contents, in order:** Disabled · built-in public profiles (`ProfileSetup`
`isPublic()`, with their shipped `icon_*.png` finally rendered) · user configs from
`config/urbex/profiles/*.json`.

**Flows:**

- *Select preset* → detail panel updates (description / extraDescription / warning from the
  profile, mini-preview, world-style line).
- *Customize this preset…* → opens the editor on a **copy** of the profile. The base preset
  object in `STANDARD_PROFILES` is never mutated (fixes the `customize()` pollution from
  #65).
- Editor **Done** → the edited copy becomes this world's config (the transient "customized"
  path that exists today, labeled "✎ Custom (based on X)" in the list).
- Editor **Save as…** → prompts for a name, writes
  `config/urbex/profiles/<name>.json` (the folder `ProfileSetup` already loads), including a
  `basedOn` field for provenance. Saved configs appear in the list on this and future
  worlds. Name collisions with built-in profiles are rejected in the dialog.
- Editor **Reset to preset** → discards edits back to the base copy.
- Editor **Cancel** → returns to the tab with no state published (fixes #65's
  cancel-publishes-anyway).

**Publishing:** selection and edits feed the existing `Config.profileFromClient` /
`jsonFromClient` statics exactly as `UrbexConfigScreen.selectProfile` does today; server-side
behavior is unchanged. `RecreateProfileRestore` (#85) retargets from `ClientProfileSetup` to
the new state holder and pre-selects the restored entry in the list.

## 3. The Customize editor

Layout (mockup "customize-structure A"): left sidebar of categories · middle scrollable
control list with a search box · right pinned live preview. Vanilla `GridLayout` /
`LinearLayout`; no absolute pixel positions; must be usable at GUI scale 4 on 1920×1080.

**Setting declarations.** A `SettingDescriptor` declares each setting once: profile field
key, category, control type, range/step, display scale (linear or logarithmic), and
localization key. The editor generates controls from the declarations list. Backing
read/write goes through the existing `Configuration` bridge for now (see Non-goals).
All 131 profile settings get descriptors — none of today's 88 missing settings stay hidden.

**Categories:** `★ General` (curated ~15: city chance, city min/max radius, building
min/max floors, ruin %, explosion chances, loot & lighting density, landscape type) · Cities ·
Buildings · Damage & ruins · Highways & rails · Spheres · Terrain & water · Spawn · Advanced
(everything exotic: per-biome multipliers, noise scales, editmode, …). **No duplication**
(revised after in-game review): each setting appears in exactly one category. The curated
common settings live *only* in General — they are removed from their home categories — so the
same slider never shows twice. `general` is thus each descriptor's real home category, not a
duplicate flag. (World style is not here; it is the Cities-tab dropdown, §1a.)

**City Chance / explosion chances — the `-1` perlin mode.** `CITY_CHANCE = -1` selects a
perlin-noise city map (the `largecities` preset uses it) — a mode, not a magnitude. The editor
shows City Chance as a **log slider over the positive range** (so 0.0001 and 0.001 are
distinguishable) plus a **"Perlin city map" toggle** that represents `-1`: toggle on ⇒ field is
`-1` and the slider is inert; toggle off ⇒ field is the slider's positive value. Explosion and
mini-explosion chances are plain log sliders (no `-1` mode).

**Controls:**

- Bounded numerics → sliders with value readout; fine-step via the existing
  `PercentageSliderElement` math (tested, kept).
- City chance, explosion/mini-explosion chance → **logarithmic sliders** (value mapped as
  `10^x`), formatted with enough decimals to distinguish 0.0001 from 0.001.
- Booleans → ON/OFF toggle buttons; enums → cycle buttons (the only place cycling remains).
- Free-text/identifier settings (block ids, biome ids) → edit boxes, in Advanced.
- Search: substring match on localized names, filters the middle panel across all
  categories; Esc clears.

**Editing model:** the editor works on the copy from section 2; a `*` in the title marks
unsaved changes; sliders update the preview live (preview recompute is debounced ~150 ms).

## 4. Preview

- Uses the **actual world seed** from the World tab when the seed field is non-empty —
  preview equals outcome. Blank seed → random preview seed; "⟳ New preview seed" rerolls it.
  With a fixed world seed the reroll button is disabled with a tooltip explaining why.
- Correctness (#67): the preview's dimension info supplies registry access so worldstyle
  city-chance multipliers and `CITY_MINHEIGHT/MAXHEIGHT` gating apply; `dimension()` and
  `getType()` agree so caches hit; `getBiome` cannot NPE; `loadedPredefined` is not latched
  when no level exists.
- Performance (#68): one preview model per (config hash, seed), rendered into a cached
  texture; recompute only on config/seed change (debounced), never per frame.
- A three-swatch legend (city block / building / water) under the map.

## 5. Help and localization

- Every control: tooltip from the setting's description text.
- Every category: one-line description under its header.
- All new-GUI strings in `assets/urbex/lang/en_us.json`; keys follow
  `urbex.setting.<field>`, `urbex.setting.<field>.tooltip`, `urbex.category.<id>`,
  `urbex.preset.<name>.*`, `urbex.screen.*`. No display string is ever used as an identity
  key (kills the `"On".equals(...)` class of bugs).

## 6. Deletions

`UrbexConfigScreen`, the `gui/elements` widget set (except the slider-math helper and its
test), `NullDimensionInfo`'s per-frame construction path, and the More-tab button injection.
`ClientProfileSetup` shrinks to (or is replaced by) the new selection-state holder.

## Error handling

- Missing/broken user config JSON in `config/urbex/profiles/` → entry shown greyed with a
  tooltip ("invalid config: <parse error>"), not a crash; selecting it is impossible.
- Preview generation failure (e.g. broken datapack asset reached through characteristics) →
  the map area shows "preview unavailable" instead of propagating; the error is logged once.
- Save-as with an empty/duplicate/reserved name → inline red message, dialog stays open.
- If the tab mixin fails to apply (`require = 1`), the game crashes at startup loudly —
  preferred over silently shipping without the tab.

## Testing

- Unit: log-slider mapping (value↔position round-trip, endpoints, formatting);
  `SettingDescriptor` completeness check (every `UrbexProfile` public field has exactly one
  descriptor — a reflection test that fails when someone adds a profile field without a
  descriptor); save-as name validation; preset-list ordering.
- The existing `PercentageSliderElementTest` and `RecreateProfileRestoreTest` keep passing
  (retargeted where the state holder moved).
- Manual checklist per phase (screenshots in PR): tab visible at GUI scales 1–4, preview
  matches a generated world for a fixed seed, Re-Create pre-selects the restored config.
- The worldgen digest is expected **unchanged** by every GUI PR (client-only work).

## Phasing

- **Phase 1 — tab & presets:** Cities tab mixin, preset list + detail panel, state display,
  publishing plumbing, Re-Create retarget, preview fixes (#67/#68) reused by the panel.
  "Customize" still opens the *old* editor. Ships alone; closes #66, #67, #68.
- **Phase 2 — editor:** the descriptor framework, sidebar/search/pinned-preview editor,
  log sliders, lang file, deletions of the old screen/elements. Closes #64, #65 and the
  remaining scope.

Each phase is one PR, independently shippable, verified in-game with screenshots plus the
unchanged digest.
