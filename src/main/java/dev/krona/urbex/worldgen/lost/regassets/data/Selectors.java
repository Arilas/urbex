package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * For a city style this object represents the possible objects for all types
 */
public class Selectors {
    private final Mergeable<ObjectSelector> buildingSelector;
    private final Mergeable<ObjectSelector> bridgeSelector;
    private final Mergeable<ObjectSelector> largeBridgeSelector;
    private final Mergeable<ObjectSelector> parkSelector;
    private final Mergeable<ObjectSelector> fountainSelector;
    private final Mergeable<ObjectSelector> stairSelector;
    private final Mergeable<ObjectSelector> frontSelector;
    private final Mergeable<ObjectSelector> railDungeonSelector;
    private final Mergeable<ObjectSelector> multiBuildingSelector;

    public static final Codec<Selectors> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("buildings").forGetter(l -> Optional.ofNullable(l.buildingSelector)),
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("bridges").forGetter(l -> Optional.ofNullable(l.bridgeSelector)),
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("largebridges").forGetter(l -> Optional.ofNullable(l.largeBridgeSelector)),
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("parks").forGetter(l -> Optional.ofNullable(l.parkSelector)),
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("fountains").forGetter(l -> Optional.ofNullable(l.fountainSelector)),
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("stairs").forGetter(l -> Optional.ofNullable(l.stairSelector)),
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("fronts").forGetter(l -> Optional.ofNullable(l.frontSelector)),
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("raildungeons").forGetter(l -> Optional.ofNullable(l.railDungeonSelector)),
                    Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("multibuildings").forGetter(l -> Optional.ofNullable(l.multiBuildingSelector))
            ).apply(instance, Selectors::new));

    public Optional<Mergeable<ObjectSelector>> getBuildingSelector() {
        return Optional.ofNullable(buildingSelector);
    }

    public Optional<Mergeable<ObjectSelector>> getBridgeSelector() { return Optional.ofNullable(bridgeSelector); }

    public Optional<Mergeable<ObjectSelector>> getLargeBridgeSelector() { return Optional.ofNullable(largeBridgeSelector); }

    public Optional<Mergeable<ObjectSelector>> getParkSelector() {
        return Optional.ofNullable(parkSelector);
    }

    public Optional<Mergeable<ObjectSelector>> getFountainSelector() {
        return Optional.ofNullable(fountainSelector);
    }

    public Optional<Mergeable<ObjectSelector>> getStairSelector() {
        return Optional.ofNullable(stairSelector);
    }

    public Optional<Mergeable<ObjectSelector>> getFrontSelector() {
        return Optional.ofNullable(frontSelector);
    }

    public Optional<Mergeable<ObjectSelector>> getRailDungeonSelector() {
        return Optional.ofNullable(railDungeonSelector);
    }

    public Optional<Mergeable<ObjectSelector>> getMultiBuildingSelector() {
        return Optional.ofNullable(multiBuildingSelector);
    }

    public Selectors(Optional<Mergeable<ObjectSelector>> buildingSelector,
                     Optional<Mergeable<ObjectSelector>> bridgeSelector,
                     Optional<Mergeable<ObjectSelector>> largeBridgeSelector,
                     Optional<Mergeable<ObjectSelector>> parkSelector,
                     Optional<Mergeable<ObjectSelector>> fountainSelector,
                     Optional<Mergeable<ObjectSelector>> stairSelector,
                     Optional<Mergeable<ObjectSelector>> frontSelector,
                     Optional<Mergeable<ObjectSelector>> railDungeonSelector,
                     Optional<Mergeable<ObjectSelector>> multiBuildingSelector) {
        this.buildingSelector = buildingSelector.orElse(null);
        this.bridgeSelector = bridgeSelector.orElse(null);
        this.largeBridgeSelector = largeBridgeSelector.orElse(null);
        this.parkSelector = parkSelector.orElse(null);
        this.fountainSelector = fountainSelector.orElse(null);
        this.stairSelector = stairSelector.orElse(null);
        this.frontSelector = frontSelector.orElse(null);
        this.railDungeonSelector = railDungeonSelector.orElse(null);
        this.multiBuildingSelector = multiBuildingSelector.orElse(null);
    }
}
