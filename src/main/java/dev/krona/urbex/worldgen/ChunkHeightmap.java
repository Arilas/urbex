package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.LandscapeType;

/**
 * A heightmap for a chunk
 */
public class ChunkHeightmap {
    private int height;
    private final LandscapeType type;
    private final int groundLevel;

    // Only valid when 'calculateAccurateHeight()' is called
    private int minHeight;
    private int maxHeight;

    public ChunkHeightmap(LandscapeType type, int groundLevel) {
        this.groundLevel = groundLevel;
        this.type = type;
        height = Short.MIN_VALUE;
    }

    // Make a copy
    public ChunkHeightmap(ChunkHeightmap other) {
        this.height = other.height;
        this.type = other.type;
        this.groundLevel = other.groundLevel;
        this.minHeight = other.minHeight;
        this.maxHeight = other.maxHeight;
    }

    public void update(int y) {
        int current = height;
        if (y <= current) {
            return;
        }

        if (type == LandscapeType.CAVERN) {
            // Here we try to find the height inside the cavern itself. Ignoring the top layer
            int base = Math.max(groundLevel - 20, 1);
            if (y > 100 || y < base) {
                return;
            }
            if (y == 100) {
                y = 127;
            }
        }
        height = y;
    }

    public int getHeight() {
        if (height < -4000) {
            return groundLevel;
        }
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    /**
     * Folds four extra sampled heights into this map's min/max, alongside its own.
     * <p>
     * Used to be {@code calculateAccurateHeight(WorldGenLevel, chunkX, chunkZ)}, which took a level
     * only to reach the chunk generator and sample those four points itself. Where the samples come
     * from is {@link TerrainSampler#sampleAccurateHeight}'s business - a preview has no generator to
     * ask - and folding them in is this class's (issue #129).
     * <p>
     * Reads the raw {@link #height} field rather than {@link #getHeight()}, unchanged: an unsampled
     * map contributes {@link Short#MIN_VALUE} to the minimum, not its ground level.
     */
    public void accurateHeights(int height0, int height1, int height2, int height3) {
        minHeight = Math.min(height, Math.min(height0, Math.min(height1, Math.min(height2, height3))));
        maxHeight = Math.max(height, Math.max(height0, Math.max(height1, Math.max(height2, height3))));
    }

    public int getMinHeight() {
        return minHeight;
    }

    public int getMaxHeight() {
        return maxHeight;
    }
}
