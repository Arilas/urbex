package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.LandscapeType;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.IDimensionInfo;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BuildingInfo#effectiveRoadType} must return {@link RoadType#NONE} for a non-city chunk
 * without ever consulting the road field - {@code isCityRaw} gates it. This is exercised through
 * the void-chunk branch of {@code isCityRaw} (a floating-landscape profile whose heightmap reports
 * ground level 0), the cheapest way to force a non-city verdict without faking the whole
 * city-factor radius scan.
 * <p>
 * Bootstrapped because {@code ChunkCoord}/{@code Level.OVERWORLD} need the vanilla registries.
 */
class BuildingInfoTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void effectiveRoadTypeIsNoneForANonCityChunkWithoutConsultingTheRoadField() {
        UrbexProfile profile = new UrbexProfile("test-void", false);
        profile.LANDSCAPE_TYPE = LandscapeType.FLOATING;
        ChunkCoord coord = new ChunkCoord(Level.OVERWORLD, 3, 4);
        ChunkHeightmap groundLevelZero = new ChunkHeightmap(LandscapeType.FLOATING, 0);

        IDimensionInfo fakeProvider = (IDimensionInfo) Proxy.newProxyInstance(
                IDimensionInfo.class.getClassLoader(),
                new Class<?>[]{IDimensionInfo.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getProfile" -> profile;
                        case "getHeightmap" -> groundLevelZero;
                        case "toString" -> "fake-void-provider";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> {
                            if (method.getDeclaringClass() == Object.class) {
                                yield null;
                            }
                            // Anything past isVoidChunk - the road field, the city style, a
                            // neighbour probe - means the early return did not fire.
                            throw new AssertionError("Unexpected call to " + method
                                    + " on the fake IDimensionInfo - effectiveRoadType should have "
                                    + "returned NONE from isCityRaw's void-chunk check before reaching it");
                        }
                    };
                });

        assertEquals(RoadType.NONE, BuildingInfo.effectiveRoadType(coord, fakeProvider, profile),
                "a void (non-city) chunk must report no road, without ever reading the road field");
    }
}
