package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.*;
import net.minecraft.resources.Identifier;
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

    public static final Codec<WorldStyleRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.STRING.optionalFieldOf("outsidestyle").forGetter(l -> Optional.ofNullable(l.outsideStyle)),
                    MultiSettings.CODEC.optionalFieldOf("multisettings").forGetter(l -> Optional.ofNullable(l.multiSettings)),
                    WorldSettings.CODEC.optionalFieldOf("settings").forGetter(l -> Optional.ofNullable(l.worldSettings)),
                    ScatteredSettings.CODEC.optionalFieldOf("scattered").forGetter(l -> Optional.ofNullable(l.scatteredSettings)),
                    PartSelector.CODEC.optionalFieldOf("parts").forGetter(l -> Optional.ofNullable(l.partSelector)),
                    Mergeable.codec(CityStyleSelector.CODEC).optionalFieldOf("citystyles").forGetter(l -> Optional.ofNullable(l.cityStyleSelectors)),
                    Mergeable.codec(CityBiomeMultiplier.CODEC).optionalFieldOf("citybiomemultipliers").forGetter(l -> Optional.ofNullable(l.cityBiomeMultipliers))
            ).apply(instance, WorldStyleRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    // Null on either of these means "not declared here", so the chain reads it from an ancestor.
    private final String outsideStyle;
    private final MultiSettings multiSettings;
    private final WorldSettings worldSettings;
    private final ScatteredSettings scatteredSettings;
    private final PartSelector partSelector;
    private final Mergeable<CityStyleSelector> cityStyleSelectors;
    private final Mergeable<CityBiomeMultiplier> cityBiomeMultipliers;

    public WorldStyleRE(Optional<Identifier> extendsId,
                        Optional<String> outsideStyle,
                        Optional<MultiSettings> multiSettings,
                        Optional<WorldSettings> worldSettings,
                        Optional<ScatteredSettings> scatteredSettings,
                        Optional<PartSelector> partSelector,
                        Optional<Mergeable<CityStyleSelector>> cityStyleSelector,
                        Optional<Mergeable<CityBiomeMultiplier>> cityBiomeMultipliers) {
        this.extendsId = extendsId;
        this.outsideStyle = outsideStyle.orElse(null);
        this.multiSettings = multiSettings.orElse(null);
        this.worldSettings = worldSettings.orElse(null);
        this.scatteredSettings = scatteredSettings.orElse(null);
        this.partSelector = partSelector.orElse(null);
        this.cityStyleSelectors = cityStyleSelector.orElse(null);
        this.cityBiomeMultipliers = cityBiomeMultipliers.orElse(null);
    }

    @Nullable
    public String getOutsideStyle() {
        return outsideStyle;
    }

    @Nullable
    public PartSelector getPartSelector() {
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
