package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.format.palette.CompiledEntry;
import dev.krona.urbex.format.palette.TraitSet;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

class RuinDamageTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ruinsUseEachMarkersAuthoredMaterialInsteadOfAlwaysPlacingTheStylesIronBars() {
        CompiledEntry nextDamage = CompiledEntry.of(new CompiledEntry.Resolved[]{
                new CompiledEntry.Resolved(Blocks.IRON_BARS.defaultBlockState(), TraitSet.EMPTY)});
        for (BlockState target : List.of(Blocks.BRICKS.defaultBlockState(), Blocks.COBWEB.defaultBlockState())) {
            CompiledPalette.Placed damaged = new CompiledPalette.Placed(target, null, false, nextDamage);
            assertSame(damaged, Decorations.ruinBarReplacement(damaged,
                    () -> fail("an authored damage target must not resolve the generic bars marker")),
                    "ruins retain the full damaged node, including its own follow-up damage and rotation policy");
        }
        CompiledPalette.Placed styleBars = new CompiledPalette.Placed(
                Blocks.IRON_BARS.defaultBlockState(), null, true, nextDamage);
        assertSame(styleBars, Decorations.ruinBarReplacement(null, () -> styleBars),
                "a bar continuing from below retains its style marker's traits too");
    }
}
