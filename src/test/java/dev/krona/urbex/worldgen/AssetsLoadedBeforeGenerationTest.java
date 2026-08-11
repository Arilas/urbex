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
 * The invariant Task 5c exists to hold: <strong>no chunk generates against unloaded assets</strong>.
 * <p>
 * For the life of the project nothing asserted it, and nothing had to: {@code AssetRegistries.load}
 * was called only from {@code ServerTickEvents.END_LEVEL_TICK}, while {@code prepareLevels()} -
 * "Preparing spawn area" - generates its chunks inside {@code initServer()}, before any tick fires.
 * Those chunks were written with no decoration and persisted that way. The failure is entirely
 * silent: {@code Stuff.generateStuff} looks a tag up, gets nothing back, and places nothing.
 * <p>
 * {@link #generationPathLoadsTheStuffIndexBeforeItTouchesTheLevel()} is the guard. It drives
 * {@link CityFeature#getDimensionInfo}, which every generation path reaches first
 * ({@code CarverHookMixin} -> {@code generateFromPipeline} -> {@code getDimensionInfo}, before
 * {@code CityGenerator.generate} is called), through a level that throws the moment anything but
 * {@code registryAccess()} is asked of it. If the load ever moves back off that path, the stuff
 * index is still empty when the level is reached and the test fails.
 * <p>
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
    void clearRegistries() {
        AssetRegistries.reset();
    }

    @Test
    void generationPathLoadsTheStuffIndexBeforeItTouchesTheLevel() {
        assertNull(AssetRegistries.stuffIndex().byTag().get("rubble"),
                "precondition: nothing is filed under the tag before anything loads");

        WorldGenLevel level = levelThatOnlyAnswersRegistryAccess();
        CityFeature feature = new CityFeature();

        // getDimensionInfo cannot complete against this level - getLevel() throws. What matters is
        // how far it got first: the assets have to be loaded before that point, not after it.
        assertThrows(ReachedTheLevel.class, () -> feature.getDimensionInfo(level));

        List<StuffObject> rubble = AssetRegistries.stuffIndex().byTag().get("rubble");
        assertNotNull(rubble, "the stuff tag index must be populated before generation reads the "
                + "level - an empty index places no decoration and says nothing about it");
        assertEquals(List.of("urbex:cobweb"), rubble.stream().map(StuffObject::getName).toList());
    }

    /**
     * The other half of the argument, and the half a deletion would take away silently.
     * <p>
     * The test above pins the generation-path load, which is what guarantees a chunk never generates
     * against unloaded assets. It says nothing about the <em>eager</em> load, and without that one
     * Task 4a's rule reverts from "a bad asset fails the world load, naming the file" to "a bad
     * asset throws from a worldgen worker mid-generation" - with every test still green, because
     * generation would go on loading the registries itself. So the registration is pinned here
     * directly: register the server events, fire {@code ServerLevelEvents.LOAD}, and require that
     * something on it loaded the registries.
     * <p>
     * A null level is enough to tell the two apart. {@code RegistryAssetRegistry.loadAll} returns
     * immediately for one, so nothing is actually resolved, but {@code load} still latches - and if
     * the registration line is gone, the invoker has no callbacks, nothing runs, and the latch stays
     * false. The registration is left on the static event afterwards; nothing else in the suite
     * invokes it.
     * <p>
     * <b>Why that latch is the signal, and what would break it.</b> {@code load} latching on a null
     * level is exactly the no-op latch {@code loadPredefinedStuff} deliberately refuses (issue #67 -
     * see the null guard in {@code AssetRegistries.loadPredefinedStuff}), because for predefined
     * cities a real level arriving later still has to be able to load. {@code load} has no such
     * guard, and none is needed in production: nothing ever calls it with a null level, since both
     * call sites hand it a level they already hold. But if {@code load} ever gains that guard, this
     * test starts failing while the registration it is pinning is perfectly intact. If that happens,
     * the fix is to give this test a level rather than to delete it.
     */
    @Test
    void theEagerLoadIsWiredToTheLevelLoadEvent() {
        assertFalse(AssetRegistries.isLoaded(), "precondition: @BeforeEach reset the registries");

        ServerEventHandlers.register();
        ServerLevelEvents.LOAD.invoker().onLevelLoad(null, null);

        assertTrue(AssetRegistries.isLoaded(),
                "ServerLevelEvents.LOAD must load the asset registries - it is what keeps the "
                        + "eager validation a load-time check instead of a mid-generation one");
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
     * every other call. {@code AssetRegistries.load} needs nothing else; {@code getDimensionInfo}
     * needs {@code getLevel()}, which is exactly the line this test wants the load to precede.
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
