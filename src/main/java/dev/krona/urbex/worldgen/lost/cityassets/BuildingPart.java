package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartRE;
import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
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
import javax.annotation.Nullable;

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

    // The palette this part paints with: its own inline one, or the refpalette it names, resolved by
    // the compiler before this object is published (issue #128). A reference used to be resolved
    // lazily from the first chunk that asked, which is why it needed a level.
    private Palette localPalette = null;
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
    public BuildingPart(@Nullable AssetIndex<Variant> variants, AssetIndex<Palette> palettes,
                        List<BuildingPartRE> chainRootFirst) {
        BuildingPartRE leaf = chainRootFirst.get(chainRootFirst.size() - 1);
        name = leaf.getRegistryName();

        Integer declaredXSize = null;
        Integer declaredZSize = null;
        String[] declaredSlices = null;
        List<PaletteRE> inlinePalettes = new ArrayList<>();
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
            // Inline palettes stack: they are a keyed collection like a registered palette, so a
            // later block repaints the characters it declares and keeps the rest. Naming a palette
            // with refpalette instead is a different choice, not another layer, so it drops them.
            if (re.getLocalPalette() != null) {
                inlinePalettes.add(re.getLocalPalette());
                refPalette = null;
            } else if (re.getRefPaletteName() != null) {
                refPalette = re.getRefPaletteName();
                inlinePalettes.clear();
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

        if (!inlinePalettes.isEmpty()) {
            localPalette = Palette.inline(variants, name, inlinePalettes); // @todo get the full palette instead
        } else if (refPalette != null) {
            refPaletteName = refPalette;
            // Resolved here, not on the first chunk that asks. The lazy version cached the answer on
            // this asset and needed a CommonLevelAccessor to reach the palette registry, so a
            // refpalette naming something absent surfaced from a worldgen worker (issue #128).
            localPalette = palettes.getOrThrow(refPalette);
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

    /** The fully-qualified id, e.g. {@code "urbex:street_straight"}. */
    @Override
    public String getName() {
        return name.toString();
    }

    @Override
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
    public Palette getLocalPalette() {
        return localPalette;
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
