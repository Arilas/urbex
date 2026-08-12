package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.*;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A world style.
 * <p>
 * {@code outsidestyle} and {@code citystyles} are optional here rather than required, because a
 * world style that only swaps its scattered settings should not have to restate them. Requiredness
 * is checked after the chain is resolved, in
 * {@link dev.krona.urbex.worldgen.lost.cityassets.WorldStyle}.
 */
public class WorldStyleRE implements IAsset<WorldStyleRE>, Extendable {

    private static final Codec<WorldStyleRE> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.STRING.optionalFieldOf("name").forGetter(l -> Optional.ofNullable(l.displayName)),
                    Codec.STRING.optionalFieldOf("outsidestyle").forGetter(l -> Optional.ofNullable(l.outsideStyle)),
                    MultiSettings.CODEC.optionalFieldOf("multisettings").forGetter(l -> Optional.ofNullable(l.multiSettings)),
                    WorldSettings.CODEC.optionalFieldOf("settings").forGetter(l -> Optional.ofNullable(l.worldSettings)),
                    ScatteredSettings.CODEC.optionalFieldOf("scattered").forGetter(l -> Optional.ofNullable(l.scatteredSettings)),
                    PartSelector.Decl.CODEC.optionalFieldOf("parts").forGetter(l -> Optional.ofNullable(l.partSelector)),
                    Mergeable.codec(CityStyleSelector.CODEC).optionalFieldOf("citystyles").forGetter(l -> Optional.ofNullable(l.cityStyleSelectors)),
                    Mergeable.codec(CityBiomeMultiplier.CODEC).optionalFieldOf("citybiomemultipliers").forGetter(l -> Optional.ofNullable(l.cityBiomeMultipliers)),
                    DataTools.BLOCK_TAG_CODEC.optionalFieldOf("rotatable").forGetter(l -> Optional.ofNullable(l.rotatable))
            ).apply(instance, WorldStyleRE::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<WorldStyleRE> CODEC = RetiredKeys.reject(RAW, "worldstyle");

    private Identifier name;
    private final Optional<Identifier> extendsId;
    // The human-readable label the world-style picker shows instead of the id. Null means "not
    // declared here", so the chain reads it from an ancestor; a chain that declares none anywhere
    // falls back to the id in WorldStyle, which is what the picker showed before the field existed.
    private final String displayName;
    // Null on either of these means "not declared here", so the chain reads it from an ancestor.
    private final String outsideStyle;
    private final MultiSettings multiSettings;
    private final WorldSettings worldSettings;
    private final ScatteredSettings scatteredSettings;
    private final PartSelector.Decl partSelector;
    private final Mergeable<CityStyleSelector> cityStyleSelectors;
    private final Mergeable<CityBiomeMultiplier> cityBiomeMultipliers;
    // Null means "not declared here", so the chain reads it from an ancestor. A chain that declares
    // none at all falls back to urbex:rotatable in WorldStyle -- the behaviour every world style had
    // before this field existed.
    private final TagKey<Block> rotatable;

    public WorldStyleRE(Optional<Identifier> extendsId,
                        Optional<String> displayName,
                        Optional<String> outsideStyle,
                        Optional<MultiSettings> multiSettings,
                        Optional<WorldSettings> worldSettings,
                        Optional<ScatteredSettings> scatteredSettings,
                        Optional<PartSelector.Decl> partSelector,
                        Optional<Mergeable<CityStyleSelector>> cityStyleSelector,
                        Optional<Mergeable<CityBiomeMultiplier>> cityBiomeMultipliers,
                        Optional<TagKey<Block>> rotatable) {
        this.extendsId = extendsId;
        this.displayName = displayName.orElse(null);
        this.outsideStyle = outsideStyle.orElse(null);
        this.multiSettings = multiSettings.orElse(null);
        this.worldSettings = worldSettings.orElse(null);
        this.scatteredSettings = scatteredSettings.orElse(null);
        this.partSelector = partSelector.orElse(null);
        this.cityStyleSelectors = cityStyleSelector.orElse(null);
        this.cityBiomeMultipliers = cityBiomeMultipliers.orElse(null);
        this.rotatable = rotatable.orElse(null);
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public String getOutsideStyle() {
        return outsideStyle;
    }

    @Nullable
    public TagKey<Block> getRotatable() {
        return rotatable;
    }

    @Nullable
    public PartSelector.Decl getPartSelector() {
        return partSelector;
    }

    @Nullable
    public ScatteredSettings getScatteredSettings() {
        return scatteredSettings;
    }

    @Nullable
    public Mergeable<CityStyleSelector> getCityStyleSelectors() {
        return cityStyleSelectors;
    }

    @Nullable
    public Mergeable<CityBiomeMultiplier> getCityBiomeMultipliers() {
        return cityBiomeMultipliers;
    }

    @Nullable
    public MultiSettings getMultiSettings() {
        return multiSettings;
    }

    @Nullable
    public WorldSettings getWorldSettings() {
        return worldSettings;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public WorldStyleRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}
