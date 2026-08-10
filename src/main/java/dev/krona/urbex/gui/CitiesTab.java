package dev.krona.urbex.gui;

import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.gui.preview.CityPreview;
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
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;

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

    private final PresetListWidget list;
    private final StringWidget nameLabel;
    private final MultiLineTextWidget infoText;
    private final PreviewWidget previewWidget;
    private final Button rerollButton;
    private final Button customizeButton;

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

        this.layout.columnSpacing(COLUMN_SPACING);
        this.layout.addChild(list, 0, 0);
        LinearLayout detailColumn = this.layout.addChild(LinearLayout.vertical().spacing(ROW_SPACING), 0, 1);
        detailColumn.addChild(nameLabel);
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

        // Everything the description and the preview have to share, after the name row, the button
        // row and the three gaps between the four stacked blocks.
        int flexible = Math.max(0, contentHeight - font.lineHeight - BUTTON_HEIGHT - ROW_SPACING * 3);

        int infoRows = Mth.clamp(flexible / 2 / font.lineHeight, 1, MAX_INFO_ROWS);
        infoText.setMaxWidth(detailWidth);
        infoText.setMaxRows(infoRows);

        int previewHeight = flexible - infoText.getHeight();
        if (previewHeight < MIN_PREVIEW_HEIGHT) {
            previewWidget.visible = false;
            previewWidget.setSize(0, 0);
        } else {
            previewWidget.visible = true;
            previewWidget.setSize(detailWidth, previewHeight);
        }
    }

    /**
     * Opens the pre-redesign editor on the preset the tab is showing. Phase 2 replaces that screen
     * (Task 6) with one that edits {@link PresetSelection} directly; until then the two keep
     * separate state, so the selection has to be handed across explicitly - otherwise
     * "Customize this preset..." would open on whatever the old editor was last left on.
     * {@code UrbexConfigScreen.done()} mirrors the result back, so a round trip stays consistent.
     */
    private void openCustomizeEditor() {
        requestReopenOnCitiesTab();
        PresetSelection.Entry entry = PresetSelection.CLIENT.selected();
        if (!entry.custom() && entry.profile().isPresent()) {
            // Custom entries are deliberately left alone: ClientProfileSetup still holds the edited
            // profile that produced them, and setProfile() cannot hand back one it never made.
            ClientProfileSetup.CLIENT_SETUP.setProfile(entry.id());
        }
        Minecraft.getInstance().gui.setScreen(new UrbexConfigScreen(screen));
    }

    /** Repopulates the detail panel from whatever {@link PresetSelection#CLIENT} currently holds. */
    private void refreshDetail() {
        PresetSelection.Entry entry = PresetSelection.CLIENT.selected();
        nameLabel.setMessage(entry.name().copy().withStyle(ChatFormatting.BOLD));
        infoText.setMessage(describe(entry));
        customizeButton.active = !PresetSelection.DISABLED_ID.equals(entry.id());
        refreshSeedControls();
        if (lastTabArea != null) {
            // The new description is a different number of lines than the old one, so the preview
            // below it has to move (and resize) with it - otherwise a long blurb draws over the
            // preview and a short one leaves a hole.
            doLayout(lastTabArea);
        }
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
        UrbexProfile profile = entry.profile().orElse(null);
        if (profile == null) {
            return Component.translatable("urbex.preset.disabled.info");
        }
        MutableComponent result = Component.literal(profile.getDescription());
        if (!profile.getExtraDescription().isEmpty()) {
            result.append(CommonComponents.NEW_LINE)
                    .append(Component.literal(profile.getExtraDescription()).withStyle(ChatFormatting.AQUA));
        }
        if (!profile.getWarning().isEmpty()) {
            result.append(CommonComponents.NEW_LINE)
                    .append(Component.literal(profile.getWarning()).withStyle(ChatFormatting.RED));
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
            UrbexProfile profile = PresetSelection.CLIENT.selected().profile().orElse(null);
            String worldStyle = profile == null ? "" : profile.getWorldStyle();
            long seed = CityPreview.seedFromUi(screen.getUiState().getSeed(), previewSeedFallback);
            // A no-op unless (profile, worldstyle, seed) actually changed, so driving it from the
            // render pass is what makes the preview follow selection changes, seed edits and tab
            // switches without any of them needing to know about the preview.
            preview.update(profile, worldStyle, seed);

            // CityPreview draws the map into (width, height - legend) and the legend underneath it,
            // so a square-ish map means passing a width close to the total height; the legend then
            // gets the full column width to run along.
            preview.render(graphics, getX(), getY(), Math.min(getWidth(), getHeight()), getHeight());
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
