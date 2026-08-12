package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartMeta;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A structure part.
 * <p>
 * {@code xsize}, {@code zsize} and {@code slices} are optional here rather than required, because a
 * part that only repaints what it {@code extends} should not have to restate its ancestor's
 * geometry. Requiredness is checked after the chain is resolved, in
 * {@link dev.krona.urbex.worldgen.lost.cityassets.BuildingPart}.
 */
public class BuildingPartDefinition implements Extendable {

    private static final Codec<BuildingPartDefinition> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.INT.optionalFieldOf("xsize").forGetter(l -> Optional.ofNullable(l.xSize)),
                    Codec.INT.optionalFieldOf("zsize").forGetter(l -> Optional.ofNullable(l.zSize)),
                    Codec.list(Codec.list(Codec.STRING)).optionalFieldOf("slices").forGetter(BuildingPartDefinition::createSlices),
                    Codec.STRING.optionalFieldOf("refpalette").forGetter(l -> Optional.ofNullable(l.refPaletteName)),
                    PaletteDefinition.CODEC.optionalFieldOf("palette").forGetter(l -> Optional.ofNullable(l.localPalette)),
                    Mergeable.codec(PartMeta.CODEC).optionalFieldOf("meta").forGetter(l -> Optional.ofNullable(l.metadata))
            ).apply(instance, BuildingPartDefinition::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<BuildingPartDefinition> CODEC = RetiredKeys.reject(RAW, "part");


    private final Optional<Identifier> extendsId;

    // Data per height level, one string of xSize*zSize characters each. Null when this entry
    // declares no slices of its own and takes its ancestor's.
    private final String[] slices;

    // Dimension (should be less then 16x16). Null when inherited.
    private final Integer xSize;
    private final Integer zSize;

    private PaletteDefinition localPalette = null;
    private final String refPaletteName;

    private final Mergeable<PartMeta> metadata;

    public BuildingPartDefinition(Optional<Identifier> extendsId, Optional<Integer> xSize, Optional<Integer> zSize,
                          Optional<List<List<String>>> slices, Optional<String> refpalette,
                          Optional<PaletteDefinition> locpalette, Optional<Mergeable<PartMeta>> metadata) {
        this.extendsId = extendsId;
        this.slices = slices.map(BuildingPartDefinition::flatten).orElse(null);
        this.xSize = xSize.orElse(null);
        this.zSize = zSize.orElse(null);
        this.refPaletteName = refpalette.map(String::intern).orElse(null);
        this.localPalette = locpalette.orElse(null);
        this.metadata = metadata.orElse(null);
    }

    private static String[] flatten(List<List<String>> slices) {
        String[] result = new String[slices.size()];
        int idx = 0;
        for (List<String> slice : slices) {
            StringBuilder builder = new StringBuilder();
            for (String s : slice) {
                builder.append(s);
            }
            result[idx++] = builder.toString();
        }
        return result;
    }

    private Optional<List<List<String>>> createSlices() {
        if (slices == null) {
            return Optional.empty();
        }
        List<List<String>> result = new ArrayList<>();
        for (String slice : slices) {
            List<String> s = new ArrayList<>();
            if (xSize == null || zSize == null || slice.length() != xSize * zSize) {
                // No geometry to split by (it lives further up the extends chain), so emit the
                // level as one row: that decodes back to the same flattened string.
                s.add(slice);
            } else {
                for (int z = 0; z < zSize; z++) {
                    s.add(slice.substring(z * xSize, z * xSize + xSize));
                }
            }
            result.add(s);
        }
        return Optional.of(result);
    }

    @Nullable
    public Mergeable<PartMeta> getMetadata() {
        return metadata;
    }

    @Nullable
    public String[] getSlices() {
        return slices;
    }

    @Nullable
    public Integer getxSize() {
        return xSize;
    }

    @Nullable
    public Integer getzSize() {
        return zSize;
    }

    public PaletteDefinition getLocalPalette() {
        return localPalette;
    }

    public String getRefPaletteName() {
        return refPaletteName;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }


}
