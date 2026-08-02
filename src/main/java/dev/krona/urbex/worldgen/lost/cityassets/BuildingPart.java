package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.PartMeta;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
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

    public BuildingPart(BuildingPartRE object) {
        name = object.getRegistryName();
        xSize = object.getxSize();
        zSize = object.getzSize();
        slices = object.getSlices();
        if (object.getLocalPalette() != null) {
            localPalette = new Palette("__local__" + name.getPath());
            localPalette.parsePaletteArray(object.getLocalPalette()); // @todo get the full palette instead
        } else if (object.getRefPaletteName() != null) {
            refPaletteName = object.getRefPaletteName();
        }
        vslices = buildVslices();
        if (object.getMetadata() != null) {
            for (PartMeta meta : object.getMetadata()) {
                String key = meta.key();
                if (meta.i() != null) {
                    metadata.put(key, meta.i());
                } else if (meta.f() != null) {
                    metadata.put(key, meta.f());
                } else if (meta.bool() != null) {
                    metadata.put(key, meta.bool());
                } else if (meta.chr() != null) {
                    metadata.put(key, meta.chr().charAt(0));
                } else if (meta.str() != null) {
                    metadata.put(key, meta.str());
                }
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
