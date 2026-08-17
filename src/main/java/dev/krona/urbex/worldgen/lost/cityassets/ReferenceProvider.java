package dev.krona.urbex.worldgen.lost.cityassets;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.util.Set;

/**
 * Whether the thing that would satisfy a reference is installed at all.
 *
 * <p>"Does this resolve" is the wrong question for a reference that is allowed not to. A pack may
 * list a loot table or a mob from a mod it does not require, so that the content appears for players
 * who have that mod and is simply absent for everyone else - and reporting those would mean a warning
 * per optional entry, every load, for a pack that is working as written.</p>
 *
 * <p>So a soft reference is only reported when its <em>provider</em> is present and the id still does
 * not resolve. That is a different fact: the mod is installed and renamed the thing, or the name is a
 * typo. Both are worth a line; "you do not have that mod" is not.</p>
 *
 * <p>The presence test differs by what would provide it, which is why there are two methods rather
 * than one. A block, an entity or a loot table comes from a <strong>mod</strong>, so the loader
 * answers. An Urbex asset comes from a <strong>datapack</strong>, which need not be a mod at all - a
 * pack shipping only JSON registers assets under its own namespace with no mod id to ask about - so
 * the question is whether anything loaded actually registered assets in that namespace.</p>
 *
 * <p>None of this applies to a reference generation <em>dereferences</em>. A city style naming a
 * building that is not installed is fatal whatever the author intended, because
 * {@code getOrThrow} throws from a worldgen worker either way; only references that fail softly - a
 * matcher that never fires, a loot table that yields nothing - can be quietly absent.</p>
 */
public final class ReferenceProvider {

    private ReferenceProvider() {
    }

    /**
     * For a reference into a mod's registries: a block, an entity type, a loot table.
     *
     * <p>{@code minecraft} always counts as installed, and that is not belt-and-braces: it is the
     * point of asking. A vanilla id that stops resolving has been renamed, which is exactly the
     * failure that made the whole {@code urbex:chains} decoration invisible (issue #91), and it must
     * stay reportable. It is also why this cannot be left to the loader alone - outside a game,
     * {@code isModLoaded("minecraft")} answers false.</p>
     */
    public static boolean modIsInstalled(Identifier reference) {
        return modIsInstalled(reference.getNamespace());
    }

    /**
     * The same question asked of a namespace directly, for a caller that has one and no id to hang it
     * on - a version 2 palette's {@code "when": {"mod": "create"}} ({@code WEIGHT.023}), which names a
     * mod and nothing in it.
     */
    public static boolean modIsInstalled(String namespace) {
        return "minecraft".equals(namespace) || FabricLoader.getInstance().isModLoaded(namespace);
    }

    /**
     * For a reference to another Urbex asset: is anything loaded registering assets in that
     * namespace?
     *
     * @param namespaces every namespace the compiled snapshot holds assets in, which is the closest
     *                   thing to "which packs are installed" that a datapack-only pack can be seen by
     */
    static boolean packIsInstalled(Identifier reference, Set<String> namespaces) {
        return namespaces.contains(reference.getNamespace());
    }
}
