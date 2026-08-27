package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.palette.CompiledEntry;
import dev.krona.urbex.format.palette.CompiledTrait;
import dev.krona.urbex.format.palette.TraitSet;
import dev.krona.urbex.format.palette.traits.BlockEntityNbt;
import dev.krona.urbex.format.palette.traits.Light;
import dev.krona.urbex.format.palette.traits.Loot;
import dev.krona.urbex.format.palette.traits.Spawner;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;

/**
 * A version 2 slot's {@link TraitSet} as the {@link Palette.Info} generation already knows how to apply.
 *
 * <p>{@code 09-migration.md} §2's table is this correspondence written down, read right to left: version
 * 1's {@code loot}, {@code mob}, {@code tag} and {@code lightSource} are {@code urbex:loot},
 * {@code urbex:spawner}, {@code urbex:block_entity} and {@code urbex:light}. Four of the seven traits;
 * the other three are applied by passes that do not go through {@code Parts} at all
 * ({@code TRAIT.095}'s phases — {@code urbex:rotatable} transforms, {@code urbex:optional} selects in
 * the decoration pass, {@code urbex:damaged} is a pass of its own).</p>
 *
 * <h2>Built once per distinct trait set, never at a position</h2>
 *
 * <p>{@code LOAD.040} forbids allocating while resolving a marker at a position, so nothing here may run
 * during generation. It runs while the merged palette is built: {@code LOAD.023} interns trait sets, so
 * a weighted marker whose 128 slots share one set converts it once, and {@link CompiledPalette} keeps
 * the results in the slot array generation indexes.</p>
 *
 * <h2>What is lost in the direction of travel, and why it is not lost yet</h2>
 *
 * <p>{@link Palette.Info} is per marker and a {@link TraitSet} is per slot ({@code LOAD.021}), so this
 * mapping is many-to-one in the wrong direction. That is safe only because the caller keeps one
 * {@code Info} <em>per slot</em> rather than one per marker — the per-slot shape survives, and it is
 * version 1 that is widened to fit it rather than version 2 narrowed. A marker whose lantern slots carry
 * {@code urbex:light} and whose stone slots do not therefore keeps that distinction, which is the whole
 * of what {@code LOAD.021}'s {@code > Why} says version 1 could not represent.</p>
 */
final class V2Traits {

    private V2Traits() {
    }

    /**
     * The {@code Info} a slot's traits amount to, or {@code null} when it carries none that place.
     *
     * <p>Null rather than an empty {@code Info}, because {@code Parts} branches on it: a marker with no
     * metadata takes the {@code needsPoiUpdate}/{@code needsTodo} arm, and an empty-but-present
     * {@code Info} would silently disable both for every version 2 block. That is the same
     * {@code isSpecial()} distinction version 1 draws, kept rather than reinvented.</p>
     *
     * @param socket the compiled entry when this marker is a {@code light_socket}, whose candidates are
     *               its block source ({@code MODEL.070}) and which has no slots of its own
     */
    @Nullable
    static Palette.Info infoOf(TraitSet traits, @Nullable CompiledEntry socket) {
        String mob = idOf(traits, Spawner.TYPE.id(), value -> ((Spawner.Value) value).pool());
        String loot = idOf(traits, Loot.TYPE.id(), value -> ((Loot.Value) value).pool());
        CompiledTrait nbt = traits.traits().get(BlockEntityNbt.TYPE.id());
        LightSource light = lightOf(traits, socket);
        if (mob == null && loot == null && nbt == null && light == null) {
            return null;
        }
        return Palette.Info.of(mob, loot, light,
                nbt == null ? null : ((BlockEntityNbt.Value) nbt.value()).initialNbt());
    }

    /**
     * {@code urbex:light} and {@code light_socket}, which are the two things version 1 spelled
     * {@code lightSource}.
     *
     * <p>{@code MODEL.075} keeps them apart in version 2 — "a socket is a kind rather than a trait
     * because it selects the block; the {@code urbex:light} trait states that a block already selected
     * is an optional light" — and version 1 conflated them into one field with an {@code isSocket()}
     * test. {@link LightSource} is that conflated shape, so both arrive here: a socket becomes a pool,
     * an in-place light becomes a pool-less source whose replacement is its {@code unlit} satellite.</p>
     */
    @Nullable
    private static LightSource lightOf(TraitSet traits, @Nullable CompiledEntry socket) {
        CompiledTrait light = traits.traits().get(Light.TYPE.id());
        if (socket != null) {
            // TRAIT.055's socket-level replacement, already inherited by every candidate that did not
            // declare its own - see V2Sockets.unlitOf. This one is the fallback for a candidate the
            // placer rejects entirely, which is what LightSource.unlit is for on version 1's side.
            return new LightSource(V2Sockets.poolOf(socket),
                    light == null ? BlockChoice.AIR : replacement(light));
        }
        return light == null ? null : new LightSource(null, replacement(light));
    }

    /**
     * The {@code unlit} satellite as a {@link BlockChoice}: one state, or the weighted list its slots
     * came from.
     *
     * <p>Recovered from slot counts for the reason {@code V2Sockets} states at length — {@code
     * WEIGHT.040} materialises once at compile time, so the counts <em>are</em> the apportionment and
     * reading the authored weights would round an already-rounded number twice. Unlike a socket
     * candidate this one keeps its weights, because an in-place replacement is written at the marker's
     * own position and {@code BlockChoice.Weighted} addresses it there.</p>
     */
    private static BlockChoice replacement(CompiledTrait light) {
        CompiledEntry unlit = light.satellite(Light.UNLIT);
        if (unlit == null || unlit.slotCount() == 0) {
            return BlockChoice.AIR;
        }
        if (unlit.slotCount() == 1) {
            return BlockChoice.of(unlit.slot(0).state());
        }
        List<Pair<Integer, BlockState>> weighted = new ArrayList<>();
        BlockState running = null;
        int count = 0;
        for (int slot = 0; slot < unlit.slotCount(); slot++) {
            BlockState state = unlit.slot(slot).state();
            if (state != running) {
                if (running != null) {
                    weighted.add(Pair.of(count, running));
                }
                running = state;
                count = 0;
            }
            count++;
        }
        weighted.add(Pair.of(count, running));
        return BlockChoice.of(weighted);
    }

    @Nullable
    private static String idOf(TraitSet traits, Identifier id,
                               java.util.function.Function<Object, Identifier> pool) {
        CompiledTrait trait = traits.traits().get(id);
        return trait == null ? null : pool.apply(trait.value()).toString();
    }
}
