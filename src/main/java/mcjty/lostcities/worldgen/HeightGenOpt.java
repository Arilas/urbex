package mcjty.lostcities.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.apache.commons.lang3.mutable.MutableObject;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.function.Predicate;

public class HeightGenOpt {

    public static int getBaseHeight(NoiseBasedChunkGenerator generator, int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState rnd) {
        return iterateNoiseColumn(generator, generator.generatorSettings().get(), level, rnd, x, z, null, type.isOpaque()).orElse(level.getMinBuildHeight());
    }

    private static Aquifer.FluidPicker fluidPicker;

    private static OptionalInt iterateNoiseColumn(NoiseBasedChunkGenerator generator, NoiseGeneratorSettings noise, LevelHeightAccessor pLevel, RandomState pRandom, int pX, int pZ, @Nullable MutableObject<NoiseColumn> pColumn, @Nullable Predicate<BlockState> pStoppingState) {
        NoiseSettings settings = noise.noiseSettings().clampToHeightAccessor(pLevel);
        int cellH = settings.getCellHeight();
        int minY = settings.minY();
        int cellMinY = Mth.floorDiv(minY, cellH);
        int cellHeight = Mth.floorDiv(settings.height(), cellH);
        if (cellHeight <= 0) {
            return OptionalInt.empty();
        } else {
            BlockState[] states;
            if (pColumn == null) {
                states = null;
            } else {
                states = new BlockState[settings.height()];
                pColumn.setValue(new NoiseColumn(minY, states));
            }

            int cellWidth = settings.getCellWidth();
            int cellPX = Math.floorDiv(pX, cellWidth);
            int cellPZ = Math.floorDiv(pZ, cellWidth);
            int cellOX = Math.floorMod(pX, cellWidth);
            int cellOZ = Math.floorMod(pZ, cellWidth);
            int cellX = cellPX * cellWidth;
            int cellZ = cellPZ * cellWidth;
            double xFactor = (double)cellOX / (double)cellWidth;
            double zFactor = (double)cellOZ / (double)cellWidth;
            fluidPicker = createFluidPicker(noise);
//            NoiseChunk $$22 = new NoiseChunk(1, pRandom, $$18, $$19, $$6, BeardifierMarker.INSTANCE, noise, (Aquifer.FluidPicker)generator.globalFluidPicker.get(), Blender.empty());
            NoiseChunk chunk = new NoiseChunk(1, pRandom, cellX, cellZ, settings, BeardifierMarker.INSTANCE, noise, fluidPicker, Blender.empty());
            chunk.initializeForFirstCellX();
            chunk.advanceCellX(0);

            for(int y = cellHeight - 1; y >= 0; --y) {
                chunk.selectCellYZ(y, 0);

                for(int y2 = cellH - 1; y2 >= 0; --y2) {
                    int cellEndBlockY = (cellMinY + y) * cellH + y2;
                    double dY = (double)y2 / (double)cellH;
                    chunk.updateForY(cellEndBlockY, dY);
                    chunk.updateForX(pX, xFactor);
                    chunk.updateForZ(pZ, zFactor);
                    BlockState stateI = chunk.getInterpolatedState();
                    BlockState state = stateI == null ? noise.defaultBlock() : stateI;
                    if (states != null) {
                        int idx = y * cellH + y2;
                        states[idx] = state;
                    }

                    if (pStoppingState != null && pStoppingState.test(state)) {
                        chunk.stopInterpolation();
                        return OptionalInt.of(cellEndBlockY + 1);
                    }
                }
            }

            chunk.stopInterpolation();
            return OptionalInt.empty();
        }
    }

    protected static enum BeardifierMarker implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        private BeardifierMarker() {
        }

        public double compute(DensityFunction.FunctionContext p_208515_) {
            return (double)0.0F;
        }

        public void fillArray(double[] p_208517_, DensityFunction.ContextProvider p_208518_) {
            Arrays.fill(p_208517_, (double)0.0F);
        }

        public double minValue() {
            return (double)0.0F;
        }

        public double maxValue() {
            return (double)0.0F;
        }
    }

    public static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings pSettings) {
        Aquifer.FluidStatus $$1 = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int $$2 = pSettings.seaLevel();
        Aquifer.FluidStatus $$3 = new Aquifer.FluidStatus($$2, pSettings.defaultFluid());
        Aquifer.FluidStatus $$4 = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        return (p_224274_, p_224275_, p_224276_) -> p_224275_ < Math.min(-54, $$2) ? $$1 : $$3;
    }



}
