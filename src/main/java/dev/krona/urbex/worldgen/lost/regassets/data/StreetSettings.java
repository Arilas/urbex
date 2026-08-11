package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * For a city style this object represents settings for streets
 */
public class StreetSettings {
    private final Float fountainChance;
    private final Float frontChance;
    private final Integer streetWidth;
    private final Character streetBlock;
    private final Character streetBaseBlock;
    private final Character streetVariantBlock;
    private final Character borderBlock;
    private final Character wallBlock;
    // Null on any of these three means "this file did not mention that family", so the extends
    // chain supplies it; see StreetParts.merge.
    private final StreetParts.Decl parts;
    private final StreetParts.Decl largeParts;
    private final StreetParts.Decl tertiaryParts;

    public static final Codec<StreetSettings> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.optionalFieldOf("fountainchance").forGetter(l -> Optional.ofNullable(l.fountainChance)),
                    Codec.FLOAT.optionalFieldOf("frontchance").forGetter(l -> Optional.ofNullable(l.frontChance)),
                    Codec.INT.optionalFieldOf("width").forGetter(l -> Optional.ofNullable(l.streetWidth)),
                    Codec.STRING.optionalFieldOf("street").forGetter(l -> DataTools.toNullable(l.streetBlock)),
                    Codec.STRING.optionalFieldOf("streetbase").forGetter(l -> DataTools.toNullable(l.streetBaseBlock)),
                    Codec.STRING.optionalFieldOf("streetvariant").forGetter(l -> DataTools.toNullable(l.streetVariantBlock)),
                    Codec.STRING.optionalFieldOf("border").forGetter(l -> DataTools.toNullable(l.borderBlock)),
                    Codec.STRING.optionalFieldOf("wall").forGetter(l -> DataTools.toNullable(l.wallBlock)),
                    StreetParts.Decl.CODEC.optionalFieldOf("parts").forGetter(l -> Optional.ofNullable(l.parts)),
                    StreetParts.Decl.CODEC.optionalFieldOf("largeparts").forGetter(l -> Optional.ofNullable(l.largeParts)),
                    StreetParts.Decl.CODEC.optionalFieldOf("tertiaryparts").forGetter(l -> Optional.ofNullable(l.tertiaryParts))
            ).apply(instance, StreetSettings::new));

    public Float getFountainChance() {
        return fountainChance;
    }

    public Float getFrontChance() {
        return frontChance;
    }

    public Integer getStreetWidth() {
        return streetWidth;
    }

    public Character getStreetBlock() {
        return streetBlock;
    }

    public Character getStreetBaseBlock() {
        return streetBaseBlock;
    }

    public Character getStreetVariantBlock() {
        return streetVariantBlock;
    }

    public Character getBorderBlock() {
        return borderBlock;
    }

    public Character getWallBlock() {
        return wallBlock;
    }

    public Optional<StreetParts.Decl> getParts() {
        return Optional.ofNullable(parts);
    }

    public Optional<StreetParts.Decl> getLargeParts() {
        return Optional.ofNullable(largeParts);
    }

    public Optional<StreetParts.Decl> getTertiaryParts() {
        return Optional.ofNullable(tertiaryParts);
    }

    public StreetSettings(Optional<Float> fountainChance,
                          Optional<Float> frontChance,
                          Optional<Integer> streetWidth,
                          Optional<String> streetBlock,
                          Optional<String> streetBaseBlock,
                          Optional<String> streetVariantBlock,
                          Optional<String> borderBlock,
                          Optional<String> wallBlock,
                          Optional<StreetParts.Decl> parts,
                          Optional<StreetParts.Decl> largeParts,
                          Optional<StreetParts.Decl> tertiaryParts) {
        this.fountainChance = fountainChance.orElse(null);
        this.frontChance = frontChance.orElse(null);
        this.streetWidth = streetWidth.orElse(null);
        this.streetBlock = DataTools.getNullableChar(streetBlock);
        this.streetBaseBlock = DataTools.getNullableChar(streetBaseBlock);
        this.streetVariantBlock = DataTools.getNullableChar(streetVariantBlock);
        this.borderBlock = DataTools.getNullableChar(borderBlock);
        this.wallBlock = DataTools.getNullableChar(wallBlock);
        this.parts = parts.orElse(null);
        this.largeParts = largeParts.orElse(null);
        this.tertiaryParts = tertiaryParts.orElse(null);
    }
}
