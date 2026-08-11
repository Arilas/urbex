package dev.krona.urbex.gui;

import dev.krona.urbex.Urbex;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Carries the Urbex preset selection of a world across the vanilla Re-Create flow (issue #85).
 * <p>
 * Vanilla rebuilds the CreateWorldScreen from the source world's level.dat, but the preset choice
 * lives in Urbex's own saved data ({@code data/urbex/data.dat}), which vanilla never consults - so a
 * re-created world silently lost its cities. {@code WorldListEntryMixin} calls {@link #capture} with
 * the source world's folder when Re-Create is clicked; the next CreateWorldScreen consumes the
 * pending restore via {@link #consumePending}.
 */
public final class RecreateProfileRestore {

    /** The preset selection read from a source world: {@link dev.krona.urbex.data.UrbexData}'s three
     *  fields, verbatim. */
    public record Pending(String preset, String worldStyle, String overridesJson) {
    }

    private static Pending pending = null;

    private RecreateProfileRestore() {
    }

    /** Reads the Urbex saved data of the world in {@code levelId} and stashes its preset selection. */
    public static void capture(String levelId) {
        pending = null;
        try {
            Path worldDir = Minecraft.getInstance().getLevelSource().getLevelPath(levelId);
            Path dataFile = worldDir.resolve("data").resolve("urbex").resolve("data.dat");
            if (!Files.exists(dataFile)) {
                // Pre-namespaced layout, in case the world was created before the id change
                dataFile = worldDir.resolve("data").resolve("urbex_data.dat");
            }
            if (!Files.exists(dataFile)) {
                return;
            }
            CompoundTag root = NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap());
            pending = parse(root).orElse(null);
        } catch (Exception e) {
            Urbex.getLogger().warn("Could not read the Urbex preset selection of world '{}' for re-creation", levelId, e);
        }
    }

    /** Extracts the preset selection from a saved-data NBT root. Empty if none was stored. */
    public static Optional<Pending> parse(CompoundTag root) {
        CompoundTag data = root.getCompoundOrEmpty("data");
        String preset = data.getStringOr("preset", "");
        if (preset.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Pending(preset, data.getStringOr("worldStyle", ""), data.getStringOr("overrides", "")));
    }

    /** Applies and clears the stashed preset selection; called when a fresh CreateWorldScreen opens. */
    public static void consumePending() {
        Pending p = pending;
        pending = null;
        if (p != null) {
            PresetSelection.CLIENT.restore(p.preset(), p.worldStyle(), p.overridesJson());
        }
    }
}
