package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.*;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class WorldStyleRE implements IAsset<WorldStyleRE>, Extendable {

    public static final Codec<WorldStyleRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.STRING.fieldOf("outsidestyle").forGetter(l -> l.outsideStyle),
                    MultiSettings.CODEC.optionalFieldOf("multisettings").forGetter(l -> Optional.ofNullable(l.multiSettings)),
                    WorldSettings.CODEC.optionalFieldOf("settings").forGetter(l -> Optional.ofNullable(l.worldSettings)),
                    ScatteredSettings.CODEC.optionalFieldOf("scattered").forGetter(l -> Optional.ofNullable(l.scatteredSettings)),
                    PartSelector.CODEC.optionalFieldOf("parts").forGetter(l -> Optional.ofNullable(l.partSelector)),
                    Mergeable.codec(CityStyleSelector.CODEC).fieldOf("citystyles").forGetter(l -> l.cityStyleSelectors),
                    Mergeable.codec(CityBiomeMultiplier.CODEC).optionalFieldOf("citybiomemultipliers").forGetter(l -> Optional.ofNullable(l.cityBiomeMultipliers))
            ).apply(instance, WorldStyleRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final String outsideStyle;
    private final MultiSettings multiSettings;
    private final WorldSettings worldSettings;
    private final ScatteredSettings scatteredSettings;
    private final PartSelector partSelector;
    private final Mergeable<CityStyleSelector> cityStyleSelectors;
    private final Mergeable<CityBiomeMultiplier> cityBiomeMultipliers;

    public WorldStyleRE(Optional<Identifier> extendsId,
                        String outsideStyle,
                        Optional<MultiSettings> multiSettings,
                        Optional<WorldSettings> worldSettings,
                        Optional<ScatteredSettings> scatteredSettings,
                        Optional<PartSelector> partSelector,
                        Mergeable<CityStyleSelector> cityStyleSelector,
                        Optional<Mergeable<CityBiomeMultiplier>> cityBiomeMultipliers) {
        this.extendsId = extendsId;
        this.outsideStyle = outsideStyle;
        this.multiSettings = multiSettings.orElse(null);
        this.worldSettings = worldSettings.orElse(null);
        this.scatteredSettings = scatteredSettings.orElse(null);
        this.partSelector = partSelector.orElse(null);
        this.cityStyleSelectors = cityStyleSelector;
        this.cityBiomeMultipliers = cityBiomeMultipliers.orElse(null);
    }

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
