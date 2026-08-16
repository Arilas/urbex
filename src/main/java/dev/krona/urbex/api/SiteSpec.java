package dev.krona.urbex.api;

import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * What a site is built from: a value, complete on its own, that {@link UrbexApi#site} turns into a
 * live {@link UrbexSite}.
 *
 * <p><strong>Experimental.</strong> See {@link UrbexApi} for what that means.</p>
 *
 * <p>Build one with {@link #builder}. Everything but the id, the preset and the field has a
 * defensible default, so the shortest useful spec is three arguments and a {@code build()}.</p>
 *
 * @param id                  the site's identity within a level. Two calls to
 *                            {@link UrbexApi#site} with the same id return the same site, so this
 *                            is what makes a per-chunk call cheap. It also names the site in any
 *                            diagnostic, so name it after what it builds.
 * @param preset              the preset this site generates with, as a {@code urbex:preset}
 *                            registry id. Independent of whatever preset the dimension itself uses.
 * @param worldStyles         which world styles the site draws from. A single-entry mix is the
 *                            ordinary case and draws no randomness at all.
 * @param presetOverridesJson a {@code PresetDefinition} JSON overlay applied on top of the resolved
 *                            preset, or null. This is how a caller reaches every preset field there
 *                            is - lighting density, floor counts, ruin chance, corridor chance -
 *                            without this API growing a method per field. Malformed JSON is logged
 *                            and the un-overridden preset used, exactly as for a dimension.
 * @param field               where the sites are and how high they sit. Pure, total, thread-safe;
 *                            read {@link SiteField} before writing one.
 * @param minY                the lowest block Y the site may write at, inclusive.
 * @param maxY                the highest block Y the site may write at, inclusive.
 */
public record SiteSpec(Identifier id, Identifier preset, WorldStyleMix worldStyles,
                       @Nullable String presetOverridesJson, SiteField field, int minY, int maxY) {

    public SiteSpec {
        Objects.requireNonNull(id, "an Urbex site needs an id");
        Objects.requireNonNull(preset, "Urbex site '" + id + "' needs a preset");
        Objects.requireNonNull(worldStyles, "Urbex site '" + id + "' needs at least one world style");
        Objects.requireNonNull(field, "Urbex site '" + id + "' needs a SiteField");
        if (maxY < minY) {
            throw new IllegalArgumentException("Urbex site '" + id + "' has a window whose top ("
                    + maxY + ") is below its bottom (" + minY + ")");
        }
    }

    public static Builder builder(Identifier id, Identifier preset, SiteField field) {
        return new Builder(id, preset, field);
    }

    /**
     * Assembles a {@link SiteSpec}. Not thread-safe, and not meant to be: build the spec once, at
     * mod initialisation or the first time a level needs it, and keep the {@link UrbexSite}.
     */
    public static final class Builder {

        private final Identifier id;
        private final Identifier preset;
        private final SiteField field;
        private WorldStyleMix worldStyles = WorldStyleMix.of(UrbexApi.DEFAULT_WORLD_STYLE);
        @Nullable
        private String presetOverridesJson;
        private int minY = UrbexApi.DEFAULT_MIN_Y;
        private int maxY = UrbexApi.DEFAULT_MAX_Y;

        private Builder(Identifier id, Identifier preset, SiteField field) {
            this.id = id;
            this.preset = preset;
            this.field = field;
        }

        /** The one world style this site builds in. */
        public Builder worldStyle(Identifier style) {
            this.worldStyles = WorldStyleMix.of(style);
            return this;
        }

        /** Several weighted world styles; only the ratios of the weights matter. */
        public Builder worldStyles(WorldStyleMix mix) {
            this.worldStyles = mix;
            return this;
        }

        /** @see SiteSpec#presetOverridesJson() */
        public Builder presetOverrides(@Nullable String json) {
            this.presetOverridesJson = json;
            return this;
        }

        /**
         * The vertical window, inclusive at both ends. Nothing this site generates leaves it.
         *
         * <p>Leave room above what you expect to build: the window bounds the site's <em>planning</em>
         * as well as its writing, so a window ten blocks tall does not produce a squashed building,
         * it produces a building with no floors in it.</p>
         */
        public Builder window(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
            return this;
        }

        public SiteSpec build() {
            return new SiteSpec(id, preset, worldStyles, presetOverridesJson, field, minY, maxY);
        }
    }
}
