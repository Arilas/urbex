package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A palette of materials as used by building parts - the version 1 format.
 * <p>
 * {@code palette} is optional here rather than required, because requiredness is checked after the
 * {@code extends} chain is resolved, in {@link dev.krona.urbex.worldgen.lost.cityassets.Palette}.
 * <p>
 * <b>No datapack reaches this class.</b> {@code VER.018} unregistered it: the palettes registry
 * dispatches on version 2 alone, and a document declaring version 1 - or declaring nothing - is refused
 * with {@code DIAG.066} before it decodes. {@code VER.001} and {@code VER.004}, which used to be the
 * reasons this class had to keep working, are retired.
 * <p>
 * <b>Why it still exists.</b> {@code VER.021} - "a world generated from the converted pack is identical
 * to one generated from the original" - is verified by compiling every shipped version 1 palette both
 * ways and comparing marker by marker, which needs a version 1 implementation for as long as the
 * converter does. This is that implementation, and it has exactly one caller left:
 * {@code V1ToV2Test}. It is excluded by name from {@code RetiredKeysRejectedTest}'s registry sweep,
 * which counts registered codecs, so registering it again fails that count rather than passing
 * unnoticed.
 * <p>
 * It belongs in test scope and is not there yet: moving it means moving {@code PaletteEntry},
 * {@code BlockEntry}, {@code LightSourceSettings}, {@code Palette}'s version 1 half and
 * {@code LightPool}'s version 1 entry with it, and {@code Palette} is one class serving both formats.
 * Recorded here rather than in a tracker because this javadoc is what the next reader of this file
 * will see. Nothing new should be built on it.
 * <p>
 * The one thing that changed here is that it names {@link PaletteAssetDefinition} instead of
 * {@link Extendable} - the interface that supersedes it also carries the format version - and gained
 * {@link #formatVersion()}. Nothing else about this class moved.
 */
public class PaletteDefinition implements PaletteAssetDefinition {

    private static final Codec<PaletteDefinition> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.list(PaletteEntry.CODEC).optionalFieldOf("palette").forGetter(l -> Optional.ofNullable(l.paletteEntries))
            ).apply(instance, PaletteDefinition::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<PaletteDefinition> CODEC = RetiredKeys.reject(RAW, "palette");

    private final Optional<Identifier> extendsId;
    // Null when this entry declares no palette of its own and takes its ancestor's.
    private final List<PaletteEntry> paletteEntries;

    public PaletteDefinition(Optional<Identifier> extendsId, Optional<List<PaletteEntry>> entries) {
        this.extendsId = extendsId;
        this.paletteEntries = entries.map(List::copyOf).orElse(null);
    }

    @Nullable
    public List<PaletteEntry> getPaletteEntries() {
        return paletteEntries;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    /** {@code VER.001}: a palette file with no {@code version} is this format. */
    @Override
    public int formatVersion() {
        return 1;
    }


}
