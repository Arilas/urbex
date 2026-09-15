package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The block-entity capabilities shared by palette validation and marker decoration. */
public final class MarkerCapabilities {

    // These are engine registry states and types, never palette, asset or compilation data. Factories
    // are inspected once per state; generation does not construct a throwaway entity at every write.
    private static final Map<BlockState, Capabilities> STATES = new ConcurrentHashMap<>();
    private static final Capabilities NONE = new Capabilities(null, false, false);

    private MarkerCapabilities() {
    }

    public static boolean supportsLoot(BlockState state) {
        return capabilities(state).loot();
    }

    public static boolean supportsSpawner(BlockState state) {
        return capabilities(state).spawner();
    }

    @Nullable
    public static BlockEntityType<?> blockEntityType(BlockState state) {
        return capabilities(state).type();
    }

    private static Capabilities capabilities(BlockState state) {
        if (!state.hasBlockEntity()) {
            return NONE;
        }
        return STATES.computeIfAbsent(state, MarkerCapabilities::inspect);
    }

    private static Capabilities inspect(BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock block)) {
            return NONE;
        }
        BlockEntity entity = block.newBlockEntity(BlockPos.ZERO, state);
        if (entity == null || !entity.getType().isValid(state)) {
            return NONE;
        }
        return new Capabilities(entity.getType(), entity instanceof RandomizableContainerBlockEntity,
                entity instanceof SpawnerBlockEntity);
    }

    private record Capabilities(@Nullable BlockEntityType<?> type, boolean loot, boolean spawner) {
    }
}
