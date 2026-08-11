package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * The immutable form of {@link #apply}, for a field held as a value rather than accumulated in a
     * mutable list.
     * <p>
     * The three distinguishable inputs stay distinguishable: an absent {@code incoming} is a file
     * that did not mention the field, and hands back {@code base} - including a null {@code base},
     * which is "nothing in the chain has declared it yet" and is what
     * {@link dev.krona.urbex.worldgen.lost.cityassets.Resolved#require} later turns into a load
     * error. A declared bare array replaces, an empty one included. The {@code {"replace": false}}
     * form appends to what the chain inherited, or is simply the whole value when nothing preceded
     * it.
     *
     * @param base     what the chain has accumulated so far, or null if no entry declared this field
     * @param incoming what this one chain entry declared, or empty if it did not mention the field
     */
    public static <E> List<E> fold(@Nullable List<E> base, Optional<Mergeable<E>> incoming) {
        if (incoming.isEmpty()) {
            return base;
        }
        Mergeable<E> declared = incoming.get();
        if (base == null || declared.replace()) {
            return List.copyOf(declared.values());
        }
        List<E> combined = new ArrayList<>(base);
        combined.addAll(declared.values());
        return List.copyOf(combined);
    }
}
