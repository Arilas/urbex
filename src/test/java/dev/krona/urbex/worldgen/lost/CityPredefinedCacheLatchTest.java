package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the "Important" review finding on Task 1: City's four static
 * ready-flags (predefinedCityMapReady, predefinedBuildingMapReady, occupiedBuildingReady,
 * occupiedStreetReady) must not latch to true off a preview call (a null level/world) - doing so
 * would permanently stop a later real dimension from ever loading its predefined content, exactly
 * the #67-style bug that the old AssetRegistries.loadedPredefined latch also had.
 * <p>
 * AssetRegistries.loadedPredefined is forced true in {@link #resetState()} so that every call here
 * reaches City's own guard directly, without needing real datapack-registered
 * predefined-city content just to exercise the flag logic in isolation.
 * <p>
 * Bootstrapped ({@link Bootstrap#bootStrap()}) because {@code ChunkCoord}/{@code Level.OVERWORLD}
 * and the vanilla registries City touches need it; done in {@code @BeforeAll} rather than a static
 * field so it runs before any class - including this one - references {@code Level}.
 */
class CityPredefinedCacheLatchTest {

    private ChunkCoord coord;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void resetState() throws Exception {
        coord = new ChunkCoord(Level.OVERWORLD, 3, 4);
        City.cleanPredefinedCache();
    }

    @Test
    void nullLevelDoesNotLatchPredefinedCityMapReady() throws Exception {
        City.getPredefinedCity(fakeProvider(null), coord);
        assertFalse(getStaticBoolean(City.class, "predefinedCityMapReady"),
                "a preview call (a provider with no level) must not latch the ready flag");

        City.getPredefinedCity(fakeProvider(harmlessLevel()), coord);
        assertTrue(getStaticBoolean(City.class, "predefinedCityMapReady"),
                "a real level must still be able to latch it afterwards");
    }

    @Test
    void nullLevelDoesNotLatchPredefinedBuildingMapReady() throws Exception {
        City.getPredefinedBuildingAtTopLeft(fakeProvider(null), coord);
        assertFalse(getStaticBoolean(City.class, "predefinedBuildingMapReady"),
                "a preview call (a provider with no level) must not latch the ready flag");

        City.getPredefinedBuildingAtTopLeft(fakeProvider(harmlessLevel()), coord);
        assertTrue(getStaticBoolean(City.class, "predefinedBuildingMapReady"),
                "a real level must still be able to latch it afterwards");
    }

    @Test
    void nullLevelDoesNotLatchPredefinedStreetMapReady() throws Exception {
        City.getPredefinedStreetAt(fakeProvider(null), coord);
        assertFalse(getStaticBoolean(City.class, "predefinedStreetMapReady"),
                "a preview call (a provider with no level) must not latch the ready flag");

        City.getPredefinedStreetAt(fakeProvider(harmlessLevel()), coord);
        assertTrue(getStaticBoolean(City.class, "predefinedStreetMapReady"),
                "a real level must still be able to latch it afterwards");
    }

    @Test
    void nullWorldDoesNotLatchOccupiedBuildingOrStreetReady() throws Exception {
        City.isChunkOccupied(fakeProvider(null), coord);
        assertFalse(getStaticBoolean(City.class, "occupiedBuildingReady"),
                "a preview call (null world) must not latch the ready flag");
        assertFalse(getStaticBoolean(City.class, "occupiedStreetReady"),
                "a preview call (null world) must not latch the ready flag");

        City.isChunkOccupied(fakeProvider(harmlessLevel()), coord);
        assertTrue(getStaticBoolean(City.class, "occupiedBuildingReady"),
                "a real world must still be able to latch it afterwards");
        assertTrue(getStaticBoolean(City.class, "occupiedStreetReady"),
                "a real world must still be able to latch it afterwards");
    }

    private static void setStaticBoolean(Class<?> owner, String field, boolean value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static boolean getStaticBoolean(Class<?> owner, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    /**
     * A non-null level that is never actually invoked: City's guarded bodies only read the (empty)
     * predefined-city index off the provider's snapshot, so only the level's non-nullness matters.
     */
    private static WorldGenLevel harmlessLevel() {
        return (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class},
                (proxy, method, args) -> {
                    throw new AssertionError("Unexpected call to " + method + " - City should not need "
                            + "anything from the level itself, only whether it has one");
                });
    }

    private static IDimensionInfo fakeProvider(WorldGenLevel world) {
        return (IDimensionInfo) Proxy.newProxyInstance(
                IDimensionInfo.class.getClassLoader(),
                new Class<?>[]{IDimensionInfo.class},
                (proxy, method, args) -> {
                    if ("getWorld".equals(method.getName())) {
                        return world;
                    }
                    if ("assets".equals(method.getName())) {
                        return AssetSnapshot.empty();
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "fake-provider";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    throw new AssertionError("Unexpected call to " + method + " on the fake IDimensionInfo");
                });
    }
}
