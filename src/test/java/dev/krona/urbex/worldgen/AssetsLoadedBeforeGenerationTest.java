package dev.krona.urbex.worldgen;

import com.mojang.serialization.Lifecycle;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.setup.ServerEventHandlers;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.StuffObject;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsRE;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant this file exists to hold: <strong>no chunk generates against unloaded assets</strong>.
 * <p>
 * For the life of the project nothing asserted it, and nothing had to: {@code AssetRegistries.load}
 * was called only from {@code ServerTickEvents.END_LEVEL_TICK}, while {@code prepareLevels()} -
 * "Preparing spawn area" - generates its chunks inside {@code initServer()}, before any tick fires.
 * Those chunks were written with no decoration and persisted that way. The failure is entirely
 * silent: {@code Stuff.generateStuff} looks a tag up, gets nothing back, and places nothing.
 * <p>
 * Its owner has changed (issue #125) and so has the shape of the argument, which is the reason this
 * file was rewritten rather than deleted. The guarantee used to live on the generation path itself -
 * {@code CityFeature.getDimensionInfo} called {@code AssetRegistries.load} before any chunk work -
 * because that same path was where the registries were <em>reset</em>. Nothing resets them from a
 * worker any more, and enforcement is now two statements that together leave no gap:
 * <ol>
 * <li>{@link #theLevelLoadHandlerLoadsAssetsBeforeItReachesTheLevel()} - the level-load handler
 *     loads the registries before it touches the level it was handed, so a level that has loaded has
 *     loaded assets. A plain lifecycle event was not enough on its own before; it is now, because of
 *     the second half.</li>
 * <li>{@link #generationRefusesALevelWithNoPublishedRuntime()} - generation reads a published
 *     runtime and does nothing without one. There is no path left that generates first and loads
 *     afterwards, which is what the generation-path load was compensating for.</li>
 * </ol>
 * A test that asserted "a cobweb lands at (x, y, z)" would not do this job: it would pass on any
 * arrangement that eventually loads the registries, including one that loads them a tick too late.
 */
class AssetsLoadedBeforeGenerationTest {

    /** Thrown by the fake level the first time anything past {@code registryAccess()} is asked. */
    private static final class ReachedTheLevel extends RuntimeException {
        ReachedTheLevel(String method) {
            super(method);
        }
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    @AfterEach
    void clearRegistriesAndSession() {
        GenerationSession session = GenerationSession.current();
        if (session != null) {
            GenerationSession.closeFor(session.owner());
        }
        AssetRegistries.reset();
    }

    /**
     * The eager half, and the half a deletion would take away silently: the registries are resolved
     * while the world is loading, so a broken pack refuses the world naming the file instead of
     * throwing from a worldgen worker mid-generation.
     * <p>
     * The ordering within the handler is what is actually pinned here, and the null level is what
     * pins it - it plays the part the throwing proxy plays below. {@code GenerationSession.load}
     * loads the assets and then builds the level's runtime, which reads the level; handed nothing,
     * that second step fails. So the exception says the handler went on to reach the level, and the
     * latched registries say the load happened before it did. Move the load after the runtime build
     * and the assertion below fails.
     * <p>
     * {@code AssetRegistries.load} latching on a null level is exactly the no-op latch
     * {@code loadPredefinedStuff} deliberately refuses (issue #67), and none is needed here: nothing
     * in production ever calls it with a null level. If it ever gains that guard, this test starts
     * failing while the wiring it pins is intact - the fix then is to give this test a level, not to
     * delete it.
     */
    @Test
    void theLevelLoadHandlerLoadsAssetsBeforeItReachesTheLevel() {
        assertFalse(AssetRegistries.isLoaded(), "precondition: @BeforeEach reset the registries");

        ServerEventHandlers.register();
        assertThrows(NullPointerException.class,
                () -> ServerLevelEvents.LOAD.invoker().onLevelLoad(null, null),
                "the handler must go on to build the level's runtime, which reads the level");

        assertTrue(AssetRegistries.isLoaded(),
                "ServerLevelEvents.LOAD must load the asset registries before it reads the level - "
                        + "it is what keeps the eager validation a load-time check, and what makes "
                        + "'a loaded level has loaded assets' true for every level");
    }

    /**
     * The other half. Generation is gated on a published runtime, and only the level-load handler
     * above publishes one - so the ordering it guarantees is the only way into generation.
     * <p>
     * Asserted through {@link GenerationSession#planningFor}, which is what
     * {@code CityFeature.generateFromPipeline} calls first and what every other entry point
     * (commands, spawn placement, structure suppression) shares. Its predecessor,
     * {@code CityFeature.getDimensionInfo}, would instead have <em>built</em> the missing state on
     * the spot, from a worldgen worker, which is why it had to load the registries itself.
     */
    @Test
    void generationRefusesALevelWithNoPublishedRuntime() {
        assertNull(GenerationSession.current(), "precondition: @BeforeEach closed the session");

        assertNull(GenerationSession.runtimeFor(levelThatOnlyAnswersRegistryAccess()),
                "with no session there is nothing to generate against - and the level is not even "
                        + "read, let alone used to build state on the spot as getDimensionInfo did");

        GenerationSession session = GenerationSession.open(null);
        assertEquals(0, session.loadedLevelCount(),
                "a fresh session generates nowhere until a level load publishes a runtime");
    }

    /**
     * The loader populates the index it is asked for. Split out from the ordering test above, which
     * a null level cannot cover: this one hands the loader a level that answers exactly the one call
     * it needs and throws for everything else, so it also pins that the load reads nothing more.
     */
    @Test
    void theLoaderPopulatesTheStuffIndexFromNothingButRegistryAccess() {
        assertNull(AssetRegistries.stuffIndex().byTag().get("rubble"),
                "precondition: nothing is filed under the tag before anything loads");

        AssetRegistries.load(levelThatOnlyAnswersRegistryAccess());

        List<StuffObject> rubble = AssetRegistries.stuffIndex().byTag().get("rubble");
        assertNotNull(rubble, "the stuff tag index must be populated before generation reads the "
                + "level - an empty index places no decoration and says nothing about it");
        assertEquals(List.of("urbex:cobweb"), rubble.stream().map(StuffObject::getName).toList());
    }

    @Test
    void theTagIndexIsPublishedWholeAndCannotBeMutatedAfterwards() throws Exception {
        Field field = AssetRegistries.class.getDeclaredField("stuffIndex");
        assertTrue(Modifier.isVolatile(field.getModifiers()),
                "the index is swapped in one write and read without a lock; without volatile a "
                        + "worker can miss the write entirely");
        assertFalse(Modifier.isPublic(field.getModifiers()),
                "readers must go through stuffIndex(), which re-reads the volatile field - a direct "
                        + "handle on the field would hand out a stale index after a reset");

        AssetRegistries.load(levelThatOnlyAnswersRegistryAccess());

        field.setAccessible(true);
        Map<String, List<StuffObject>> published = ((AssetRegistries.StuffIndex) field.get(null)).byTag();
        assertThrows(UnsupportedOperationException.class, () -> published.put("late", List.of()),
                "the published map must be finished before it is published - the putAll this "
                        + "replaced let a worker see some tags and not others");
        assertThrows(UnsupportedOperationException.class,
                () -> AssetRegistries.stuffIndex().byTag().get("rubble").add(null),
                "each tag's list is immutable for the same reason");
    }

    /**
     * A level that hands over a registry access carrying one tagged stuff entry, and throws for
     * every other call - {@code getLevel()} included, which is what makes "the lookup did not even
     * read the level" an assertion rather than a claim. {@code AssetRegistries.load} needs nothing
     * else.
     */
    private static WorldGenLevel levelThatOnlyAnswersRegistryAccess() {
        RegistryAccess access = registriesWithOneRubbleStuff();
        return (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class},
                (proxy, method, args) -> {
                    if ("registryAccess".equals(method.getName())) {
                        return access;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "level-that-only-answers-registryAccess";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    throw new ReachedTheLevel(method.getName());
                });
    }

    /**
     * Every registry {@code AssetRegistries.load} touches - the ten it walks plus the three
     * {@code loadReachableCityStyles} enumerates - all empty but {@code stuff}, which holds a single
     * {@code urbex:cobweb} tagged {@code rubble}.
     */
    private static RegistryAccess registriesWithOneRubbleStuff() {
        MappedRegistry<StuffSettingsRE> stuff = new MappedRegistry<>(
                CustomRegistries.STUFF_REGISTRY_KEY, Lifecycle.stable());
        Identifier cobweb = Identifier.fromNamespaceAndPath("urbex", "cobweb");
        stuff.register(ResourceKey.create(CustomRegistries.STUFF_REGISTRY_KEY, cobweb),
                new StuffSettingsRE(Optional.empty(),
                        Optional.of(new Mergeable<>(true, List.of("rubble"))),
                        Optional.of("\\"),
                        Optional.empty(), Optional.empty(),
                        Optional.of(1), Optional.of(1), Optional.of(1),
                        Optional.of(true), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                RegistrationInfo.BUILT_IN);

        return new RegistryAccess.ImmutableRegistryAccess(List.of(
                empty(CustomRegistries.VARIANTS_REGISTRY_KEY),
                empty(CustomRegistries.PALETTE_REGISTRY_KEY),
                empty(CustomRegistries.CONDITIONS_REGISTRY_KEY),
                empty(CustomRegistries.STYLE_REGISTRY_KEY),
                empty(CustomRegistries.PART_REGISTRY_KEY),
                empty(CustomRegistries.BUILDING_REGISTRY_KEY),
                empty(CustomRegistries.MULTIBUILDINGS_REGISTRY_KEY),
                empty(CustomRegistries.SCATTERED_REGISTRY_KEY),
                empty(CustomRegistries.WORLDSTYLES_REGISTRY_KEY),
                // load() reaches these two through loadReachableCityStyles: it enumerates the
                // preset and predefined-city registries to find every city style something can
                // select. Empty here, so nothing resolves - but lookupOrThrow needs them present.
                empty(CustomRegistries.CITYSTYLES_REGISTRY_KEY),
                empty(CustomRegistries.PRESET_REGISTRY_KEY),
                empty(CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY),
                stuff.freeze())).freeze();
    }

    private static <T> Registry<T> empty(ResourceKey<Registry<T>> key) {
        return new MappedRegistry<>(key, Lifecycle.stable()).freeze();
    }
}
