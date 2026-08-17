package dev.krona.urbex.format.palette;

import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * The {@code definitions} registry, as the resolver sees it ({@code REF.010}).
 * <p>
 * A type of its own rather than a bare {@code Map}, for the reason {@code REF.012} gives: "There is no
 * search order between the two. A name resolves in exactly one tier, decided by the presence of a
 * colon, and a failure in that tier is not retried in the other." A named tier is a named lookup, and
 * {@link #get} is the only way into this one - so a bare name can never reach it and a qualified name
 * can never fall back out of it.
 * <p>
 * Holds whole {@link DefinitionAssetDefinition assets} rather than their nodes, because a definitions
 * asset carries its own {@code $imports} ({@code REF.018}) and a pointer written inside it is expanded
 * against those and not against the referring file's ({@code REF.086}).
 *
 * @param byId every loaded definitions asset
 */
public record DefinitionIndex(Map<Identifier, DefinitionAssetDefinition> byId) {

    private static final DefinitionIndex EMPTY = new DefinitionIndex(Map.of());

    public DefinitionIndex(Map<Identifier, DefinitionAssetDefinition> byId) {
        this.byId = Map.copyOf(byId);
    }

    /** No definitions asset is loaded, which is what a palette resolved on its own resolves against. */
    public static DefinitionIndex empty() {
        return EMPTY;
    }

    /** The asset with this id, or empty - which {@code REF.013} refuses with {@code DIAG.030}. */
    public Optional<DefinitionAssetDefinition> get(Identifier id) {
        return Optional.ofNullable(byId.get(id));
    }
}
