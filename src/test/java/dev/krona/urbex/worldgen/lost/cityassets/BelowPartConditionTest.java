package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionTest;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BelowPartConditionTest {

    private static Predicate<ConditionContext> testWith(Set<String> belowPart, Set<String> inpart) {
        ConditionTest test = new ConditionTest(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.ofNullable(belowPart), Optional.ofNullable(inpart),
                Optional.empty(), Optional.empty(), Optional.empty());
        return ConditionContext.parseTest(test);
    }

    private static ConditionContext context(String part, String belowPart) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        ChunkCoord coord = new ChunkCoord(dimension, 0, 0);
        return new ConditionContext(0, 1, 0, 5, part, belowPart, "somebuilding", coord) {
            @Override
            public boolean isBuilding() {
                return true;
            }

            @Override
            public boolean isSphere() {
                return false;
            }

            @Override
            public Identifier getBiome() {
                return Identifier.fromNamespaceAndPath("minecraft", "plains");
            }
        };
    }

    @Test
    public void belowpartMatchesThePartBelow() {
        Predicate<ConditionContext> pred = testWith(Set.of("top_floor"), null);
        assertTrue(pred.test(context("roof", "top_floor")));
    }

    @Test
    public void belowpartDoesNotMatchTheCurrentPart() {
        // The old implementation read context.getPart(), making belowpart an exact duplicate
        // of inpart (issue #58): this predicate would wrongly match a part named "roof" that
        // sits on anything.
        Predicate<ConditionContext> pred = testWith(Set.of("roof"), null);
        assertFalse(pred.test(context("roof", "top_floor")));
    }

    @Test
    public void inpartStillMatchesTheCurrentPart() {
        Predicate<ConditionContext> pred = testWith(null, Set.of("roof"));
        assertTrue(pred.test(context("roof", "top_floor")));
    }
}
