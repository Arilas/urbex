package dev.krona.urbex.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The modal "Save preset as…" dialog raised from {@link CustomizeScreen}: a single name
 * {@link EditBox} plus confirm/cancel. Confirming runs {@link #validateName} against the taken names;
 * a failure is shown inline (the dialog stays open), a success hands the accepted name to the caller.
 * <p>
 * {@link #validateName} is deliberately a pure, static function of {@code (name, taken)} with no
 * widget or game state, so it is unit-tested headless ({@code SaveAsValidationTest}); everything else
 * here is GL widget code exercised only manually.
 */
public class SaveAsDialog extends Screen {

    /** Profile file/registry ids are {@code [a-z0-9_]+}: the same charset {@code ProfileSetup} keys on. */
    private static final String NAME_PATTERN = "[a-z0-9_]+";
    private static final int NAME_MAX_LENGTH = 64;
    private static final int CONTENT_WIDTH = 220;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_SPACING = 6;

    private final Screen parent;
    private final Set<String> taken;
    private final Consumer<String> onAccept;

    private EditBox nameBox;
    /** The current inline error, or {@code null} while the field is (so far) acceptable. */
    @Nullable
    private Component error;

    public SaveAsDialog(Screen parent, Set<String> taken, Consumer<String> onAccept) {
        super(Component.translatable("urbex.saveas.title"));
        this.parent = parent;
        this.taken = taken;
        this.onAccept = onAccept;
    }

    /**
     * Validates a proposed preset name against the names already in use.
     *
     * <p>Rejections, in order: {@code null}/empty/blank ({@code urbex.saveas.err.empty}); a name that
     * isn't {@code [a-z0-9_]+} once lowercased ({@code urbex.saveas.err.invalid}); a name whose
     * lowercased form is already taken ({@code urbex.saveas.err.taken}). An accepted name returns an
     * empty {@link Optional}. The compare is on the lowercased candidate, so {@code "Cavern"} collides
     * with the built-in {@code cavern}, and {@code "MyWasteland"} is accepted (it lowercases cleanly).</p>
     */
    public static Optional<Component> validateName(@Nullable String name, Set<String> taken) {
        if (name == null || name.strip().isEmpty()) {
            return Optional.of(Component.translatable("urbex.saveas.err.empty"));
        }
        String lower = name.strip().toLowerCase(Locale.ROOT);
        if (!lower.matches(NAME_PATTERN)) {
            return Optional.of(Component.translatable("urbex.saveas.err.invalid"));
        }
        if (taken.contains(lower)) {
            return Optional.of(Component.translatable("urbex.saveas.err.taken"));
        }
        return Optional.empty();
    }

    @Override
    protected void init() {
        GridLayout grid = new GridLayout();
        grid.defaultCellSetting().paddingBottom(ROW_SPACING);

        Font font = this.font;
        nameBox = new EditBox(font, CONTENT_WIDTH, ROW_HEIGHT, Component.translatable("urbex.saveas.hint"));
        nameBox.setMaxLength(NAME_MAX_LENGTH);
        nameBox.setHint(Component.translatable("urbex.saveas.hint"));
        // Re-validating on every keystroke clears a stale error the moment the name becomes valid,
        // rather than leaving it up until the next confirm attempt.
        nameBox.setResponder(text -> error = validateName(text, taken).orElse(null));
        grid.addChild(nameBox, 0, 0, 1, 2);

        Button confirm = Button.builder(CommonComponents.GUI_DONE, b -> tryAccept())
                .width((CONTENT_WIDTH - ROW_SPACING) / 2).build();
        Button cancel = Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .width(CONTENT_WIDTH - ROW_SPACING - confirm.getWidth()).build();
        grid.addChild(confirm, 1, 0);
        grid.addChild(cancel, 1, 1);

        grid.arrangeElements();
        int x = (this.width - CONTENT_WIDTH) / 2;
        int y = this.height / 2 - grid.getHeight() / 2;
        grid.setX(x);
        grid.setY(y);
        grid.visitWidgets(this::addRenderableWidget);

        setInitialFocus(nameBox);
    }

    private void tryAccept() {
        Optional<Component> result = validateName(nameBox.getValue(), taken);
        if (result.isPresent()) {
            error = result.get();
            return;
        }
        onAccept.accept(nameBox.getValue().strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Surfaces an IO failure the save path hit after this dialog handed off an accepted name. The
     * caller keeps the dialog open and calls this so the player sees why nothing was saved.
     */
    public void showIoError() {
        this.error = Component.translatable("urbex.saveas.err.io");
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // GLFW_KEY_ENTER (257) / GLFW_KEY_KP_ENTER (335): confirm from the keyboard while the name box
        // has focus, matching how vanilla single-field dialogs behave.
        if ((event.key() == 257 || event.key() == 335) && nameBox.isFocused()) {
            tryAccept();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleX = (this.width - font.width(title)) / 2;
        graphics.text(font, title, titleX, this.height / 2 - font.lineHeight - 30, 0xffffffff);
        if (error != null) {
            int errX = (this.width - font.width(error)) / 2;
            graphics.text(font, error, errX, this.height / 2 + 26, 0xffff5555);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
