package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.lost.DamageArea;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the explosions did, and the repair afterwards.
 *
 * <p>Two passes over the chunk, run back to back and in this order: {@link #breakBlocks} walks every
 * section an explosion intersects and replaces what it damaged, and {@link #fixFloatingBlocks} then
 * deletes whatever the first pass left hanging in the air with nothing under it.</p>
 *
 * <p>Moved out of {@link CityGenerator} unchanged - same code, same order, same RNG - as part of
 * splitting that class up (issue #11). It reaches back through {@code feature} for the dimension's
 * shape and its air and liquid states, which is the convention the rest of this package already
 * uses.</p>
 */
public class Damage {

    private Damage() {
    }

    public static void breakBlocks(ChunkGenContext ctx, CityGenerator feature, int chunkX, int chunkZ, ChunkPlan info) {
        ChunkDriver driver = ctx.driver;
        int cx = chunkX << 4;
        int cz = chunkZ << 4;

        DamageArea damageArea = info.getDamageArea();

        float damageFactor = 1.0f;

        boolean hasCollectedDamage = false;
        float[][] collectedDamage = new float[16][16];

        int minSection = feature.provider.shape().minSection();
        int maxSection = feature.provider.shape().maxSection();
        for (int yy = minSection; yy <= maxSection; yy++) {
            java.util.List<dev.krona.urbex.worldgen.lost.Explosion> sectionExplosions = damageArea.explosionsIntersecting(yy);
            boolean hasExplosions = !sectionExplosions.isEmpty();
            for (int y = 0; y < 16; y++) {
                if (hasExplosions) {
                    int cury = yy * 16 + y;
                    for (int x = 0; x < 16; x++) {
                        driver.current(x, cury, 0);
                        for (int z = 0; z < 16; z++) {
                            BlockState d = driver.getBlock();
                            if (d != feature.air || cury <= info.waterLevel) {
                                float damage = DamageArea.getDamage(sectionExplosions, cx + x, cury, cz + z) * damageFactor;
                                if (damage >= 0.001) {
                                    collectedDamage[x][z] += damage;
                                    hasCollectedDamage = true;
                                }
                            }
                            driver.incZ();
                        }
                    }
                }
                if (hasCollectedDamage) {
                    int cntDamaged = 0;
                    int cntAir = 0;
                    int cury = yy * 16 + y;
                    hasCollectedDamage = false;
                    for (int x = 0; x < 16; x++) {
                        driver.current(x, cury, 0);
                        for (int z = 0; z < 16; z++) {
                            BlockState d = driver.getBlock();
                            if (d != feature.air || cury <= info.waterLevel) {
                                float damage = collectedDamage[x][z];
                                if (damage >= 0.001) {
                                    BlockState newd = damageArea.damageBlock(d, feature.provider, ctx.tags, cx + x, cury, cz + z, damage, info.getCompiledPalette(), feature.liquid);
                                    if (newd != d) {
                                        driver.block(newd);
                                        cntDamaged++;
                                    }
                                }
                            } else {
                                cntAir++;
                            }
                            driver.incZ();
//                            collectedDamage[x][z] -= .75f;
                            collectedDamage[x][z] /= 1.4f;
//                            collectedDamage[x][z] = 0;
                            if (collectedDamage[x][z] <= 0) {
                                collectedDamage[x][z] = 0;
                            } else {
                                hasCollectedDamage = true;
                            }
                        }
                    }

                    int tot = cntDamaged + cntAir;
                    if (tot > 250) {
                        damageFactor = 200;
                    } else if (tot > 220) {
                        damageFactor = damageFactor * 1.4f;
                    } else if (tot > 180) {
                        damageFactor = damageFactor * 1.2f;
                    }

                }
            }
        }
    }

    private static int countNotEmpty(ChunkGenContext ctx, CityGenerator feature, int y, int max) {
        ChunkDriver driver = ctx.driver;
        int cnt = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (driver.getBlock(x, y, z) != feature.air) {
                    cnt++;
                    if (cnt >= max) {
                        return cnt;
                    }
                }
            }
        }
        return cnt;
    }

    /// Fix floating blocks after an explosion
    public static void fixFloatingBlocks(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info) {
        ChunkDriver driver = ctx.driver;
        if (info.profile.isCavern() && !info.hasBuilding) {
            // In a cavern we only do this correction when there is a building
            return;
        }

        int start = info.getDamageArea().getLowestExplosionHeight();
        if (start == -1) {
            // Nothing is affected
            return;
        }
        int end = info.getDamageArea().getHighestExplosionHeight();

        for (int y = start; y <= end; y++) {
            int count = countNotEmpty(ctx, feature, y, 20);
            if (count < 16) {   // @todo configurable?
                // (Almost) empty! That means everything above this can be deleted
                // Except in a cavern, there we only delete the building
                if (info.profile.isCavern()) {
                    // We know we have a building
                    int maxY = info.getCityGroundLevel() + info.getNumFloors() * CityGenerator.FLOORHEIGHT;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            driver.setBlockRangeToAir(x, y + 1, z, maxY);
                        }
                    }
                } else {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            driver.setBlockRangeToAir(x, y + 1, z, feature.provider.shape().maxBuildHeight());
                        }
                    }
                }
                break;
            }
        }
    }
}
