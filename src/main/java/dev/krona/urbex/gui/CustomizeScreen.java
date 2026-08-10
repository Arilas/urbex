package dev.krona.urbex.gui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.gui.preview.CityPreview;
import dev.krona.urbex.gui.settings.SettingCategory;
import dev.krona.urbex.gui.settings.SettingControls;
import dev.krona.urbex.gui.settings.SettingDescriptor;
import dev.krona.urbex.gui.settings.Settings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * The Phase 2 "Customize this preset…" editor: the metadata-driven successor to
 * {@code UrbexConfigScreen}. It works entirely on a private {@link UrbexProfile} copy of the preset it
 * was opened on, so nothing global is touched until the player presses Done - which is exactly what
 * makes Cancel/ESC a clean discard (issue #65). Layout is a category list on the left, a scrollable
 * column of setting controls in the middle (with a search box on top that filters across categories),
 * and a live {@link CityPreview} on the right; the bottom bar carries Save-as / Reset / Done / Cancel.
 * <p>
 * Nothing is placed at fixed screen pixels: {@link #layoutWidgets()} derives every rectangle from the
 * screen size, dropping the preview column when it would squeeze the controls too thin, so the editor
 * stays usable down to GUI scale 4.
 */
public class CustomizeScreen extends Screen {

    private static final int MARGIN = 8;
    private static final int GAP = 6;
    private static final int ROW_HEIGHT = 20;
    private static final int TITLE_HEIGHT = 12;

    private static final int MIN_CATEGORY_WIDTH = 84;
    private static final int MAX_CATEGORY_WIDTH = 150;
    private static final int MIN_SETTINGS_WIDTH = 150;
    private static final int MIN_PREVIEW_WIDTH = 110;
    private static final int MAX_PREVIEW_WIDTH = 210;
    /** Below this the preview would be a smear rather than a map - give the width back to the controls. */
    private static final int MIN_PREVIEW_HEIGHT = 60;
    private static final int SETTINGS_ROW_HEIGHT = 26;
    private static final int CATEGORY_ROW_HEIGHT = 18;

    private static final long PREVIEW_DEBOUNCE_MS = 150;

    private final Screen parent;
    @Nullable
    private final CreateWorldScreen createWorldScreen;
    private final String baseName;
    private final UrbexProfile base;
    private final UrbexProfile copy;
    private CityPreview preview;
    /** True once {@link #removed()} has closed the preview, so the next {@link #init()} rebuilds it. */
    private boolean previewClosed;
    private final RandomSource random = RandomSource.create();

    private SettingCategory selectedCategory = SettingCategory.GENERAL;
    private String searchText = "";
    private boolean dirty;

    /** Preview seed while the world seed field is empty; the reroll button rolls a fresh one. */
    private long previewSeedFallback;
    private boolean previewDirty;
    private long nextPreviewUpdateAt;

    private EditBox searchBox;
    private CategoryList categoryList;
    private SettingsList settingsList;
    private PreviewWidget previewWidget;
    private Button rerollButton;
    /** The four bottom-bar buttons in display order, repopulated each {@link #init()}. */
    private final List<AbstractWidget> bottomBar = new ArrayList<>();
    /** Suppresses the search box responder while the code (not the player) clears it on a category switch. */
    private boolean suppressSearchResponder;

    public CustomizeScreen(Screen parent, UrbexProfile base, String baseName) {
        super(Component.translatable("urbex.screen.customize.title", baseName));
        this.parent = parent;
        this.createWorldScreen = parent instanceof CreateWorldScreen cws ? cws : null;
        this.base = base;
        this.baseName = baseName == null ? "" : baseName;
        this.copy = new UrbexProfile(this.baseName + "-copy", false);
        this.copy.copyFrom(base);
        this.preview = new CityPreview(previewRegistries(createWorldScreen));
        this.previewSeedFallback = random.nextLong();
    }

    @Override
    protected void init() {
        Font font = this.font;

        // removed() closes the preview when this screen is replaced (including by the Save-as dialog);
        // re-showing the same instance afterwards has to rebuild it, since a closed CityPreview keeps
        // its cache key and would otherwise never recompute the now-null texture.
        if (previewClosed) {
            preview = new CityPreview(previewRegistries(createWorldScreen));
            previewClosed = false;
        }

        searchBox = new EditBox(font, 0, 0, 0, ROW_HEIGHT, Component.translatable("urbex.screen.customize.search"));
        searchBox.setHint(Component.translatable("urbex.screen.customize.search"));
        // Set the value before wiring the responder: doing it after would fire rebuildControls() while
        // settingsList is still null.
        searchBox.setValue(searchText);
        searchBox.setResponder(text -> {
            if (suppressSearchResponder) {
                return;
            }
            searchText = text;
            rebuildControls();
        });
        addRenderableWidget(searchBox);

        categoryList = new CategoryList();
        addRenderableWidget(categoryList);

        settingsList = new SettingsList();
        addRenderableWidget(settingsList);
        rebuildControls();

        previewWidget = new PreviewWidget();
        addRenderableWidget(previewWidget);

        rerollButton = Button.builder(Component.translatable("urbex.preview.reroll"), b -> {
            previewSeedFallback = random.nextLong();
            schedulePreview();
        }).build();
        addRenderableWidget(rerollButton);

        bottomBar.clear();
        bottomBar.add(addRenderableWidget(Button.builder(Component.translatable("urbex.screen.customize.save_as"), b -> openSaveAs()).build()));
        bottomBar.add(addRenderableWidget(Button.builder(Component.translatable("urbex.screen.customize.reset"), b -> reset()).build()));
        bottomBar.add(addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> done()).build()));
        bottomBar.add(addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> cancel()).build()));

        layoutWidgets();
        refreshRerollState();
        // Compute the first frame's map straightaway rather than leaving a debounce-length blank.
        updatePreview();
    }

    /**
     * Derives every widget rectangle from the current screen size. The bottom button bar and the two
     * lists always survive; the preview column is the first thing dropped when the middle would other-
     * wise fall below {@link #MIN_SETTINGS_WIDTH}.
     */
    private void layoutWidgets() {
        int innerWidth = this.width - MARGIN * 2;
        int searchY = MARGIN + TITLE_HEIGHT + GAP;
        int contentTop = searchY + ROW_HEIGHT + GAP;
        int bottomBarY = this.height - MARGIN - ROW_HEIGHT;
        int contentHeight = Math.max(ROW_HEIGHT, bottomBarY - GAP - contentTop);

        int categoryWidth = Mth.clamp(innerWidth * 22 / 100, MIN_CATEGORY_WIDTH, MAX_CATEGORY_WIDTH);

        int previewWidth = Mth.clamp(innerWidth * 30 / 100, MIN_PREVIEW_WIDTH, MAX_PREVIEW_WIDTH);
        int previewHeight = Math.min(previewWidth, contentHeight - ROW_HEIGHT - GAP);
        int settingsWidthWithPreview = innerWidth - categoryWidth - GAP - previewWidth - GAP;
        boolean showPreview = settingsWidthWithPreview >= MIN_SETTINGS_WIDTH && previewHeight >= MIN_PREVIEW_HEIGHT;

        int settingsWidth = showPreview ? settingsWidthWithPreview : innerWidth - categoryWidth - GAP;

        int categoryX = MARGIN;
        int settingsX = categoryX + categoryWidth + GAP;

        // The search box spans the category+settings columns so it reads as "search everything", not
        // just the current column.
        searchBox.setPosition(categoryX, searchY);
        searchBox.setWidth(categoryWidth + GAP + settingsWidth);
        searchBox.setHeight(ROW_HEIGHT);

        categoryList.updateSizeAndPosition(categoryWidth, contentHeight, categoryX, contentTop);
        settingsList.updateSizeAndPosition(settingsWidth, contentHeight, settingsX, contentTop);

        if (showPreview) {
            int previewX = settingsX + settingsWidth + GAP;
            previewWidget.visible = true;
            previewWidget.setPosition(previewX, contentTop);
            previewWidget.setSize(previewWidth, previewHeight);
            rerollButton.visible = true;
            rerollButton.setPosition(previewX, contentTop + previewHeight + GAP);
            rerollButton.setSize(previewWidth, ROW_HEIGHT);
        } else {
            previewWidget.visible = false;
            previewWidget.setSize(0, 0);
            rerollButton.visible = false;
        }

        layoutBottomBar(innerWidth, bottomBarY);
    }

    private void layoutBottomBar(int innerWidth, int y) {
        int count = bottomBar.size();
        if (count == 0) {
            return;
        }
        int buttonWidth = (innerWidth - GAP * (count - 1)) / count;
        int x = MARGIN;
        for (int i = 0; i < count; i++) {
            AbstractWidget button = bottomBar.get(i);
            // The last button absorbs the integer-division remainder so the bar reaches the right margin.
            int w = i == count - 1 ? MARGIN + innerWidth - x : buttonWidth;
            button.setPosition(x, y);
            button.setSize(w, ROW_HEIGHT);
            x += buttonWidth + GAP;
        }
    }

    private void rebuildControls() {
        settingsList.rebuild(currentDescriptors());
    }

    /**
     * The descriptors to show right now: a blank search shows the selected category's set (for GENERAL
     * that is the curated general duplicates); a non-blank search shows {@link Settings#search} hits
     * de-duplicated on key, preferring the curated general duplicate over the home descriptor. That
     * preference matters only for the handful of keys that have both: the general copy carries the
     * friendlier control (e.g. the log-scale chance sliders with their usable 0.0001↔… range), and
     * both write the same field, so search surfaces the nicer knob without changing behaviour.
     */
    private List<SettingDescriptor> currentDescriptors() {
        String query = searchText.strip();
        if (query.isEmpty()) {
            return Settings.byCategory(selectedCategory);
        }
        LinkedHashMap<String, SettingDescriptor> byKey = new LinkedHashMap<>();
        for (SettingDescriptor d : Settings.search(query)) {
            byKey.merge(d.key(), d, (existing, candidate) -> candidate.general() ? candidate : existing);
        }
        return new ArrayList<>(byKey.values());
    }

    private void onSettingChanged() {
        if (!dirty) {
            dirty = true;
        }
        schedulePreview();
    }

    private void schedulePreview() {
        previewDirty = true;
        nextPreviewUpdateAt = System.currentTimeMillis() + PREVIEW_DEBOUNCE_MS;
    }

    @Override
    public void tick() {
        super.tick();
        if (previewDirty && System.currentTimeMillis() >= nextPreviewUpdateAt) {
            updatePreview();
        }
    }

    private void updatePreview() {
        previewDirty = false;
        preview.update(copy, copy.getWorldStyle(), currentSeed());
    }

    private long currentSeed() {
        String seedField = createWorldScreen == null ? "" : createWorldScreen.getUiState().getSeed();
        return CityPreview.seedFromUi(seedField, previewSeedFallback);
    }

    private void refreshRerollState() {
        boolean seedTyped = createWorldScreen != null && !createWorldScreen.getUiState().getSeed().isBlank();
        rerollButton.active = !seedTyped;
        rerollButton.setTooltip(seedTyped
                ? Tooltip.create(Component.translatable("urbex.preview.seed_locked"))
                : null);
    }

    // ---- flows --------------------------------------------------------------

    private void done() {
        PresetSelection.CLIENT.applyCustomized(copy, baseName);
        PresetSelection.CLIENT.publish();
        returnToTab();
    }

    private void cancel() {
        // No publish and nothing global was touched: the copy is simply dropped.
        returnToTab();
    }

    private void reset() {
        copy.copyFrom(base);
        dirty = false;
        rebuildControls();
        schedulePreview();
    }

    @Override
    public void onClose() {
        cancel();
    }

    private void returnToTab() {
        CitiesTab.requestReopenOnCitiesTab();
        // Release the preview's texture (and the frozen RegistryAccess it pins) on the way out for
        // good. Note the Save-as dialog trip goes through removed() instead, which rebuilds on return.
        preview.close();
        previewClosed = true;
        this.minecraft.gui.setScreen(parent);
    }

    private void openSaveAs() {
        Set<String> taken = new HashSet<>(ProfileSetup.STANDARD_PROFILES.keySet());
        SaveAsDialog[] holder = new SaveAsDialog[1];
        holder[0] = new SaveAsDialog(this, taken, name -> {
            if (!performSave(name)) {
                holder[0].showIoError();
            }
        });
        this.minecraft.gui.setScreen(holder[0]);
    }

    /**
     * Writes the copy to {@code config/urbex/profiles/<name>.json} (with a {@code basedOn} member),
     * registers it as a non-public standard profile, selects it and returns to the tab. Returns
     * {@code false} - leaving the dialog open to show the IO error - if the file could not be written.
     */
    private boolean performSave(String name) {
        UrbexProfile saved = new UrbexProfile(name, false);
        saved.copyFrom(copy);
        JsonObject json = saved.toJson(false);
        json.addProperty("basedOn", baseName);

        Path dir = FabricLoader.getInstance().getConfigDir().resolve("urbex").resolve("profiles");
        try {
            Files.createDirectories(dir);
            String pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json);
            Files.writeString(dir.resolve(name + ".json"), pretty);
        } catch (IOException e) {
            Urbex.getLogger().error("Couldn't save custom profile '{}'", name, e);
            return false;
        }

        // Register as a first-class user profile so it shows same-session (and, via the file above,
        // after a restart) as its own selectable row with "based on" provenance.
        ProfileSetup.STANDARD_PROFILES.put(name, saved);
        ProfileSetup.USER_PROFILES.add(name);
        ProfileSetup.PROFILE_BASED_ON.put(name, baseName);
        PresetSelection.CLIENT.select(name);
        PresetSelection.CLIENT.publish();
        returnToTab();
        return true;
    }

    // ---- rendering ----------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        MutableComponent shown = Component.translatable("urbex.screen.customize.title", baseName);
        if (dirty) {
            shown.append(Component.literal(" *"));
        }
        int titleX = (this.width - font.width(shown)) / 2;
        graphics.text(font, shown, titleX, MARGIN, 0xffffffff);
    }

    @Override
    public void removed() {
        // Called whenever this screen is superseded - by the parent tab on Done/Cancel (already closed
        // in returnToTab), or by the Save-as dialog mid-session. Closing here bounds the live texture
        // to one at a time across a dialog trip; init() rebuilds it if the same instance is re-shown.
        preview.close();
        previewClosed = true;
    }

    // ---- registry access for the preview ------------------------------------

    @Nullable
    private static RegistryAccess previewRegistries(@Nullable CreateWorldScreen screen) {
        if (screen == null) {
            return null;
        }
        RegistryAccess access = screen.getUiState().getSettings().worldgenLoadContext();
        return access.lookup(Registries.BIOME).isPresent() ? access : null;
    }

    // ---- category list ------------------------------------------------------

    private final class CategoryList extends ObjectSelectionList<CategoryList.Row> {

        private boolean restoring;

        private CategoryList() {
            super(Minecraft.getInstance(), MIN_CATEGORY_WIDTH, MIN_CATEGORY_WIDTH, 0, CATEGORY_ROW_HEIGHT);
            this.centerListVertically = false;
            restoring = true;
            try {
                Row toSelect = null;
                for (SettingCategory category : SettingCategory.values()) {
                    Row row = new Row(category);
                    addEntry(row);
                    if (category == selectedCategory) {
                        toSelect = row;
                    }
                }
                if (toSelect != null) {
                    setSelected(toSelect);
                }
            } finally {
                restoring = false;
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(0, getWidth() - scrollbarWidth() - 4);
        }

        @Override
        public void setSelected(@Nullable Row row) {
            Row previous = getSelected();
            super.setSelected(row);
            if (row == null || restoring || row == previous) {
                return;
            }
            selectedCategory = row.category;
            // Browsing a category is a fresh context; clear any lingering search so the column shows
            // the category, not stale cross-category hits.
            suppressSearchResponder = true;
            searchBox.setValue("");
            suppressSearchResponder = false;
            searchText = "";
            rebuildControls();
        }

        private final class Row extends ObjectSelectionList.Entry<Row> {

            private final SettingCategory category;
            private final Component label;

            private Row(SettingCategory category) {
                this.category = category;
                this.label = Component.translatable(category.labelKey());
            }

            @Override
            public Component getNarration() {
                return label;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                Font font = Minecraft.getInstance().font;
                int textY = getContentY() + Math.max(0, (getContentHeight() - font.lineHeight) / 2);
                graphics.text(font, label, getContentX(), textY, 0xffffffff);
            }
        }
    }

    // ---- settings list ------------------------------------------------------

    private final class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {

        private SettingsList() {
            super(Minecraft.getInstance(), MIN_SETTINGS_WIDTH, MIN_SETTINGS_WIDTH, 0, SETTINGS_ROW_HEIGHT);
            this.centerListVertically = false;
        }

        private void rebuild(List<SettingDescriptor> descriptors) {
            clearEntries();
            setScrollAmount(0);
            int controlWidth = getRowWidth();
            for (SettingDescriptor descriptor : descriptors) {
                AbstractWidget control = SettingControls.create(descriptor, copy, CustomizeScreen.this::onSettingChanged, controlWidth);
                addEntry(new Row(control));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(0, getWidth() - scrollbarWidth() - 4);
        }

        private final class Row extends ContainerObjectSelectionList.Entry<Row> {

            private final AbstractWidget control;

            private Row(AbstractWidget control) {
                this.control = control;
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(control);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(control);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                control.setWidth(getContentWidth());
                control.setX(getContentX());
                control.setY(getContentY() + Math.max(0, (getContentHeight() - control.getHeight()) / 2));
                control.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    // ---- preview widget -----------------------------------------------------

    /** Non-interactive shell hosting the {@link CityPreview}; the debounce drives {@code update}, not this. */
    private final class PreviewWidget extends AbstractWidget {

        private PreviewWidget() {
            super(0, 0, 0, 0, CommonComponents.EMPTY);
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            preview.render(graphics, getX(), getY(), Math.min(getWidth(), getHeight()), getHeight());
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return false;
        }
    }
}
