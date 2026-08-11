package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * A list field that can either replace what an ancestor in the {@code extends} chain put there, or
 * be appended to it.
 * <p>
 * A bare JSON array replaces - that is the rule an author gets without asking for anything. The
 * object form opts into appending, mirroring the shape of vanilla tag files:
 * <pre>{ "replace": false, "values": [ ... ] }</pre>
 */
public record Mergeable<E>(boolean replace, List<E> values) {

    public static <E> Codec<Mergeable<E>> codec(Codec<E> element) {
        Codec<Mergeable<E>> object = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("replace", true).forGetter(Mergeable::replace),
                element.listOf().fieldOf("values").forGetter(Mergeable::values)
        ).apply(instance, Mergeable::new));

        return Codec.either(element.listOf(), object).xmap(
                either -> either.map(list -> new Mergeable<>(true, list), o -> o),
                m -> m.replace() ? Either.left(m.values()) : Either.right(m));
    }

    /** Applies this onto {@code target}, which already holds whatever the chain inherited. */
    public static <E> void apply(List<E> target, Mergeable<E> incoming) {
        if (incoming.replace()) {
            target.clear();
        }
        target.addAll(incoming.values());
    }
}
