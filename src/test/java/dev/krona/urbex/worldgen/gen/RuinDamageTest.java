package dev.krona.urbex.worldgen.gen;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class RuinDamageTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ruinsUseEachMarkersAuthoredMaterialInsteadOfAlwaysPlacingTheStylesIronBars() {
        for (BlockState target : List.of(Blocks.BRICKS.defaultBlockState(), Blocks.COBWEB.defaultBlockState())) {
            assertEquals(target, Decorations.ruinBarReplacement(target,
                    () -> fail("an authored damage target must not resolve the generic bars marker")));
        }
        assertEquals(Blocks.IRON_BARS.defaultBlockState(),
                Decorations.ruinBarReplacement(null, () -> Blocks.IRON_BARS.defaultBlockState()),
                "a bar continuing from the block below still uses its style material");
    }
}
