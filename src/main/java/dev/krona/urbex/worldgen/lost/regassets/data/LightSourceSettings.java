package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * What a palette entry means by {@code lightSource}.
 *
 * <p>Two forms, told apart by whether any placement list is present rather than by a mode flag.
 * A <em>socket</em> declares weighted candidates for {@code floor}, {@code wall}, {@code ceiling}
 * or {@code free} and needs no block of its own; the pool is its block source, and placement is
 * deferred so it can find its support and orient itself. An <em>in-place</em> source declares no
 * list at all: the entry's own {@code block}, {@code blocks}, {@code variant} or
 * {@code frompalette} is the lit block, written exactly where the author wrote it.</p>
 *
 * <p>Either form may name the {@code unlit} block written when the lighting density roll rejects
 * the marker, or when a socket finds nowhere to put a light. That replacement is the whole point of
 * the type: a light source is never filtered out of the output, so a pack's lanterns respond to
 * lighting density without its streets losing the fixtures that held them.</p>
 */
public record LightSourceSettings(List<Entry> floor, List<Entry> wall, List<Entry> ceiling,
                                  List<Entry> free, @Nullable String unlit,
                                  @Nullable List<BlockEntry> unlitBlocks) {

    /** What {@code "lightSource": true} decodes to: this entry's own block, replaced by air. */
    public static final LightSourceSettings OWN_BLOCK =
            new LightSourceSettings(List.of(), List.of(), List.of(), List.of(), null, null);

    private static final Codec<LightSourceSettings> OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(Entry.CODEC).optionalFieldOf("floor", List.of()).forGetter(LightSourceSettings::floor),
            Codec.list(Entry.CODEC).optionalFieldOf("wall", List.of()).forGetter(LightSourceSettings::wall),
            Codec.list(Entry.CODEC).optionalFieldOf("ceiling", List.of()).forGetter(LightSourceSettings::ceiling),
            Codec.list(Entry.CODEC).optionalFieldOf("free", List.of()).forGetter(LightSourceSettings::free),
            Codec.STRING.optionalFieldOf("unlit").forGetter(value -> Optional.ofNullable(value.unlit())),
            Codec.list(BlockEntry.CODEC).optionalFieldOf("unlitBlocks")
                    .forGetter(value -> Optional.ofNullable(value.unlitBlocks()))
    ).apply(instance, (floor, wall, ceiling, free, unlit, unlitBlocks) ->
            new LightSourceSettings(floor, wall, ceiling, free, unlit.orElse(null), unlitBlocks.orElse(null))));

    /**
     * {@code true} or an object. The shorthand exists because the in-place form has nothing to say
     * beyond "this block is a light" in the common case, and {@code "lightSource": {}} is a strange
     * thing to ask an author to write.
     * <p>
     * {@code false} is refused rather than read as "not a light source". A field that can be
     * present and mean nothing is the shape of a datapack that quietly says something other than
     * what its author read - omitting the field is how you say no.
     */
    public static final Codec<LightSourceSettings> CODEC = Codec.either(Codec.BOOL, OBJECT_CODEC)
            .comapFlatMap(LightSourceSettings::fromEither, LightSourceSettings::toEither);

    public LightSourceSettings {
        floor = List.copyOf(floor);
        wall = List.copyOf(wall);
        ceiling = List.copyOf(ceiling);
        free = List.copyOf(free);
        unlitBlocks = unlitBlocks == null ? null : List.copyOf(unlitBlocks);
    }

    /** Whether this declares candidates of its own, and so is placed by the deferred light placer. */
    public boolean isSocket() {
        return !floor.isEmpty() || !wall.isEmpty() || !ceiling.isEmpty() || !free.isEmpty();
    }

    private static DataResult<LightSourceSettings> fromEither(Either<Boolean, LightSourceSettings> either) {
        return either.map(
                enabled -> enabled
                        ? DataResult.success(OWN_BLOCK)
                        : DataResult.error(() -> "'lightSource: false' says nothing; omit the field instead"),
                settings -> settings.unlit() != null && settings.unlitBlocks() != null
                        ? DataResult.error(() -> "A light source declares both 'unlit' and 'unlitBlocks'; it has one replacement, so name it once")
                        : DataResult.success(settings));
    }

    private static Either<Boolean, LightSourceSettings> toEither(LightSourceSettings settings) {
        return settings.equals(OWN_BLOCK) ? Either.left(true) : Either.right(settings);
    }

    /**
     * One weighted candidate of a socket's {@code floor}, {@code wall}, {@code ceiling} or
     * {@code free}, and what stands in its place when the light is off.
     * <p>
     * The replacement belongs to the candidate rather than to the socket because a socket's
     * candidates are not interchangeable: a floor torch and a wall torch go dark as two different
     * blocks, and only the candidate knows which. One replacement for the whole socket could be
     * right for at most one of its placements.
     */
    public record Entry(int weight, String block, @Nullable String unlit) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("weight").forGetter(Entry::weight),
                Codec.STRING.fieldOf("block").forGetter(Entry::block),
                Codec.STRING.optionalFieldOf("unlit").forGetter(entry -> Optional.ofNullable(entry.unlit()))
        ).apply(instance, (weight, block, unlit) -> new Entry(weight, block, unlit.orElse(null))));

        public Entry(int weight, String block) {
            this(weight, block, null);
        }
    }
}
