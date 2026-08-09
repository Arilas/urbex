package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverLevelReaderTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void pendingDriverStateParticipatesInRealSurvivalChecks() {
        BlockPos marker = new BlockPos(5, 70, 5);
        BlockPos support = marker.below();
        LevelReader delegate = emptyLevel();
        Map<BlockPos, BlockState> pending = Map.of(support, Blocks.STONE.defaultBlockState());

        LevelReader overlay = DriverLevelReader.overlay(delegate,
                pos -> pending.getOrDefault(pos, delegate.getBlockState(pos)));

        assertEquals(Blocks.STONE.defaultBlockState(), overlay.getBlockState(support));
        assertTrue(Blocks.TORCH.defaultBlockState().canSurvive(overlay, marker));
    }

    private static LevelReader emptyLevel() {
        return (LevelReader) Proxy.newProxyInstance(
                LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) {
                        return Blocks.AIR.defaultBlockState();
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "empty-level";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == float.class) {
                        return 0.0f;
                    }
                    if (type == double.class) {
                        return 0.0d;
                    }
                    return null;
                });
    }
}
