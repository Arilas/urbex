package mcjty.lostcities.worldgen;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.DensityFunctions.Marker;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class NoiseChunkOpt implements DensityFunction.ContextProvider, DensityFunction.FunctionContext {
   private final NoiseSettings noiseSettings;
   final int cellCountXZ;
   final int cellCountY;
   final int cellNoiseMinY;
   private final int firstCellX;
   private final int firstCellZ;
   final int firstNoiseX;
   final int firstNoiseZ;
   final List<NoiseChunkOpt.NoiseInterpolator> interpolators;
   final List<NoiseChunkOpt.CacheAllInCell> cellCaches;
   private final Map<DensityFunction, DensityFunction> wrapped = new HashMap<>();
   private final Long2IntMap preliminarySurfaceLevel = new Long2IntOpenHashMap();
   private final Aquifer aquifer;
   private final DensityFunction initialDensityNoJaggedness;
   private final NoiseChunkOpt.BlockStateFiller blockStateRule;
//   private final Blender blender;
//   private final FlatCache blendAlpha;
//   private final FlatCache blendOffset;
   private final DensityFunctions.BeardifierOrMarker beardifier;
   private long lastBlendingDataPos = ChunkPos.INVALID_CHUNK_POS;
   private Blender.BlendingOutput lastBlendingOutput = new Blender.BlendingOutput(1.0D, 0.0D);
   final int noiseSizeXZ;
   final int cellWidth;
   final int cellHeight;
   boolean interpolating;
   boolean fillingCell;
   private int cellStartBlockX;
   int cellStartBlockY;
   private int cellStartBlockZ;
   int inCellX;
   int inCellY;
   int inCellZ;
   long interpolationCounter;
   long arrayInterpolationCounter;
   int arrayIndex;
   private final DensityFunction.ContextProvider sliceFillingContextProvider;

   // YES
   public NoiseChunkOpt(int pCellCountXZ, RandomState pRandom, int pFirstNoiseX, int pFirstNoiseZ, NoiseSettings pNoiseSettings, DensityFunctions.BeardifierOrMarker pBeardifier, NoiseGeneratorSettings pNoiseGeneratorSettings, NoiseChunkOpt.FluidStatusV fluidStatus, Blender pBlendifier) {
      this.lastBlendingDataPos = ChunkPos.INVALID_CHUNK_POS;
      this.lastBlendingOutput = new Blender.BlendingOutput((double)1.0F, (double)0.0F);
      sliceFillingContextProvider = new DensityFunction.ContextProvider() {
         public DensityFunction.FunctionContext forIndex(int p_209253_) {
            NoiseChunkOpt.this.cellStartBlockY = (p_209253_ + NoiseChunkOpt.this.cellNoiseMinY) * NoiseChunkOpt.this.cellHeight;
            ++NoiseChunkOpt.this.interpolationCounter;
            NoiseChunkOpt.this.inCellY = 0;
            NoiseChunkOpt.this.arrayIndex = p_209253_;
            return NoiseChunkOpt.this;
         }

         public void fillAllDirectly(double[] p_209255_, DensityFunction p_209256_) {
            for(int i2 = 0; i2 < NoiseChunkOpt.this.cellCountY + 1; ++i2) {
               NoiseChunkOpt.this.cellStartBlockY = (i2 + NoiseChunkOpt.this.cellNoiseMinY) * NoiseChunkOpt.this.cellHeight;
               ++NoiseChunkOpt.this.interpolationCounter;
               NoiseChunkOpt.this.inCellY = 0;
               NoiseChunkOpt.this.arrayIndex = i2;
               p_209255_[i2] = p_209256_.compute(NoiseChunkOpt.this);
            }

         }
      };
      this.noiseSettings = pNoiseSettings;
      this.cellWidth = pNoiseSettings.getCellWidth();
      this.cellHeight = pNoiseSettings.getCellHeight();
      this.cellCountXZ = pCellCountXZ;
      this.cellCountY = Mth.floorDiv(pNoiseSettings.height(), this.cellHeight);
      this.cellNoiseMinY = Mth.floorDiv(pNoiseSettings.minY(), this.cellHeight);
      this.firstCellX = Math.floorDiv(pFirstNoiseX, this.cellWidth);
      this.firstCellZ = Math.floorDiv(pFirstNoiseZ, this.cellWidth);
      this.interpolators = Lists.newArrayList();
      this.cellCaches = Lists.newArrayList();
      this.firstNoiseX = QuartPos.fromBlock(pFirstNoiseX);
      this.firstNoiseZ = QuartPos.fromBlock(pFirstNoiseZ);
      this.noiseSizeXZ = QuartPos.fromBlock(pCellCountXZ * this.cellWidth);
//      this.blender = pBlendifier;
      this.beardifier = pBeardifier;
//      this.blendAlpha = new FlatCache(new BlendAlpha(), false);
//      this.blendOffset = new FlatCache(new BlendOffset(), false);

//      for(int $$9 = 0; $$9 <= this.noiseSizeXZ; ++$$9) {
//         int $$10 = this.firstNoiseX + $$9;
//         int $$11 = QuartPos.toBlock($$10);
//
//         for(int $$12 = 0; $$12 <= this.noiseSizeXZ; ++$$12) {
//            int $$13 = this.firstNoiseZ + $$12;
//            int $$14 = QuartPos.toBlock($$13);
//            Blender.BlendingOutput $$15 = blender.blendOffsetAndFactor($$11, $$14);
//            this.blendAlpha.values[$$9][$$12] = $$15.alpha();
//            this.blendOffset.values[$$9][$$12] = $$15.blendingOffset();
//         }
//      }

      NoiseRouter noiserouter = pRandom.router();
      NoiseRouter noiserouter1 = noiserouter.mapAll(this::wrap);
      if (!pNoiseGeneratorSettings.isAquifersEnabled()) {
         this.aquifer = new Aquifer() {
            @Nullable
            public BlockState computeSubstance(DensityFunction.FunctionContext p_208172_, double p_208173_) {
               return p_208173_ > 0.0D ? null : fluidStatus.at(p_208172_.blockY());
            }

            public boolean shouldScheduleFluidUpdate() {
               return false;
            }
         };
      } else {
         int k1 = SectionPos.blockToSectionCoord(pFirstNoiseX);
         int l1 = SectionPos.blockToSectionCoord(pFirstNoiseZ);
         this.aquifer = new NoiseBasedAquifer(this, new ChunkPos(k1, l1), noiserouter1, pRandom.aquiferRandom(), pNoiseSettings.minY(), pNoiseSettings.height(), fluidStatus);
      }

      ImmutableList.Builder<NoiseChunkOpt.BlockStateFiller> builder = ImmutableList.builder();
      DensityFunction densityfunction = DensityFunctions.cacheAllInCell(DensityFunctions.add(noiserouter1.finalDensity(), BeardifierMarker.INSTANCE)).mapAll(this::wrap);
      builder.add((context) -> {
         return this.aquifer.computeSubstance(context, densityfunction.compute(context));
      });
//      if (pNoiseGeneratorSettings.oreVeinsEnabled()) {
//         builder.add(OreVeinifier.create(noiserouter1.veinToggle(), noiserouter1.veinRidged(), noiserouter1.veinGap(), pRandom.oreRandom()));
//      }

      this.blockStateRule = new MaterialRuleList(builder.build());
      this.initialDensityNoJaggedness = noiserouter1.initialDensityWithoutJaggedness();
   }

   public int preliminarySurfaceLevel(int pX, int pZ) {
      int i = QuartPos.toBlock(QuartPos.fromBlock(pX));
      int j = QuartPos.toBlock(QuartPos.fromBlock(pZ));
      return this.preliminarySurfaceLevel.computeIfAbsent(ColumnPos.asLong(i, j), this::computePreliminarySurfaceLevel);
   }

   private int computePreliminarySurfaceLevel(long p_198250_) {
      int i = ColumnPos.getX(p_198250_);
      int j = ColumnPos.getZ(p_198250_);
      int k = this.noiseSettings.minY();

      for(int l = k + this.noiseSettings.height(); l >= k; l -= this.cellHeight) {
         if (this.initialDensityNoJaggedness.compute(new DensityFunction.SinglePointContext(i, l, j)) > 0.390625D) {
            return l;
         }
      }

      return Integer.MAX_VALUE;
   }



   @Nullable
   public BlockState getInterpolatedState() {
      return this.blockStateRule.calculate(this);
   }

   @Override
   public int blockX() {
      return this.cellStartBlockX + this.inCellX;
   }

   @Override
   public int blockY() {
      return this.cellStartBlockY + this.inCellY;
   }

   @Override
   public int blockZ() {
      return this.cellStartBlockZ + this.inCellZ;
   }

   @Override
   public Blender getBlender() {
      return Blender.empty();
   }

   public @NotNull NoiseChunkOpt forIndex(int pArrayIndex) {
      int i = Math.floorMod(pArrayIndex, this.cellWidth);
      int j = Math.floorDiv(pArrayIndex, this.cellWidth);
      int k = Math.floorMod(j, this.cellWidth);
      int l = this.cellHeight - 1 - Math.floorDiv(j, this.cellWidth);
      this.inCellX = k;
      this.inCellY = l;
      this.inCellZ = i;
      this.arrayIndex = pArrayIndex;
      return this;
   }

   @Override
   public void fillAllDirectly(double[] pValues, DensityFunction pFunction) {
      this.arrayIndex = 0;

      for(int i = this.cellHeight - 1; i >= 0; --i) {
         this.inCellY = i;

         for(int j = 0; j < this.cellWidth; ++j) {
            this.inCellX = j;

            for(int k = 0; k < this.cellWidth; ++k) {
               this.inCellZ = k;
               pValues[this.arrayIndex++] = pFunction.compute(this);
            }
         }
      }

   }

   // YES
   private void fillSlice(boolean pIsSlice0, int pStart) {
      this.cellStartBlockX = pStart * this.cellWidth;
      this.inCellX = 0;

      for(int i = 0; i < this.cellCountXZ + 1; ++i) {
         int j = this.firstCellZ + i;
         this.cellStartBlockZ = j * this.cellWidth;
         this.inCellZ = 0;
         ++this.arrayInterpolationCounter;

         for(NoiseChunkOpt.NoiseInterpolator interpolator : this.interpolators) {
            double[] adouble = (pIsSlice0 ? interpolator.slice0 : interpolator.slice1)[i];
            interpolator.fillArray(adouble, this.sliceFillingContextProvider);
         }
      }

      ++this.arrayInterpolationCounter;
   }

   // YES
   public void initializeForFirstCellX() {
      if (this.interpolating) {
         throw new IllegalStateException("Staring interpolation twice");
      } else {
         this.interpolating = true;
         this.interpolationCounter = 0L;
         this.fillSlice(true, this.firstCellX);
      }
   }

   // YES
   public void advanceCellX(int pIncrement) {
      this.fillSlice(false, this.firstCellX + pIncrement + 1);
      this.cellStartBlockX = (this.firstCellX + pIncrement) * this.cellWidth;
   }

   // YES
   public void selectCellYZ(int pY, int pZ) {
      this.interpolators.forEach((p_209205_) -> {
         p_209205_.selectCellYZ(pY, pZ);
      });
      this.fillingCell = true;
      this.cellStartBlockY = (pY + this.cellNoiseMinY) * this.cellHeight;
      this.cellStartBlockZ = (this.firstCellZ + pZ) * this.cellWidth;
      ++this.arrayInterpolationCounter;

      for(NoiseChunkOpt.CacheAllInCell noisechunk$cacheallincell : this.cellCaches) {
         noisechunk$cacheallincell.noiseFiller.fillArray(noisechunk$cacheallincell.values, this);
      }

      ++this.arrayInterpolationCounter;
      this.fillingCell = false;
   }

   // YES
   public void updateForY(int pCellEndBlockY, double pY) {
      this.inCellY = pCellEndBlockY - this.cellStartBlockY;
      this.interpolators.forEach((p_209238_) -> {
         p_209238_.updateForY(pY);
      });
   }

   // YES
   public void updateForX(int pCellEndBlockX, double pX) {
      this.inCellX = pCellEndBlockX - this.cellStartBlockX;
      this.interpolators.forEach((p_209229_) -> {
         p_209229_.updateForX(pX);
      });
   }

   // YES
   public void updateForZ(int pCellEndBlockZ, double pZ) {
      this.inCellZ = pCellEndBlockZ - this.cellStartBlockZ;
      ++this.interpolationCounter;
      this.interpolators.forEach((p_209188_) -> {
         p_209188_.updateForZ(pZ);
      });
   }

   // YES
   public void stopInterpolation() {
      if (!this.interpolating) {
         throw new IllegalStateException("Staring interpolation twice");
      } else {
         this.interpolating = false;
      }
   }

   Blender.BlendingOutput getOrComputeBlendingOutput(int pChunkX, int pChunkZ) {
      long i = ChunkPos.asLong(pChunkX, pChunkZ);
      if (this.lastBlendingDataPos == i) {
         return this.lastBlendingOutput;
      } else {
         this.lastBlendingDataPos = i;
         Blender.BlendingOutput blender$blendingoutput = new Blender.BlendingOutput(1.0D, 0.0D);
         this.lastBlendingOutput = blender$blendingoutput;
         return blender$blendingoutput;
      }
   }

   protected DensityFunction wrap(DensityFunction p_209214_) {
      return this.wrapped.computeIfAbsent(p_209214_, this::wrapNew);
   }

   private DensityFunction wrapNew(DensityFunction p_209234_) {
      if (p_209234_ instanceof Marker || p_209234_ instanceof Marker) {
         Marker densityfunctions$marker = (Marker)p_209234_;
         Object object;
         switch (densityfunctions$marker.type()) {
            case Interpolated:
               object = new NoiseChunkOpt.NoiseInterpolator(densityfunctions$marker.wrapped());
               break;
            case FlatCache:
               object = new NoiseChunkOpt.FlatCache(densityfunctions$marker.wrapped(), true);
               break;
            case Cache2D:
               object = new NoiseChunkOpt.Cache2D(densityfunctions$marker.wrapped());
               break;
            case CacheOnce:
               object = new NoiseChunkOpt.CacheOnce(densityfunctions$marker.wrapped());
               break;
            case CacheAllInCell:
               object = new NoiseChunkOpt.CacheAllInCell(densityfunctions$marker.wrapped());
               break;
            default:
               throw new IncompatibleClassChangeError();
         }

         return (DensityFunction)object;
      } else {
//         if (this.blender != Blender.empty()) {
//            if (p_209234_ == BlendAlphaFunc.INSTANCE) {
//               return this.blendAlpha;
//            }
//
//            if (p_209234_ == BlendOffsetFunc.INSTANCE) {
//               return this.blendOffset;
//            }
//         }

         if (p_209234_ == BeardifierMarker.INSTANCE) {
            return this.beardifier;
         } else if (p_209234_ instanceof DensityFunctions.HolderHolder) {
            DensityFunctions.HolderHolder densityfunctions$holderholder = (DensityFunctions.HolderHolder)p_209234_;
            return densityfunctions$holderholder.function().value();
         } else {
            return p_209234_;
         }
      }
   }

   @FunctionalInterface
   public interface BlockStateFiller {
      @Nullable
      BlockState calculate(DensityFunction.FunctionContext pContext);
   }

   public record MaterialRuleList(List<BlockStateFiller> materialRuleList) implements BlockStateFiller {
      @Nullable
      public BlockState calculate(DensityFunction.FunctionContext p_209815_) {
         for(BlockStateFiller noisechunk$blockstatefiller : this.materialRuleList) {
            BlockState blockstate = noisechunk$blockstatefiller.calculate(p_209815_);
            if (blockstate != null) {
               return blockstate;
            }
         }

         return null;
      }
   }

   static class Cache2D implements DensityFunctions.MarkerOrMarked, NoiseChunkOpt.NoiseChunkDensityFunction {
      private final DensityFunction function;
      private long lastPos2D = ChunkPos.INVALID_CHUNK_POS;
      private double lastValue;

      Cache2D(DensityFunction pFunction) {
         this.function = pFunction;
      }

      public double compute(FunctionContext pContext) {
         int i = pContext.blockX();
         int j = pContext.blockZ();
         long k = ChunkPos.asLong(i, j);
         if (this.lastPos2D == k) {
            return this.lastValue;
         } else {
            this.lastPos2D = k;
            double d0 = this.function.compute(pContext);
            this.lastValue = d0;
            return d0;
         }
      }

      public void fillArray(double[] pArray, ContextProvider pContextProvider) {
         this.function.fillArray(pArray, pContextProvider);
      }

      public DensityFunction wrapped() {
         return this.function;
      }

      @Override
      public Marker.Type type() {
         return Marker.Type.Cache2D;
      }
   }

   class CacheAllInCell implements DensityFunctions.MarkerOrMarked, NoiseChunkOpt.NoiseChunkDensityFunction {
      final DensityFunction noiseFiller;
      final double[] values;

      CacheAllInCell(DensityFunction pNoiseFilter) {
         this.noiseFiller = pNoiseFilter;
         this.values = new double[NoiseChunkOpt.this.cellWidth * NoiseChunkOpt.this.cellWidth * NoiseChunkOpt.this.cellHeight];
         NoiseChunkOpt.this.cellCaches.add(this);
      }

      public double compute(FunctionContext pContext) {
         if (pContext != NoiseChunkOpt.this) {
            return this.noiseFiller.compute(pContext);
         } else if (!NoiseChunkOpt.this.interpolating) {
            throw new IllegalStateException("Trying to sample interpolator outside the interpolation loop");
         } else {
            int i = NoiseChunkOpt.this.inCellX;
            int j = NoiseChunkOpt.this.inCellY;
            int k = NoiseChunkOpt.this.inCellZ;
            return i >= 0 && j >= 0 && k >= 0 && i < NoiseChunkOpt.this.cellWidth && j < NoiseChunkOpt.this.cellHeight && k < NoiseChunkOpt.this.cellWidth ? this.values[((NoiseChunkOpt.this.cellHeight - 1 - j) * NoiseChunkOpt.this.cellWidth + i) * NoiseChunkOpt.this.cellWidth + k] : this.noiseFiller.compute(pContext);
         }
      }

      public void fillArray(double[] pArray, ContextProvider pContextProvider) {
         pContextProvider.fillAllDirectly(pArray, this);
      }

      public DensityFunction wrapped() {
         return this.noiseFiller;
      }

      public Marker.Type type() {
         return Marker.Type.CacheAllInCell;
      }
   }

   class CacheOnce implements DensityFunctions.MarkerOrMarked, NoiseChunkOpt.NoiseChunkDensityFunction {
      private final DensityFunction function;
      private long lastCounter;
      private long lastArrayCounter;
      private double lastValue;
      @Nullable
      private double[] lastArray;

      CacheOnce(DensityFunction pFunction) {
         this.function = pFunction;
      }

      public double compute(FunctionContext pContext) {
         if (pContext != NoiseChunkOpt.this) {
            return this.function.compute(pContext);
         } else if (this.lastArray != null && this.lastArrayCounter == NoiseChunkOpt.this.arrayInterpolationCounter) {
            return this.lastArray[NoiseChunkOpt.this.arrayIndex];
         } else if (this.lastCounter == NoiseChunkOpt.this.interpolationCounter) {
            return this.lastValue;
         } else {
            this.lastCounter = NoiseChunkOpt.this.interpolationCounter;
            double d0 = this.function.compute(pContext);
            this.lastValue = d0;
            return d0;
         }
      }

      public void fillArray(double[] pArray, ContextProvider pContextProvider) {
         if (this.lastArray != null && this.lastArrayCounter == NoiseChunkOpt.this.arrayInterpolationCounter) {
            System.arraycopy(this.lastArray, 0, pArray, 0, pArray.length);
         } else {
            this.wrapped().fillArray(pArray, pContextProvider);
            if (this.lastArray != null && this.lastArray.length == pArray.length) {
               System.arraycopy(pArray, 0, this.lastArray, 0, pArray.length);
            } else {
               this.lastArray = (double[])pArray.clone();
            }

            this.lastArrayCounter = NoiseChunkOpt.this.arrayInterpolationCounter;
         }
      }

      public DensityFunction wrapped() {
         return this.function;
      }

      public Marker.Type type() {
         return Marker.Type.CacheOnce;
      }
   }

   class FlatCache implements DensityFunctions.MarkerOrMarked, NoiseChunkOpt.NoiseChunkDensityFunction {
      private final DensityFunction noiseFiller;
      final double[][] values;

      FlatCache(DensityFunction pNoiseFiller, boolean pComputeValues) {
         this.noiseFiller = pNoiseFiller;
         this.values = new double[NoiseChunkOpt.this.noiseSizeXZ + 1][NoiseChunkOpt.this.noiseSizeXZ + 1];
         if (pComputeValues) {
            for(int i = 0; i <= NoiseChunkOpt.this.noiseSizeXZ; ++i) {
               int j = NoiseChunkOpt.this.firstNoiseX + i;
               int k = QuartPos.toBlock(j);

               for(int l = 0; l <= NoiseChunkOpt.this.noiseSizeXZ; ++l) {
                  int i1 = NoiseChunkOpt.this.firstNoiseZ + l;
                  int j1 = QuartPos.toBlock(i1);
                  this.values[i][l] = pNoiseFiller.compute(new SinglePointContext(k, 0, j1));
               }
            }
         }

      }

      public double compute(FunctionContext pContext) {
         int i = QuartPos.fromBlock(pContext.blockX());
         int j = QuartPos.fromBlock(pContext.blockZ());
         int k = i - NoiseChunkOpt.this.firstNoiseX;
         int l = j - NoiseChunkOpt.this.firstNoiseZ;
         int i1 = this.values.length;
         return k >= 0 && l >= 0 && k < i1 && l < i1 ? this.values[k][l] : this.noiseFiller.compute(pContext);
      }

      public void fillArray(double[] pArray, ContextProvider pContextProvider) {
         pContextProvider.fillAllDirectly(pArray, this);
      }

      public DensityFunction wrapped() {
         return this.noiseFiller;
      }

      public Marker.Type type() {
         return Marker.Type.FlatCache;
      }
   }

   interface NoiseChunkDensityFunction extends DensityFunction {
      DensityFunction wrapped();

      default double minValue() {
         return this.wrapped().minValue();
      }

      default double maxValue() {
         return this.wrapped().maxValue();
      }
   }

   public class NoiseInterpolator implements DensityFunctions.MarkerOrMarked, NoiseChunkOpt.NoiseChunkDensityFunction {
      double[][] slice0;
      double[][] slice1;
      private final DensityFunction noiseFiller;
      private double noise000;
      private double noise001;
      private double noise100;
      private double noise101;
      private double noise010;
      private double noise011;
      private double noise110;
      private double noise111;
      private double valueXZ00;
      private double valueXZ10;
      private double valueXZ01;
      private double valueXZ11;
      private double valueZ0;
      private double valueZ1;
      private double value;

      NoiseInterpolator(DensityFunction pNoiseFilter) {
         this.noiseFiller = pNoiseFilter;
         this.slice0 = this.allocateSlice(NoiseChunkOpt.this.cellCountY, NoiseChunkOpt.this.cellCountXZ);
         this.slice1 = this.allocateSlice(NoiseChunkOpt.this.cellCountY, NoiseChunkOpt.this.cellCountXZ);
         NoiseChunkOpt.this.interpolators.add(this);
      }

      private double[][] allocateSlice(int pCellCountY, int pCellCountXZ) {
         int i = pCellCountXZ + 1;
         int j = pCellCountY + 1;
         double[][] adouble = new double[i][j];

         for(int k = 0; k < i; ++k) {
            adouble[k] = new double[j];
         }

         return adouble;
      }

      void selectCellYZ(int pY, int pZ) {
         this.noise000 = this.slice0[pZ][pY];
         this.noise001 = this.slice0[pZ + 1][pY];
         this.noise100 = this.slice1[pZ][pY];
         this.noise101 = this.slice1[pZ + 1][pY];
         this.noise010 = this.slice0[pZ][pY + 1];
         this.noise011 = this.slice0[pZ + 1][pY + 1];
         this.noise110 = this.slice1[pZ][pY + 1];
         this.noise111 = this.slice1[pZ + 1][pY + 1];
      }

      void updateForY(double pY) {
         this.valueXZ00 = Mth.lerp(pY, this.noise000, this.noise010);
         this.valueXZ10 = Mth.lerp(pY, this.noise100, this.noise110);
         this.valueXZ01 = Mth.lerp(pY, this.noise001, this.noise011);
         this.valueXZ11 = Mth.lerp(pY, this.noise101, this.noise111);
      }

      void updateForX(double pX) {
         this.valueZ0 = Mth.lerp(pX, this.valueXZ00, this.valueXZ10);
         this.valueZ1 = Mth.lerp(pX, this.valueXZ01, this.valueXZ11);
      }

      void updateForZ(double pZ) {
         this.value = Mth.lerp(pZ, this.valueZ0, this.valueZ1);
      }

      public double compute(FunctionContext pContext) {
         if (pContext != NoiseChunkOpt.this) {
            return this.noiseFiller.compute(pContext);
         } else if (!NoiseChunkOpt.this.interpolating) {
            throw new IllegalStateException("Trying to sample interpolator outside the interpolation loop");
         } else {
            return NoiseChunkOpt.this.fillingCell ? Mth.lerp3((double) NoiseChunkOpt.this.inCellX / (double) NoiseChunkOpt.this.cellWidth, (double) NoiseChunkOpt.this.inCellY / (double) NoiseChunkOpt.this.cellHeight, (double) NoiseChunkOpt.this.inCellZ / (double) NoiseChunkOpt.this.cellWidth, this.noise000, this.noise100, this.noise010, this.noise110, this.noise001, this.noise101, this.noise011, this.noise111) : this.value;
         }
      }

      public void fillArray(double[] pArray, ContextProvider pContextProvider) {
         if (NoiseChunkOpt.this.fillingCell) {
            pContextProvider.fillAllDirectly(pArray, this);
         } else {
            this.wrapped().fillArray(pArray, pContextProvider);
         }
      }

      public DensityFunction wrapped() {
         return this.noiseFiller;
      }

      private void swapSlices() {
         double[][] adouble = this.slice0;
         this.slice0 = this.slice1;
         this.slice1 = adouble;
      }

      public Marker.Type type() {
         return Marker.Type.Interpolated;
      }
   }

   private enum BeardifierMarker implements DensityFunctions.BeardifierOrMarker {
      INSTANCE;

      public double compute(DensityFunction.FunctionContext p_208515_) {
         return 0.0D;
      }

      public void fillArray(double[] p_208517_, DensityFunction.ContextProvider p_208518_) {
         Arrays.fill(p_208517_, 0.0D);
      }

      public double minValue() {
         return 0.0D;
      }

      public double maxValue() {
         return 0.0D;
      }
   }

   public interface FluidPickerV {
      FluidStatusV computeFluid(int pX, int pY, int pZ);
   }

   public static final class FluidStatusV {
      /** The y height of the aquifer. */
      final int fluidLevel;
      /** The fluid state the aquifer is filled with. */
      final BlockState fluidType;

      public FluidStatusV(int pFluidLevel, BlockState pFluidType) {
         this.fluidLevel = pFluidLevel;
         this.fluidType = pFluidType;
      }

      public BlockState at(int pY) {
         return pY < this.fluidLevel ? this.fluidType : Blocks.AIR.defaultBlockState();
      }
   }

   public static class NoiseBasedAquifer implements Aquifer {
      private static final int X_RANGE = 10;
      private static final int Y_RANGE = 9;
      private static final int Z_RANGE = 10;
      private static final int X_SEPARATION = 6;
      private static final int Y_SEPARATION = 3;
      private static final int Z_SEPARATION = 6;
      private static final int X_SPACING = 16;
      private static final int Y_SPACING = 12;
      private static final int Z_SPACING = 16;
      private static final int MAX_REASONABLE_DISTANCE_TO_AQUIFER_CENTER = 11;
      private static final double FLOWING_UPDATE_SIMULARITY = similarity(Mth.square(10), Mth.square(12));
      private final NoiseChunkOpt noiseChunk;
      protected final DensityFunction barrierNoise;
      private final DensityFunction fluidLevelFloodednessNoise;
      private final DensityFunction fluidLevelSpreadNoise;
      protected final DensityFunction lavaNoise;
      private final PositionalRandomFactory positionalRandomFactory;
      protected final FluidStatusV[] aquiferCache;
      protected final long[] aquiferLocationCache;
      private final NoiseChunkOpt.FluidStatusV fluidStatus;
      private final DensityFunction erosion;
      private final DensityFunction depth;
      protected boolean shouldScheduleFluidUpdate;
      protected final int minGridX;
      protected final int minGridY;
      protected final int minGridZ;
      protected final int gridSizeX;
      protected final int gridSizeZ;
      private static final int[][] SURFACE_SAMPLING_OFFSETS_IN_CHUNKS = new int[][]{{0, 0}, {-2, -1}, {-1, -1}, {0, -1}, {1, -1}, {-3, 0}, {-2, 0}, {-1, 0}, {1, 0}, {-2, 1}, {-1, 1}, {0, 1}, {1, 1}};

      NoiseBasedAquifer(NoiseChunkOpt pNoiseChunk, ChunkPos pChunkPos, NoiseRouter pNoiseRouter, PositionalRandomFactory pPositionalRandomFactory, int pMinY, int pHeight, NoiseChunkOpt.FluidStatusV fluidStatus) {
         this.noiseChunk = pNoiseChunk;
         this.barrierNoise = pNoiseRouter.barrierNoise();
         this.fluidLevelFloodednessNoise = pNoiseRouter.fluidLevelFloodednessNoise();
         this.fluidLevelSpreadNoise = pNoiseRouter.fluidLevelSpreadNoise();
         this.lavaNoise = pNoiseRouter.lavaNoise();
         this.erosion = pNoiseRouter.erosion();
         this.depth = pNoiseRouter.depth();
         this.positionalRandomFactory = pPositionalRandomFactory;
         this.minGridX = this.gridX(pChunkPos.getMinBlockX()) - 1;
         this.fluidStatus = fluidStatus;
         int i = this.gridX(pChunkPos.getMaxBlockX()) + 1;
         this.gridSizeX = i - this.minGridX + 1;
         this.minGridY = this.gridY(pMinY) - 1;
         int j = this.gridY(pMinY + pHeight) + 1;
         int k = j - this.minGridY + 1;
         this.minGridZ = this.gridZ(pChunkPos.getMinBlockZ()) - 1;
         int l = this.gridZ(pChunkPos.getMaxBlockZ()) + 1;
         this.gridSizeZ = l - this.minGridZ + 1;
         int i1 = this.gridSizeX * k * this.gridSizeZ;
         this.aquiferCache = new FluidStatusV[i1];
         this.aquiferLocationCache = new long[i1];
         Arrays.fill(this.aquiferLocationCache, Long.MAX_VALUE);
      }

      /**
       * @return A cache index based on grid positions.
       */
      protected int getIndex(int pGridX, int pGridY, int pGridZ) {
         int i = pGridX - this.minGridX;
         int j = pGridY - this.minGridY;
         int k = pGridZ - this.minGridZ;
         return (j * this.gridSizeZ + k) * this.gridSizeX + i;
      }

      @Nullable
      public BlockState computeSubstance(DensityFunction.FunctionContext pContext, double pSubstance) {
         int i = pContext.blockX();
         int j = pContext.blockY();
         int k = pContext.blockZ();
         if (pSubstance > 0.0D) {
            this.shouldScheduleFluidUpdate = false;
            return null;
         } else {
            int l = Math.floorDiv(i - 5, 16);
            int i1 = Math.floorDiv(j + 1, 12);
            int j1 = Math.floorDiv(k - 5, 16);
            int k1 = Integer.MAX_VALUE;
            int l1 = Integer.MAX_VALUE;
            int i2 = Integer.MAX_VALUE;
            long packedPos = 0L;
            long k2 = 0L;
            long l2 = 0L;

            for (int i3 = 0; i3 <= 1; ++i3) {
               for (int j3 = -1; j3 <= 1; ++j3) {
                  for (int k3 = 0; k3 <= 1; ++k3) {
                     int l3 = l + i3;
                     int i4 = i1 + j3;
                     int j4 = j1 + k3;
                     int cacheIdx = this.getIndex(l3, i4, j4);
                     long pp5 = this.aquiferLocationCache[cacheIdx];
                     long pp4;
                     if (pp5 != Long.MAX_VALUE) {
                        pp4 = pp5;
                     } else {
                        RandomSource randomsource = this.positionalRandomFactory.at(l3, i4, j4);
                        pp4 = BlockPos.asLong(l3 * 16 + randomsource.nextInt(10), i4 * 12 + randomsource.nextInt(9), j4 * 16 + randomsource.nextInt(10));
                        this.aquiferLocationCache[cacheIdx] = pp4;
                     }

                     int x4 = BlockPos.getX(pp4) - i;
                     int y4 = BlockPos.getY(pp4) - j;
                     int z4 = BlockPos.getZ(pp4) - k;
                     int sq4 = x4 * x4 + y4 * y4 + z4 * z4;
                     if (k1 >= sq4) {
                        l2 = k2;
                        k2 = packedPos;
                        packedPos = pp4;
                        i2 = l1;
                        l1 = k1;
                        k1 = sq4;
                     } else if (l1 >= sq4) {
                        l2 = k2;
                        k2 = pp4;
                        i2 = l1;
                        l1 = sq4;
                     } else if (i2 >= sq4) {
                        l2 = pp4;
                        i2 = sq4;
                     }
                  }
               }
            }

            FluidStatusV aquifer$fluidstatus1 = this.getAquiferStatus(packedPos);
            double d1 = similarity(k1, l1);
            BlockState blockstate = aquifer$fluidstatus1.at(j);
            if (d1 <= 0.0D) {
               this.shouldScheduleFluidUpdate = d1 >= FLOWING_UPDATE_SIMULARITY;
               return blockstate;
            } else if (blockstate.is(Blocks.WATER) && this.fluidStatus.at(j - 1).is(Blocks.LAVA)) {
               this.shouldScheduleFluidUpdate = true;
               return blockstate;
            } else {
               MutableDouble mutabledouble = new MutableDouble(Double.NaN);
               FluidStatusV aquifer$fluidstatus2 = this.getAquiferStatus(k2);
               double d2 = d1 * this.calculatePressure(pContext, mutabledouble, aquifer$fluidstatus1, aquifer$fluidstatus2);
               if (pSubstance + d2 > 0.0D) {
                  this.shouldScheduleFluidUpdate = false;
                  return null;
               } else {
                  FluidStatusV aquifer$fluidstatus3 = this.getAquiferStatus(l2);
                  double d0 = similarity(k1, i2);
                  if (d0 > 0.0D) {
                     double d3 = d1 * d0 * this.calculatePressure(pContext, mutabledouble, aquifer$fluidstatus1, aquifer$fluidstatus3);
                     if (pSubstance + d3 > 0.0D) {
                        this.shouldScheduleFluidUpdate = false;
                        return null;
                     }
                  }

                  double d4 = similarity(l1, i2);
                  if (d4 > 0.0D) {
                     double d5 = d1 * d4 * this.calculatePressure(pContext, mutabledouble, aquifer$fluidstatus2, aquifer$fluidstatus3);
                     if (pSubstance + d5 > 0.0D) {
                        this.shouldScheduleFluidUpdate = false;
                        return null;
                     }
                  }

                  this.shouldScheduleFluidUpdate = true;
                  return blockstate;
               }
            }
         }
      }

      /**
       * Returns {@code true} if there should be a fluid update scheduled - due to a fluid block being placed in a
       * possibly unsteady position - at the last position passed into computeState.
       * This <strong>must</strong> be invoked only after computeState, and will be using the same parameters
       * as that method.
       */
      public boolean shouldScheduleFluidUpdate() {
         return this.shouldScheduleFluidUpdate;
      }

      /**
       * Compares two distances (between aquifers).
       * @return {@code 1.0} if the distances are equal, and returns smaller values the more different in absolute value
       * the two distances are.
       */
      protected static double similarity(int pFirstDistance, int pSecondDistance) {
         double d0 = 25.0D;
         return 1.0D - (double)Math.abs(pSecondDistance - pFirstDistance) / 25.0D;
      }

      private double calculatePressure(DensityFunction.FunctionContext pContext, MutableDouble pSubstance, FluidStatusV pFirstFluid, FluidStatusV pSecondFluid) {
         int i = pContext.blockY();
         BlockState blockstate = pFirstFluid.at(i);
         BlockState blockstate1 = pSecondFluid.at(i);
         if ((!blockstate.is(Blocks.LAVA) || !blockstate1.is(Blocks.WATER)) && (!blockstate.is(Blocks.WATER) || !blockstate1.is(Blocks.LAVA))) {
            int j = Math.abs(pFirstFluid.fluidLevel - pSecondFluid.fluidLevel);
            if (j == 0) {
               return 0.0D;
            } else {
               double d0 = 0.5D * (double)(pFirstFluid.fluidLevel + pSecondFluid.fluidLevel);
               double d1 = (double)i + 0.5D - d0;
               double d2 = (double)j / 2.0D;
               double d9 = d2 - Math.abs(d1);
               double d10;
               if (d1 > 0.0D) {
                  double d11 = 0.0D + d9;
                  if (d11 > 0.0D) {
                     d10 = d11 / 1.5D;
                  } else {
                     d10 = d11 / 2.5D;
                  }
               } else {
                  double d15 = 3.0D + d9;
                  if (d15 > 0.0D) {
                     d10 = d15 / 3.0D;
                  } else {
                     d10 = d15 / 10.0D;
                  }
               }

               double d16 = 2.0D;
               double d12;
               if (!(d10 < -2.0D) && !(d10 > 2.0D)) {
                  double d13 = pSubstance.getValue();
                  if (Double.isNaN(d13)) {
                     double d14 = this.barrierNoise.compute(pContext);
                     pSubstance.setValue(d14);
                     d12 = d14;
                  } else {
                     d12 = d13;
                  }
               } else {
                  d12 = 0.0D;
               }

               return 2.0D * (d12 + d10);
            }
         } else {
            return 2.0D;
         }
      }

      protected int gridX(int pX) {
         return Math.floorDiv(pX, 16);
      }

      protected int gridY(int pY) {
         return Math.floorDiv(pY, 12);
      }

      protected int gridZ(int pZ) {
         return Math.floorDiv(pZ, 16);
      }

      /**
       * Calculates the aquifer at a given location. Internally references a cache using the grid positions as an index.
       * If the cache is not populated, computes a new aquifer at that grid location using {@link #computeFluid}.
       * @param pPackedPos The aquifer block position, packed into a {@code long}.
       */
      private FluidStatusV getAquiferStatus(long pPackedPos) {
         int i = BlockPos.getX(pPackedPos);
         int j = BlockPos.getY(pPackedPos);
         int k = BlockPos.getZ(pPackedPos);
         int l = this.gridX(i);
         int i1 = this.gridY(j);
         int j1 = this.gridZ(k);
         int k1 = this.getIndex(l, i1, j1);
         FluidStatusV aquifer$fluidstatus = this.aquiferCache[k1];
         if (aquifer$fluidstatus != null) {
            return aquifer$fluidstatus;
         } else {
            FluidStatusV aquifer$fluidstatus1 = this.computeFluid(i, j, k);
            this.aquiferCache[k1] = aquifer$fluidstatus1;
            return aquifer$fluidstatus1;
         }
      }

      private FluidStatusV computeFluid(int pX, int pY, int pZ) {
         int i = Integer.MAX_VALUE;
         int j = pY + 12;
         int k = pY - 12;
         boolean flag = false;

         for(int[] aint : SURFACE_SAMPLING_OFFSETS_IN_CHUNKS) {
            int l = pX + SectionPos.sectionToBlockCoord(aint[0]);
            int i1 = pZ + SectionPos.sectionToBlockCoord(aint[1]);
            int j1 = this.noiseChunk.preliminarySurfaceLevel(l, i1);
            int k1 = j1 + 8;
            boolean flag1 = aint[0] == 0 && aint[1] == 0;
            if (flag1 && k > k1) {
               return this.fluidStatus;
            }

            boolean flag2 = j > k1;
            if (flag2 || flag1) {
               if (!this.fluidStatus.at(k1).isAir()) {
                  if (flag1) {
                     flag = true;
                  }

                  if (flag2) {
                     return this.fluidStatus;
                  }
               }
            }

            i = Math.min(i, j1);
         }

         int l1 = this.computeSurfaceLevel(pX, pY, pZ, this.fluidStatus, i, flag);
         return new FluidStatusV(l1, this.computeFluidType(pX, pY, pZ, this.fluidStatus, l1));
      }

      private int computeSurfaceLevel(int pX, int pY, int pZ, FluidStatusV pFluidStatus, int pMaxSurfaceLevel, boolean p_223915_) {
         DensityFunction.SinglePointContext densityfunction$singlepointcontext = new DensityFunction.SinglePointContext(pX, pY, pZ);
         double d0;
         double d1;
         if (OverworldBiomeBuilder.isDeepDarkRegion(this.erosion, this.depth, densityfunction$singlepointcontext)) {
            d0 = -1.0D;
            d1 = -1.0D;
         } else {
            int i = pMaxSurfaceLevel + 8 - pY;
            int j = 64;
            double d2 = p_223915_ ? Mth.clampedMap((double)i, 0.0D, 64.0D, 1.0D, 0.0D) : 0.0D;
            double d3 = Mth.clamp(this.fluidLevelFloodednessNoise.compute(densityfunction$singlepointcontext), -1.0D, 1.0D);
            double d4 = Mth.map(d2, 1.0D, 0.0D, -0.3D, 0.8D);
            double d5 = Mth.map(d2, 1.0D, 0.0D, -0.8D, 0.4D);
            d0 = d3 - d5;
            d1 = d3 - d4;
         }

         int k;
         if (d1 > 0.0D) {
            k = pFluidStatus.fluidLevel;
         } else if (d0 > 0.0D) {
            k = this.computeRandomizedFluidSurfaceLevel(pX, pY, pZ, pMaxSurfaceLevel);
         } else {
            k = DimensionType.WAY_BELOW_MIN_Y;
         }

         return k;
      }

      private int computeRandomizedFluidSurfaceLevel(int pX, int pY, int pZ, int pMaxSurfaceLevel) {
         int i = 16;
         int j = 40;
         int k = Math.floorDiv(pX, 16);
         int l = Math.floorDiv(pY, 40);
         int i1 = Math.floorDiv(pZ, 16);
         int j1 = l * 40 + 20;
         int k1 = 10;
         double d0 = this.fluidLevelSpreadNoise.compute(new DensityFunction.SinglePointContext(k, l, i1)) * 10.0D;
         int l1 = Mth.quantize(d0, 3);
         int i2 = j1 + l1;
         return Math.min(pMaxSurfaceLevel, i2);
      }

      private BlockState computeFluidType(int pX, int pY, int pZ, FluidStatusV pFluidStatus, int pSurfaceLevel) {
         BlockState blockstate = pFluidStatus.fluidType;
         if (pSurfaceLevel <= -10 && pSurfaceLevel != DimensionType.WAY_BELOW_MIN_Y && pFluidStatus.fluidType != Blocks.LAVA.defaultBlockState()) {
            int i = 64;
            int j = 40;
            int k = Math.floorDiv(pX, 64);
            int l = Math.floorDiv(pY, 40);
            int i1 = Math.floorDiv(pZ, 64);
            double d0 = this.lavaNoise.compute(new DensityFunction.SinglePointContext(k, l, i1));
            if (Math.abs(d0) > 0.3D) {
               blockstate = Blocks.LAVA.defaultBlockState();
            }
         }

         return blockstate;
      }
   }

   static <A, O> KeyDispatchDataCodec<O> singleArgumentCodec(Codec<A> pCodec, Function<A, O> pFromFunction, Function<O, A> pToFunction) {
      return KeyDispatchDataCodec.of(pCodec.fieldOf("argument").xmap(pFromFunction, pToFunction));
   }

   static <O> KeyDispatchDataCodec<O> singleFunctionArgumentCodec(Function<DensityFunction, O> pFromFunction, Function<O, DensityFunction> pToFunction) {
      return singleArgumentCodec(DensityFunction.HOLDER_HELPER_CODEC, pFromFunction, pToFunction);
   }

   public final class OreVeinifier {
      private static final float VEININESS_THRESHOLD = 0.4F;
      private static final int EDGE_ROUNDOFF_BEGIN = 20;
      private static final double MAX_EDGE_ROUNDOFF = 0.2;
      private static final float VEIN_SOLIDNESS = 0.7F;
      private static final float MIN_RICHNESS = 0.1F;
      private static final float MAX_RICHNESS = 0.3F;
      private static final float MAX_RICHNESS_THRESHOLD = 0.6F;
      private static final float CHANCE_OF_RAW_ORE_BLOCK = 0.02F;
      private static final float SKIP_ORE_IF_GAP_NOISE_IS_BELOW = -0.3F;

      private OreVeinifier() {
      }

      protected static BlockStateFiller create(DensityFunction pVeinToggle, DensityFunction pVeinRidged, DensityFunction pVeinGap, PositionalRandomFactory pRandom) {
         BlockState $$4 = null;
         return (p_209666_) -> {
            double $$6 = pVeinToggle.compute(p_209666_);
            int $$7 = p_209666_.blockY();
            OreVeinifier.VeinType $$8 = $$6 > (double)0.0F ? OreVeinifier.VeinType.COPPER : OreVeinifier.VeinType.IRON;
            double $$9 = Math.abs($$6);
            int $$10 = $$8.maxY - $$7;
            int $$11 = $$7 - $$8.minY;
            if ($$11 >= 0 && $$10 >= 0) {
               int $$12 = Math.min($$10, $$11);
               double $$13 = Mth.clampedMap((double)$$12, (double)0.0F, (double)20.0F, -0.2, (double)0.0F);
               if ($$9 + $$13 < (double)0.4F) {
                  return $$4;
               } else {
                  RandomSource $$14 = pRandom.at(p_209666_.blockX(), $$7, p_209666_.blockZ());
                  if ($$14.nextFloat() > 0.7F) {
                     return $$4;
                  } else if (pVeinRidged.compute(p_209666_) >= (double)0.0F) {
                     return $$4;
                  } else {
                     double $$15 = Mth.clampedMap($$9, (double)0.4F, (double)0.6F, (double)0.1F, (double)0.3F);
                     if ((double)$$14.nextFloat() < $$15 && pVeinGap.compute(p_209666_) > (double)-0.3F) {
                        return $$14.nextFloat() < 0.02F ? $$8.rawOreBlock : $$8.ore;
                     } else {
                        return $$8.filler;
                     }
                  }
               }
            } else {
               return $$4;
            }
         };
      }

      protected static enum VeinType {
         COPPER(Blocks.COPPER_ORE.defaultBlockState(), Blocks.RAW_COPPER_BLOCK.defaultBlockState(), Blocks.GRANITE.defaultBlockState(), 0, 50),
         IRON(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Blocks.RAW_IRON_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState(), -60, -8);

         final BlockState ore;
         final BlockState rawOreBlock;
         final BlockState filler;
         protected final int minY;
         protected final int maxY;

         private VeinType(BlockState pOre, BlockState pRawOreBlock, BlockState pFiller, int pMinY, int pMaxY) {
            this.ore = pOre;
            this.rawOreBlock = pRawOreBlock;
            this.filler = pFiller;
            this.minY = pMinY;
            this.maxY = pMaxY;
         }
      }
   }

   class BlendAlpha implements NoiseChunkDensityFunction {
      BlendAlpha() {
      }

      public DensityFunction wrapped() {
         return BlendAlphaFunc.INSTANCE;
      }

      public DensityFunction mapAll(DensityFunction.Visitor p_224365_) {
         return this.wrapped().mapAll(p_224365_);
      }

      public double compute(DensityFunction.FunctionContext p_209264_) {
         return NoiseChunkOpt.this.getOrComputeBlendingOutput(p_209264_.blockX(), p_209264_.blockZ()).alpha();
      }

      public void fillArray(double[] p_209266_, DensityFunction.ContextProvider p_209267_) {
         p_209267_.fillAllDirectly(p_209266_, this);
      }

      public double minValue() {
         return (double)0.0F;
      }

      public double maxValue() {
         return (double)1.0F;
      }

      public KeyDispatchDataCodec<? extends DensityFunction> codec() {
         return BlendAlphaFunc.CODEC;
      }
   }

   class BlendOffset implements NoiseChunkDensityFunction {
      BlendOffset() {
      }

      public DensityFunction wrapped() {
         return BlendOffsetFunc.INSTANCE;
      }

      public DensityFunction mapAll(DensityFunction.Visitor p_224368_) {
         return this.wrapped().mapAll(p_224368_);
      }

      public double compute(DensityFunction.FunctionContext p_209276_) {
         return NoiseChunkOpt.this.getOrComputeBlendingOutput(p_209276_.blockX(), p_209276_.blockZ()).blendingOffset();
      }

      public void fillArray(double[] p_209278_, DensityFunction.ContextProvider p_209279_) {
         p_209279_.fillAllDirectly(p_209278_, this);
      }

      public double minValue() {
         return Double.NEGATIVE_INFINITY;
      }

      public double maxValue() {
         return Double.POSITIVE_INFINITY;
      }

      public KeyDispatchDataCodec<? extends DensityFunction> codec() {
         return BlendOffsetFunc.CODEC;
      }
   }

   protected static enum BlendAlphaFunc implements DensityFunction.SimpleFunction {
      INSTANCE;

      public static final KeyDispatchDataCodec<DensityFunction> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

      private BlendAlphaFunc() {
      }

      public double compute(DensityFunction.FunctionContext p_208536_) {
         return (double)1.0F;
      }

      public void fillArray(double[] p_208538_, DensityFunction.ContextProvider p_208539_) {
         Arrays.fill(p_208538_, (double)1.0F);
      }

      public double minValue() {
         return (double)1.0F;
      }

      public double maxValue() {
         return (double)1.0F;
      }

      public KeyDispatchDataCodec<? extends DensityFunction> codec() {
         return CODEC;
      }
   }

   protected static enum BlendOffsetFunc implements DensityFunction.SimpleFunction {
      INSTANCE;

      public static final KeyDispatchDataCodec<DensityFunction> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

      private BlendOffsetFunc() {
      }

      public double compute(DensityFunction.FunctionContext p_208573_) {
         return (double)0.0F;
      }

      public void fillArray(double[] p_208575_, DensityFunction.ContextProvider p_208576_) {
         Arrays.fill(p_208575_, (double)0.0F);
      }

      public double minValue() {
         return (double)0.0F;
      }

      public double maxValue() {
         return (double)0.0F;
      }

      public KeyDispatchDataCodec<? extends DensityFunction> codec() {
         return CODEC;
      }
   }

}

