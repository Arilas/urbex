package dev.krona.urbex.setup;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A weighted set of world styles: what generates a dimension when several datapacks each bring
 * their own kind of city. Immutable, validated on construction, and pure - no registry lookup
 * happens here, so this is testable headless and safe to build on any thread.
 * <p>
 * One string grammar serves both places a mix is written down - the {@code dimensionsWithPresets}
 * config entry (after the {@code @}) and {@code UrbexData}'s saved selection:
 * <pre>urbex:standard*0.1+urbexmt:moderntweaks*0.9</pre>
 * {@code +} separates entries and {@code *} separates an id from its weight. Those two are forced:
 * {@code :} and {@code /} belong to {@link Identifier}, and {@code ,} already separates entries of
 * the {@code dimensionsWithPresets} list itself. A weight of 1 is implicit, so a single style
 * formats as the bare id it was before mixing existed - which is what lets an old save and an old
 * config line keep parsing unchanged.
 * <p>
 * Weights are relative: only their ratios matter. {@code 0.1} and {@code 0.9} say the same thing as
 * {@code 1} and {@code 9}.
 */
public record WorldStyleMix(List<Entry> entries) {

    /** One weighted style. {@code weight} is relative: only ratios matter, never the absolute value. */
    public record Entry(Identifier style, float weight) {
    }

    private static final char ENTRY_SEPARATOR = '+';
    private static final char WEIGHT_SEPARATOR = '*';

    /**
     * The serial form, as a codec, for {@code UrbexData}. Deliberately over the same string the
     * config parser reads rather than a list-of-objects encoding: two representations of one value
     * is two parsers to keep in step, and the string is what a server owner types anyway.
     */
    public static final Codec<WorldStyleMix> CODEC = Codec.STRING.comapFlatMap(
            spec -> {
                try {
                    return DataResult.success(parse(spec));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(e::getMessage);
                }
            },
            WorldStyleMix::format);

    public WorldStyleMix {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("A world style mix needs at least one style");
        }
        Set<Identifier> seen = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.style() == null) {
                throw new IllegalArgumentException("A world style mix entry has no style id");
            }
            if (!(entry.weight() > 0) || !Float.isFinite(entry.weight())) {
                throw new IllegalArgumentException("World style '" + entry.style()
                        + "' has weight " + entry.weight() + "; weights must be finite and above zero");
            }
            if (!seen.add(entry.style())) {
                throw new IllegalArgumentException("World style '" + entry.style()
                        + "' appears twice in the same mix");
            }
        }
        entries = List.copyOf(entries);
    }

    public static WorldStyleMix of(Identifier style) {
        return new WorldStyleMix(List.of(new Entry(style, 1.0f)));
    }

    public static WorldStyleMix of(List<Entry> entries) {
        return new WorldStyleMix(entries);
    }

    /**
     * Parses the grammar above. Throws rather than returning an empty optional: every caller has a
     * different thing to do with a bad spec (a config line is logged and dropped, a saved selection
     * falls back to the default), and the message names which part was wrong.
     */
    public static WorldStyleMix parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Empty world style spec");
        }
        List<Entry> parsed = new ArrayList<>();
        for (String part : spec.split("\\" + ENTRY_SEPARATOR, -1)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Empty entry in world style spec '" + spec + "'");
            }
            String[] halves = trimmed.split("\\" + WEIGHT_SEPARATOR, -1);
            if (halves.length > 2) {
                throw new IllegalArgumentException("World style entry '" + trimmed
                        + "' has more than one weight");
            }
            Identifier style;
            try {
                // fromName, not Identifier.parse: an unqualified id is an error here exactly as it
                // is in every other datapack cross-reference, and its message carries the hint.
                style = DataTools.fromName(halves[0].trim());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Bad world style id in '" + trimmed + "': "
                        + e.getMessage(), e);
            }
            float weight = 1.0f;
            if (halves.length == 2) {
                try {
                    weight = Float.parseFloat(halves[1].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Bad weight in world style entry '" + trimmed + "'", e);
                }
            }
            parsed.add(new Entry(style, weight));
        }
        return new WorldStyleMix(parsed);
    }

    /**
     * The serial form. {@link Float#toString} is the shortest decimal that reads back as the same
     * float, so {@code format} round-trips through {@link #parse} exactly.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            if (!sb.isEmpty()) {
                sb.append(ENTRY_SEPARATOR);
            }
            sb.append(entry.style());
            if (entry.weight() != 1.0f) {
                sb.append(WEIGHT_SEPARATOR).append(Float.toString(entry.weight()));
            }
        }
        return sb.toString();
    }

    public boolean isSingle() {
        return entries.size() == 1;
    }

    public Optional<Identifier> single() {
        return isSingle() ? Optional.of(entries.get(0).style()) : Optional.empty();
    }

    /**
     * The style world-spanning settings come from - highway and railway parts, world settings, the
     * multichunk grid size. The heaviest entry; ties break on the id string rather than on list
     * position, because the list can arrive in registry iteration order, which is
     * {@code ConcurrentHashMap} bucket order and would make the answer depend on file names.
     */
    public Identifier primary() {
        Entry best = entries.get(0);
        for (Entry entry : entries) {
            if (entry.weight() > best.weight()) {
                best = entry;
            } else if (entry.weight() == best.weight()
                    && entry.style().toString().compareTo(best.style().toString()) < 0) {
                best = entry;
            }
        }
        return best.style();
    }

    /** What the experimental gate applies when mixing is off: keep the primary, drop the rest. */
    public WorldStyleMix reducedToPrimary() {
        return isSingle() ? this : of(primary());
    }

    /** Every style this mix names, for validation and asset preloading. */
    public List<Identifier> styles() {
        List<Identifier> ids = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            ids.add(entry.style());
        }
        return List.copyOf(ids);
    }
}
