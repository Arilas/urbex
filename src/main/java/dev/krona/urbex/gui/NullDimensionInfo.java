package dev.krona.urbex.gui;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.gui.preview.PreviewTerrain;
import dev.krona.urbex.plan.grid.GridRoadField;
import dev.krona.urbex.plan.grid.GridSettings;
import dev.krona.urbex.worldgen.DimensionCaches;
import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.LevelShape;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.WorldStyleField;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.lost.cityassets.AssetCompiler;
import dev.krona.urbex.worldgen.lost.cityassets.AssetDiagnostics;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class NullDimensionInfo implements IDimensionInfo {

    /** What the placeholder world style calls itself, so a load error can name it. */
    private static final Identifier PLACEHOLDER_ID =
            Identifier.fromNamespaceAndPath(Urbex.MODID, "preview_placeholder");
    /** A style the bundled pack actually ships, and qualified; see {@link #placeholderStyle()}. */
    private static final String PLACEHOLDER_OUTSIDE_STYLE = Urbex.MODID + ":standard";

    private final Random random;
    private final PreviewTerrain terrain;
    private final PlanningContext planning;
    private final CityGenerator feature;

    /**
     * The preview's dimension info. Takes the whole {@link WorldStyleMix} the player chose, so a
     * mixed selection previews as a mix rather than as its primary alone - judging a balance before
     * committing to the world is the point of the control.
     * <p>
     * Every id resolves independently: one style the datapacks no longer ship falls back to the
     * placeholder for that entry alone, rather than taking the whole preview with it.
     */
    public NullDimensionInfo(Preset profile, WorldStyleMix worldStyles, long seed, @Nullable RegistryAccess registryAccess) {
        DimensionCaches caches = new DimensionCaches(seed);
        // The preview compiles its own snapshot and owns it, rather than reaching for the server's.
        // It has no session - it runs on the client, on the world-creation screen, before any server
        // exists - and must not acquire one. Diagnostics are discarded on purpose: a broken pack is
        // the world load's business to refuse, and a preview that threw would leave the player unable
        // to see why. Individual ids still fall back to the placeholder below.
        AssetSnapshot assets = registryAccess == null
                ? AssetSnapshot.empty()
                : AssetCompiler.compile(registryAccess, new AssetDiagnostics());
        List<WorldStyleField.Weighted> resolvedEntries = new ArrayList<>(worldStyles.entries().size());
        for (WorldStyleMix.Entry entry : worldStyles.entries()) {
            WorldStyle resolved = null;
            if (registryAccess != null) {
                try {
                    resolved = assets.worldStyles().get(entry.style());
                } catch (RuntimeException e) {
                    // Preview only: fall back to the placeholder below if the chosen style isn't
                    // registered (e.g. a stale GUI worldStyle no longer shipped by any datapack).
                    Urbex.LOGGER.debug("Preview could not resolve worldstyle '{}'; using the placeholder.",
                            entry.style(), e);
                }
            }
            resolvedEntries.add(new WorldStyleField.Weighted(entry.weight(),
                    resolved != null ? resolved : new WorldStyle(PLACEHOLDER_ID, List.of(placeholderStyle()))));
        }
        random = new Random(seed);
        terrain = new PreviewTerrain(profile, registryAccess);
        planning = new PlanningContext(
                seed,
                // The overworld, which is what the preview draws.
                Level.OVERWORLD,
                profile,
                assets,
                new WorldStyleField(seed, resolvedEntries),
                // The preview's own seed and dimension, so the roads it draws are the roads the
                // world will have. Same construction as DefaultDimensionInfo; there is no server to
                // ask.
                new GridRoadField(seed, Level.OVERWORLD.identifier().toString(),
                        GridSettings.fromPreset(profile)),
                caches,
                // The vanilla overworld's shape: a preview runs before any level exists, so there is
                // nothing to ask, and every planning rule that reads a height bound or the water
                // line gets a real answer rather than an NPE off a null level (issue #129).
                LevelShape.VANILLA_OVERWORLD,
                terrain);
        feature = new CityGenerator(planning, profile);
    }

    /**
     * The world style the preview falls back to when it cannot resolve the chosen one.
     * <p>
     * It is a one-entry {@code extends} chain, so it has nothing to inherit from and must declare
     * every field {@link WorldStyle} requires <em>after</em> resolution by itself - today
     * {@code outsidestyle}, {@code citystyles} and the whole of {@code parts}, down to each of the
     * twenty-two wiring components {@code PartSelector.requireComplete} checks. Anything left absent
     * is an {@link IllegalStateException} out of the constructor rather than a decode failure, and
     * this is the one place in {@code src/main} that builds a {@code WorldStyleDefinition} by hand instead
     * of decoding one, so no datapack test covers it; {@code NullDimensionInfoPlaceholderTest} does.
     * <p>
     * The lists are declared and empty rather than absent because the preview draws no parts: it
     * samples biomes, city placement, road classes and rail/highway chunk <em>types</em>, none of
     * which reads a part name. Naming real parts here would be a claim about a datapack that, on
     * this path, either isn't loaded or doesn't have the style the player asked for.
     * <p>
     * It carries no id, because a decoded world style no longer carries one: {@link #PLACEHOLDER_ID}
     * is handed to the {@link WorldStyle} constructor beside this, which is where a load error looking
     * for a name will find it (issue #128).
     */
    private static WorldStyleDefinition placeholderStyle() {
        return new WorldStyleDefinition(
                Optional.empty(),
                // No display name: this style is never offered in the picker, so nothing would read
                // one, and inventing a label here would put a name on screen if that ever changed.
                Optional.empty(),
                // Fully qualified, like every other asset reference: a bare name throws out of
                // DataTools.fromName the moment anything resolves it.
                Optional.of(PLACEHOLDER_OUTSIDE_STYLE),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new PartSelector.Decl(
                        Optional.of(new HighwayParts.Decl(
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts())),
                        Optional.of(new RailwayParts.Decl(
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts(),
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts(),
                                noParts(), noParts(), noParts(), noParts())))),
                Optional.of(new Mergeable<>(true, Collections.emptyList())),
                Optional.empty(),
                // No 'rotatable': the preview places no parts, so nothing is ever rotated, and
                // naming a tag here would be a claim about a datapack this path has not loaded.
                Optional.empty()
        );
    }

    /** One wiring component, declared as empty: the preview places no parts. */
    private static Optional<Mergeable<String>> noParts() {
        return Optional.of(new Mergeable<>(true, Collections.emptyList()));
    }

    /** The config preview renderer's own source. Nothing here places generated blocks. */
    public Random getRandom() {
        return random;
    }

    @Override
    public PlanningContext planning() {
        return planning;
    }

    @Override
    public CityGenerator getFeature() {
        return feature;
    }

    /** The bitmap character at a chunk, for the renderer that colours the preview map from it. */
    public char getBiomeChar(int chunkX, int chunkZ) {
        return terrain.biomeChar(chunkX, chunkZ);
    }

}
