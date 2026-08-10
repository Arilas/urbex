package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.varia.Tools;

import java.util.List;
import java.util.Optional;

public record StreetParts(List<String> straight, List<String> end, List<String> bend,
                          List<String> t, List<String> none, List<String> all, List<String> connector,
                          List<String> stair) {

    public static final Codec<StreetParts> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Tools.listOrStringList("straight", "street_straight", StreetParts::straight),
            Tools.listOrStringList("end", "street_end", StreetParts::end),
            Tools.listOrStringList("bend", "street_bend", StreetParts::bend),
            Tools.listOrStringList("t", "street_t", StreetParts::t),
            Tools.listOrStringList("none", "street_none", StreetParts::none),
            Tools.listOrStringList("all", "street_all", StreetParts::all),
            Tools.listOrStringList("connector", "urbex:street_large_connector", StreetParts::connector),
            Tools.listOrStringList("stair", "urbex:street_stair", StreetParts::stair))
            .apply(instance, StreetParts::new)
    );

    public static final StreetParts DEFAULT = new StreetParts(
            List.of("urbex:street_straight"),
            List.of("urbex:street_end"),
            List.of("urbex:street_bend"),
            List.of("urbex:street_t"),
            List.of("urbex:street_none"),
            List.of("urbex:street_all"),
            List.of("urbex:street_large_connector"),
            List.of("urbex:street_stair"));

    public Optional<StreetParts> get() {
        if (this == DEFAULT) {
            return Optional.empty();
        } else {
            return Optional.of(this);
        }
    }
}
