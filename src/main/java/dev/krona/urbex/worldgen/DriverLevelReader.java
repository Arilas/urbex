package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Function;

public final class DriverLevelReader {

    private DriverLevelReader() {
    }

    public static LevelReader overlay(LevelReader delegate, Function<BlockPos, BlockState> stateAt) {
        return (LevelReader) Proxy.newProxyInstance(
                LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")
                            && args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                        return stateAt.apply(pos);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
