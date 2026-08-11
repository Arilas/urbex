package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;

import java.util.Optional;
import java.util.Set;

public record MiscSettings(
        Optional<Boolean> editMode,
        Optional<Boolean> generateNether) {

    public static final Set<String> KEYS = Set.of("editMode", "generateNether");

    private static final Codec<MiscSettings> RAW = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.BOOL.optionalFieldOf("editMode").forGetter(MiscSettings::editMode),
                    Codec.BOOL.optionalFieldOf("generateNether").forGetter(MiscSettings::generateNether)
            ).apply(i, MiscSettings::new));
    public static final Codec<MiscSettings> CODEC = UnknownKeys.warning(RAW, KEYS, "misc");

    public void apply(Preset p) {
        editMode.ifPresent(v -> p.EDITMODE = v);
        generateNether.ifPresent(v -> p.GENERATE_NETHER = v);
    }
}