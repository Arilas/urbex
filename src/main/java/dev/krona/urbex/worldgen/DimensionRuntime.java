package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.PresetChoice;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Everything one loaded level generates with, built when that level loads and retired when it
 * unloads.
 *
 * <p>This replaces {@code CityFeature}'s process-global {@code Map<ResourceKey<Level>,
 * IDimensionInfo>} and the dirty-counter protocol that maintained it. That map was keyed by
 * dimension id alone, so a second world in the same JVM inherited the first world's entry until
 * something remembered to bump a counter; and the counter's reset ran from the generation path
 * itself, which is how a worker could have the asset registries cleared underneath it and save an
 * undecorated chunk (issue #125).</p>
 *
 * <p>{@code planning} is {@code null} for a level Urbex does not generate in. That is a cached
 * answer, not an absent one: the alternative is re-deriving "this dimension has no preset" from the
 * config on every chunk of every vanilla dimension.</p>
 *
 * <p>The record is the phase-1 shape from the milestone plan, deliberately not the final one:
 * {@code planning} holds today's {@link IDimensionInfo} until #129 replaces it with an explicit
 * planning context. {@link #caches()} and {@link #generator()} read like the components they will
 * become while still delegating to the one object that owns them today - two record components
 * holding the same objects would be two owners of one thing, which is the mistake this whole epic
 * is about. {@code tasks} is a real component, because nothing else owns it.</p>
 *
 * <p>{@code tagEpoch} is the server's, not this level's: it is the same instance in every loaded
 * level's runtime, because block tags come from the server's reloadable resources. It is a slot
 * rather than a {@link TagSnapshot} so that a {@code /reload} can swap the epoch without rebuilding
 * anything here - which is the whole of what a reload changes (issue #128).</p>
 */
public record DimensionRuntime(ServerLevel level, @Nullable IDimensionInfo planning, LevelTaskQueue tasks,
                               @Nullable TagEpoch tagEpoch) {

    public DimensionRuntime(ServerLevel level, @Nullable IDimensionInfo planning, @Nullable TagEpoch tagEpoch) {
        this(level, planning, new LevelTaskQueue(level.dimension().identifier().toString()), tagEpoch);
    }

    /**
     * A runtime for a level Urbex does not generate in. It still carries a task queue: the tick
     * handler drains whatever runtime the level has, and an empty queue costs nothing. It carries no
     * tag epoch, for the same reason it carries no planning context - nothing generates here.
     */
    public static DimensionRuntime disabled(ServerLevel level) {
        return new DimensionRuntime(level, null, null);
    }

    public boolean isEnabled() {
        return planning != null;
    }

    /** The per-level caches. Never call on a disabled runtime. */
    public DimensionCaches caches() {
        return planning.caches();
    }

    /** The generator this level's chunks are driven by. Never call on a disabled runtime. */
    public CityGenerator generator() {
        return planning.getFeature();
    }

    /**
     * The block tags a chunk starting now generates against. Never call on a disabled runtime.
     * <p>
     * Call it <em>once</em>, at the start of a generation, and pass the result down: this is the
     * live slot, so a second call later in the same chunk may answer from a different epoch. That is
     * what {@link ChunkGenContext} holds it for.
     */
    public TagSnapshot tags() {
        return tagEpoch.current();
    }

    /**
     * Builds the runtime for {@code level}.
     *
     * <p>Everything here used to happen lazily on the generation path, the first time a chunk of the
     * dimension was built, behind a {@code putIfAbsent} race that two worker threads could both
     * enter. It happens once now, on the thread that loads the level, before that level can generate
     * anything - which is also what makes the {@code CITY_STYLE_ALTERNATIVE} check below a load-time
     * refusal rather than an exception from a worker.</p>
     *
     * <p>The snapshot arrives already compiled; {@link GenerationSession#load} is the one caller and
     * builds it before calling this. A runtime cannot exist without one, which is what makes "no
     * chunk generates against unloaded assets" structural rather than a check. The tag epoch arrives
     * the same way and for the same reason.</p>
     */
    static DimensionRuntime create(ServerLevel level, AssetSnapshot assets, TagEpoch tagEpoch) {
        ResourceKey<Level> type = level.dimension();
        PresetChoice choice = Config.getPresetChoiceForDimension(level, type);
        if (choice == null) {
            return disabled(level);
        }
        Preset preset = Presets.resolve(level.registryAccess(), choice.preset());
        if (choice.overridesJson().isPresent()) {
            // Fail-soft, unlike the preset id resolution above: the overrides JSON is either a
            // client-published payload PresetSelection.publish() encoded itself (trustworthy), or
            // saved data read back from disk - a corrupted/hand-edited save file must not refuse the
            // level. PresetSelection.restore() already validates before publishing, so this guard is
            // a backstop against corrupted saved data reaching this far, not the primary defense.
            try {
                PresetDefinition re = PresetDefinition.CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(choice.overridesJson().get())).getOrThrow();
                preset = Presets.applyOverrides(preset, re);
            } catch (Exception e) {
                Urbex.getLogger().error("Malformed Urbex preset overrides for dimension '{}'; " +
                        "generating with the un-overridden preset '{}'.", type.identifier(), choice.preset(), e);
            }
        }
        // Route 4 of the four that name a city style (see AssetRegistries.loadReachableCityStyles):
        // the alternative style can arrive as per-world override JSON rather than from a registry
        // entry, so the load-time sweep cannot see it - a player types an id into the ADVANCED
        // settings box and it rides into the world through UrbexData. Checked here instead, once per
        // level load and before any chunk work, so an incomplete or missing style refuses the level
        // naming the dimension rather than throwing from a worker on every chunk. This is
        // deliberately not fail-soft like the overrides parse above: a malformed payload can be
        // ignored and the un-overridden preset used, but a style that cannot resolve has no such
        // fallback - City.getCityStyle would simply hand null on to generation.
        requireCityStyle(assets, preset.CITY_STYLE_ALTERNATIVE, type.identifier());
        return new DimensionRuntime(level,
                new DefaultDimensionInfo(level, assets, preset, choice.worldStyles()), tagEpoch);
    }

    /**
     * Refuses the level if its per-world {@code cityStyleAlternative} override names a city style the
     * pack does not have.
     * <p>
     * The one city-style reference no load-time sweep can see: it arrives as override JSON from the
     * customization GUI through {@code UrbexData} rather than from a registry entry, so the compiler
     * never walked it. Checked here, once per level load and before any chunk work, and deliberately
     * not fail-soft like the overrides parse above - a malformed payload can be ignored and the
     * un-overridden preset used, but a style that cannot resolve has no such fallback:
     * {@code City.getCityStyle} would hand null on to generation.
     */
    private static void requireCityStyle(AssetSnapshot assets, @Nullable String name, Object selectedBy) {
        if (name == null || name.isBlank()) {
            return;
        }
        CityStyle style = assets.cityStyles().get(name);
        if (style == null) {
            throw new IllegalStateException("City style '" + name + "', selected by '" + selectedBy
                    + "', is not registered by any loaded datapack.");
        }
    }
}
