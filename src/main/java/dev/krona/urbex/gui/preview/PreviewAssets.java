package dev.krona.urbex.gui.preview;

import dev.krona.urbex.worldgen.lost.cityassets.AssetCompiler;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.core.RegistryAccess;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;

/**
 * The compiled assets the world-creation preview plans against, built once per set of registries.
 *
 * <p>A snapshot is a pure function of the {@link RegistryAccess} it is compiled from, but the
 * preview rebuilds its {@link PreviewContext} on every change of preset, world style, seed or view -
 * and each of those used to compile the whole thing again, on the render thread, inside the widget's
 * render pass. Measured against the bundled datapack alone that was ~300 ms a click, and it scales
 * with the number of loaded packs; the Cities tab and the Customize screen each paid it separately,
 * because they hold separate {@code CityPreview}s.</p>
 *
 * <h2>Why the key is the instance, and why there is only one</h2>
 *
 * <p>Registries do change while the screen is open: switching datapacks reloads the world-gen
 * context and produces a <em>new</em> {@code RegistryAccess}, and a snapshot held across that would
 * preview a pack the player has just turned off. Identity is exactly the right key for that - a
 * reload cannot hand back the same instance - and it needs no assumption about
 * {@code RegistryAccess} equality, which is Object's.</p>
 *
 * <p>One entry, not a map: the create-world screen previews one world at a time, so a second entry
 * could only ever hold registries nothing will ask about again. The key is weak so a reload's old
 * registries are collectable, and {@link #clear} drops the value outright when the screen goes away
 * - see {@code CitiesTab.closeActivePreview}, which exists for the same reason.</p>
 *
 * <p>Reachable from the render thread and from the preview's background worker, so every access is
 * synchronized. Compilation happens under the lock: two threads racing to compile the same
 * registries would otherwise do the expensive thing twice.</p>
 */
final class PreviewAssets {

    private PreviewAssets() {
    }

    @Nullable
    private static WeakReference<RegistryAccess> key;
    @Nullable
    private static AssetSnapshot value;

    /**
     * The snapshot for {@code access}, compiling it iff these are not the registries already held.
     *
     * <p>{@code null} - the GUI's own fallback when the world-creation context has no biome registry,
     * or the Customize screen has no parent screen at all - is an empty snapshot rather than a
     * compile, and is not cached: there is nothing to reuse.</p>
     */
    static synchronized AssetSnapshot of(@Nullable RegistryAccess access) {
        if (access == null) {
            return AssetSnapshot.empty();
        }
        if (value != null && key != null && key.get() == access) {
            return value;
        }
        // Deliberately the unvalidated path: the preview discards diagnostics, and producing them is
        // ~96% of a compile. See AssetCompiler.compileWithoutValidation.
        AssetSnapshot compiled = AssetCompiler.compileWithoutValidation(access);
        key = new WeakReference<>(access);
        value = compiled;
        return compiled;
    }

    /**
     * Releases the held snapshot. Called when the create-world screen goes away, so a world that was
     * never created does not keep its compiled assets - and, through them, its registries - alive.
     */
    static synchronized void clear() {
        key = null;
        value = null;
    }
}
