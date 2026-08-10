package dev.krona.urbex.gui.preview;

import com.mojang.blaze3d.platform.NativeImage;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.gui.NullDimensionInfo;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.ChunkCharacteristics;
import dev.krona.urbex.worldgen.lost.City;
import dev.krona.urbex.worldgen.lost.Highway;
import dev.krona.urbex.worldgen.lost.RailChunkType;
import dev.krona.urbex.worldgen.lost.Railway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldOptions;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * The world-creation city/building preview: a small (62x58) chunk-grid image sampled from a
 * {@link NullDimensionInfo}, cached against the (profile, worldstyle, seed, mode) that produced it so
 * repeated {@link #update} calls while the player is merely dragging a slider don't recompute
 * anything.
 * <p>
 * Three {@link Mode modes} share the same texture pipeline (compute an {@link #WIDTH}x{@link #HEIGHT}
 * {@code int[]}, upload it as a {@link DynamicTexture}, blit it aspect-fit with a legend strip below):
 * <ul>
 *   <li>{@link Mode#MAP} - the region map (city / building / water / terrain per chunk). Used by the
 *       Cities tab and by every editor category that isn't building- or transport-specific.</li>
 *   <li>{@link Mode#CITY} - a close-up side-elevation of one city's building layout (floors above the
 *       ground line, cellars below) with the explosion damage overlaid on top, so the building shape
 *       and its destruction read together in one view (the old editor's separate Buildings + Damage
 *       modes combined).</li>
 *   <li>{@link Mode#TRANSPORT} - the region map (dimmed) with the highway and rail network drawn over
 *       it.</li>
 * </ul>
 * "Honest" relative to the old editor's preview map renderer: that preview always ran
 * with {@code IDimensionInfo.getWorld() == null}, which silently skipped the worldstyle
 * city-chance multiplier and the CITY_MINHEIGHT/MAXHEIGHT gate in {@code City.getCityFactor} (both
 * guarded on {@code getWorld() != null}). This preview is built with real registry access, so those
 * guards - now keyed on {@code registryAccess() != null} - evaluate the same rules a real dimension
 * would (see {@code City.java} and {@code NullDimensionInfo} for the registry-access plumbing).
 */
public class CityPreview implements AutoCloseable {

    /** Which of the three views {@link #update} should render. Folded into the cache key. */
    public enum Mode {
        /** The region map: city / building / water / terrain, one pixel per chunk. */
        MAP,
        /** One city's building elevation (floors + cellars) with explosion damage overlaid. */
        CITY,
        /** The region map, dimmed, with the highway and rail network drawn on top. */
        TRANSPORT
    }

    public static final int WIDTH = NullDimensionInfo.PREVIEW_WIDTH;
    public static final int HEIGHT = NullDimensionInfo.PREVIEW_HEIGHT;

    /** The fixed-height legend strip {@link #render} draws below the map; public so callers that
     *  size a host widget can budget for it (the map itself keeps the {@link #WIDTH}:{@link #HEIGHT}
     *  aspect ratio, the legend is extra). */
    public static final int LEGEND_HEIGHT = 10;
    private static final int SWATCH_SIZE = 8;
    private static final int SWATCH_GAP = 6;

    // Region-map palette (MAP mode, and the dimmed base of TRANSPORT mode).
    private static final int CITY_COLOR = 0xff995555;
    private static final int BUILDING_COLOR = 0xffffffff;
    private static final int WATER_COLOR = 0xff000066;

    // Transport-overlay palette (semi-transparent, blended over the dimmed map).
    private static final int RAIL_OVERLAY = 0x99992222;
    private static final int HIGHWAY_OVERLAY = 0x99ffffff;
    private static final int HIGHWAY_OVER_RAIL_OVERLAY = 0x99777777;
    // Opaque legend swatches for the transport overlays.
    private static final int HIGHWAY_COLOR = 0xffffffff;
    private static final int RAIL_COLOR = 0xff992222;

    // City-elevation palette.
    private static final int SKY_COLOR = 0xff0099bb;
    private static final int GROUND_COLOR = 0xff996633;
    private static final int FLOOR_COLOR = 0xffffffff;
    private static final int CELLAR_COLOR = 0xff333333;
    private static final int DAMAGE_OVERLAY = 0x66ff0000;
    private static final int DAMAGE_COLOR = 0xffff0000;

    private record Key(int profileJsonHash, String worldStyle, long seed, Mode mode) {}

    @Nullable
    private final RegistryAccess registryAccess;

    private final int[] colors = new int[WIDTH * HEIGHT];

    @Nullable
    private Key key;
    @Nullable
    private DynamicTexture texture;
    /** The mode the current {@link #colors}/{@link #texture} were rendered in; drives the legend. */
    private Mode mode = Mode.MAP;

    public CityPreview(@Nullable RegistryAccess registryAccess) {
        this.registryAccess = registryAccess;
    }

    /**
     * Recomputes the preview iff (profile JSON, worldStyle, seed, mode) differs from the last call.
     * {@code profile == null} clears whatever is showing (nothing selected yet).
     */
    public void update(@Nullable UrbexProfile profile, String worldStyle, long seed, Mode mode) {
        if (profile == null) {
            key = null;
            closeTexture();
            return;
        }
        int profileJsonHash = profile.toJson(false).toString().hashCode();
        if (!needsRecompute(profileJsonHash, worldStyle, seed, mode)) {
            return;
        }
        recompute(profile, seed, mode);
    }

    /**
     * True (and remembers the new key) the first time this exact key is seen; false - no recompute
     * needed - on every repeat. Package-visible so the cache behaviour is testable without a
     * running game (no GL, no registries required).
     */
    boolean needsRecompute(int profileJsonHash, String worldStyle, long seed, Mode mode) {
        Key newKey = new Key(profileJsonHash, worldStyle, seed, mode);
        if (newKey.equals(key)) {
            return false;
        }
        key = newKey;
        return true;
    }

    /**
     * Vanilla's own rule for turning the seed text field into a long
     * ({@code WorldOptions.parseSeed}): trim; blank means "no seed typed" (the fallback, normally a
     * freshly-rolled random seed); a number parses as that number; anything else hashes.
     */
    public static long seedFromUi(String seedField, long fallbackRandom) {
        return WorldOptions.parseSeed(seedField).orElse(fallbackRandom);
    }

    /**
     * The largest {@code (w, h)} that fits a {@code srcW x srcH} map inside an {@code availW x availH}
     * box while preserving the {@code srcW:srcH} aspect ratio (so the source never smears the way a
     * free-stretched blit does). Fits to the width first and falls back to the height when that would
     * overflow, so the result is bounded by both axes. Returns {@code {0, 0}} for any non-positive
     * input. Pure integer math with no GL or texture dependency, so it is unit-testable headlessly;
     * callers add {@link #LEGEND_HEIGHT} on top of the returned {@code h} for the legend strip.
     */
    public static int[] fitPreview(int availW, int availH, int srcW, int srcH) {
        if (availW <= 0 || availH <= 0 || srcW <= 0 || srcH <= 0) {
            return new int[]{0, 0};
        }
        int w = availW;
        int h = Math.round((float) w * srcH / srcW);
        if (h > availH) {
            h = availH;
            w = Math.round((float) h * srcW / srcH);
        }
        return new int[]{Math.min(w, availW), Math.min(h, availH)};
    }

    public void render(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (texture == null) {
            renderUnavailable(g, x, y, w, h);
            return;
        }
        int mapHeight = Math.max(0, h - LEGEND_HEIGHT);
        // blit(view, sampler, x0, y0, x1, y1, u0, u1, v0, v1): the int quartet is the *absolute end*
        // corner (x1, y1), not a width/height - confirmed by decompiling BlitRenderState.getBounds,
        // which builds its ScreenRectangle as (x0, y0, x1 - x0, y1 - y0). The float quartet groups
        // as (u0, u1, v0, v1), not (u0, v0, u1, v1) - confirmed against GuiGraphicsExtractor's own
        // internal 128x128 blit call, which passes (0, 0, 128, 128, 0.0F, 1.0F, 0.0F, 1.0F, -1) to
        // this same innerBlit. Full-texture UVs are therefore (0, 1, 0, 1), not (0, 0, 1, 1).
        g.blit(texture.getTextureView(), texture.getSampler(), x, y, x + w, y + mapHeight, 0f, 1f, 0f, 1f);
        renderLegend(g, x, y + mapHeight);
    }

    @Override
    public void close() {
        closeTexture();
        // Drop the cache key too: leaving it set would make a reused-after-close preview believe its
        // (now-null) texture is still valid for that key and render "unavailable" forever. Callers
        // rebuild today, so this only removes the trap.
        key = null;
    }

    private void recompute(UrbexProfile profile, long seed, Mode mode) {
        this.mode = mode;
        // The datapack-derived predefined-city/street maps are static (shared with real worldgen)
        // and keyed only by chunk coord, not by profile - drop them so a new profile/seed combo
        // doesn't see another profile's predefined content. Mirrors the old editor's preview
        // refresh.
        City.cleanPredefinedCache();
        switch (mode) {
            // Only the map/transport samplers walk a NullDimensionInfo; CITY renders straight from the profile,
            // so it does not pay to build one there.
            case MAP -> renderMap(new NullDimensionInfo(profile, seed, registryAccess));
            case TRANSPORT -> renderTransport(new NullDimensionInfo(profile, seed, registryAccess), profile);
            case CITY -> renderCity(profile, seed);
        }
        uploadTexture();
    }

    // ---- MAP -----------------------------------------------------------------

    private void renderMap(NullDimensionInfo diminfo) {
        for (int z = 0; z < HEIGHT; z++) {
            for (int x = 0; x < WIDTH; x++) {
                colors[z * WIDTH + x] = sampleColor(diminfo, x, z);
            }
        }
    }

    private static int sampleColor(NullDimensionInfo diminfo, int x, int z) {
        char b = diminfo.getBiomeChar(x, z);
        int terrainColor = switch (b) {
            // '-' and '=' are the ocean/river water chars - same blue the old renderPreviewMap used.
            case '-', '=' -> WATER_COLOR;
            case '#' -> 0xff447744;
            case '+' -> 0xff335533;
            case '*', 'd' -> 0xffcccc55;
            default -> 0xff005500;
        };
        ChunkCoord coord = new ChunkCoord(diminfo.dimension(), x, z);
        ChunkCharacteristics characteristics = BuildingInfo.getChunkCharacteristicsGui(coord, diminfo);
        if (characteristics.isCity) {
            return BuildingInfo.hasBuildingGui(x, z, diminfo, characteristics) ? BUILDING_COLOR : CITY_COLOR;
        }
        return terrainColor;
    }

    // ---- TRANSPORT -----------------------------------------------------------

    /**
     * The region map dimmed to a third of its brightness (so the network reads on top of it, as the
     * old {@code renderPreviewTransports} did with its {@code soft} base), with rail and highway
     * chunks blended over. Highway-over-rail is a distinct grey so an interchange is visible.
     */
    private void renderTransport(NullDimensionInfo diminfo, UrbexProfile profile) {
        for (int z = 0; z < HEIGHT; z++) {
            for (int x = 0; x < WIDTH; x++) {
                int base = soften(sampleColor(diminfo, x, z));
                ChunkCoord c = new ChunkCoord(diminfo.dimension(), x, z);
                boolean hasRail = Railway.getRailChunkType(c, diminfo, profile).getType() != RailChunkType.NONE;
                int overlay = hasRail ? RAIL_OVERLAY : 0;
                int levelX = Highway.getXHighwayLevel(c, diminfo, profile);
                int levelZ = Highway.getZHighwayLevel(c, diminfo, profile);
                if (levelX >= 0 || levelZ >= 0) {
                    overlay = hasRail ? HIGHWAY_OVER_RAIL_OVERLAY : HIGHWAY_OVERLAY;
                }
                colors[z * WIDTH + x] = overlay == 0 ? base : blend(base, overlay);
            }
        }
    }

    // ---- CITY ----------------------------------------------------------------

    /**
     * A side-elevation schematic of one city, ported from the old editor's {@code renderPreviewCity}
     * with {@code showDamage} always on. The 14 columns are a slice through the city (distance from
     * the centre column decides how many floors each building rolls, via the same
     * BUILDING_MINFLOORS/MAXFLOORS(_CHANCE) math the generator uses); floors rise above the ground
     * line, cellars drop below it, and the two explosion footprints (EXPLOSION_* and MINI_EXPLOSION_*)
     * are stippled over the result in translucent red. Dimensions are scaled from the old fixed
     * 150-pixel canvas into the {@link #WIDTH}x{@link #HEIGHT} texture buffer, so the proportions match
     * the original at a smaller size.
     */
    private void renderCity(UrbexProfile profile, long seed) {
        final int base = 44;      // ground line (of HEIGHT = 58): ~0.8 down, as the old base/150 was
        final int dimHor = 4;     // column pitch (old 10 of 150 -> 4 of 62)
        final int dimVer = 2;     // floor/cellar pitch (old 4 of 150 -> 2 of 58)

        // Sky above the ground line, ground below it.
        fillRect(0, 0, WIDTH, base, SKY_COLOR);
        fillRect(0, base, WIDTH, HEIGHT, GROUND_COLOR);

        final float radius = 190;
        Random rand = new Random(seed);
        for (int x = 0; x < 14; x++) {
            float factor = 0;
            float sqdist = (x * 16 - 7 * 16) * (x * 16 - 7 * 16);
            if (sqdist < radius * radius) {
                float dist = (float) Math.sqrt(sqdist);
                factor = (radius - dist) / radius;
            }
            if (factor > 0 && x > 0) {
                int maxfloors = profile.BUILDING_MAXFLOORS;
                int randdist = (int) (profile.BUILDING_MINFLOORS_CHANCE
                        + (factor + .1f) * (profile.BUILDING_MAXFLOORS_CHANCE - profile.BUILDING_MINFLOORS_CHANCE));
                if (randdist < 1) {
                    randdist = 1;
                }
                int f = profile.BUILDING_MINFLOORS + rand.nextInt(randdist);
                f++;
                if (f > maxfloors + 1) {
                    f = maxfloors + 1;
                }
                int minfloors = profile.BUILDING_MINFLOORS + 1;
                if (f < minfloors) {
                    f = minfloors;
                }
                for (int i = 0; i < f; i++) {
                    fillRect(dimHor * x, base - i * dimVer - dimVer,
                            dimHor * x + dimHor - 1, base - i * dimVer + dimVer - 1 - dimVer, FLOOR_COLOR);
                }

                int maxcellars = profile.BUILDING_MAXCELLARS;
                int fb = profile.BUILDING_MINCELLARS + ((maxcellars <= 0) ? 0 : rand.nextInt(maxcellars + 1));
                for (int i = 0; i < fb; i++) {
                    fillRect(dimHor * x, base + i * dimVer,
                            dimHor * x + dimHor - 1, base + i * dimVer + dimVer - 1, CELLAR_COLOR);
                }
            }
        }

        // Damage: the two explosion footprints, stippled with a single shared RNG exactly as the old
        // renderPreviewCity(showDamage=true) did (one Random(333) drives both blasts in order).
        float horFactor = 1.0f * dimHor / 16.0f;
        float verFactor = 1.0f * dimVer / 6.0f;
        Random rnd = new Random(333);
        // Old centres were leftRender+75 and leftRender+35 within a 150-wide canvas (0.5 and ~0.233).
        drawExplosion(base, horFactor, verFactor, Math.round(WIDTH * 0.5f),
                profile.EXPLOSION_MINHEIGHT, profile.EXPLOSION_MAXRADIUS, rnd);
        drawExplosion(base, horFactor, verFactor, Math.round(WIDTH * 0.233f),
                profile.MINI_EXPLOSION_MINHEIGHT, profile.MINI_EXPLOSION_MAXRADIUS, rnd);
    }

    private void drawExplosion(int base, float horFactor, float verFactor, int cx,
                               int minHeight, int explosionRadius, Random rnd) {
        int cz = (int) (base - (minHeight - 65) * verFactor);
        for (int x = (int) (cx - explosionRadius * horFactor); x <= cx + explosionRadius * horFactor; x++) {
            for (int z = (int) (cz - explosionRadius * verFactor); z <= cz + explosionRadius * verFactor; z++) {
                double sqdist = (cx - x) * (cx - x) / horFactor / horFactor
                        + (cz - z) * (cz - z) / verFactor / verFactor;
                double dist = Math.sqrt(sqdist);
                if (dist < explosionRadius - 3) {
                    double damage = 3.0f * (explosionRadius - dist) / explosionRadius;
                    if (rnd.nextFloat() < damage) {
                        fillRect(x, z, x + 1, z + 1, DAMAGE_OVERLAY);
                    }
                }
            }
        }
    }

    // ---- buffer helpers ------------------------------------------------------

    /**
     * Fills {@code [x0, x1) x [y0, y1)} of the colour buffer with {@code argb} (the same half-open
     * rectangle convention {@code GuiGraphics.fill} uses). Fully opaque colours overwrite; anything
     * with alpha &lt; 0xff is alpha-blended over what is already there. Out-of-bounds pixels are
     * clipped, so callers don't have to bounds-check the elevation/explosion math.
     */
    private void fillRect(int x0, int y0, int x1, int y1, int argb) {
        int lox = Math.max(0, x0);
        int loy = Math.max(0, y0);
        int hix = Math.min(WIDTH, x1);
        int hiy = Math.min(HEIGHT, y1);
        boolean opaque = ((argb >>> 24) & 0xff) == 0xff;
        for (int y = loy; y < hiy; y++) {
            for (int x = lox; x < hix; x++) {
                int idx = y * WIDTH + x;
                colors[idx] = opaque ? argb : blend(colors[idx], argb);
            }
        }
    }

    /** Straight src-over-dst alpha blend; the destination is treated as opaque (it always is here). */
    private static int blend(int dst, int src) {
        int sa = (src >>> 24) & 0xff;
        if (sa == 0) {
            return dst;
        }
        if (sa == 0xff) {
            return src;
        }
        int inv = 255 - sa;
        int r = (((src >> 16) & 0xff) * sa + ((dst >> 16) & 0xff) * inv) / 255;
        int g = (((src >> 8) & 0xff) * sa + ((dst >> 8) & 0xff) * inv) / 255;
        int b = ((src & 0xff) * sa + (dst & 0xff) * inv) / 255;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    /** Dims an opaque colour to a third of its brightness (the old {@code soften}), keeping alpha. */
    private static int soften(int argb) {
        int r = ((argb >> 16) & 0xff) / 3;
        int g = ((argb >> 8) & 0xff) / 3;
        int b = (argb & 0xff) / 3;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private void uploadTexture() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, WIDTH, HEIGHT, false);
        for (int z = 0; z < HEIGHT; z++) {
            for (int x = 0; x < WIDTH; x++) {
                image.setPixel(x, z, colors[z * WIDTH + x]);
            }
        }
        DynamicTexture next = new DynamicTexture(() -> "urbex_city_preview", image);
        closeTexture();
        texture = next;
    }

    private void closeTexture() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    private static void renderUnavailable(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        Font font = Minecraft.getInstance().font;
        Component message = Component.translatable("urbex.preview.unavailable");
        int textX = x + Math.max(0, (w - font.width(message)) / 2);
        int textY = y + Math.max(0, (h - font.lineHeight) / 2);
        g.text(font, message, textX, textY, 0xffaaaaaa);
    }

    /** The legend under the map, whose swatches name whatever {@link #mode} is currently shown. */
    private void renderLegend(GuiGraphicsExtractor g, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int cx = x;
        switch (mode) {
            case MAP -> {
                cx = renderSwatch(g, font, cx, y, CITY_COLOR, Component.translatable("urbex.preview.legend.city"));
                cx = renderSwatch(g, font, cx, y, BUILDING_COLOR, Component.translatable("urbex.preview.legend.building"));
                renderSwatch(g, font, cx, y, WATER_COLOR, Component.translatable("urbex.preview.legend.water"));
            }
            case CITY -> {
                cx = renderSwatch(g, font, cx, y, BUILDING_COLOR, Component.translatable("urbex.preview.legend.building"));
                cx = renderSwatch(g, font, cx, y, DAMAGE_COLOR, Component.translatable("urbex.preview.legend.damage"));
                renderSwatch(g, font, cx, y, SKY_COLOR, Component.translatable("urbex.preview.legend.empty"));
            }
            case TRANSPORT -> {
                cx = renderSwatch(g, font, cx, y, HIGHWAY_COLOR, Component.translatable("urbex.preview.legend.highway"));
                cx = renderSwatch(g, font, cx, y, RAIL_COLOR, Component.translatable("urbex.preview.legend.rail"));
                renderSwatch(g, font, cx, y, CITY_COLOR, Component.translatable("urbex.preview.legend.city"));
            }
        }
    }

    private static int renderSwatch(GuiGraphicsExtractor g, Font font, int x, int y, int color, Component label) {
        g.fill(x, y, x + SWATCH_SIZE, y + SWATCH_SIZE, color);
        int textX = x + SWATCH_SIZE + 2;
        g.text(font, label, textX, y + 1, 0xffffffff);
        return textX + font.width(label) + SWATCH_GAP;
    }
}
