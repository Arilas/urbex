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
 * Since part wiring became required, a reachable city style that does not resolve is a load error -
 * but only when something registers it as a root. {@code AssetCompiler} gathers the base and edge
 * roots from world-style selectors plus explicit predefined-city roots, and that source list is
 * maintained by hand: a new selection path that forgets to register there reverts silently to
 * failing from a worldgen worker, mid-generation, with the whole suite still green. Nothing in the
 * type system prevents it.
 * <p>
 * This pins the shape that is easiest to add by accident - a <em>new lookup site</em>. It cannot see
 * a new name <em>source</em> feeding an existing site, so it is a guard rather than a proof. If this
 * test fails, the fix is not merely to widen the list here: make the new root reachable from
 * {@code AssetCompiler.reachableCityStyles}, or validate it eagerly at the producer boundary.
 */
class CityStyleLookupSitesTest {

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * A lookup on the snapshot's city-style index, not a field reference: the four methods that
     * resolve a name. The shape changed with issue #128 - {@code AssetRegistries.CITYSTYLES.get(level,
     * name)} became {@code assets().cityStyles().get(name)} - so this pattern matches the accessor
     * rather than a static field.
     */
    private static final Pattern LOOKUP =
            Pattern.compile("cityStyles\\s*\\(\\s*\\)\\s*\\.\\s*(get|getOrThrow|getOrWarn|all)\\s*\\(");

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
                        // The style of the chunk, blended from the styles its neighbours resolved.
                        // Was ChunkPlan.java until issue #11 split candidate resolution out of it;
                        // the same lookup, in the class that now performs it.
                        "ChunkCandidates.java",
                        // The final member selected from world-style and predefined-city roots.
                        "City.java"),
                List.copyOf(found),
                "a new city-style lookup site appeared; see this test's javadoc before adding it here");
    }
}
