package mcjty.lostcities.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;

import java.util.Arrays;
import java.util.OptionalInt;

public class HeightGenOpt {

    public static int getBaseHeight(NoiseBasedChunkGenerator generator, int x, int z, LevelHeightAccessor level, RandomState rnd) {
        return iterateNoiseColumn(generator.generatorSettings().value(), level, rnd, x, z).orElse(level.getMinBuildHeight());
    }

    private static NoiseChunkOpt.FluidPickerV fluidPicker;

    private static OptionalInt iterateNoiseColumn(NoiseGeneratorSettings noise, LevelHeightAccessor pLevel, RandomState pRandom, int pX, int pZ) {
        NoiseSettings settings = noise.noiseSettings().clampToHeightAccessor(pLevel);
        int cellH = settings.getCellHeight();
        int minY = settings.minY();
        int cellMinY = Mth.floorDiv(minY, cellH);
        int cellHeight = Mth.floorDiv(settings.height(), cellH);
        if (cellHeight <= 0) {
            return OptionalInt.empty();
        } else {
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
            NoiseChunkOpt chunk = new NoiseChunkOpt(1, pRandom, cellX, cellZ, settings, BeardifierMarker.INSTANCE, noise, fluidPicker);
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

                    if (state.blocksMotion()) {
                        chunk.stopInterpolation();
                        return OptionalInt.of(cellEndBlockY + 1);
                    }
                }
            }

            chunk.stopInterpolation();
            return OptionalInt.empty();
        }
    }

    protected enum BeardifierMarker implements DensityFunctions.BeardifierOrMarker {
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

    public static NoiseChunkOpt.FluidPickerV createFluidPicker(NoiseGeneratorSettings pSettings) {
        NoiseChunkOpt.FluidStatusV lava = new NoiseChunkOpt.FluidStatusV(-54, Blocks.LAVA.defaultBlockState());
        int sea = pSettings.seaLevel();
        NoiseChunkOpt.FluidStatusV def = new NoiseChunkOpt.FluidStatusV(sea, pSettings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, sea) ? lava : def;
    }



}
