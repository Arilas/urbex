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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>
 * The other half of this file is where issue #91 draws its line: a block this game does not have is
 * absent (dropped from a weighted list, air where there is no list), and a block it does have
 * written with a property it does not is still a load error.
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
     * A block this game does not have is {@code null} from {@link Tools#resolveState} and air from
     * {@link Tools#stringToState} — never an exception, whether or not the string carries
     * properties. The property-carrying form is the one that used to throw, and since compilation
     * moved to load time that threw took the whole world with it rather than one chunk (issue #91).
     */
    @Test
    void anAbsentBlockIsNullRatherThanAThrow() {
        assertNull(Tools.resolveState("somemod:no_such_block", BuiltInRegistries.BLOCK, OWNER));
        assertNull(Tools.resolveState("somemod:no_such_block[facing=north]", BuiltInRegistries.BLOCK, OWNER));

        assertSame(Blocks.AIR.defaultBlockState(),
                Tools.stringToState("somemod:no_such_block", BuiltInRegistries.BLOCK, OWNER));
        assertSame(Blocks.AIR.defaultBlockState(),
                Tools.stringToState("somemod:no_such_block[facing=north]", BuiltInRegistries.BLOCK, OWNER));
    }

    /**
     * The line #91 draws, and the reason it is drawn at the block id: a property expression this
     * game does not have, on a block it <em>does</em>, is a mistake in the file. No amount of
     * installing mods fixes it, so it stays a load error — which is what keeps {@code LightPool}'s
     * candidate diagnostics working.
     */
    @Test
    void aBadPropertyOnAKnownBlockIsStillAnError() {
        assertThrows(RuntimeException.class,
                () -> Tools.resolveState("minecraft:torch[not_a_property=true]", BuiltInRegistries.BLOCK, OWNER));
    }

    /**
     * A string that is not even a legal id resolves as absent rather than throwing. The bundled pack
     * shipped one — {@code minecraft:red_sandstone@2}, a 1.12 {@code name@meta} string — and because
     * {@code Identifier.parse} threw on it from inside {@code Palette}'s constructor, it took the
     * world load with it.
     */
    @Test
    void anIdThatIsNotEvenLegalResolvesAsAbsent() {
        assertNull(Tools.resolveState("minecraft:red_sandstone@2", BuiltInRegistries.BLOCK, OWNER));
    }

    /**
     * The boundary itself: a lookup that does not have the block answers "not here", even though the
     * process-global registry three lines away does have it. Before the lookup became a parameter
     * there was no way to write this test, because there was no way to say which registry to ask.
     */
    @Test
    void resolutionAnswersFromTheLookupItIsGivenNotAGlobalOne() {
        HolderLookup<Block> onlyStone = registryOf("stone", Blocks.STONE);

        assertSame(Blocks.STONE.defaultBlockState(),
                Tools.stringToState("minecraft:stone", onlyStone, OWNER));
        assertNull(Tools.resolveState("minecraft:diamond_block", onlyStone, OWNER),
                "BuiltInRegistries has diamond_block; the lookup handed in does not");
        assertNull(Tools.resolveState("minecraft:oak_stairs[facing=east]", onlyStone, OWNER),
                "and the property-carrying form asks the same lookup for its block");
    }

    private static HolderLookup<Block> registryOf(String path, Block block) {
        MappedRegistry<Block> registry = new MappedRegistry<>(Registries.BLOCK, Lifecycle.stable());
        registry.register(ResourceKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(path)),
                block, RegistrationInfo.BUILT_IN);
        return registry.freeze();
    }
}
