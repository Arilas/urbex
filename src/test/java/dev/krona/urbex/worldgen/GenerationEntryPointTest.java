package dev.krona.urbex.worldgen;

import dev.krona.urbex.setup.Registration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing reaches generation through a process-global instance any more.
 * <p>
 * The carver-stage mixin used to call {@code Registration.cityFeature()} - a static slot holding
 * whichever {@code CityFeature} the last {@code Registration.init()} produced - and skip generation
 * silently when it answered null. That is the shape #129 is about: a static asked for the thing that
 * generates, when the level's own published {@link DimensionRuntime} already has everything the
 * answer would have been used for.
 * <p>
 * Asserted by reflection rather than by reading the mixin's source, because what matters is that
 * there is nothing to look up: a static entry point cannot be reached through an instance, and a
 * class with no {@code CityFeature} field has no instance to hand out.
 */
class GenerationEntryPointTest {

    @Test
    void theCarverStageEntryPointNeedsNoRegisteredInstance() throws Exception {
        Method entry = CityFeature.class.getDeclaredMethod("generateFromPipeline",
                net.minecraft.server.level.WorldGenRegion.class,
                net.minecraft.world.level.chunk.ChunkAccess.class);

        assertTrue(Modifier.isStatic(entry.getModifiers()),
                "generateFromPipeline holds no state and reads everything from the level's published "
                        + "runtime, so the carver hook must not need an instance to call it");
    }

    @Test
    void registrationKeepsNoFeatureInstanceToBeLookedUp() {
        for (Field field : Registration.class.getDeclaredFields()) {
            assertTrue(!CityFeature.class.isAssignableFrom(field.getType()),
                    "Registration." + field.getName() + " is a process-global CityFeature slot; "
                            + "generation is reached through GenerationSession, not through this");
        }
        assertEquals(0, Registration.class.getDeclaredFields().length,
                "Registration registers and holds nothing - a field here is state with the lifetime "
                        + "of the process rather than of a server");
    }
}
