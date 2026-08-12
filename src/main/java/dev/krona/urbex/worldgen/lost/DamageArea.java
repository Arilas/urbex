package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.GeometryTools;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.TagSnapshot;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class DamageArea {

    public static final float BLOCK_DAMAGE_CHANCE = .7f;

    private final long seed;
    private final int chunkX;
    private final int chunkZ;
    private final List<Explosion> explosions = new ArrayList<>();
    private final AABB chunkBox;
    private final Preset profile;
    private final int minSectionY;
    private final int maxSectionY;

    private final BlockState air;

    public DamageArea(int chunkX, int chunkZ, IDimensionInfo provider, BuildingInfo info) {
        this.seed = provider.getSeed();
        this.profile = info.profile;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.air = Blocks.AIR.defaultBlockState();
        chunkBox = new AABB(chunkX << 4, provider.getWorld().getMinY(), chunkZ << 4, (chunkX << 4) + 15, provider.getWorld().getMaxY() + 1, (chunkZ << 4) + 15);
        this.minSectionY = provider.getWorld().getMinY() >> 4;
        this.maxSectionY = provider.getWorld().getMaxY() >> 4;

        int offset = (Math.max(info.profile.EXPLOSION_MAXRADIUS, info.profile.MINI_EXPLOSION_MAXRADIUS)+15) / 16;
        for (int cx = chunkX - offset; cx <= chunkX + offset; cx++) {
            for (int cz = chunkZ - offset; cz <= chunkZ + offset; cz++) {
                ChunkCoord coord = new ChunkCoord(provider.getType(), cx, cz);
                if ((!info.profile.EXPLOSIONS_IN_CITIES_ONLY) || BuildingInfo.isCity(coord, provider)) {
                    Explosion explosion = getExplosionAt(coord, provider);
                    if (explosion != null) {
                        if (intersectsWith(explosion.getCenter(), explosion.getRadius())) {
//                            Float chance = BuildingInfo.getBuildingInfo(cx, cz, provider).getChunkCharacteristics(cx, cz, provider).cityStyle.getExplosionChance();
                            Float chance = BuildingInfo.getChunkCharacteristics(coord, provider).cityStyle.getExplosionChance();
                            if (isAccepted(coord, chance, Rng.Purpose.EXPLOSION_ACCEPT)) {
                                explosions.add(explosion);
                            }
                        }
                    }
                    explosion = getMiniExplosionAt(coord, provider);
                    if (explosion != null) {
                        if (intersectsWith(explosion.getCenter(), explosion.getRadius())) {
//                            Float chance = BuildingInfo.getBuildingInfo(cx, cz, provider).getChunkCharacteristics(cx, cz, provider).cityStyle.getExplosionChance();
                            Float chance = BuildingInfo.getChunkCharacteristics(coord, provider).cityStyle.getExplosionChance();
                            if (isAccepted(coord, chance, Rng.Purpose.EXPLOSION_MINI_ACCEPT)) {
                                explosions.add(explosion);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Whether the city style keeps the explosion rolled at {@code coord}.
     * <p>
     * Addressed at the explosion's own chunk, not at the chunk asking. Every chunk within the blast
     * radius evaluates the same explosion, and each reaches it having skipped a different number of
     * non-intersecting candidates - so a single per-chunk stream gave the same explosion a different
     * draw in each observer, and a crater was accepted on one side of a chunk border and rejected on
     * the other. This is the case {@code ChunkGenContext.rng} warns about: a stream drawn a variable
     * number of times per chunk. Addressed by {@code coord}, every observer agrees.
     */
    private boolean isAccepted(ChunkCoord coord, Float chance, Rng.Purpose purpose) {
        if (chance == null) {
            return true;
        }
        return Rng.at(seed, coord.chunkX(), coord.chunkZ(), purpose).nextFloat() < chance;
    }

    /**
     * Damage one block. The two rolls are addressed by the block's own position rather than drawn
     * from a stream: whether a block is damaged is decided per block, and the number of blocks
     * damaged before it depends on what was already in the world.
     * <p>
     * {@code tags} is passed in rather than held on this object: a {@code DamageArea} is cached on
     * the chunk's {@link BuildingInfo} and outlives any one generation, so a tag epoch stored here
     * would be the wrong one for every chunk after the next {@code /reload} (issue #128).
     */
    public BlockState damageBlock(BlockState b, IDimensionInfo provider, TagSnapshot tags, int x, int y, int z, float damage, CompiledPalette palette, BlockState liquidChar) {
        if (tags.isNotBreakable(b)) {
            return b;
        }

        if (tags.isEasyBreakable(b)) {
            damage *= 2.5f;    // As if this block gets double the damage
        }
        if (Rng.floatAtPos(seed, x, y, z, Rng.Purpose.DAMAGE) <= damage) {
            BlockState damaged = palette.canBeDamagedToIronBars(b);
            int waterlevel = Tools.getSeaLevel(provider.getWorld());//profile.GROUNDLEVEL - profile.WATERLEVEL_OFFSET;
            if (damage < BLOCK_DAMAGE_CHANCE && damaged != null) {
                if (Rng.floatAtPos(seed, x, y, z, Rng.Purpose.DAMAGE_VARIANT) < .7f) {
                    b = damaged;
                } else {
                    b = y <= waterlevel ? liquidChar : air;
                }
            } else {
                b = y <= waterlevel ? liquidChar : air;
            }
        }
        return b;
    }

    private boolean intersectsWith(BlockPos center, int radius) {
        double dmin = GeometryTools.squaredDistanceBoxPoint(chunkBox, center);
        return dmin <= radius * radius;
    }

    private Explosion getExplosionAt(ChunkCoord coord, IDimensionInfo provider) {
        RandomSource randomExplosion = Rng.at(seed, coord.chunkX(), coord.chunkZ(), Rng.Purpose.EXPLOSION);
        if (randomExplosion.nextFloat() < profile.EXPLOSION_CHANCE) {
            return new Explosion(Tools.randomBetween(randomExplosion, profile.EXPLOSION_MINRADIUS, profile.EXPLOSION_MAXRADIUS),
                    new BlockPos((coord.chunkX() << 4) + randomExplosion.nextInt(16),
                            BuildingInfo.getBuildingInfo(coord, provider).cityLevel * 6 + Tools.randomBetween(randomExplosion, profile.EXPLOSION_MINHEIGHT, profile.EXPLOSION_MAXHEIGHT),
                            (coord.chunkZ() << 4) + randomExplosion.nextInt(16)));
        }
        return null;
    }

    private Explosion getMiniExplosionAt(ChunkCoord coord, IDimensionInfo provider) {
        RandomSource randomMiniExplosion = Rng.at(seed, coord.chunkX(), coord.chunkZ(), Rng.Purpose.EXPLOSION_MINI);
        if (randomMiniExplosion.nextFloat() < profile.MINI_EXPLOSION_CHANCE) {
            return new Explosion(Tools.randomBetween(randomMiniExplosion, profile.MINI_EXPLOSION_MINRADIUS, profile.MINI_EXPLOSION_MAXRADIUS),
                    new BlockPos((coord.chunkX() << 4) + randomMiniExplosion.nextInt(16),
                            BuildingInfo.getBuildingInfo(coord, provider).cityLevel * 6 + Tools.randomBetween(randomMiniExplosion, profile.MINI_EXPLOSION_MINHEIGHT, profile.MINI_EXPLOSION_MAXHEIGHT),
                            (coord.chunkZ() << 4) + randomMiniExplosion.nextInt(16)));
        }
        return null;
    }

    // Return true if this chunk is affected by explosions
    public boolean hasExplosions() {
        return !explosions.isEmpty();
    }

    public List<Explosion> getExplosions() {
        return explosions;
    }

    // Return true if this subchunk (every 16 blocks) is affected by explosions.
    public boolean hasExplosions(int y) {
        AABB box = new AABB(chunkX << 4, y << 4, chunkZ << 4, (chunkX << 4) + 15, (y << 4) + 15, (chunkZ << 4) + 15);
        for (Explosion explosion : explosions) {
            double dmin = GeometryTools.squaredDistanceBoxPoint(box, explosion.getCenter());
            if (dmin <= explosion.getRadius() * explosion.getRadius()) {
                return true;
            }
        }
        return false;
    }

    // Get the lowest height that is affected by an explosion.
    public int getLowestExplosionHeight() {
        for (int yy = minSectionY; yy <= maxSectionY; yy++) {
            if (hasExplosions(yy)) {
                return yy * 16;
            }
        }
        return -1;
    }

    // Get the highest height that is affected by an explosion.
    public int getHighestExplosionHeight() {
        for (int yy = maxSectionY; yy >= minSectionY; yy--) {
            if (hasExplosions(yy)) {
                return yy * 16 + 15;
            }
        }
        return -1;
    }

    // Give an indication of how much damage this chunk got
    public float getDamageFactor() {
        float damage = 0.0f;
        for (Explosion explosion : explosions) {
            double sq = explosion.getCenter().distToCenterSqr(chunkX * 16.0, explosion.getCenter().getY(), chunkZ * 16.0);
            if (sq < explosion.getSqradius()) {
                double d = Math.sqrt(sq);
                damage += 3.0f * (explosion.getRadius() - d) / explosion.getRadius();
            }
        }
        return damage;
    }

    /**
     * The explosions whose blast sphere can reach subchunk {@code sectionY} of this chunk.
     * The damage loop used to re-walk the complete explosion list for every block of every
     * Y-level even though this intersection was already known per 16-block band (issue #49).
     */
    public List<Explosion> explosionsIntersecting(int sectionY) {
        AABB box = new AABB(chunkX << 4, sectionY << 4, chunkZ << 4, (chunkX << 4) + 15, (sectionY << 4) + 15, (chunkZ << 4) + 15);
        List<Explosion> result = new ArrayList<>();
        for (Explosion explosion : explosions) {
            double dmin = GeometryTools.squaredDistanceBoxPoint(box, explosion.getCenter());
            if (dmin <= explosion.getRadius() * explosion.getRadius()) {
                result.add(explosion);
            }
        }
        return result;
    }

    /** As {@link #getDamage(int, int, int)}, over a pre-filtered per-section explosion list. */
    public static float getDamage(List<Explosion> explosions, int x, int y, int z) {
        float damage = 0.0f;
        for (Explosion explosion : explosions) {
            double sq = explosion.getCenter().distToCenterSqr(x, y, z);
            if (sq < explosion.getSqradius()) {
                double d = Math.sqrt(sq);
                damage += 3.0f * (explosion.getRadius() - d) / explosion.getRadius();
            }
        }
        return damage;
    }

    // Get a number indicating how much damage this point should get. 0 Means no damage
    public float getDamage(int x, int y, int z) {
        float damage = 0.0f;
        for (Explosion explosion : explosions) {
            double sq = explosion.getCenter().distToCenterSqr(x, y, z);
            if (sq < explosion.getSqradius()) {
                double d = Math.sqrt(sq);
                damage += 3.0f * (explosion.getRadius() - d) / explosion.getRadius();
            }
        }
        return damage;
    }

}
