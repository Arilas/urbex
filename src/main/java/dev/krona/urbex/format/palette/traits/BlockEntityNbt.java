package dev.krona.urbex.format.palette.traits;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.palette.PointerResolver;
import dev.krona.urbex.format.palette.RawNode;
import dev.krona.urbex.format.palette.ResolvedNode;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.format.palette.TraitType;
import dev.krona.urbex.format.palette.TraitValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code urbex:block_entity} - the NBT a block entity is initialised with ({@code TRAIT.040}).
 * <p>
 * <b>{@code TRAIT.041} is the rule version 1 did not have,</b> and its {@code > Why} is the measurement:
 * version 1 "accepted it, scanned the block-entity registry for a type accepting the state, found none,
 * and wrote nothing. The NBT the author supplied simply never appeared." Refusing it costs an author one
 * message and buys back a silence that could survive a whole pack's lifetime.
 * <p>
 * <b>Which reading of "a node whose block has no block entity" this takes.</b> A node may resolve to
 * several states, and the rule's singular "its block" does not say whether one of them having a block
 * entity is enough. This refuses only when <em>none</em> of the states the node resolves to has one,
 * for the reason {@code ACCEPT} exists as a rule class: a weighted marker of a chest and a barrel and
 * one decorative wall block is a real shape, the NBT reaches two of the three, and refusing the whole
 * marker over the third is the over-rejection that made a version 1 validator report 45 problems in a
 * correct pack. A node with no resolvable state at all - every block absent, {@code MODEL.042} - is
 * likewise not refused, because nothing was found to be wrong with it.
 */
public final class BlockEntityNbt implements TraitType<BlockEntityNbt.Value> {

    /** The single registered instance. */
    public static final BlockEntityNbt TYPE = new BlockEntityNbt();

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "block_entity");

    /** {@code TRAIT.040}: the required field. */
    public static final String NBT = "nbt";

    /**
     * {@code TRAIT.042}: the keys the loader supplies, which a file may not decide.
     * <p>
     * <b>Dropped and reported, which is what {@code TRAIT.042} now is: a {@code WARN}.</b> The four
     * keys cannot be honoured - the loader knows the position and the type and the file does not - so
     * refusing would refuse a pack whose block entities are written correctly, and dropping them
     * without a word is the version 1 behaviour {@code MODEL.004}'s {@code > Why} measures, whose
     * documented symptom was <em>"(no message at all)"</em>. {@code DIAG.904} allows exactly one level
     * between those two and this is it: {@link #initialNbt} does not carry them, and {@code DIAG.026}
     * says so.
     */
    public static final Set<String> LOADER_SUPPLIED_KEYS = Set.of("x", "y", "z", "id");

    private static final Codec<Value> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CompoundTag.CODEC.fieldOf(NBT).forGetter(Value::nbt)
    ).apply(instance, Value::new));

    /** @param nbt the block entity's initial NBT, as written */
    public record Value(CompoundTag nbt) implements TraitValue {

        /** {@code TRAIT.042}: the NBT the loader writes, with the four keys it supplies removed. */
        public CompoundTag initialNbt() {
            CompoundTag copy = nbt.copy();
            LOADER_SUPPLIED_KEYS.forEach(copy::remove);
            return copy;
        }
    }

    private BlockEntityNbt() {
    }

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public Codec<Value> codec() {
        return CODEC;
    }

    @Override
    public Set<String> keys() {
        return Set.of(NBT);
    }

    @Override
    public Set<String> blockValuedFields() {
        return Set.of();
    }

    @Override
    public List<ReferenceTarget> references() {
        return List.of();
    }

    @Override
    public Map<String, RawNode> satellites(Value value) {
        return Map.of();
    }

    @Override
    public Value withSatellites(Value value, Map<String, RawNode> satellites) {
        return value;
    }

    @Override
    public Map<ReferenceTarget, List<Identifier>> referenced(Value value) {
        return Map.of();
    }

    /**
     * {@code TRAIT.041} and {@code TRAIT.042}: the refusal, and the warning beside it.
     * <p>
     * Both, and in this order, because they are about different things and only one refuses. The
     * warning fires whatever the block is - a file that writes {@code x} into a chest's NBT has written
     * something the loader will drop just as squarely as one that writes it into a stone block's.
     */
    @Override
    public void validate(Value value, ResolvedNode owner, TraitContext context,
                         PointerResolver.Site site, Diagnostics diagnostics) {
        // TRAIT.042, and the keys are named in the file's own order rather than the set's, so one file
        // produces one sentence every run.
        List<String> supplied = value.nbt().keySet().stream()
                .filter(LOADER_SUPPLIED_KEYS::contains)
                .sorted()
                .map(key -> "'" + key + "'")
                .toList();
        if (!supplied.isEmpty()) {
            diagnostics.warn(Diag.DIAG_026, site.location(), String.join(", ", supplied));
        }

        // TRAIT.041.
        List<BlockState> states = context.statesOf(owner);
        if (states.isEmpty() || states.stream().anyMatch(BlockState::hasBlockEntity)) {
            return;
        }
        // DIAG.022 names the block, and it names it as the file wrote it: reversing a BlockState back
        // through the registry would print a canonical id the author may never have typed. Duplicates
        // dropped, declaration order kept - both so that one file produces one sentence, every run.
        Set<String> named = new LinkedHashSet<>(context.writtenBlocks(owner));
        diagnostics.error(Diag.DIAG_022, site.location(), String.join(", ", named));
    }
}
