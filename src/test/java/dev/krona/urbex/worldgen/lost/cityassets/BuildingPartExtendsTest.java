package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.BuildingPartRE;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartMeta;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A part's geometry comes from the last entry in its {@code extends} chain that declares it, so
 * "the radio tower, repainted" is a file with an {@code extends} and a {@code refpalette} in it.
 */
class BuildingPartExtendsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 2x2 footprint, two levels. */
    private static final List<List<String>> TOWER = List.of(
            List.of("ab", "cd"),
            List.of("ef", "gh"));

    @Test
    void aPartThatOnlySwapsItsPaletteKeepsItsAncestorsGeometry() {
        BuildingPartRE parent = part("radiotower", geometry(2, 2, TOWER)
                .refpalette("urbex:radiotower"));
        BuildingPartRE child = part("radiotower_rusted", inherits("urbex:radiotower")
                .refpalette("urbexmt:radiotower_rusted"));

        BuildingPart resolved = new BuildingPart(List.of(parent, child));

        assertEquals(2, resolved.getXSize());
        assertEquals(2, resolved.getZSize());
        assertEquals(2, resolved.getSliceCount());
        assertArrayEquals(new String[]{"abcd", "efgh"}, resolved.getSlices());
        assertEquals("urbexmt:radiotower_rusted", resolved.getRefPaletteName(),
                "the child's own refpalette still wins");
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "radiotower_rusted"), resolved.getId());
    }

    @Test
    void declaringSlicesReplacesTheInheritedOnesWholesale() {
        BuildingPartRE parent = part("tower", geometry(2, 2, TOWER));
        BuildingPartRE child = part("tower_short", inherits("urbex:tower")
                .slices(List.of(List.of("ij", "kl"))));

        BuildingPart resolved = new BuildingPart(List.of(parent, child));

        assertEquals(1, resolved.getSliceCount());
        assertArrayEquals(new String[]{"ijkl"}, resolved.getSlices());
        assertEquals(2, resolved.getXSize(), "dimensions are still inherited");
        assertEquals(2, resolved.getZSize());
    }

    @Test
    void anXSizeThatContradictsInheritedSlicesIsALoadError() {
        BuildingPartRE parent = part("tower", geometry(2, 2, TOWER));
        BuildingPartRE child = part("tower_wide", inherits("urbex:tower").xsize(3));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(List.of(parent, child)));

        assertTrue(error.getMessage().contains("urbex:tower_wide"),
                () -> "the error must name the part: " + error.getMessage());
        assertTrue(error.getMessage().contains("3"),
                () -> "the error must name the declared size: " + error.getMessage());
        assertTrue(error.getMessage().contains("2 wide"),
                () -> "the error must name the actual width: " + error.getMessage());
    }

    @Test
    void aZSizeThatContradictsInheritedSlicesIsALoadError() {
        BuildingPartRE parent = part("tower", geometry(2, 2, TOWER));
        BuildingPartRE child = part("tower_deep", inherits("urbex:tower").zsize(5));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(List.of(parent, child)));

        assertTrue(error.getMessage().contains("urbex:tower_deep"),
                () -> "the error must name the part: " + error.getMessage());
    }

    @Test
    void aChainThatNeverDeclaresSlicesIsALoadError() {
        BuildingPartRE parent = part("abstract_tower", new Builder().xsize(2).zsize(2));
        BuildingPartRE child = part("tower_rusted", inherits("urbex:abstract_tower")
                .refpalette("urbex:rusted"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(List.of(parent, child)));

        assertTrue(error.getMessage().contains("urbex:tower_rusted"),
                () -> "the error must name the part that failed to resolve: " + error.getMessage());
        assertTrue(error.getMessage().contains("slices"),
                () -> "the error must say what is missing: " + error.getMessage());
    }

    @Test
    void aChainThatNeverDeclaresDimensionsIsALoadError() {
        BuildingPartRE parent = part("sliced_only", new Builder().slices(TOWER));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(List.of(parent)));

        assertTrue(error.getMessage().contains("urbex:sliced_only"), error::getMessage);
    }

    @Test
    void metadataFromTheChildReplacesTheParentsUnlessItOptsIntoAppending() {
        BuildingPartRE parent = part("tower", geometry(2, 2, TOWER).meta(true, meta("support")));
        BuildingPartRE replacing = part("tower_a", inherits("urbex:tower").meta(true, meta("nowater")));
        BuildingPartRE appending = part("tower_b", inherits("urbex:tower").meta(false, meta("nowater")));

        BuildingPart replaced = new BuildingPart(List.of(parent, replacing));
        assertTrue(replaced.getMetaBoolean("nowater"));
        assertFalse(replaced.getMetaBoolean("support"), "a bare array replaces");

        BuildingPart appended = new BuildingPart(List.of(parent, appending));
        assertTrue(appended.getMetaBoolean("nowater"));
        assertTrue(appended.getMetaBoolean("support"), "{\"replace\": false} keeps the inherited meta");
    }

    private static PartMeta meta(String key) {
        return new PartMeta(key, Boolean.TRUE, null, null, null, null);
    }

    private static Builder geometry(int xSize, int zSize, List<List<String>> slices) {
        return new Builder().xsize(xSize).zsize(zSize).slices(slices);
    }

    private static Builder inherits(String id) {
        return new Builder().extendsId(id);
    }

    private static BuildingPartRE part(String path, Builder builder) {
        return builder.build().setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
    }

    private static final class Builder {
        private Optional<Identifier> extendsId = Optional.empty();
        private Optional<Integer> xSize = Optional.empty();
        private Optional<Integer> zSize = Optional.empty();
        private Optional<List<List<String>>> slices = Optional.empty();
        private Optional<String> refpalette = Optional.empty();
        private Optional<Mergeable<PartMeta>> meta = Optional.empty();

        Builder extendsId(String id) {
            this.extendsId = Optional.of(Identifier.parse(id));
            return this;
        }

        Builder xsize(int x) {
            this.xSize = Optional.of(x);
            return this;
        }

        Builder zsize(int z) {
            this.zSize = Optional.of(z);
            return this;
        }

        Builder slices(List<List<String>> slices) {
            this.slices = Optional.of(slices);
            return this;
        }

        Builder refpalette(String name) {
            this.refpalette = Optional.of(name);
            return this;
        }

        Builder meta(boolean replace, PartMeta... values) {
            this.meta = Optional.of(new Mergeable<>(replace, List.of(values)));
            return this;
        }

        BuildingPartRE build() {
            return new BuildingPartRE(extendsId, xSize, zSize, slices, refpalette, Optional.empty(), meta);
        }
    }
}
