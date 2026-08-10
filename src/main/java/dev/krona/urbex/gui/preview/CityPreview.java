package dev.krona.urbex.gui.preview;

import com.mojang.blaze3d.platform.NativeImage;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.gui.NullDimensionInfo;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.ChunkCharacteristics;
import dev.krona.urbex.worldgen.lost.City;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldOptions;

import javax.annotation.Nullable;

/**
 * The world-creation city/building preview: a small (62x58) chunk-grid map sampled from a
 * {@link NullDimensionInfo}, cached against the (profile, worldstyle, seed) that produced it so
 * repeated {@link #update} calls while the player is merely dragging a slider don't recompute
 * anything.
 * <p>
 * "Honest" relative to the old editor's preview map renderer: that preview always ran
 * with {@code IDimensionInfo.getWorld() == null}, which silently skipped the worldstyle
 * city-chance multiplier and the CITY_MINHEIGHT/MAXHEIGHT gate in {@code City.getCityFactor} (both
 * guarded on {@code getWorld() != null}). This preview is built with real registry access, so those
 * guards - now keyed on {@code registryAccess() != null} - evaluate the same rules a real dimension
 * would (see {@code City.java} and {@code NullDimensionInfo} for the registry-access plumbing).
 */
public class CityPreview implements AutoCloseable {

    public static final int WIDTH = NullDimensionInfo.PREVIEW_WIDTH;
    public static final int HEIGHT = NullDimensionInfo.PREVIEW_HEIGHT;

    /** The fixed-height legend strip {@link #render} draws below the map; public so callers that
     *  size a host widget can budget for it (the map itself keeps the {@link #WIDTH}:{@link #HEIGHT}
     *  aspect ratio, the legend is extra). */
    public static final int LEGEND_HEIGHT = 10;
    private static final int SWATCH_SIZE = 8;
    private static final int SWATCH_GAP = 6;

    private static final int CITY_COLOR = 0xff995555;
    private static final int BUILDING_COLOR = 0xffffffff;
    private static final int WATER_COLOR = 0xff000066;

    private record Key(int profileJsonHash, String worldStyle, long seed) {}

    @Nullable
    private final RegistryAccess registryAccess;

    private final int[] colors = new int[WIDTH * HEIGHT];

    @Nullable
    private Key key;
    @Nullable
    private DynamicTexture texture;

    public CityPreview(@Nullable RegistryAccess registryAccess) {
        this.registryAccess = registryAccess;
    }

    /**
     * Recomputes the preview iff (profile JSON, worldStyle, seed) differs from the last call.
     * {@code profile == null} clears whatever is showing (nothing selected yet).
     */
    public void update(@Nullable UrbexProfile profile, String worldStyle, long seed) {
        if (profile == null) {
            key = null;
            closeTexture();
            return;
        }
        int profileJsonHash = profile.toJson(false).toString().hashCode();
        if (!needsRecompute(profileJsonHash, worldStyle, seed)) {
            return;
        }
        recompute(profile, seed);
    }

    /**
     * True (and remembers the new key) the first time this exact key is seen; false - no recompute
     * needed - on every repeat. Package-visible so the cache behaviour is testable without a
     * running game (no GL, no registries required).
     */
    boolean needsRecompute(int profileJsonHash, String worldStyle, long seed) {
        Key newKey = new Key(profileJsonHash, worldStyle, seed);
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
        // (now-null) texture is still valid for that (profile, worldStyle, seed) and render
        // "unavailable" forever. Callers rebuild today, so this only removes the trap.
        key = null;
    }

    private void recompute(UrbexProfile profile, long seed) {
        // The datapack-derived predefined-city/street maps are static (shared with real worldgen)
        // and keyed only by chunk coord, not by profile - drop them so a new profile/seed combo
        // doesn't see another profile's predefined content. Mirrors the old editor's preview
        // refresh.
        City.cleanPredefinedCache();
        NullDimensionInfo diminfo = new NullDimensionInfo(profile, seed, registryAccess);
        for (int z = 0; z < HEIGHT; z++) {
            for (int x = 0; x < WIDTH; x++) {
                colors[z * WIDTH + x] = sampleColor(diminfo, x, z);
            }
        }
        uploadTexture();
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

    private static void renderLegend(GuiGraphicsExtractor g, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int cx = x;
        cx = renderSwatch(g, font, cx, y, CITY_COLOR, Component.translatable("urbex.preview.legend.city"));
        cx = renderSwatch(g, font, cx, y, BUILDING_COLOR, Component.translatable("urbex.preview.legend.building"));
        renderSwatch(g, font, cx, y, WATER_COLOR, Component.translatable("urbex.preview.legend.water"));
    }

    private static int renderSwatch(GuiGraphicsExtractor g, Font font, int x, int y, int color, Component label) {
        g.fill(x, y, x + SWATCH_SIZE, y + SWATCH_SIZE, color);
        int textX = x + SWATCH_SIZE + 2;
        g.text(font, label, textX, y + 1, 0xffffffff);
        return textX + font.width(label) + SWATCH_GAP;
    }
}
