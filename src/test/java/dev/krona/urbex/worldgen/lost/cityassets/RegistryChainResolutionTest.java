package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.ConditionRE;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsRE;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.IdentifierMatcher;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Two-entry-chain coverage for the registries where a wrong fold is silent rather than loud.
 * <p>
 * The bundled datapack uses {@code extends} only in {@code citystyles} and {@code presets}, so
 * every other registry only ever resolves a chain of one in the digest runs and in gameplay -
 * {@link StuffSettingsRE#resolve} even short-circuits that case. Without these tests the fold
 * bodies would never execute anywhere.
 * <p>
 * {@code stuff} is the dangerous one: {@code AssetRegistries.load} files each {@link StuffObject}
 * into {@code STUFF_BY_TAG} by its tags and {@code Stuff} reads back by tag, so a fold that dropped
 * inherited tags would produce decoration that simply never spawns - no exception, no log line, and
 * nothing a digest would catch.
 */
class RegistryChainResolutionTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---------------------------------------------------------------- stuff

    @Test
    void stuffThatDeclaresNoTagsKeepsItsAncestors() {
        // The silent-damage case: dropping these would unfile the object from STUFF_BY_TAG and it
        // would never be selected for placement again.
        StuffObject resolved = new StuffObject(List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").build()));

        assertEquals(List.of("all", "indoor"), resolved.getSettings().getTags());
    }

    @Test
    void stuffTagsReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        StuffObject replaced = new StuffObject(List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").tags(true, "rare").build()));
        assertEquals(List.of("rare"), replaced.getSettings().getTags(), "a bare array replaces");

        StuffObject appended = new StuffObject(List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").tags(false, "rare").build()));
        assertEquals(List.of("all", "indoor", "rare"), appended.getSettings().getTags(),
                "{\"replace\": false} keeps the inherited tags and adds after them");
    }

    @Test
    void stuffInheritsEveryOptionalScalarTheChildOmits() {
        IdentifierMatcher onlyLibraries = new IdentifierMatcher(
                Optional.of(List.of("urbex:library")), Optional.empty());
        StuffSettingsRE parent = stuff("torches")
                .tags(true, "all")
                .minheight(40).maxheight(90)
                .inbuilding(true).seesky(false)
                .buildings(onlyLibraries)
                .build();
        StuffSettingsRE child = stuff("torches_rare").column("XX").counts(2, 9, 7).build();

        StuffSettingsRE resolved = new StuffObject(List.of(parent, child)).getSettings();

        assertNotSame(child, resolved, "a two-entry chain must actually fold, not hand back the leaf");
        assertEquals(40, resolved.getMinheight(), "minheight is inherited");
        assertEquals(90, resolved.getMaxheight(), "maxheight is inherited");
        assertEquals(Boolean.TRUE, resolved.isInBuilding(), "inbuilding is inherited");
        assertEquals(Boolean.FALSE, resolved.isSeesky(),
                "seesky is inherited - and 'declared false' must not read as 'undeclared'");
        assertSame(onlyLibraries, resolved.getBuildingMatcher(), "the matcher is inherited");
        assertSame(IdentifierMatcher.ANY,
                new StuffObject(List.of(stuff("plain").build(), stuff("plain_child").build()))
                        .getSettings().getBuildingMatcher(),
                "a matcher no entry in the chain declares still reads as ANY");
    }

    @Test
    void stuffRequiredScalarsComeFromTheLeaf() {
        StuffSettingsRE resolved = new StuffObject(List.of(
                stuff("torches").column("AB").counts(1, 2, 3).build(),
                stuff("torches_rare").column("CD").counts(4, 5, 6).build())).getSettings();

        assertEquals("CD", resolved.getColumn());
        assertEquals(4, resolved.getMincount());
        assertEquals(5, resolved.getMaxcount());
        assertEquals(6, resolved.getAttempts());
    }

    @Test
    void stuffResolvedFromAChainOfOneIsTheEntryItself() {
        StuffSettingsRE only = stuff("torches").tags(true, "all").build();

        assertSame(only, new StuffObject(List.of(only)).getSettings());
    }

    // ----------------------------------------------------------- conditions

    @Test
    void conditionValuesReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        ConditionRE parent = condition("loot", true, "urbex:common_loot", "urbex:rare_loot");

        assertEquals(Set.of("urbex:barrel_loot"),
                valuesOf(new Condition(List.of(parent, condition("loot_barrel", true, "urbex:barrel_loot")))),
                "a bare array replaces");
        assertEquals(Set.of("urbex:common_loot", "urbex:rare_loot", "urbex:barrel_loot"),
                valuesOf(new Condition(List.of(parent, condition("loot_barrel", false, "urbex:barrel_loot")))),
                "{\"replace\": false} keeps the inherited values");
    }

    // ------------------------------------------------------------- variants

    @Test
    void variantBlocksReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        VariantRE parent = variant("stones", true,
                new BlockEntry(1, "minecraft:stone"), new BlockEntry(2, "minecraft:andesite"));

        Variant replaced = new Variant(List.of(parent,
                variant("stones_deep", true, new BlockEntry(3, "minecraft:deepslate"))));
        assertEquals(List.of("minecraft:deepslate"), blockIdsOf(replaced), "a bare array replaces");
        assertEquals(List.of(3), replaced.getBlocks().stream().map(org.apache.commons.lang3.tuple.Pair::getLeft).toList());

        Variant appended = new Variant(List.of(parent,
                variant("stones_deep", false, new BlockEntry(3, "minecraft:deepslate"))));
        assertEquals(List.of("minecraft:stone", "minecraft:andesite", "minecraft:deepslate"),
                blockIdsOf(appended), "{\"replace\": false} keeps the inherited blocks, in order");
    }

    // -------------------------------------------------------------- helpers

    private static List<String> blockIdsOf(Variant variant) {
        return variant.getBlocks().stream()
                .map(pair -> BuiltInRegistries.BLOCK.getKey(pair.getRight().getBlock()).toString())
                .toList();
    }

    /**
     * The distinct values a condition can hand back. {@code Condition} exposes only a weighted
     * draw, so this sweeps a fixed random sequence; the sequence is seeded, so the result is a
     * fixed computation rather than a flaky one.
     */
    private static Set<String> valuesOf(Condition condition) {
        ConditionContext context = context();
        RandomSource random = RandomSource.create(1234L);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(condition.getRandomValue(random, context));
        }
        return seen;
    }

    private static ConditionContext context() {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        return new ConditionContext(0, 1, 0, 5, "part", "belowpart", "building",
                new ChunkCoord(dimension, 0, 0)) {
            @Override
            public boolean isBuilding() {
                return true;
            }

            @Override
            public Identifier getBiome() {
                return Identifier.fromNamespaceAndPath("minecraft", "plains");
            }
        };
    }

    private static ConditionRE condition(String path, boolean replace, String... values) {
        List<ConditionPart> parts = List.of(values).stream()
                .map(value -> new ConditionPart(1.0f, value,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
                .toList();
        return new ConditionRE(Optional.empty(), new Mergeable<>(replace, parts))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
    }

    private static VariantRE variant(String path, boolean replace, BlockEntry... blocks) {
        return new VariantRE(Optional.empty(), new Mergeable<>(replace, List.of(blocks)))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
    }

    private static StuffBuilder stuff(String path) {
        return new StuffBuilder(path);
    }

    private static final class StuffBuilder {
        private final String path;
        private Optional<Mergeable<String>> tags = Optional.empty();
        private String column = "AA";
        private Optional<Integer> minheight = Optional.empty();
        private Optional<Integer> maxheight = Optional.empty();
        private int mincount = 1;
        private int maxcount = 1;
        private int attempts = 1;
        private Optional<Boolean> inbuilding = Optional.empty();
        private Optional<Boolean> seesky = Optional.empty();
        private Optional<IdentifierMatcher> buildings = Optional.empty();

        StuffBuilder(String path) {
            this.path = path;
        }

        StuffBuilder tags(boolean replace, String... values) {
            this.tags = Optional.of(new Mergeable<>(replace, List.of(values)));
            return this;
        }

        StuffBuilder column(String column) {
            this.column = column;
            return this;
        }

        StuffBuilder counts(int mincount, int maxcount, int attempts) {
            this.mincount = mincount;
            this.maxcount = maxcount;
            this.attempts = attempts;
            return this;
        }

        StuffBuilder minheight(int minheight) {
            this.minheight = Optional.of(minheight);
            return this;
        }

        StuffBuilder maxheight(int maxheight) {
            this.maxheight = Optional.of(maxheight);
            return this;
        }

        StuffBuilder inbuilding(boolean inbuilding) {
            this.inbuilding = Optional.of(inbuilding);
            return this;
        }

        StuffBuilder seesky(boolean seesky) {
            this.seesky = Optional.of(seesky);
            return this;
        }

        StuffBuilder buildings(IdentifierMatcher matcher) {
            this.buildings = Optional.of(matcher);
            return this;
        }

        StuffSettingsRE build() {
            return new StuffSettingsRE(Optional.empty(), tags, column, minheight, maxheight,
                    mincount, maxcount, attempts, inbuilding, seesky,
                    Optional.empty(), Optional.empty(), Optional.empty(), buildings)
                    .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
        }
    }
}
