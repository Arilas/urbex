package dev.krona.urbex.worldgen.lost.regassets;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * A registry entry that can build on another entry of the same registry via its {@code extends}
 * field. Resolution is {@link dev.krona.urbex.worldgen.lost.cityassets.ExtendsChain}'s job; this
 * interface only exposes the link.
 */
public interface Extendable {
    Optional<Identifier> getExtends();
}
