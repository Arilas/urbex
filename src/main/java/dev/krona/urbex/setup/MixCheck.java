package dev.krona.urbex.setup;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.gen.Scattered;
import dev.krona.urbex.worldgen.lost.City;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredReference;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredSettings;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Headless census of a weighted world-style mix: with
 * {@code -Durbex.mixCheck=radius[,offset]} set, the server walks that chunk square right after
 * startup and reports which world style governs each city, each scatter area and each ordinary
 * chunk, then shuts down.
 * <p>
 * It exists because the thing mixing promises is not a hash. A digest proves generation did not
 * change; this proves it <em>did</em>, in the specific way the feature claims - that cities from
 * two datapacks appear in one world at roughly the weights asked for, that each city is internally
 * one flavour, and that scattered structures mix along with them. None of that is observable from a
 * digest, and reproducing it by hand means flying around a world.
 * <p>
 * Nothing here generates blocks: every question is a pure function of the seed and the coordinate,
 * so the census reads the same answers generation would without paying for it. Sibling of
 * {@link DigestCheck} and deliberately shaped like it - one system property, one greppable verdict
 * line, {@code server.halt} at the end.
 */
public final class MixCheck {

    public static final String PROP = "urbex.mixCheck";
    /**
     * When present, the check fails unless every style in the mix actually governs at least one
     * city and at least one scatter area in the sampled square. That is the difference between
     * "the mix parsed" and "the mix is reachable", and only the latter is worth anything.
     */
    public static final String PROP_REQUIRE_ALL = "urbex.mixCheck.requireAll";
    public static final String OK = "URBEX-MIX-CHECK: OK";
    public static final String FAIL = "URBEX-MIX-CHECK: FAIL";

    private MixCheck() {
    }

    /** No-op unless the {@value #PROP} system property is present. Called once from mod init. */
    public static void registerIfRequested() {
        String value = System.getProperty(PROP);
        if (value == null) {
            return;
        }
        String[] parts = value.split(",");
        int radius = Integer.parseInt(parts[0].trim());
        int offset = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
        boolean requireAll = System.getProperty(PROP_REQUIRE_ALL) != null;

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                census(server.overworld(), radius, offset, requireAll);
            } catch (Throwable t) {
                Urbex.getLogger().error("Mix check crashed", t);
                report(FAIL + " (" + t + ")");
            } finally {
                server.halt(false);
            }
        });
    }

    private static void census(ServerLevel level, int radius, int offset, boolean requireAll) {
        IDimensionInfo provider = Registration.cityFeature().getDimensionInfo(level);
        if (provider == null) {
            report(FAIL + " (no Urbex preset configured for the overworld)");
            return;
        }

        List<String> styleNames = new ArrayList<>();
        for (WorldStyle style : provider.worldStyles().styles()) {
            styleNames.add(style.getName());
        }
        report("MIXCHECK styles=" + String.join(",", styleNames)
                + " primary=" + provider.worldStyles().primary().getName()
                + " single=" + provider.worldStyles().isSingle());

        Map<String, Integer> cityStylesByWorldStyle = new TreeMap<>();
        Map<String, Integer> chunkStyles = new TreeMap<>();
        Map<String, Integer> scatterStyles = new TreeMap<>();
        Set<String> cityStyleIds = new LinkedHashSet<>();
        Set<String> scatterAnchors = new LinkedHashSet<>();
        Set<String> scatterNames = new LinkedHashSet<>();
        int cityCenters = 0;

        for (int cx = offset - radius; cx <= offset + radius; cx++) {
            for (int cz = offset - radius; cz <= offset + radius; cz++) {
                ChunkCoord coord = new ChunkCoord(provider.getType(), cx, cz);

                chunkStyles.merge(provider.worldStyles().atChunk(provider, coord).getName(), 1, Integer::sum);

                if (City.isCityCenter(coord, provider)) {
                    cityCenters++;
                    cityStylesByWorldStyle.merge(
                            provider.worldStyles().atCityCenter(coord).getName(), 1, Integer::sum);
                    String cityStyle = City.getCityStyleForCityCenter(coord, provider);
                    if (cityStyle != null) {
                        cityStyleIds.add(cityStyle);
                    }
                }

                // One entry per distinct scatter area, not per chunk: an area is what draws a style.
                ChunkCoord anchor = Scattered.areaAnchor(provider, coord);
                if (scatterAnchors.add(anchor.chunkX() + ":" + anchor.chunkZ())) {
                    WorldStyle areaStyle = provider.worldStyles().atScatterArea(anchor);
                    scatterStyles.merge(areaStyle.getName(), 1, Integer::sum);
                    ScatteredSettings settings = areaStyle.getScatteredSettings();
                    if (settings != null) {
                        for (ScatteredReference reference : settings.getList()) {
                            scatterNames.add(reference.getName());
                        }
                    }
                }
            }
        }

        report("MIXCHECK cityCenters=" + cityCenters + " byWorldStyle=" + cityStylesByWorldStyle);
        report("MIXCHECK cityStyles=" + cityStyleIds);
        report("MIXCHECK scatterAreas=" + scatterAnchors.size() + " byWorldStyle=" + scatterStyles);
        report("MIXCHECK scatterStructures=" + scatterNames);
        report("MIXCHECK chunks byWorldStyle=" + chunkStyles);

        if (cityCenters == 0) {
            report(FAIL + " (no city centres in the sampled square - widen the radius)");
            return;
        }
        if (requireAll) {
            for (String style : styleNames) {
                if (!cityStylesByWorldStyle.containsKey(style)) {
                    report(FAIL + " (world style '" + style + "' governs no city in the sample)");
                    return;
                }
                if (!scatterStyles.containsKey(style)) {
                    report(FAIL + " (world style '" + style + "' governs no scatter area in the sample)");
                    return;
                }
            }
        }
        report(OK + " cityCenters=" + cityCenters + " scatterAreas=" + scatterAnchors.size());
    }

    private static void report(String line) {
        Urbex.getLogger().info(line);
        System.out.println(line);
    }
}
