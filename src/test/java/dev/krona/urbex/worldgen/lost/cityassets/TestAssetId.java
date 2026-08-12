package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;

/**
 * The registry id a test hands to a compiled asset it builds by hand.
 * <p>
 * A compiled asset takes its id as a constructor argument, because the compiler already has it -
 * the decoded definition used to have it written into it by {@code IAsset.setRegistryName}, which
 * made compilation mutate the authored model (issue #128). Tests build chains without a registry,
 * so they have to supply one. {@link #ANY} is for the many tests where the id is not what is under
 * test and only exists so an error message has something to name; {@link #of} is for the few that
 * assert on it.
 */
public final class TestAssetId {

    /** For a test that does not care what the asset is called. */
    public static final Identifier ANY = Identifier.fromNamespaceAndPath("urbex", "test_asset");

    private TestAssetId() {
    }

    public static Identifier of(String path) {
        return Identifier.fromNamespaceAndPath("urbex", path);
    }
}
