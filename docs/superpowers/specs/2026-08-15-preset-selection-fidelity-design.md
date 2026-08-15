# Preset selection fidelity: customization, Re-Create, storage, and pack control

The Cities tab can show a selection that is not the one the world will generate with. Three
reported symptoms turn out to share that single shape, and a fourth defect - which world records
its own selection - is what makes two of them permanent rather than cosmetic.

## The defects

### 1. A customization is invisible after Done

`PresetListWidget.refreshEntries()` rebuilds every row from `PresetSelection.CLIENT`. Rebuilding
goes through `AbstractSelectionList.clearEntries()`, which clears the row list *and* nulls the
selection; the scroll amount then clamps back to the top. `refreshEntries()` restores the selection
with `setSelected(toSelect)` - which sets state and nothing else. Nothing in `gui/` calls
`scrollToEntry` or `centerScrollOn`.

The customized entry is appended **last**. With the twelve shipped presets that is row 14 of 14, at
`ROW_HEIGHT = 24`, so the list needs 336px to show it. Returning from the editor therefore lands on
a list scrolled to the top with the selection highlight off-screen below, showing stock preset rows.
It reads as "the original preset is still selected".

Two things make it worse once the row *is* visible: it is labelled a bare `Custom`, and it carries
the base preset's icon (`Preset.getIcon()` reads through) and description (`CitiesTab.describe`
reads through). Nothing on screen says the edits are live.

Separately, `CustomizeScreen.onClose()` calls `cancel()`, so Escape discards a dirty draft with no
warning, and `reset()` restores `base` - which, when the editor was opened on an already-customized
row, is the customization rather than the stock preset.

### 2. Re-Create does not restore what the world had

- `RecreateProfileRestore.parse` reads `preset`, `worldStyle` and `overrides`. It never reads
  `worldStyleMix`, which is the key `UrbexData.setChoice` writes whenever the selection is a real
  mix. A re-created world silently collapses to the mix's primary style.
- `PresetSelection.setAvailableWorldStyles` prunes the chosen mix against the injected list and
  sets it to `null` - "use the default" - when nothing survives. `CitiesTab.registeredWorldStyles`
  injects an empty list whenever the worldstyle registry is not reachable. This runs on every tab
  construction, including every window resize, so a restored or player-chosen style can be reset to
  the default and then republished as the default on the next click.
- `restore()` publishes the saved selection unconditionally, but the *visual* selection only lands
  if the saved preset is among the browsable entries. When it is not, the tab shows Disabled while
  the world still generates with the saved preset. The tab is lying, and there is no row that can
  represent an unlisted preset.

### 3. Only client-published selections are recorded

`WorldSelectionResolver.resolve` returns `persist = false` for the configured (global config)
selection. A world created on a modpack's `selectedPreset` therefore records nothing in `UrbexData`.

Two consequences. Editing the config later changes what an **existing** world generates, and terrain
is written once, so that is the expensive kind of wrong. And Re-Create on such a world finds an empty
`preset` key, restores nothing, and drops the player on Disabled with the default world style -
symptom 2 all over again, from a different cause.

The resolver's javadoc argues the current behaviour is deliberate: the config is "a default a player
can change between sessions and expect to take effect". That reasoning holds for runtime settings.
It does not hold for a worldgen selection.

### 4. No pack-level control

`selectedPreset` / `selectedWorldStyle` exist but only act as the server-side fallback when nothing
else is selected. They do not drive what the Cities tab starts on, so a pack's choice is invisible
to the player and one click replaces it. `selectedWorldStyle` also accepts only a single id
(`WorldStyleMix.of(DataTools.fromName(style))`), so a pack cannot ship a mix.

## The design

### Selection visibility and customized identity

- `PresetListWidget.refreshEntries()` scrolls the selected row into view after restoring it. This is
  the root-cause fix for symptom 1 and also makes a Re-Create restore of a preset far down the list
  visible.
- The customized entry sorts **directly after the preset it was customized from**, not at the end of
  the list, so it appears where the player was already looking. It keeps the `urbex:customized`
  sentinel id; only its position in `entries()` changes.
- Its label becomes `Customized: <base name> *`. The trailing marker matches the `*` the editor
  already puts in its own title.
- The detail panel gains an explicit "modified copy of `<base>`" line above the description, and a
  **Revert** action that drops the customization and selects the base preset again. Without it a
  customization is a one-way door.
- `CustomizeScreen` confirms before discarding a dirty draft on Escape or Cancel. A clean draft
  still closes straight through.
- The editor remembers the stock preset it was ultimately derived from, so **Reset** always means
  "back to the shipped preset", not "back to my last customization".

### Re-Create fidelity

- `RecreateProfileRestore.Pending` carries the mix spec, and `parse` reads `worldStyleMix` with the
  legacy `worldStyle` key as the fallback - the same read order `UrbexData.getSelectedWorldStyles`
  already uses. `PresetSelection.restore` parses whichever it was handed.
- `setAvailableWorldStyles` treats an empty injected list as "the registry is not reachable yet" and
  leaves the chosen mix alone. Pruning only happens against a non-empty list, and a mix that prunes
  to nothing keeps its primary rather than collapsing to `null`.
- When a pending restore names a preset that is not among the injected entries, `PresetSelection`
  synthesizes a selected row for it carrying the saved id, so the tab shows what will actually
  generate. The row is marked as unlisted and cannot be customized (there is no resolved `Preset`
  behind it).

### Storage

`WorldSelectionResolver` persists the configured selection as well, so every world records the
selection it was created with on first generation. A later config edit then cannot reach a world
that already exists, and Re-Create finds a record on every world Urbex generated.

This is a deliberate behaviour reversal and the javadoc says so.

### Pack control

`UrbexConfig` gains `citiesTabAccess`, one of `editable` (default), `locked`, `hidden`:

- **editable** - today's behaviour.
- **locked** - the tab renders, showing the configured selection, with the preset list, the world
  style selector and the customize button all inactive and tooltipped "set by the modpack". The
  player can see what they will get and cannot change it.
- **hidden** - the tab is not added at all. The configured selection still generates and is still
  recorded.

One enum rather than two booleans, because `lock = false, hide = true` has no meaning and a schema
that can express it invites the question.

`selectedPreset` / `selectedWorldStyle` become the Cities tab's initial selection, and
`selectedWorldStyle` parses through `WorldStyleMix.parse`, so a pack can ship a mix (subject to the
existing `experimentalMultiWorldStyles` gate, which is applied to the value, not only to the UI).

## Testing

Everything above except the row widgets themselves is headless-testable and gets a regression test:
the customized entry's position and label, restore round-tripping a mix, pruning against an empty
list, the unlisted-preset row, the resolver's `persist` decision, the config enum's parsing and its
effect on the tab's initial selection. The row widgets need a running client; the scroll-into-view
call is verified by the fact that `refreshEntries` asks the list to scroll to the row it selected.

The five digest goldens must be unchanged: none of this touches generation, only which selection
reaches it.
