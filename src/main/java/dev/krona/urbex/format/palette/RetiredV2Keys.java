package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.StrictKeys;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The retired-key table of {@code docs/format/palette/09-migration.md} §3, as code.
 * <p>
 * {@code VER.010} refuses a key version 2 renamed and names the replacement; {@code VER.011} refuses a
 * key version 2 deleted and says what to do instead; {@code VER.012} forbids ever silently accepting
 * one as an alias, or silently ignoring one. The three together are why this table exists rather than
 * the keys simply being absent from the codec: absent, they would be unknown keys, and while version 2
 * refuses those too ({@code MODEL.004}), it would refuse them as spelling mistakes. An author who wrote
 * {@code blocks} did not misspell anything - they wrote the version 1 format - and the useful sentence
 * is "write {@code choices}", not "check the spelling".
 * <p>
 * The doctrine is measured, and predates this format. {@code torch} and {@code light} were kept
 * decodable in version 1 <em>purely so they could fail by name</em>: dropped from the codec they would
 * have become unknown keys, which a version 1 palette ignores, and the pack would have kept placing a
 * permanent torch while its author believed the lighting setting applied to it.
 * <p>
 * <b>{@code tag} is in this table and is also a live version 2 key.</b> Version 1's {@code tag} held
 * block-entity NBT; version 2's names a block tag, on a node of kind {@code tag}. Both readings are
 * reachable, and which one applies is decided by where the key appears: on a {@code tag} node it is the
 * live key and this table is never consulted, and on any other node it is not a key of that node
 * ({@code MODEL.013}) and this table answers with the trait that replaced it. That is only true because
 * the key check knows the node's kind - see {@link RawNode}.
 */
public final class RetiredV2Keys {

    private RetiredV2Keys() {
    }

    /**
     * Retired key -> what to say about it, in the order §3's table lists them.
     * <p>
     * A lookup, not an iteration order that anything depends on: {@code StrictKeys} walks the keys the
     * <em>file</em> declares, sorted, so that a file carrying two retired keys reports the same message
     * every run. The order here is for the reader comparing this against §3.
     */
    public static final Map<String, StrictKeys.Retirement> TABLE = table();

    private static Map<String, StrictKeys.Retirement> table() {
        Map<String, StrictKeys.Retirement> table = new LinkedHashMap<>();

        // Renamed: VER.010, DIAG.060. The replacement is a key, so the message can name it and stop.
        table.put("random", renamed("weight"));
        table.put("blocks", renamed("choices, under \"kind\": \"weighted\""));
        table.put("frompalette", renamed("of, under \"kind\": \"alias\""));

        // Deleted: VER.011, DIAG.061. There is no key to point at, so each says what to write instead.
        table.put("char", deleted("The marker is the object key."));
        table.put("variant", deleted("Write a '$ref' naming a definitions asset."));
        table.put("damaged", deleted("Write the 'urbex:damaged' trait, with 'into'."));
        table.put("mob", deleted("Write the 'urbex:spawner' trait, with 'pool'."));
        table.put("loot", deleted("Write the 'urbex:loot' trait, with 'pool'."));
        table.put("tag", deleted("Write the 'urbex:block_entity' trait, with 'nbt'."));
        table.put("lightSource", deleted("Write \"kind\": \"light_socket\" for a socket,"
                + " or the 'urbex:light' trait for a block that is an optional light."));
        table.put("unlitBlocks", deleted("Write 'unlit', which is now a node and may be a weighted list."));

        // Deleted in version 1 already, and still refused here: a version 2 file that carries one is a
        // file converted from a pack that never loaded. RetiredKeys refuses these on the version 1
        // branch, and RetiredKeysRejectedTest requires both branches to.
        table.put("inherit", deleted("Write 'extends' instead."));
        table.put("parent", deleted("Write 'extends' instead."));
        table.put("torch", deleted("Write the 'urbex:light' trait."));
        table.put("light", deleted("Write the 'urbex:light' trait."));

        return java.util.Collections.unmodifiableMap(table);
    }

    private static StrictKeys.Retirement renamed(String replacement) {
        return new StrictKeys.Retirement(Diag.DIAG_060, replacement);
    }

    private static StrictKeys.Retirement deleted(String whatToDoInstead) {
        return new StrictKeys.Retirement(Diag.DIAG_061, whatToDoInstead);
    }
}
