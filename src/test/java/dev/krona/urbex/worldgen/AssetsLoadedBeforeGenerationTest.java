package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant this file exists to hold: <strong>no chunk generates against unloaded assets</strong>.
 * <p>
 * It has been rewritten twice, because each time the thing enforcing it moved. That history is the
 * point of keeping the file rather than the tests inside it:
 * <ol>
 * <li>Originally nothing enforced it. {@code AssetRegistries.load} was called from a level tick,
 *     while {@code prepareLevels()} generates its chunks before any tick fires, so spawn-area chunks
 *     were written undecorated and persisted that way - silently, because a missing stuff tag places
 *     nothing and says nothing.</li>
 * <li>Then the generation path enforced it: {@code CityFeature.getDimensionInfo} called
 *     {@code AssetRegistries.load} before any chunk work, because that same path was where the
 *     registries got reset.</li>
 * <li>Now <em>nothing enforces it, because nothing can violate it</em> (issue #128). Compiled assets
 *     live in an {@link AssetSnapshot}; a {@link DimensionRuntime} cannot be constructed without one;
 *     and generation does nothing without a published runtime. There is no order to get wrong,
 *     no latch to check and no reset to race.</li>
 * </ol>
 * What is left to test is that the structure really is that shape, which is what the two tests below
 * do. A test that asserted "a cobweb lands at (x, y, z)" would not do this job: it would pass on any
 * arrangement that eventually compiles the assets, including one that compiles them a tick too late.
 */
class AssetsLoadedBeforeGenerationTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    @AfterEach
    void closeSession() {
        GenerationSession session = GenerationSession.current();
        if (session != null) {
            GenerationSession.closeFor(session.owner());
        }
    }

    /**
     * A runtime cannot exist without compiled assets, because the compiler's output is a parameter of
     * its construction rather than something it goes and fetches. This is the whole invariant now, and
     * it is a signature rather than a check - which is why it is asserted structurally: a future edit
     * that reintroduced a no-snapshot path would leave every behavioural test green.
     */
    @Test
    void noRuntimeCanBeBuiltWithoutCompiledAssets() {
        List<Method> create = Arrays.stream(DimensionRuntime.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("create"))
                .toList();

        assertEquals(1, create.size(), "one way to build a runtime, not several");
        assertTrue(Arrays.asList(create.getFirst().getParameterTypes()).contains(AssetSnapshot.class),
                "DimensionRuntime.create must be handed the compiled assets: a runtime that could "
                        + "fetch or compile them itself is how a chunk generated against unloaded ones");
    }

    /**
     * And generation is gated on a published runtime, so the ordering above is the only way in.
     * <p>
     * Asserted through {@link GenerationSession#runtimeFor}, which is what
     * {@code CityFeature.generateFromPipeline} calls first and what every other entry point
     * (commands, spawn placement, structure suppression) shares. Its distant predecessor,
     * {@code CityFeature.getDimensionInfo}, would instead have <em>built</em> the missing state on the
     * spot, from a worldgen worker - which is why it had to load the registries itself.
     */
    @Test
    void generationRefusesALevelWithNoPublishedRuntime() {
        assertNull(GenerationSession.current(), "precondition: @BeforeEach closed the session");

        assertNull(GenerationSession.runtimeFor(levelThatThrowsIfTouched()),
                "with no session there is nothing to generate against - and the level is not even "
                        + "read, let alone used to build state on the spot");

        GenerationSession session = GenerationSession.openFor(new Object());
        assertEquals(0, session.loadedLevelCount(),
                "a fresh session generates nowhere until a level load compiles and publishes");
        assertNull(session.assets(),
                "and it has no assets until then either: they are compiled by the first level load, "
                        + "from that level's frozen registries");
    }

    /**
     * A level that throws for every call. {@code runtimeFor} must answer without touching it, which is
     * what makes "the lookup builds nothing" an assertion rather than a claim.
     */
    private static ServerLevelAccessorProxy levelThatThrowsIfTouched() {
        return (ServerLevelAccessorProxy) Proxy.newProxyInstance(
                ServerLevelAccessorProxy.class.getClassLoader(),
                new Class<?>[]{ServerLevelAccessorProxy.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "level-that-throws-if-touched";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    throw new AssertionError("runtimeFor read the level: " + method.getName());
                });
    }

    /** {@code WorldGenLevel} is the interface a {@code WorldGenRegion} presents to generation. */
    private interface ServerLevelAccessorProxy extends WorldGenLevel {
    }
}
