package dev.krona.urbex.setup;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.UrbexConfig;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The configuration as the rest of the mod means it: identifiers rather than strings, dimension
 * rules rather than a list of {@code dimension=preset[@worldstyle]} lines.
 *
 * <p>{@link UrbexConfig} is the file. This is what it says. The two are separate because the
 * strings in a file are not the values a decision is made from, and the translation used to happen
 * wherever a decision needed one: {@code dimensionsWithPresets} was re-parsed on every preset-cache
 * build <em>and</em> again during start-up validation, and {@code avoidStructures} was parsed into a
 * lazily-filled nullable static that three unrelated code paths had to remember to invalidate
 * (issue #130).</p>
 *
 * <p>Parsing once, here, also moves every "bad format for config value" message to load time, where
 * a player can act on it - the same messages used to be logged from a worldgen worker on whichever
 * chunk first built the cache.</p>
 *
 * @param dimensionRules  each {@code dimensionsWithPresets} entry that parsed, in declaration order.
 *                        A malformed entry is reported and dropped rather than throwing: one bad
 *                        line should not take the whole list down.
 * @param selectedPreset  the overworld's own selection, or null if the file names none
 * @param selectedWorldStyles the overworld's own world styles; a single-entry mix, because this is
 *                        the overworld-only default for installs that never open the Cities tab, and
 *                        a mix here would add a third place to look for one setting
 * @param avoidStructures the structure ids cities keep away from, parsed once
 */
public record GlobalConfig(
        UrbexConfig file,
        List<DimensionRule> dimensionRules,
        @Nullable Identifier selectedPreset,
        WorldStyleMix selectedWorldStyles,
        Set<Identifier> avoidStructures) {

    /** One {@code dimensionsWithPresets} entry, parsed. */
    public record DimensionRule(ResourceKey<Level> dimension, PresetChoice choice) {}

    public static final GlobalConfig DEFAULT = of(UrbexConfig.DEFAULT);

    /**
     * Translates a parsed file.
     *
     * <p>The {@code experimentalMultiWorldStyles} gate is applied here, reading the flag off the
     * same file the entries came from. It used to read the published active config while that config
     * was being built.</p>
     */
    public static GlobalConfig of(UrbexConfig file) {
        List<DimensionRule> rules = new ArrayList<>();
        Map<ResourceKey<Level>, String> seen = new LinkedHashMap<>();
        for (String entry : file.dimensionsWithPresets()) {
            parseDimensionEntry(entry, file.experimentalMultiWorldStyles()).ifPresent(rule -> {
                String previous = seen.put(rule.dimension(), entry);
                if (previous != null) {
                    Urbex.getLogger().warn("Two dimensionsWithPresets entries name '{}': '{}' is "
                                    + "replaced by '{}'.", rule.dimension().identifier(), previous, entry);
                    rules.removeIf(existing -> existing.dimension().equals(rule.dimension()));
                }
                rules.add(rule);
            });
        }

        Identifier selectedPreset = null;
        String preset = file.selectedPreset();
        if (preset != null && !preset.isEmpty()) {
            try {
                selectedPreset = DataTools.fromName(preset);
            } catch (Exception e) {
                Urbex.getLogger().error("Bad selectedPreset '{}' in config; ignoring it. {}",
                        preset, e.getMessage());
            }
        }
        WorldStyleMix selectedStyles = Config.DEFAULT_WORLD_STYLE_MIX;
        String style = file.selectedWorldStyle();
        if (style != null && !style.isEmpty()) {
            try {
                selectedStyles = WorldStyleMix.of(DataTools.fromName(style));
            } catch (Exception e) {
                Urbex.getLogger().error("Bad selectedWorldStyle '{}' in config; using '{}'. {}",
                        style, Config.DEFAULT_WORLD_STYLE, e.getMessage());
            }
        }

        Set<Identifier> avoided = file.avoidStructures().stream()
                .map(GlobalConfig::parseStructureId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        return new GlobalConfig(file, List.copyOf(rules), selectedPreset, selectedStyles, avoided);
    }

    @Nullable
    private static Identifier parseStructureId(String raw) {
        try {
            return Identifier.parse(raw);
        } catch (Exception e) {
            Urbex.getLogger().error("Bad structure id '{}' in avoidStructures; ignoring it.", raw);
            return null;
        }
    }

    /**
     * Parses one {@code dimensionsWithPresets} entry: {@code dimension=preset[@worldstyle]}.
     * <p>
     * The preset and worldstyle names must name their namespace: {@link DataTools#fromName} rejects
     * a bare one rather than defaulting it, so {@code minecraft:overworld=default} is refused and
     * {@code minecraft:overworld=urbex:default} is not. (The dimension id on the left is parsed by
     * {@link Identifier#parse} and does still default, to {@code minecraft} - it is a vanilla id,
     * not a datapack cross-reference.) Malformed entries - wrong arity on either side of {@code =},
     * or an id that fails to parse - are logged and rejected rather than thrown, so one bad line in
     * the config doesn't take the whole list down.
     * <p>
     * The rejection messages carry {@code e.getMessage()} through, because for the two
     * {@code fromName} calls that is the only place the "add a namespace, e.g. urbex:default" hint
     * exists - and a config written before namespaces were mandatory is exactly the case that hits
     * it, so it is the one message that user will see.
     */
    public static Optional<DimensionRule> parseDimensionEntry(String entry, boolean allowMixes) {
        String[] split = entry.split("=");
        if (split.length != 2) {
            Urbex.getLogger().error("Bad format for config value: '{}'! Expected 'dimension=preset[@worldstyle]'.", entry);
            return Optional.empty();
        }
        ResourceKey<Level> dimensionType;
        try {
            dimensionType = ResourceKey.create(Registries.DIMENSION, Identifier.parse(split[0]));
        } catch (Exception e) {
            Urbex.getLogger().error("Bad dimension id in config value: '{}'!", entry);
            return Optional.empty();
        }

        String presetPart = split[1];
        String presetName = presetPart;
        WorldStyleMix worldStyles = Config.DEFAULT_WORLD_STYLE_MIX;
        int at = presetPart.indexOf('@');
        if (at >= 0) {
            presetName = presetPart.substring(0, at);
            String stylePart = presetPart.substring(at + 1);
            try {
                // The whole tail, not one id: a single qualified id is just a one-entry mix, so the
                // pre-mixing form parses unchanged.
                worldStyles = Config.gateMix(WorldStyleMix.parse(stylePart), allowMixes,
                        "dimensionsWithPresets entry '" + entry + "'");
            } catch (Exception e) {
                Urbex.getLogger().error("Bad worldstyle spec in config value: '{}'! {}", entry, e.getMessage());
                return Optional.empty();
            }
        }

        Identifier presetId;
        try {
            presetId = DataTools.fromName(presetName);
        } catch (Exception e) {
            Urbex.getLogger().error("Bad preset id in config value: '{}'! {}", entry, e.getMessage());
            return Optional.empty();
        }

        return Optional.of(new DimensionRule(dimensionType,
                new PresetChoice(presetId, worldStyles, Optional.empty())));
    }

    /** The rule for {@code dimension}, or null. Last declaration wins; see {@link #of}. */
    @Nullable
    public PresetChoice ruleFor(ResourceKey<Level> dimension) {
        for (DimensionRule rule : dimensionRules) {
            if (rule.dimension().equals(dimension)) {
                return rule.choice();
            }
        }
        return null;
    }
}
