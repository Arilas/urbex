package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.Parts;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a marker carrying several traits amounts to, which version 1 got wrong for its whole lifetime.
 *
 * <p>{@code Parts.generatePart} used to test the four metadata fields in an {@code else if} chain, so a
 * marker carrying two of them applied the first and dropped the rest without a word. The list tests
 * check {@link Palette.Info#applied()}, which decides which traits run and in what order. The NBT
 * tests check their result in a real chunk: invoking both decorators is not enough if the second
 * overwrites the first one's data.</p>
 */
class MarkerTraitsComposeTest {

    private static final CompoundTag NBT = new CompoundTag();
    private static final BlockPos AT = new BlockPos(3, 64, 9);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aBlockEntityDecoratorKeepsTheMobWrittenByTheSameMarkersSpawner() throws Exception {
        ProtoChunk chunk = emptyChunk();
        CompoundTag spawner = spawnerNbt("minecraft:skeleton");
        chunk.setBlockEntityNbt(spawner);
        CompoundTag authored = new CompoundTag();
        authored.putInt("Delay", 40);
        CompoundTag originalAuthored = authored.copy();
        CompoundTag originalSpawner = spawner.copy();

        decorate(chunk, blockEntityType("mob_spawner"), authored, spawner);

        CompoundTag queued = chunk.getBlockEntityNbt(AT);
        assertNotNull(queued);
        assertEquals(spawner.get("SpawnData"), queued.get("SpawnData"),
                "block_entity must not erase the mob selected by the same marker's spawner trait");
        assertEquals(40, queued.getIntOr("Delay", -1));
        assertEquals(originalAuthored, authored, "the compiled palette's NBT is shared between placements");
        assertEquals(originalSpawner, spawner);
        assertNotSame(spawner, queued);
    }

    @Test
    void authoredNbtWinsConflictsWhilePositionAndTypeRemainLoaderSupplied() throws Exception {
        ProtoChunk chunk = emptyChunk();
        CompoundTag spawner = spawnerNbt("minecraft:skeleton");
        chunk.setBlockEntityNbt(spawner);
        CompoundTag authored = spawnerNbt("minecraft:zombie");
        authored.putInt("x", -1);
        authored.putInt("y", -1);
        authored.putInt("z", -1);
        authored.putString("id", "minecraft:barrel");
        CompoundTag originalAuthored = authored.copy();

        decorate(chunk, blockEntityType("mob_spawner"), authored, spawner);

        CompoundTag queued = chunk.getBlockEntityNbt(AT);
        assertNotNull(queued);
        assertEquals(authored.get("SpawnData"), queued.get("SpawnData"),
                "block_entity runs after spawner, so an explicitly authored mob takes precedence");
        assertEquals(AT.getX(), queued.getIntOr("x", -1));
        assertEquals(AT.getY(), queued.getIntOr("y", -1));
        assertEquals(AT.getZ(), queued.getIntOr("z", -1));
        assertEquals("minecraft:mob_spawner", queued.getStringOr("id", ""));
        assertEquals(originalAuthored, authored);
        assertEquals(spawnerNbt("minecraft:skeleton"), spawner);
    }

    @Test
    void aLaterPartDoesNotInheritNbtAlreadyQueuedAtItsPosition() throws Exception {
        for (BlockEntityType<?> type : List.of(blockEntityType("mob_spawner"), blockEntityType("barrel"))) {
            ProtoChunk chunk = emptyChunk();
            CompoundTag previous = spawnerNbt("minecraft:skeleton");
            previous.putInt("RequiredPlayerRange", 99);
            chunk.setBlockEntityNbt(previous);
            CompoundTag authored = new CompoundTag();
            authored.putInt("Delay", 40);

            decorate(chunk, type, authored, null);

            CompoundTag queued = chunk.getBlockEntityNbt(AT);
            assertNotNull(queued);
            assertFalse(queued.contains("SpawnData"), "a different marker's mob must not survive");
            assertFalse(queued.contains("RequiredPlayerRange"), "nor any other stale NBT");
            assertEquals(40, queued.getIntOr("Delay", -1));
            assertEquals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString(),
                    queued.getStringOr("id", ""));
        }
    }

    @Test
    void aSpawnerDecoratorsNbtIsNotCopiedIntoAnIncompatibleBlockEntity() throws Exception {
        ProtoChunk chunk = emptyChunk();
        CompoundTag spawner = spawnerNbt("minecraft:skeleton");
        chunk.setBlockEntityNbt(spawner);
        CompoundTag authored = new CompoundTag();
        authored.putString("CustomName", "Supplies");

        decorate(chunk, blockEntityType("barrel"), authored, spawner);

        CompoundTag queued = chunk.getBlockEntityNbt(AT);
        assertNotNull(queued);
        assertFalse(queued.contains("SpawnData"));
        assertEquals("minecraft:barrel", queued.getStringOr("id", ""));
        assertEquals("Supplies", queued.getStringOr("CustomName", ""));
    }

    @Rule("TRAIT.004")
    @Test
    void aMarkerCarryingBothALightAndAMobAppliesBothRatherThanOnlyTheFirstOne() {
        Palette.Info both = Palette.Info.of("urbex:easymobs", null, inPlaceLight(), null);

        assertEquals(List.of(MarkerTrait.LIGHT, MarkerTrait.SPAWNER), both.applied(),
                "a spawner beside a light must apply both traits; the else-if chain this replaces "
                        + "applied the light and lost the mob");
    }

    @Rule("TRAIT.004")
    @Rule("TRAIT.095")
    @Test
    void aMarkerCarryingAllFourTraitsAppliesAllFourInTheOrderTheSpecificationDefinesThem() {
        Palette.Info everything =
                Palette.Info.of("urbex:easymobs", "urbex:chest", inPlaceLight(), NBT);

        assertEquals(
                List.of(MarkerTrait.LIGHT, MarkerTrait.LOOT, MarkerTrait.SPAWNER,
                        MarkerTrait.BLOCK_ENTITY),
                everything.applied(),
                "TRAIT.095's phase order: selection first, then the three decorators");
    }

    /**
     * {@code TRAIT.095} and {@code TRAIT.096}, asserted over the list rather than over a world.
     *
     * <p>This is the assertion the phase order needs and a golden cannot give it. No marker in the
     * shipped pack carries two of the four traits, so inverting the order moves nothing measurable -
     * and the order <em>was</em> inverted for the length of this task, because this enum was written in
     * {@code 01-traits.md} §4's section order before {@code TRAIT.095} existed and was not brought into
     * line when it did.</p>
     *
     * <p>What inverting it costs, concretely: {@code Parts.handleBlockEntity} derives the block entity
     * type from the block it is handed and queues NBT for it. Run before the light, it queues against
     * the <em>lit</em> block, the light then swaps in its {@code unlit} replacement, and
     * {@code Parts.forgetBlockEntities} discards the orphaned data - silently. That would make
     * {@code TRAIT.044}'s accept case, a campfire whose unlit replacement is a barrel, a promise the
     * loader checks and the generator breaks.</p>
     */
    @Rule("TRAIT.095")
    @Rule("TRAIT.096")
    @Test
    void selectionIsAppliedBeforeDecorationSoNbtIsQueuedAgainstTheBlockThatSurvives() {
        Palette.Info info = Palette.Info.of(null, null, inPlaceLight(), NBT);

        assertEquals(List.of(MarkerTrait.LIGHT, MarkerTrait.BLOCK_ENTITY), info.applied());
        assertTrue(info.applied().indexOf(MarkerTrait.LIGHT)
                        < info.applied().indexOf(MarkerTrait.BLOCK_ENTITY),
                "TRAIT.096: a decoration trait applies to the state selection produced, so the light "
                        + "must have chosen the block before the NBT is attached to it");
    }

    @Test
    void everySelectionTraitPrecedesEveryDecorationTraitWhateverTheMarkerCarries() {
        List<MarkerTrait> all = Palette.Info.of("urbex:easymobs", "urbex:chest", inPlaceLight(), NBT)
                .applied();

        assertEquals(0, all.indexOf(MarkerTrait.LIGHT),
                "the only selection trait leads, and TRAIT.095 forbids anything preceding it: " + all);
    }

    @Test
    void aMarkerCarryingOneTraitAppliesExactlyThatOneSoNothingThatShipsTodayCanMove() {
        assertEquals(List.of(MarkerTrait.LIGHT),
                Palette.Info.of(null, null, inPlaceLight(), null).applied());
        assertEquals(List.of(MarkerTrait.SPAWNER),
                Palette.Info.of("urbex:easymobs", null, null, null).applied());
        assertEquals(List.of(MarkerTrait.LOOT),
                Palette.Info.of(null, "urbex:chest", null, null).applied());
        assertEquals(List.of(MarkerTrait.BLOCK_ENTITY),
                Palette.Info.of(null, null, null, NBT).applied());
    }

    @Test
    void anEmptyLootOrMobStringAppliesNothingExactlyAsTheChainItReplacesDidNot() {
        Palette.Info empty = Palette.Info.of("", "", null, null);

        assertTrue(empty.applied().isEmpty(),
                "version 1 guarded both fields on isEmpty() as well as on null, and a marker that is "
                        + "'special' enough to skip the block's other handling but applies nothing is "
                        + "behaviour this change deliberately preserves rather than fixes");
        assertTrue(empty.isSpecial(),
                "isSpecial still answers the same way, which is what keeps the surrounding branch "
                        + "unchanged");
    }

    @Test
    void aMarkerWithNoMetadataCarriesNoTraitsAtAll() {
        assertTrue(Palette.Info.of(null, null, null, null).applied().isEmpty());
    }

    @Test
    void theAppliedListIsImmutableSoNoGenerationPassCanEditWhatAMarkerCarries() {
        List<MarkerTrait> applied = Palette.Info.of("urbex:easymobs", null, null, null).applied();

        assertThrows(UnsupportedOperationException.class, () -> applied.add(MarkerTrait.LIGHT));
    }

    /** An in-place light source: no socket pool, and air behind it when the light is off. */
    private static LightSource inPlaceLight() {
        return new LightSource(null, BlockChoice.AIR);
    }

    /** Call the actual queue writer without constructing an unrelated city plan or server. */
    private static void decorate(ProtoChunk chunk, BlockEntityType<?> type,
                                 CompoundTag authored, CompoundTag spawnerNbt) throws Exception {
        Method writer = Parts.class.getDeclaredMethod("queueBlockEntityNbt", ChunkAccess.class,
                BlockPos.class, BlockEntityType.class, CompoundTag.class, CompoundTag.class);
        writer.setAccessible(true);
        writer.invoke(null, chunk, AT, type, authored, spawnerNbt);
    }

    private static BlockEntityType<?> blockEntityType(String name) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.parse("minecraft:" + name));
    }

    private static CompoundTag spawnerNbt(String mob) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", mob);
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", AT.getX());
        tag.putInt("y", AT.getY());
        tag.putInt("z", AT.getZ());
        tag.putString("id", "minecraft:mob_spawner");
        tag.put("SpawnData", SpawnData.CODEC.encodeStart(NbtOps.INSTANCE,
                new SpawnData(entity, Optional.empty(), Optional.empty())).getOrThrow());
        return tag;
    }

    private static ProtoChunk emptyChunk() {
        Holder<Biome> biome = Holder.direct(new Biome.BiomeBuilder()
                .temperature(0.5f).downfall(0.5f)
                .specialEffects(new BiomeSpecialEffects(0, Optional.empty(), Optional.empty(),
                        Optional.empty(), BiomeSpecialEffects.GrassColorModifier.NONE))
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY)
                .build());
        IdMapper<Holder<Biome>> biomeIds = new IdMapper<>();
        biomeIds.add(biome);
        PalettedContainerFactory containers = new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                Strategy.createForBiomes(biomeIds), biome, null);
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY,
                LevelHeightAccessor.create(-64, 384), containers, null);
    }
}
