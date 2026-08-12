package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An entry in a palette
 */
public class PaletteEntry {

    public static final Codec<PaletteEntry> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.PALETTE_CHAR_STRING.fieldOf("char").forGetter(PaletteEntry::getChr),
                    Codec.STRING.optionalFieldOf("block").forGetter(l -> Optional.ofNullable(l.getBlock())),
                    Codec.STRING.optionalFieldOf("variant").forGetter(l -> Optional.ofNullable(l.getVariant())),
                    Codec.STRING.optionalFieldOf("frompalette").forGetter(l -> Optional.ofNullable(l.getFrompalette())),
                    Codec.list(BlockEntry.CODEC).optionalFieldOf("blocks").forGetter(l -> Optional.ofNullable(l.getBlocks())),
                    Codec.STRING.optionalFieldOf("damaged").forGetter(l -> Optional.ofNullable(l.getDamaged())),
                    Codec.STRING.optionalFieldOf("mob").forGetter(l -> Optional.ofNullable(l.getMob())),
                    Codec.STRING.optionalFieldOf("loot").forGetter(l -> Optional.ofNullable(l.getLoot())),
                    Codec.BOOL.optionalFieldOf("torch").forGetter(l -> Optional.ofNullable(l.getTorch())),
                    LightSettings.CODEC.optionalFieldOf("light").forGetter(entry -> Optional.ofNullable(entry.getLight())),
                    CompoundTag.CODEC.optionalFieldOf("tag").forGetter(l -> Optional.ofNullable(l.getTag()))
            ).apply(instance, PaletteEntry::new));

    /**
     * Canonical copies of the two values a palette repeats most: the weighted {@code blocks} list
     * and the block-entity {@code tag}. Both are large relative to an entry and both recur verbatim
     * across dozens of files, so one copy shared by every entry that decodes to it is worth keeping.
     * <p>
     * {@link ConcurrentHashMap} rather than the {@code ObjectOpenHashSet} this used to be, for two
     * reasons that were both live. The sets were unsynchronized and mutated from
     * {@code RegistryDataLoader}'s decode, which runs its registries on a worker pool - two entries
     * decoding at once could interleave a resize and corrupt the table. And nothing ever emptied
     * them: they are static, so every palette of every world loaded in a process lifetime stayed
     * reachable through them until the JVM exited. {@link #clearPools()} is now called from
     * {@code AssetRegistries.reset()} with the rest of the asset state.
     */
    private static final Map<List<BlockEntry>, List<BlockEntry>> LIST_POOL = new ConcurrentHashMap<>();
    private static final Map<CompoundTag, CompoundTag> TAG_POOL = new ConcurrentHashMap<>();

    /** Drops both canonical-copy pools; called from {@code AssetRegistries.reset()}. */
    public static void clearPools() {
        LIST_POOL.clear();
        TAG_POOL.clear();
    }

    private String chr;
    private String block;
    private String variant;
    private String frompalette;
    private List<BlockEntry> blocks;
    private String damaged;
    private String mob;
    private String loot;
    private Boolean torch;
    private LightSettings light;
    private CompoundTag tag;

    public PaletteEntry() {
    }

    public static PaletteEntry block(String block) {
        PaletteEntry entry = new PaletteEntry();
        entry.block = block;
        return entry;
    }

    public static PaletteEntry variant(String variant) {
        PaletteEntry entry = new PaletteEntry();
        entry.variant = variant;
        return entry;
    }

    public static PaletteEntry blocks(List<BlockEntry> blocks) {
        PaletteEntry entry = new PaletteEntry();
        entry.blocks = blocks;
        return entry;
    }

    public String getChr() {
        return chr;
    }

    public String getBlock() {
        return block;
    }

    public String getVariant() {
        return variant;
    }

    public String getFrompalette() {
        return frompalette;
    }

    public List<BlockEntry> getBlocks() {
        return blocks;
    }

    public String getDamaged() {
        return damaged;
    }

    public String getMob() {
        return mob;
    }

    public String getLoot() {
        return loot;
    }

    public Boolean getTorch() {
        return torch;
    }

    public LightSettings getLight() {
        return light;
    }

    public CompoundTag getTag() {
        return tag;
    }

    private static List<BlockEntry> deduplicateList(List<BlockEntry> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return null;
        }
        List<BlockEntry> immutable = List.copyOf(incoming);
        List<BlockEntry> existing = LIST_POOL.putIfAbsent(immutable, immutable);
        return existing != null ? existing : immutable;
    }

    private static CompoundTag deduplicateTag(CompoundTag incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return null;
        }

        CompoundTag existing = TAG_POOL.putIfAbsent(incoming, incoming);
        return existing != null ? existing : incoming;
    }

    public PaletteEntry(String chr, Optional<String> block, Optional<String> variant, Optional<String> frompalette,
                        Optional<List<BlockEntry>> blocks, Optional<String> damaged,
                        Optional<String> mob, Optional<String> loot, Optional<Boolean> torch,
                        Optional<LightSettings> light,
                        Optional<CompoundTag> tag) {
        this.chr = chr.intern();
        this.block = block.map(String::intern).orElse(null);
        this.variant = variant.orElse(null);
        this.frompalette = frompalette.map(String::intern).orElse(null);
        this.blocks = deduplicateList(blocks.orElse(null));
        this.damaged = damaged.map(String::intern).orElse(null);
        this.mob = mob.map(String::intern).orElse(null);
        this.loot = loot.map(String::intern).orElse(null);
        this.torch = torch.orElse(null);
        this.light = light.orElse(null);
        this.tag = deduplicateTag(tag.orElse(null));
    }

    @Override
    public String toString() {
        return "PaletteEntry{" +
                "chr='" + chr + '\'' +
                ", block='" + block + '\'' +
                ", variant='" + variant + '\'' +
                ", frompalette='" + frompalette + '\'' +
                ", blocks=" + blocks +
                ", damaged='" + damaged + '\'' +
                ", mob='" + mob + '\'' +
                ", loot='" + loot + '\'' +
                ", torch=" + torch +
                ", light=" + light +
                ", tag=" + tag +
                '}';
    }
}
