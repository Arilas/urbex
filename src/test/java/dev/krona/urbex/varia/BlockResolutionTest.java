package dev.krona.urbex.varia;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What a datapack block string resolves to, and — the part this issue is about — <em>what it
 * resolves against</em>.
 * <p>
 * {@code Tools.stringToState} used to choose its own registry: the overworld's block lookup if
 * {@code ServerAccess.getServer()} happened to be populated, {@code BuiltInRegistries.BLOCK} if it
 * was not. Both are the same registry, so nothing was ever wrong — but "which registry did this
 * parse against" was answered by timing rather than by the caller, and asset compilation had no say
 * in it (issues #60, #128). It is a parameter now, handed down by {@code AssetCompiler} from the
 * world being loaded, and {@link #resolutionAnswersFromTheLookupItIsGivenNotAGlobalOne} is what
 * makes that a fact rather than a claim.
 */
class BlockResolutionTest {

    private static final Identifier OWNER = Identifier.fromNamespaceAndPath("urbex", "test_palette");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aPlainIdResolvesToItsDefaultState() {
        assertSame(Blocks.STONE.defaultBlockState(),
                Tools.stringToState("minecraft:stone", BuiltInRegistries.BLOCK, OWNER));
    }

    @Test
    void propertiesAreParsedRatherThanIgnored() {
        BlockState state = Tools.stringToState("minecraft:oak_stairs[facing=east]",
                BuiltInRegistries.BLOCK, OWNER);

        assertSame(Blocks.OAK_STAIRS, state.getBlock());
        assertEquals(net.minecraft.core.Direction.EAST, state.getValue(StairBlock.FACING));
    }

    /**
     * The behaviour #91 exists to change, pinned here so that when it does change this test is what
     * has to be edited — rather than the change being discovered from a moved digest.
     * <p>
     * An id this Minecraft version does not have generates as air, silently as far as the world is
     * concerned and with one warning in the log. That is how {@code minecraft:chain} (renamed
     * {@code minecraft:iron_chain} in 26.x) made the whole {@code urbex:chains} decoration invisible.
     */
    @Test
    void anUnknownIdResolvesToAirRatherThanThrowing() {
        assertSame(Blocks.AIR.defaultBlockState(),
                Tools.stringToState("somemod:no_such_block", BuiltInRegistries.BLOCK, OWNER));
    }

    /**
     * The boundary itself: a lookup that does not have the block answers "not here", even though the
     * process-global registry three lines away does have it. Before this change there was no way to
     * write this test, because there was no way to say which registry to ask.
     */
    @Test
    void resolutionAnswersFromTheLookupItIsGivenNotAGlobalOne() {
        HolderLookup<Block> onlyStone = registryOf("stone", Blocks.STONE);

        assertSame(Blocks.STONE.defaultBlockState(),
                Tools.stringToState("minecraft:stone", onlyStone, OWNER));
        assertSame(Blocks.AIR.defaultBlockState(),
                Tools.stringToState("minecraft:diamond_block", onlyStone, OWNER),
                "BuiltInRegistries has diamond_block; the lookup handed in does not");
        assertThrows(RuntimeException.class,
                () -> Tools.stringToState("minecraft:oak_stairs[facing=east]", onlyStone, OWNER),
                "and the property-parsing path asks the same lookup");
    }

    private static HolderLookup<Block> registryOf(String path, Block block) {
        MappedRegistry<Block> registry = new MappedRegistry<>(Registries.BLOCK, Lifecycle.stable());
        registry.register(ResourceKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(path)),
                block, RegistrationInfo.BUILT_IN);
        return registry.freeze();
    }
}
