package dev.krona.urbex.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every place that looks a city style up by name, so that adding one fails the build.
 * <p>
 * Since part wiring became required, a city style that does not resolve is a load error - but only
 * where something resolves it at load. {@code AssetRegistries.loadReachableCityStyles} does that by
 * enumerating the routes a name can arrive by, and that list is maintained by hand: a new selection
 * path that forgets to register there reverts silently to failing from a worldgen worker,
 * mid-generation, with the whole suite still green. Nothing in the type system prevents it.
 * <p>
 * This pins the shape that is easiest to add by accident - a <em>new lookup site</em>. It cannot see
 * a new name <em>source</em> feeding an existing site, which is exactly what the per-world preset
 * overrides route turned out to be, so it is a guard rather than a proof. If this test fails,
 * the fix is not to widen the list here: it is to make the new site's name reachable from
 * {@code loadReachableCityStyles}, or to check it where it is built as {@code CityFeature} does.
 */
class CityStyleLookupSitesTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** A lookup, not {@code reset()} or a field reference: the four methods that resolve a name. */
    private static final Pattern LOOKUP =
            Pattern.compile("CITYSTYLES\\s*\\.\\s*(get|getOrThrow|getOrWarn|getIterable)\\s*\\(");

    @Test
    void onlyTheKnownCallSitesResolveACityStyleByName() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (LOOKUP.matcher(Files.readString(file)).find()) {
                    found.add(file.getFileName().toString());
                }
            }
        }

        assertEquals(List.of(
                        // The load-time sweep, and the one wrapper CityFeature's route-4 check uses.
                        "AssetRegistries.java",
                        // The style of the chunk, blended from the styles its neighbours resolved.
                        "BuildingInfo.java",
                        // The world style's selectors, the preset alternative, the predefined city.
                        "City.java"),
                List.copyOf(found),
                "a new city-style lookup site appeared; see this test's javadoc before adding it here");
    }
}
