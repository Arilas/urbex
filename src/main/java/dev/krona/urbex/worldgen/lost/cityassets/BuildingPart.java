package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartRE;
import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartMeta;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CommonLevelAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A structure part
 */
public class BuildingPart implements IBuildingPart {

    // Meta values that you can use in assets
    public static final String META_DONTCONNECT = "dontconnect";
    public static final String META_SUPPORT = "support";
    public static final String META_Z_1 = "z1";
    public static final String META_Z_2 = "z2";
    public static final String META_NOWATER = "nowater";

    private final Identifier name;

    // Data per height level
    private final String[] slices;

    // Dimension (should be less then 16x16)
    private final int xSize;
    private final int zSize;

    // Optimized version of this part which is organized in xSize*ySize vertical strings.
    // Built in the constructor: a BuildingPart is held by the asset registry and shared by every
    // chunk being generated, so filling this in on first use was a data race the moment worldgen
    // stopped being serialised. There are a couple of hundred parts and each is tiny.
    private final char[][] vslices;

    // Cannot be resolved in the constructor when it is a *reference* to another palette: that needs
    // the level to reach the registry, and the level is not available here. Volatile, and the
    // resolution is idempotent (the registry hands back the same Palette), so a race just means two
    // threads look the same thing up.
    private volatile Palette localPalette = null;
    private String refPaletteName;

    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * Builds a fully resolved part from its {@code extends} chain, root first.
     * <p>
     * Geometry - {@code slices}, {@code xsize} and {@code zsize} - comes from the last entry in the
     * chain that declares it, each independently, which is what makes "the radio tower, repainted"
     * a file holding nothing but an {@code extends} and a {@code refpalette}. Declaring
     * {@code slices} replaces the inherited ones wholesale; declaring a size that contradicts the
     * slices actually in force is a load error rather than a silent truncation.
     */
    public BuildingPart(List<BuildingPartRE> chainRootFirst) {
        BuildingPartRE leaf = chainRootFirst.get(chainRootFirst.size() - 1);
        name = leaf.getRegistryName();

        Integer declaredXSize = null;
        Integer declaredZSize = null;
        String[] declaredSlices = null;
        PaletteRE localPaletteRE = null;
        String refPalette = null;
        List<PartMeta> meta = new ArrayList<>();
        for (BuildingPartRE re : chainRootFirst) {
            if (re.getxSize() != null) {
                declaredXSize = re.getxSize();
            }
            if (re.getzSize() != null) {
                declaredZSize = re.getzSize();
            }
            if (re.getSlices() != null) {
                declaredSlices = re.getSlices();
            }
            // refpalette and an inline palette are two ways to say the same thing, so the later
            // entry's choice wins outright rather than layering onto the earlier one's.
            if (re.getLocalPalette() != null) {
                localPaletteRE = re.getLocalPalette();
                refPalette = null;
            } else if (re.getRefPaletteName() != null) {
                refPalette = re.getRefPaletteName();
                localPaletteRE = null;
            }
            if (re.getMetadata() != null) {
                Mergeable.apply(meta, re.getMetadata());
            }
        }

        if (declaredSlices == null) {
            throw new IllegalStateException("Part '" + name + "' declares no slices, "
                    + "and neither does anything it extends");
        }
        if (declaredXSize == null || declaredZSize == null) {
            throw new IllegalStateException("Part '" + name + "' declares no "
                    + (declaredXSize == null ? "xsize" : "zsize")
                    + ", and neither does anything it extends");
        }
        xSize = declaredXSize;
        zSize = declaredZSize;
        checkGeometry(declaredSlices);
        slices = declaredSlices;

        if (localPaletteRE != null) {
            localPalette = new Palette("__local__" + name.getPath());
            localPalette.parsePaletteArray(localPaletteRE); // @todo get the full palette instead
        } else if (refPalette != null) {
            refPaletteName = refPalette;
        }
        vslices = buildVslices();
        for (PartMeta m : meta) {
            String key = m.key();
            if (m.i() != null) {
                metadata.put(key, m.i());
            } else if (m.f() != null) {
                metadata.put(key, m.f());
            } else if (m.bool() != null) {
                metadata.put(key, m.bool());
            } else if (m.chr() != null) {
                metadata.put(key, m.chr().charAt(0));
            } else if (m.str() != null) {
                metadata.put(key, m.str());
            }
        }
    }

    /**
     * Each level holds xSize*zSize characters. A mismatch means a declared size and the slices in
     * force disagree - typically a child that redeclared one dimension while inheriting geometry.
     */
    private void checkGeometry(String[] declaredSlices) {
        int perLevel = xSize * zSize;
        for (int y = 0; y < declaredSlices.length; y++) {
            int actual = declaredSlices[y].length();
            if (actual != perLevel) {
                String width = zSize > 0 && actual % zSize == 0
                        ? (actual / zSize) + " wide"
                        : actual + " characters over zsize " + zSize;
                throw new IllegalStateException("Part '" + name + "' declares xsize " + xSize
                        + " and zsize " + zSize + " but its slices are " + width
                        + " (level " + y + " holds " + actual + " of " + perLevel + " characters)");
            }
        }
    }

    @Override
    public Character getMetaChar(String key) {
        return (Character) metadata.get(key);
    }

    @Override
    public Integer getMetaInteger(String key) {
        return (Integer) metadata.get(key);
    }
    @Override
    public boolean getMetaBoolean(String key) {
        Object o = metadata.get(key);
        return o instanceof Boolean ? (Boolean) o : false;
    }
    @Override
    public Float getMetaFloat(String key) {
        return (Float) metadata.get(key);
    }
    @Override
    public String getMetaString(String key) {
        return (String) metadata.get(key);
    }

    public String getRefPaletteName() {
        return refPaletteName;
    }

    @Override
    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }


    /**
     * Vertical slices, organized by z*xSize+x
     */
    @Override
    public char[][] getVslices() {
        return vslices;
    }

    private char[][] buildVslices() {
        char[][] result = new char[xSize * zSize][];
        for (int x = 0 ; x < xSize ; x++) {
            for (int z = 0 ; z < zSize ; z++) {
                StringBuilder vs = new StringBuilder();
                boolean empty = true;
                for (int y = 0; y < slices.length; y++) {
                    Character c = getC(x, y, z);
                    vs.append(c);
                    if (c != ' ') {
                        empty = false;
                    }
                }
                if (empty) {
                    result[z*xSize+x] = null;
                } else {
                    result[z*xSize+x] = vs.toString().toCharArray();
                }
            }
        }
        return result;
    }

    @Override
    public char[] getVSlice(int x, int z) {
        return getVslices()[z*xSize + x];
    }

    @Override
    public Palette getLocalPalette(CommonLevelAccessor level) {
        Palette p = localPalette;
        if (p == null && refPaletteName != null) {
            p = AssetRegistries.PALETTES.getOrThrow(level, refPaletteName);
            localPalette = p;
        }
        return p;
    }

    @Override
    public int getSliceCount() {
        return slices.length;
    }

    public String getSlice(int i) {
        return slices[i];
    }

    public String[] getSlices() {
        return slices;
    }

    @Override
    public int getXSize() {
        return xSize;
    }

    @Override
    public int getZSize() {
        return zSize;
    }

    public Character getPaletteChar(int x, int y, int z) {
        return slices[y].charAt(z * xSize + x);
    }

    public Character getC(int x, int y, int z) {
        return slices[y].charAt(z * xSize + x);
    }
}
