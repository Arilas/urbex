package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

public record LightSettings(List<Entry> floor, List<Entry> wall,
                            List<Entry> ceiling, List<Entry> free) {
    public static final Codec<LightSettings> CODEC = RecordCodecBuilder.<LightSettings>create(instance -> instance.group(
            Codec.list(Entry.CODEC).optionalFieldOf("floor", List.of()).forGetter((LightSettings value) -> value.floor()),
            Codec.list(Entry.CODEC).optionalFieldOf("wall", List.of()).forGetter((LightSettings value) -> value.wall()),
            Codec.list(Entry.CODEC).optionalFieldOf("ceiling", List.of()).forGetter((LightSettings value) -> value.ceiling()),
            Codec.list(Entry.CODEC).optionalFieldOf("free", List.of()).forGetter((LightSettings value) -> value.free())
    ).apply(instance, LightSettings::new)).flatXmap(
            value -> value.isEmpty()
                    ? DataResult.error(() -> "A light pool must define at least one candidate")
                    : DataResult.success(value),
            DataResult::success);

    public LightSettings {
        floor = List.copyOf(floor);
        wall = List.copyOf(wall);
        ceiling = List.copyOf(ceiling);
        free = List.copyOf(free);
    }

    public boolean isEmpty() {
        return floor.isEmpty() && wall.isEmpty() && ceiling.isEmpty() && free.isEmpty();
    }

    public record Entry(int weight, String block) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(Entry::weight),
                Codec.STRING.fieldOf("block").forGetter(Entry::block)
        ).apply(instance, Entry::new));
    }
}
