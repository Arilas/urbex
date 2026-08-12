package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.gui.preview.CityPreview;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.worldgen.lost.cityassets.ExtendsChain;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The "Cities" tab of the vanilla world-creation screen (injected by
 * {@code dev.krona.urbex.mixin.CreateWorldScreenTabMixin}): a preset list on the left, and on the
 * right a detail panel with the preset's name, its description, a live {@link CityPreview} of the
 * city layout that preset would generate, and the two actions (reroll the preview seed, open the
 * customize editor).
 * <p>
 * Nothing here lays widgets out at fixed screen coordinates: every size is derived in
 * {@link #doLayout(ScreenRectangle)} from the tab area the screen hands us, so the tab stays usable
 * down to GUI scale 4 (~480x254 logical pixels), where the preview simply drops out rather than
 * pushing the buttons off-screen.
 */
public class CitiesTab extends GridLayoutTab {

    private static final Component TITLE = Component.translatable("urbex.tab.cities");

    private static final int MARGIN = 8;
    private static final int COLUMN_SPACING = 8;
    private static final int ROW_SPACING = 4;
    private static final int BUTTON_HEIGHT = 20;

    /** Beyond this the two columns just get emptier, so cap and centre instead. */
    private static final int MAX_CONTENT_WIDTH = 460;
    private static final int MIN_LIST_WIDTH = 90;
    private static final int MAX_LIST_WIDTH = 200;
    private static final int MIN_DETAIL_WIDTH = 80;
    private static final int MAX_INFO_ROWS = 5;
    /** Below this the preview is more noise than information - hide it and give the space back. */
    private static final int MIN_PREVIEW_HEIGHT = 40;
    /**
     * Caps the preview width so a tall detail panel doesn't blow the little 62x58 map up to fill the
     * whole column (the live-reported "tall smear"). Mirrors {@code CustomizeScreen}'s preview cap so
     * both call sites show the map at roughly the same modest size.
     */
    private static final int MAX_PREVIEW_WIDTH = 130;

    /** The built-in worldStyle every install ships; sorts first in the selector. */
    private static final String STANDARD_STYLE = "urbex:standard";

    /**
     * The preview owns a GPU texture, and nothing tells a {@link net.minecraft.client.gui.components.tabs.Tab}
     * when it is discarded (the screen rebuilds every tab on each {@code init()}, i.e. on every
     * window resize). Holding the live one statically and closing it when the next tab supersedes
     * it bounds that to a single texture at a time instead of one per resize.
     */
    @Nullable
    private static CityPreview activePreview;

    /**
     * Set when this tab hands the player off to the old editor, consumed by
     * {@code CreateWorldScreenTabMixin} at the tail of the next {@code CreateWorldScreen.init()}.
     * Coming back from that editor goes through {@code setScreen(parent)}, which re-runs
     * {@code init()} - and its tail calls {@code selectTab(0, false)}, dropping the player on the
     * Game tab. Without this they would have to find their way back to Cities every time.
     */
    private static boolean reopenOnCitiesTab;

    private final CreateWorldScreen screen;
    private final CityPreview preview;
    private final RandomSource random = RandomSource.create();

    /**
     * Fully-qualified worldStyle id -> the label to show for it, in the order the selector lists
     * them. Read once in the constructor: the registry cannot change while this tab is alive (a
     * datapack toggle rebuilds the whole screen, and with it this tab).
     */
    private final Map<String, String> worldStyleNames;

    private final PresetListWidget list;
    private final StringWidget nameLabel;
    private final MultiLineTextWidget infoText;
    private final PreviewWidget previewWidget;
    private final Button rerollButton;
    private final Button customizeButton;

    /**
     * The worldStyle selector - {@code null} (and absent from the layout entirely) whenever the
     * datapacks register at most one style, so the common "standard-only" install shows nothing new.
     * worldStyle is orthogonal to the preset (spec 1a): switching it never edits or clones a profile.
     * <p>
     * A plain {@link Button} that opens the {@link WorldStyleDialog} dropdown on press (vanilla has no
     * combobox widget); its label shows the effective style, refreshed whenever that changes.
     */
    @Nullable
    private final Button worldStyleButton;

    /**
     * The seed the preview falls back to while the seed field is empty - vanilla's own
     * "no seed typed" case (see {@link CityPreview#seedFromUi}). The reroll button rolls a new one;
     * a typed seed wins over it, which is why the button locks while one is present.
     */
    private long previewSeedFallback;

    /**
     * The tab area the last {@link #doLayout} ran against, so a detail-panel refresh can redo the
     * layout: the description's height is a function of its text ({@code MultiLineTextWidget
     * .getHeight()} is {@code lineCount * 9}, verified by decompilation), and nothing else re-runs
     * the layout on selection change - {@code TabManager} only calls {@code doLayout} on
     * {@code setTabArea}/{@code setCurrentTab}.
     */
    @Nullable
    private ScreenRectangle lastTabArea;

    public CitiesTab(CreateWorldScreen screen) {
        super(TITLE);
        this.screen = screen;
        this.preview = newPreview(previewRegistries(screen));
        this.previewSeedFallback = random.nextLong();

        // The registered worldStyles and presets both come from the datapacks enabled for the world
        // being created, read straight off the load context's registry - the state layer only needs
        // the enumerated lists, so the tab injects them rather than have PresetSelection reach into a
        // registry it can't see.
        this.worldStyleNames = registeredWorldStyles(screen);
        List<String> worldStyles = List.copyOf(worldStyleNames.keySet());
        PresetSelection.CLIENT.setAvailableWorldStyles(worldStyles);
        PresetSelection.CLIENT.setAvailablePresets(registeredPresets(screen));

        Font font = Minecraft.getInstance().font;

        // Widget sizes here are placeholders only: resizeChildren() derives every one of them from
        // the tab area before the first frame is drawn.
        this.list = new PresetListWidget(Minecraft.getInstance(), MIN_LIST_WIDTH, MIN_LIST_WIDTH, 0,
                entry -> refreshDetail());
        this.nameLabel = new StringWidget(MIN_DETAIL_WIDTH, font.lineHeight, CommonComponents.EMPTY, font);
        this.infoText = new MultiLineTextWidget(CommonComponents.EMPTY, font);
        this.previewWidget = new PreviewWidget();
        this.rerollButton = Button.builder(Component.translatable("urbex.preview.reroll"),
                b -> previewSeedFallback = random.nextLong()).build();
        this.customizeButton = Button.builder(Component.translatable("urbex.screen.customize"),
                b -> openCustomizeEditor()).build();

        // Only offer the selector when there's an actual choice to make (more than one registered
        // style); a single-style install keeps the tab exactly as it was.
        if (worldStyles.size() > 1) {
            WorldStyleMix initial = PresetSelection.CLIENT.effectiveWorldStyles();
            this.worldStyleButton = Button.builder(worldStyleLabel(initial), b -> openWorldStyleDropdown()).build();
            this.worldStyleButton.setTooltip(worldStyleTooltip(initial));
        } else {
            this.worldStyleButton = null;
        }

        this.layout.columnSpacing(COLUMN_SPACING);
        this.layout.addChild(list, 0, 0);
        LinearLayout detailColumn = this.layout.addChild(LinearLayout.vertical().spacing(ROW_SPACING), 0, 1);
        detailColumn.addChild(nameLabel);
        if (worldStyleButton != null) {
            detailColumn.addChild(worldStyleButton);
        }
        detailColumn.addChild(infoText);
        detailColumn.addChild(previewWidget);
        LinearLayout buttonRow = detailColumn.addChild(LinearLayout.horizontal().spacing(ROW_SPACING));
        buttonRow.addChild(rerollButton);
        buttonRow.addChild(customizeButton);

        // Vanilla's own tabs (GameTab, WorldTab) subscribe to the shared ui state the same way; the
        // listener lives exactly as long as the screen does. We need it because the reroll button
        // has to lock the moment the player types into the seed field on another tab.
        screen.getUiState().addListener(state -> refreshSeedControls());

        refreshDetail();
    }

    @Override
    public void doLayout(ScreenRectangle rectangle) {
        lastTabArea = rectangle;
        resizeChildren(rectangle);
        this.layout.arrangeElements();
        FrameLayout.alignInRectangle(this.layout, rectangle, 0.5F, 0.0F);
        // The grid moved the list widget by setting x/y; its rows are positioned separately and
        // only follow via updateSizeAndPosition.
        list.updateSizeAndPosition(list.getWidth(), list.getHeight(), list.getX(), list.getY());
    }

    /**
     * Derives every child's size from the tab area. Kept in one place (rather than spread over the
     * constructor) so the collapse order at small sizes is explicit: the preview goes first, then
     * the description shrinks row by row; the list and the buttons always survive.
     */
    private void resizeChildren(ScreenRectangle rectangle) {
        Font font = Minecraft.getInstance().font;

        int contentWidth = Math.min(Math.max(rectangle.width() - MARGIN * 2, MIN_LIST_WIDTH + MIN_DETAIL_WIDTH + COLUMN_SPACING),
                MAX_CONTENT_WIDTH);
        int contentHeight = Math.max(0, rectangle.height() - MARGIN * 2);

        int listWidth = Mth.clamp(contentWidth * 2 / 5, MIN_LIST_WIDTH, MAX_LIST_WIDTH);
        int detailWidth = Math.max(MIN_DETAIL_WIDTH, contentWidth - listWidth - COLUMN_SPACING);

        list.setSize(listWidth, contentHeight);

        nameLabel.setWidth(detailWidth);
        nameLabel.setHeight(font.lineHeight);

        rerollButton.setWidth((detailWidth - ROW_SPACING) / 2);
        rerollButton.setHeight(BUTTON_HEIGHT);
        customizeButton.setWidth(detailWidth - ROW_SPACING - rerollButton.getWidth());
        customizeButton.setHeight(BUTTON_HEIGHT);

        // Everything the description and the preview have to share, after the fixed-height blocks
        // (the name row, the button row, and the worldStyle selector when present) and the gaps
        // between the stacked blocks.
        int reserved = font.lineHeight + BUTTON_HEIGHT + ROW_SPACING * 3;
        if (worldStyleButton != null) {
            worldStyleButton.setWidth(detailWidth);
            worldStyleButton.setHeight(BUTTON_HEIGHT);
            reserved += BUTTON_HEIGHT + ROW_SPACING;
        }
        int flexible = Math.max(0, contentHeight - reserved);

        int infoRows = Mth.clamp(flexible / 2 / font.lineHeight, 1, MAX_INFO_ROWS);
        infoText.setMaxWidth(detailWidth);
        infoText.setMaxRows(infoRows);

        // Aspect-fit the 62x58 map (plus the legend strip below it) into the detail column, capped so
        // it stays a compact map at the top of the panel rather than stretching to fill every pixel
        // the description leaves free. Without this the widget took the whole flexible height and the
        // render pass smeared the source into a tall column (BUG 1).
        int previewSpace = flexible - infoText.getHeight();
        int availWidth = Math.min(detailWidth, MAX_PREVIEW_WIDTH);
        int availMapHeight = previewSpace - CityPreview.LEGEND_HEIGHT;
        int[] map = CityPreview.fitPreview(availWidth, availMapHeight, CityPreview.WIDTH, CityPreview.HEIGHT);
        int mapWidth = map[0];
        int mapHeight = map[1];
        if (mapHeight < MIN_PREVIEW_HEIGHT) {
            previewWidget.visible = false;
            previewWidget.setSize(0, 0);
        } else {
            previewWidget.visible = true;
            previewWidget.setSize(mapWidth, mapHeight + CityPreview.LEGEND_HEIGHT);
        }
    }

    /**
     * Opens the Phase 2 {@link CustomizeScreen} on the preset the tab is showing. That screen edits a
     * private copy and only touches {@link PresetSelection} on Done, so there is no cross-editor state
     * to hand across any more (unlike the old world-creation config screen this replaced). The reopen flag
     * is set here so returning from the editor - a {@code setScreen(screen)} that re-runs
     * {@code CreateWorldScreen.init()} - lands the player back on this tab.
     */
    private void openCustomizeEditor() {
        requestReopenOnCitiesTab();
        PresetSelection.Entry entry = PresetSelection.CLIENT.selected();
        Preset preset = entry.preset();
        if (preset == null) {
            // The "disabled" entry has no preset to customize; its button is inactive anyway.
            forgetReopenOnCitiesTab();
            return;
        }
        Minecraft.getInstance().gui.setScreen(new CustomizeScreen(screen, preset));
    }

    /** Repopulates the detail panel from whatever {@link PresetSelection#CLIENT} currently holds. */
    private void refreshDetail() {
        PresetSelection.Entry entry = PresetSelection.CLIENT.selected();
        nameLabel.setMessage(entry.name().copy().withStyle(ChatFormatting.BOLD));
        infoText.setMessage(describe(entry));
        customizeButton.active = !PresetSelection.DISABLED_ID.equals(entry.id());
        // A preset change may have carried over the chosen style (still valid) or reset it to the new
        // preset's own; either way the selector's label must show what actually generates. The disabled
        // row has no style ("" is never a choice), so its button simply keeps its last label.
        if (worldStyleButton != null) {
            WorldStyleMix effective = PresetSelection.CLIENT.effectiveWorldStyles();
            worldStyleButton.setMessage(worldStyleLabel(effective));
            worldStyleButton.setTooltip(worldStyleTooltip(effective));
        }
        refreshSeedControls();
        if (lastTabArea != null) {
            // The new description is a different number of lines than the old one, so the preview
            // below it has to move (and resize) with it - otherwise a long blurb draws over the
            // preview and a short one leaves a hole.
            doLayout(lastTabArea);
        }
    }

    /**
     * Opens the {@link WorldStyleDialog} dropdown over the create-world screen. Like the customize
     * editor, it hands the player off to a modal {@code Screen}; returning from it re-runs
     * {@code CreateWorldScreen.init()}, so the reopen flag brings the player back to this tab (rather
     * than the Game tab {@code init()}'s tail would otherwise select). The tab is rebuilt on that
     * return, and its constructor reads {@code effectiveWorldStyle()} for the fresh button label, so
     * a pick shows up whether or not the in-flight relabel below survives the rebuild.
     */
    private void openWorldStyleDropdown() {
        requestReopenOnCitiesTab();
        List<String> choices = PresetSelection.CLIENT.styleChoices();
        Minecraft.getInstance().gui.setScreen(new WorldStyleDialog(screen, choices, worldStyleNames,
                PresetSelection.CLIENT.effectiveWorldStyles(),
                Config.EXPERIMENTAL_MULTI_WORLD_STYLES.get(),
                this::onWorldStylesChanged));
    }

    /**
     * Applies a worldStyle the player picked from the dropdown: records it and republishes so the
     * server sees the switched style - its own {@code Config.worldStyleFromClient} value, orthogonal
     * to whatever preset is selected (spec 1a). The preview reads {@code effectiveWorldStyle()} on its
     * render pass, so it follows on its own; the selector's own size is unchanged, so no relayout is
     * needed.
     */
    private void onWorldStylesChanged(WorldStyleMix styles) {
        PresetSelection.CLIENT.setWorldStyles(styles);
        PresetSelection.CLIENT.publish();
        if (worldStyleButton != null) {
            worldStyleButton.setMessage(worldStyleLabel(styles));
            worldStyleButton.setTooltip(worldStyleTooltip(styles));
        }
    }

    /**
     * The selector's label: the lang-keyed "World Style" prefix followed by the effective style's
     * display name (its id when the datapack declares no {@code name}) - or, for a mix, how many
     * styles are in it. The per-style shares go in the tooltip rather than onto a button that still
     * has to fit at GUI scale 4.
     */
    private Component worldStyleLabel(WorldStyleMix styles) {
        Component value = styles.isSingle()
                ? Component.literal(displayName(styles.primary()))
                : Component.translatable("urbex.tab.worldstyle.mixed", styles.entries().size());
        return Component.translatable("urbex.tab.worldstyle").append(": ").append(value);
    }

    /** Each style and its normalized share, for a mix; nothing to add for a single style. */
    @Nullable
    private Tooltip worldStyleTooltip(WorldStyleMix styles) {
        if (styles.isSingle()) {
            return null;
        }
        float total = 0;
        for (WorldStyleMix.Entry entry : styles.entries()) {
            total += entry.weight();
        }
        MutableComponent lines = Component.empty();
        boolean first = true;
        for (WorldStyleMix.Entry entry : styles.entries()) {
            if (!first) {
                lines.append(CommonComponents.NEW_LINE);
            }
            first = false;
            lines.append(Component.literal(displayName(entry.style())
                    + "  " + Math.round(entry.weight() / total * 100f) + "%"));
        }
        return Tooltip.create(lines);
    }

    /** A style's label, falling back to its fully-qualified id when its pack declares no name. */
    private String displayName(Identifier style) {
        String id = style.toString();
        return worldStyleNames.getOrDefault(id, id);
    }

    /**
     * The registered worldStyles, read from the datapack registry loaded for the world being
     * created: fully-qualified id -> the label to show for it, ordered {@code standard} first then
     * alphabetical <em>by id</em> (not by label - the order must not shuffle when a pack renames
     * itself). Empty when the registry isn't reachable yet - the selector then just stays hidden.
     * <p>
     * Ids stay the currency everywhere else: {@link PresetSelection} stores and publishes them, and
     * this map only decides what the player reads. See {@link #worldStyleDisplayName}.
     */
    private static Map<String, String> registeredWorldStyles(CreateWorldScreen screen) {
        RegistryAccess access = screen.getUiState().getSettings().worldgenLoadContext();
        Optional<Registry<WorldStyleRE>> registry = access.lookup(CustomRegistries.WORLDSTYLES_REGISTRY_KEY);
        if (registry.isEmpty()) {
            return Map.of();
        }
        List<Identifier> keys = new ArrayList<>(registry.get().keySet());
        keys.sort(Comparator.comparingInt((Identifier k) -> STANDARD_STYLE.equals(k.toString()) ? 0 : 1)
                .thenComparing(Comparator.comparing(Identifier::toString)));
        Map<String, String> names = new LinkedHashMap<>();
        for (Identifier key : keys) {
            names.put(key.toString(), worldStyleDisplayName(registry.get(), key));
        }
        return names;
    }

    /**
     * The label for one worldStyle: its {@code name}, folded over the {@code extends} chain by the
     * same {@link WorldStyle#displayNameOf} worldgen uses, falling back to the fully-qualified id.
     * <p>
     * A chain this screen cannot walk - a dangling {@code extends}, or a cycle - falls back to the
     * id rather than propagating: the world will refuse to load with a message that names the real
     * cause, and a dropdown that throws out of its own constructor would take the create-world
     * screen down before the player ever got that message.
     */
    private static String worldStyleDisplayName(Registry<WorldStyleRE> registry, Identifier id) {
        try {
            return WorldStyle.displayNameOf(
                    ExtendsChain.resolve(id, registry::getValue, WorldStyleRE::getExtends), id);
        } catch (RuntimeException e) {
            return id.toString();
        }
    }

    /**
     * The browsable presets registered for the world being created, resolved (extends chains flattened)
     * against the same load-context {@code RegistryAccess} {@link #registeredWorldStyles} reads -
     * {@code urbex:default} first, then in path-then-namespace order (per {@code Presets.listBrowsable};
     * that is {@code Identifier}'s own order, not alphabetical on the whole id). Empty when
     * the registry isn't reachable yet, exactly like {@link #registeredWorldStyles}.
     * <p>
     * Deliberately goes through the pure {@link Presets#resolve(Identifier, java.util.function.Function)}
     * core with a lookup bound to this call's own {@code RegistryAccess}, not the caching
     * {@code Presets.resolve(RegistryAccess, Identifier)} wrapper worldgen uses: that cache is keyed by
     * id alone and only cleared by {@code AssetRegistries.reset()} from {@code CityFeature.cleanUp()},
     * so after playing a world (or toggling datapacks in this very screen) it can hold a stale
     * resolution for an id a different registry context now defines differently. The GUI has to see
     * exactly what {@code access} says right now, not whatever the last world resolved.
     */
    private static List<PresetSelection.Entry> registeredPresets(CreateWorldScreen screen) {
        RegistryAccess access = screen.getUiState().getSettings().worldgenLoadContext();
        Optional<Registry<PresetRE>> registry = access.lookup(CustomRegistries.PRESET_REGISTRY_KEY);
        if (registry.isEmpty()) {
            return List.of();
        }
        List<PresetSelection.Entry> entries = new ArrayList<>();
        for (Identifier id : Presets.listBrowsable(access)) {
            Preset preset = Presets.resolve(id, registry.get()::getValue);
            // The preset's authored name, falling back to the id for a datapack that declares none -
            // which is what every row read as before the field existed.
            entries.add(new PresetSelection.Entry(id, Component.literal(preset.getDisplayName()), preset));
        }
        return entries;
    }

    private void refreshSeedControls() {
        boolean seedTyped = !screen.getUiState().getSeed().isBlank();
        rerollButton.active = !seedTyped;
        rerollButton.setTooltip(seedTyped ? Tooltip.create(Component.translatable("urbex.preview.seed_locked")) : null);
    }

    /**
     * The same three-part blurb the old editor's {@code getProfileInfo} tooltip showed - plain
     * description, aqua "extra" line, red warning - minus the parts a profile leaves empty.
     */
    static Component describe(PresetSelection.Entry entry) {
        Preset preset = entry.preset();
        if (preset == null) {
            return Component.translatable("urbex.preset.disabled.info");
        }
        MutableComponent result = Component.literal(preset.getDescription());
        if (!preset.getExtraDescription().isEmpty()) {
            result.append(CommonComponents.NEW_LINE)
                    .append(Component.literal(preset.getExtraDescription()).withStyle(ChatFormatting.AQUA));
        }
        if (!preset.getWarning().isEmpty()) {
            result.append(CommonComponents.NEW_LINE)
                    .append(Component.literal(preset.getWarning()).withStyle(ChatFormatting.RED));
        }
        return result;
    }

    /**
     * The registries the preview should sample biomes from: the ones loaded for the world being
     * created, so datapack biomes and world-type choices are reflected. Degrades to {@code null}
     * (the pre-preview "registry-gated rules skipped" behaviour) rather than letting
     * {@code NullDimensionInfo}'s {@code lookupOrThrow(BIOME)} take the screen down if a
     * half-loaded context ever lacks the biome registry.
     */
    @Nullable
    private static RegistryAccess previewRegistries(CreateWorldScreen screen) {
        RegistryAccess access = screen.getUiState().getSettings().worldgenLoadContext();
        return access.lookup(Registries.BIOME).isPresent() ? access : null;
    }

    private static CityPreview newPreview(@Nullable RegistryAccess registryAccess) {
        closeActivePreview();
        activePreview = new CityPreview(registryAccess);
        return activePreview;
    }

    /**
     * Releases the live preview's GPU texture. Must be called when the {@code CreateWorldScreen}
     * goes away (see {@code ClientEventHandlers}): otherwise the texture - and, through
     * {@code CityPreview}'s registry access, the whole frozen {@code RegistryAccess} of a world
     * that was never created - would stay pinned until the player opened the screen again.
     */
    public static void closeActivePreview() {
        if (activePreview != null) {
            activePreview.close();
            activePreview = null;
        }
    }

    /** Asks for the next {@code CreateWorldScreen.init()} to land on this tab. */
    static void requestReopenOnCitiesTab() {
        reopenOnCitiesTab = true;
    }

    /** One-shot: true iff the screen is being re-initialised on the way back from the old editor. */
    public static boolean consumeReopenOnCitiesTab() {
        boolean requested = reopenOnCitiesTab;
        reopenOnCitiesTab = false;
        return requested;
    }

    /** Drops a pending {@link #reopenOnCitiesTab} that no longer belongs to this screen. */
    public static void forgetReopenOnCitiesTab() {
        reopenOnCitiesTab = false;
    }

    /**
     * Thin {@link AbstractWidget} shell so the {@link CityPreview} can live in the tab's layout and
     * be added/removed with the rest of the tab's widgets. Non-interactive: it takes no focus and
     * swallows no clicks.
     */
    private class PreviewWidget extends AbstractWidget {

        PreviewWidget() {
            super(0, 0, 0, 0, CommonComponents.EMPTY);
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            Preset preset = PresetSelection.CLIENT.selected().preset();
            // worldStyles is orthogonal to the preset (spec 1a): the chosen override if any, else the
            // default. Irrelevant for the disabled row - update() below no-ops on a null preset.
            WorldStyleMix worldStyle = PresetSelection.CLIENT.effectiveWorldStyles();
            long seed = CityPreview.seedFromUi(screen.getUiState().getSeed(), previewSeedFallback);
            // A no-op unless (preset, worldstyle, seed) actually changed, so driving it from the
            // render pass is what makes the preview follow selection changes, seed edits and tab
            // switches without any of them needing to know about the preview. The Cities tab always
            // shows the region map; the per-category city/transport views live in the editor.
            preview.update(preset, worldStyle, seed, CityPreview.Mode.MAP);

            // resizeChildren() already aspect-fit this widget to (mapWidth, mapHeight + legend) via
            // CityPreview.fitPreview, so the map keeps its 62:58 ratio - pass the widget's own size
            // straight through. CityPreview.render draws the map into (width, height - legend) and the
            // legend strip underneath.
            preview.render(graphics, getX(), getY(), getWidth(), getHeight());
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return false;
        }
    }
}
