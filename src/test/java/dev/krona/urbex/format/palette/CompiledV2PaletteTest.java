package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.traits.Damaged;
import dev.krona.urbex.format.palette.traits.Light;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compiled form, and the four {@code INVARIANT}s that say what asking it a question may cost.
 * <p>
 * {@code LOAD.040} to {@code LOAD.043} are the reason this class exists rather than the invariants being
 * asserted in a comment. {@code docs/format/README.md} §9 records that {@code LOAD} "is therefore the
 * least externally-checked part of the specification, and the easiest to let rot", precisely because
 * every rule in it is {@code MUST} or {@code INVARIANT} and none takes a fixture. These are the tests
 * that stop that being true of the four that decide whether the format's central claim - "reading the
 * format prepares everything" - is a claim or a hope.
 */
class CompiledV2PaletteTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Identifier DAMAGED = Identifier.parse("urbex:damaged");
    private static final Identifier LIGHT = Identifier.parse("urbex:light");

    /** A palette exercising every shape the compiled form has: block, weighted, tag, socket, traits. */
    private static final String PALETTE = """
            { "version": 2, "palette": {
                "X": "minecraft:stone_bricks",
                "#": { "kind": "weighted",
                       "traits": { "urbex:damaged": { "into": "minecraft:cobweb" } },
                       "choices": [
                         { "share": 0.25, "block": "minecraft:cracked_stone_bricks" },
                         { "share": 0.05, "block": "minecraft:lantern",
                           "traits": { "urbex:light": { "unlit": "minecraft:air" } } },
                         { "rest": true,  "block": "minecraft:stone_bricks" } ] },
                "e": { "block": "minecraft:lantern",
                       "traits": { "urbex:light": { "unlit": "minecraft:iron_bars" } } },
                "c": "create:andesite_casing" } }
            """;

    // ---- The compiled shape --------------------------------------------------------------------

    /**
     * {@code LOAD.020} to {@code LOAD.022}: one lookup returns both the state and the traits, per slot.
     * <p>
     * {@code LOAD.021}'s {@code > Why} is the whole design: "{@code TRAIT.005} lets two choices of one
     * marker carry different traits, so a per-marker trait table cannot represent them. Version 1 kept
     * traits in a separate map keyed by marker, which is both the wrong granularity and a second
     * lookup." So this asserts the granularity - the lantern slots of {@code '#'} carry
     * {@code urbex:light} and the stone slots do not, under one marker - and the arity, which is that
     * {@link CompiledEntry#slot} hands back both at once.
     */
    @Test
    @Rule("LOAD.020")
    @Rule("LOAD.021")
    @Rule("LOAD.022")
    void oneLookupReturnsBothTheStateAndTheTraitsAndTheTraitsArePerSlot() {
        CompiledEntry weighted = compiled().entry('#');
        assertEquals(Apportion.SLOTS, weighted.slotCount());

        Set<String> withLight = new LinkedHashSet<>();
        Set<String> withoutLight = new LinkedHashSet<>();
        for (int slot = 0; slot < weighted.slotCount(); slot++) {
            CompiledEntry.Resolved resolved = weighted.slot(slot);
            assertNotNull(resolved.state(), "LOAD.020: the slot holds a state");
            assertNotNull(resolved.traits(), "LOAD.020: and the traits that apply to it");
            assertTrue(resolved.traits().traits().containsKey(DAMAGED),
                    "every alternative inherited urbex:damaged");
            String block = resolved.state().getBlock().toString();
            (resolved.traits().traits().containsKey(LIGHT) ? withLight : withoutLight).add(block);
        }
        assertEquals(1, withLight.size(), "only the lantern slots carry urbex:light");
        assertEquals(2, withoutLight.size(), "and the two stone alternatives do not");
    }

    /**
     * {@code LOAD.023}: trait sets are interned, so slots sharing one share the object.
     * <p>
     * Asserted with {@code assertSame}, which is what the rule says and what {@code assertEquals} would
     * not: the point is one object for 128 slots, not 128 equal ones.
     * <p>
     * <b>This test used to cite {@code LOAD.030} as well, and could not have failed for it.</b>
     * {@link #PALETTE} has no {@code $defs} and no {@code $ref}, so the cross-marker line below compares
     * two traitless plain-string markers: it reduced to {@code assertSame(TraitSet.EMPTY,
     * TraitSet.EMPTY)}, which is true of any implementation whatsoever. The citation moved to
     * {@link #twoMarkersReferencingOneDefinitionShareOneCompiledRepresentation()}, which builds the
     * situation the rule is about.
     */
    @Test
    @Rule("LOAD.023")
    void traitSetsAreInternedSoSlotsSharingOneShareTheObject() {
        CompiledV2Palette palette = compiled();
        CompiledEntry weighted = palette.entry('#');

        List<TraitSet> distinct = new ArrayList<>();
        for (int slot = 0; slot < weighted.slotCount(); slot++) {
            TraitSet traits = weighted.slot(slot).traits();
            if (distinct.stream().noneMatch(seen -> seen == traits)) {
                distinct.add(traits);
            }
        }
        assertEquals(2, distinct.size(),
                "128 slots over two distinct trait sets hold two objects, not 128");

        assertSame(palette.entry('X').slot(0).traits(), palette.entry('c').slot(0).traits());
        assertSame(TraitSet.EMPTY, palette.entry('X').slot(0).traits(),
                "a slot with no traits is the one shared empty set");
        assertNotSame(palette.entry('e').slot(0).traits(), TraitSet.EMPTY);
    }

    /**
     * {@code LOAD.030}: two markers that reference one definition share one compiled representation.
     * <p>
     * The rule's situation, built rather than approximated: a {@code $defs} entry carrying a trait whose
     * satellite is itself a reference, two markers pointing at it, and two <em>different</em> blocks
     * under them so that nothing about the sharing can be an accident of the markers being alike. What
     * is asserted same is the whole chain the definition contributes - the interned {@link TraitSet},
     * the {@link CompiledTrait} inside it, and the {@link CompiledEntry} its {@code into} satellite
     * compiled to.
     * <p>
     * <b>The three negative assertions are the ones that make the positive ones mean something.</b> The
     * shared set is asserted non-empty and not {@link TraitSet#EMPTY}, because a sharing assertion over
     * the empty set is true of every implementation ever written; and {@code 'C'}, which writes an
     * {@code urbex:damaged} of its own naming a different form, is asserted <em>not</em> to share, so
     * the test distinguishes "shared because it is one definition" from "same object for everything".
     * <p>
     * "Established at compile time rather than recovered afterwards" is what {@code assertSame} on the
     * satellite says that {@code assertEquals} would not: version 1's two static interning pools existed
     * precisely to recover this identity after the fact, and {@code LOAD.031} is the other half of the
     * same sentence.
     */
    @Test
    @Rule("LOAD.030")
    void twoMarkersReferencingOneDefinitionShareOneCompiledRepresentation() {
        CompiledV2Palette palette = TraitTest.compile("""
                { "version": 2,
                  "$defs": {
                    "rubble":     { "kind": "weighted", "choices": [
                                      { "share": 0.5, "block": "minecraft:cobweb" },
                                      { "rest": true, "block": "minecraft:iron_bars" } ] },
                    "damageable": { "traits": { "urbex:damaged": { "into": { "$ref": "rubble" } } } } },
                  "palette": {
                    "A": { "$ref": "damageable", "block": "minecraft:stone_bricks" },
                    "B": { "$ref": "damageable", "block": "minecraft:bricks" },
                    "C": { "block": "minecraft:deepslate_bricks",
                           "traits": { "urbex:damaged": { "into": "minecraft:cobweb" } } } } }
                """, Set.of());

        assertNotEquals(palette.entry('A').slot(0).state(), palette.entry('B').slot(0).state(),
                "the two markers place different blocks, so nothing below is shared by looking alike");

        TraitSet first = palette.entry('A').slot(0).traits();
        TraitSet second = palette.entry('B').slot(0).traits();
        assertFalse(first.isEmpty(), "the definition contributes a trait, so there is something to share");
        assertNotSame(TraitSet.EMPTY, first, "and it is not the empty set, which shares trivially");
        assertSame(first, second, "LOAD.030: one definition, one compiled representation");

        CompiledTrait damagedA = first.get(DAMAGED).orElseThrow();
        CompiledTrait damagedB = second.get(DAMAGED).orElseThrow();
        assertSame(damagedA, damagedB, "the trait itself, not merely an equal copy");
        assertNotNull(damagedA.satellite(Damaged.INTO), "the definition's satellite compiled");
        assertSame(damagedA.satellite(Damaged.INTO), damagedB.satellite(Damaged.INTO),
                "and the satellite it resolves through is the same compiled entry");

        TraitSet own = palette.entry('C').slot(0).traits();
        assertFalse(own.isEmpty());
        assertNotSame(first, own,
                "a marker writing its own damaged form shares nothing with the definition's");
    }

    /**
     * {@code LOAD.024}: nothing of the parsed document survives into the compiled palette.
     * <p>
     * "No compiled palette holds a reference to the parsed JSON, to a definition name, to a pointer, or
     * to any string used only during compilation." The half that is easy to get wrong is a trait
     * payload: a block-valued field holds a {@link RawNode} through stage 3, and if the compiled form
     * kept the payload as it stood the satellite's block strings would still be there and the damage
     * pass would have to resolve them at a position - which {@code LOAD.042} forbids. So the satellite
     * is compiled to a {@link CompiledEntry} and the payload's field is blanked.
     */
    @Test
    @Rule("LOAD.024")
    @Rule("LOAD.042")
    void nothingOfTheRawTreeSurvivesAndASatelliteIsCompiledRatherThanDeferred() {
        CompiledTrait damaged = compiled().entry('#').slot(0).traits().get(DAMAGED).orElseThrow();

        CompiledEntry into = damaged.satellite(Damaged.INTO);
        assertEquals(Blocks.COBWEB.defaultBlockState(), into.slot(0).state(),
                "the satellite is a block state at load, not a string to resolve at a position");

        assertSame(RawNode.ABSENT, ((Damaged.Value) damaged.value()).into(),
                "and the payload no longer holds the node it was compiled from");

        CompiledTrait light = compiled().entry('e').slot(0).traits().get(LIGHT).orElseThrow();
        assertEquals(Blocks.IRON_BARS.defaultBlockState(),
                light.satellite(Light.UNLIT).slot(0).state());
        assertSame(RawNode.ABSENT, ((Light.Value) light.value()).unlit());
    }

    /** {@code MODEL.042}: a block no installed mod provides compiles to air, and the load succeeds. */
    @Test
    @Rule("MODEL.042")
    void anAbsentBlockCompilesToAirRatherThanRefusingTheWorld() {
        assertEquals(Blocks.AIR.defaultBlockState(), compiled().entry('c').slot(0).state());
    }

    // ---- The generation-time invariants ---------------------------------------------------------

    /**
     * {@code LOAD.040} and {@code LOAD.041}: resolving a marker at a position allocates nothing, and
     * boxes nothing.
     * <p>
     * <b>Measured, not asserted.</b> The loop runs a million resolutions of an ASCII marker and a
     * non-ASCII one and reads {@code getThreadAllocatedBytes} either side of it. Any per-call allocation
     * - a returned pair, a boxed {@code Integer} outside the cache, a {@code Marker} record, an
     * {@code Optional} - would be at least sixteen bytes a call and so at least sixteen megabytes over
     * the loop; the budget below is sixty-four kilobytes, which is 250 times under the smallest
     * possible failure and 0.065 bytes per call.
     * <p>
     * <b>Three measured runs, and the smallest is the one asserted.</b> A single run measures the code
     * under test plus whatever the JVM did during it - a recompilation, a safepoint's bookkeeping - and
     * the first draft of this test failed at 4272 bytes against a 4 KB budget on an otherwise clean run,
     * which is 0.004 bytes per call and obviously not a per-call allocation. Taking the minimum of
     * three removes the one-off costs without weakening the claim: a genuine allocation happens on every
     * call and so cannot be absent from the smallest run.
     * <p>
     * <b>What this establishes of {@code LOAD.041}, exactly.</b> Boxing, because boxing is allocation -
     * that is the half of the rule this measurement reaches. The other two clauses it does <em>not</em>
     * reach, because a hash lookup on an interned key and a comparison of two identical string
     * references both allocate nothing: they are structural instead, and the structure is the argument.
     * {@link CompiledV2Palette#at} takes an {@code int} codepoint, so there is no string to compare; the
     * only lookup in it is {@link MarkerIndex#index}, which {@code MarkerIndexTest} pins as two array
     * reads and a mask with no branch on whether a codepoint is ASCII; and the rest is arithmetic in
     * {@code Rng.paletteSlotAt}. Saying so here rather than claiming the measurement covers it.
     * <p>
     * The loop is warmed first, because the first call through a cold path allocates in the JIT rather
     * than in the code under test, and the accumulator is an {@code int} so that nothing the assertion
     * needs is itself an allocation.
     */
    @Test
    @Rule("LOAD.040")
    @Rule("LOAD.041")
    void resolvingAMarkerAtAPositionAllocatesNothing() {
        CompiledV2Palette palette = compiled();
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(threads.isThreadAllocatedMemoryEnabled(),
                "this JVM cannot measure per-thread allocation, so the invariant is unchecked");

        int warm = resolve(palette, 10_000);
        assertTrue(warm > 0);

        long id = Thread.currentThread().threadId();
        long allocated = Long.MAX_VALUE;
        int seen = 0;
        for (int run = 0; run < 3; run++) {
            long before = threads.getThreadAllocatedBytes(id);
            seen += resolve(palette, 1_000_000);
            allocated = Math.min(allocated, threads.getThreadAllocatedBytes(id) - before);
        }

        assertTrue(seen > 0);
        long smallest = allocated;
        assertTrue(smallest < 65_536, () -> "resolving a marker allocated " + smallest
                + " bytes over 1,000,000 calls; LOAD.040 says none");
    }

    /** The loop under measurement: no lambda, no stream, no box, and an {@code int} accumulator. */
    private static int resolve(CompiledV2Palette palette, int times) {
        int seen = 0;
        for (int at = 0; at < times; at++) {
            CompiledEntry.Resolved weighted = palette.at('#', 1234L, at & 255, 64, at & 127);
            CompiledEntry.Resolved plain = palette.at('X', 1234L, at & 255, 64, at & 127);
            if (weighted != null && plain != null) {
                seen++;
            }
        }
        return seen;
    }

    /**
     * {@code LOAD.042}: resolving a marker at a position reads no registry and no tag.
     * <p>
     * Asserted by counting, which is the only way to assert an absence of a read: the palette is
     * compiled against a tag epoch that records every question asked of it, the counter is read after
     * compilation, and ten thousand resolutions later it has not moved. A tag node is in the palette on
     * purpose - {@code MODEL.052} says a tag is "expanded at load […] and never read during generation",
     * so a palette with no tag in it would prove nothing.
     * <p>
     * The last assertion is {@code MODEL.050}'s - "one block drawn uniformly from the tag's members" -
     * and it was already here, cited by nothing. The annotation was the only thing missing, which is a
     * reminder that {@code @Rule} is the whole binding: an assertion the index cannot see is an
     * assertion the index reports as absent.
     */
    @Test
    @Rule("LOAD.042")
    @Rule("MODEL.050")
    @Rule("MODEL.052")
    void resolvingAMarkerReadsNoTag() {
        int[] asked = {0};
        TraitContext context = TraitContext.of(BuiltInRegistries.BLOCK)
                .withTags(tag -> {
                    asked[0]++;
                    return List.of("minecraft:oak_planks", "minecraft:spruce_planks");
                });
        CompiledV2Palette palette = TraitTest.compileWith("""
                { "version": 2, "palette": { "p": { "kind": "tag", "tag": "#minecraft:planks" } } }
                """, context);

        int atLoad = asked[0];
        assertTrue(atLoad > 0, "the tag was expanded at load");
        for (int at = 0; at < 10_000; at++) {
            assertNotNull(palette.at('p', 7L, at, 64, at));
        }
        assertEquals(atLoad, asked[0], "and never read again");

        Set<String> placed = new LinkedHashSet<>();
        for (int at = 0; at < 10_000; at++) {
            placed.add(palette.at('p', 7L, at, 64, at).state().getBlock().toString());
        }
        assertEquals(2, placed.size(), "MODEL.050: one block drawn uniformly from the tag's members");
    }

    /**
     * {@code LOAD.043}: the result depends only on the seed, the marker, the position and the palette.
     * <p>
     * Three claims, each one a way the version 1 path got it wrong. The same positions resolved in a
     * different order give the same answers - version 1 drew from a per-chunk sequential stream, so how
     * many other markers a chunk resolved first decided what a block was. Two palettes compiled
     * separately from one document agree, so nothing is carried between compilations. And a different
     * seed gives a different distribution, which is the assertion that the seed is actually in the
     * address rather than the other two being vacuous.
     */
    @Test
    @Rule("LOAD.043")
    void theResultDependsOnlyOnTheSeedTheMarkerThePositionAndThePalette() {
        CompiledV2Palette palette = compiled();
        List<int[]> positions = new ArrayList<>();
        for (int at = 0; at < 500; at++) {
            positions.add(new int[]{at * 7 - 100, 60 + (at % 40), 13 - at * 3});
        }

        List<String> forwards = new ArrayList<>();
        positions.forEach(pos -> forwards.add(blockAt(palette, pos, 99L)));

        List<String> backwards = new ArrayList<>();
        for (int at = positions.size() - 1; at >= 0; at--) {
            backwards.add(blockAt(palette, positions.get(at), 99L));
        }
        java.util.Collections.reverse(backwards);
        assertEquals(forwards, backwards, "the order positions are resolved in decides nothing");

        CompiledV2Palette again = compiled();
        List<String> recompiled = new ArrayList<>();
        positions.forEach(pos -> recompiled.add(blockAt(again, pos, 99L)));
        assertEquals(forwards, recompiled, "and neither does which compilation asked");

        List<String> otherSeed = new ArrayList<>();
        positions.forEach(pos -> otherSeed.add(blockAt(palette, pos, 100L)));
        assertFalse(forwards.equals(otherSeed), "the seed is in the address");
    }

    private static String blockAt(CompiledV2Palette palette, int[] pos, long seed) {
        return palette.at('#', seed, pos[0], pos[1], pos[2]).state().getBlock().toString();
    }

    /**
     * {@code LOAD.003}, {@code LOAD.011} and {@code LOAD.031}: the compiler reads only the registry it
     * was handed, keeps nothing static, and hands back something that cannot report anything.
     * <p>
     * <b>The block lookup really is wrapped this time.</b> The first version of this test said so and
     * counted the <em>tag</em> epoch of a palette with no tag in it, so the assertion was {@code 0 == 0}
     * and established nothing at all - a vacuous test is worse than no test, because it reads as
     * coverage. {@link CountingBlocks} delegates every {@code get} of the real registry and counts, the
     * count is asserted non-zero after compilation so the wrapper is known to be on the path, and it is
     * asserted unchanged after ten thousand resolutions. That is {@code LOAD.003} - a compiler reaching
     * a static registry instead of the handed one would ask it here and the counter would not see it -
     * and {@code LOAD.042}'s block half beside {@code resolvingAMarkerReadsNoTag}'s tag half.
     * <p>
     * Two compilations of one document produce equal answers with no state passed between them, which is
     * a necessary condition of {@code LOAD.031} and nowhere near a sufficient one - <b>a static interning
     * pool satisfies it exactly</b>, which is the version 1 defect that rule forbids, so this test no
     * longer cites it. {@link #compilationRetainsNoStaticStateThatOutlivesTheCompiledPalette()} is the
     * one that can fail on a pool. {@code LOAD.011} moved out for the same reason: it was argued in this
     * javadoc and asserted nowhere, and is now
     * {@link #nothingAGeneratedPaletteExposesCanReportADiagnostic()}.
     */
    @Test
    @Rule("LOAD.003")
    @Rule("LOAD.042")
    void theCompilerReadsOnlyTheRegistryItWasHandedAndKeepsNothingAfterwards() {
        CountingBlocks blocks = new CountingBlocks(BuiltInRegistries.BLOCK);
        CompiledV2Palette first = TraitTest.compileWith(PALETTE, TraitContext.of(blocks));
        int afterCompiling = blocks.reads;
        assertTrue(afterCompiling > 0,
                "the handed registry was read while compiling, so the wrapper is on the path");

        CompiledV2Palette second = TraitTest.compileWith(PALETTE, TraitContext.of(blocks));
        int afterBoth = blocks.reads;
        assertTrue(afterBoth > afterCompiling, "and again for the second compilation");

        for (int at = 0; at < 10_000; at++) {
            assertNotNull(first.at('#', 5L, at, 64, at));
            assertNotNull(second.at('#', 5L, at, 64, at));
            assertNotNull(first.at('e', 5L, at, 64, at));
        }
        assertEquals(afterBoth, blocks.reads,
                "and never once while resolving a marker at a position");

        for (int at = 0; at < 1000; at++) {
            assertEquals(first.at('#', 5L, at, 64, at).state(),
                    second.at('#', 5L, at, 64, at).state(),
                    "two compilations of one document agree, so nothing was carried between them");
        }
        assertNotSame(first, second);
    }

    /**
     * {@code LOAD.031}: compilation retains no state in a static field that outlives the compiled palette.
     * <p>
     * <b>Why this is not "two compilations agree".</b> That is what this rule used to be checked by, and
     * a static interning pool - the exact thing version 1 held two of, and the exact thing this rule
     * forbids - passes it: a pool makes two compilations agree <em>more</em> readily, not less, and the
     * two palettes are still distinct objects. The guard has to look at the static fields themselves.
     * <p>
     * So it does. Every class of {@code format.palette} and {@code format.palette.traits}, nested classes
     * included - {@code CompiledV2Palette.Compiler} among them, which is where the interning pool and the
     * two memos actually live, as instance fields - is reflected over. Two things are asserted: that no
     * static field is non-final (a mutable static is retained state by definition), and that no static
     * {@link java.util.Collection}, {@link java.util.Map} or array changes size across eight compilations
     * of eight distinct palettes. A pool that grew by one entry per palette moves a size and fails here.
     * <p>
     * <b>One compilation runs before the baseline is taken</b>, deliberately: a registry populated on
     * first use is one-time initialisation and not retained per-palette state, and folding it into the
     * baseline is what keeps this test measuring the thing the rule is about. And the scan asserts it
     * found something - a walk that silently resolved no class, or found no static collection, would
     * report "nothing grew" about nothing at all, which is the vacuity this whole fix wave is about.
     */
    @Test
    @Rule("LOAD.031")
    void compilationRetainsNoStaticStateThatOutlivesTheCompiledPalette() {
        List<Class<?>> classes = compilationClasses();
        assertTrue(classes.size() >= 20,
                () -> "the class walk found only " + classes.size() + " classes; it is not scanning"
                        + " the compiler at all");

        List<String> nonFinal = new ArrayList<>();
        for (Class<?> type : classes) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !java.lang.reflect.Modifier.isFinal(field.getModifiers())
                        && !field.isSynthetic()) {
                    nonFinal.add(type.getName() + "." + field.getName());
                }
            }
        }
        assertTrue(nonFinal.isEmpty(),
                () -> "LOAD.031: a non-final static field is retained state: " + nonFinal);

        compileEight(0);
        java.util.Map<String, Integer> before = staticContainerSizes(classes);
        assertFalse(before.isEmpty(),
                "the scan found no static collection, map or array at all, so it could not have"
                        + " noticed one growing");
        compileEight(8);
        assertEquals(before, staticContainerSizes(classes),
                "LOAD.031: a static container grew while compiling, so compilation is retaining state"
                        + " that outlives the palettes it built");
    }

    /**
     * Eight palettes, compiled, no two alike and none alike any other round's.
     * <p>
     * {@code from} is what makes the second round different from the first, and it is not decoration: a
     * pool keyed by value - which is what an interning pool is - does not grow when the same eight
     * documents are compiled twice, so re-compiling one round would have measured nothing. Proven the
     * hard way: the first version of this guard did exactly that, a deliberate leak was injected into
     * {@code compile}, and the guard passed.
     */
    private static void compileEight(int from) {
        for (int at = from; at < from + 8; at++) {
            TraitTest.compile("{ \"version\": 2, \"$defs\": { \"d\": { \"traits\":"
                    + " { \"urbex:damaged\": { \"into\": \"minecraft:cobweb\" } } } },"
                    + " \"palette\": { \"" + (char) ('A' + at) + "\": { \"$ref\": \"d\","
                    + " \"kind\": \"weighted\", \"choices\": ["
                    + " { \"weight\": " + (at + 1) + ", \"block\": \"minecraft:stone_bricks\" },"
                    + " { \"weight\": 3, \"block\": \"minecraft:bricks\" } ] } } }", Set.of());
        }
    }

    /** {@code <class>.<field>} to the size of every static container the compiler's classes declare. */
    private static java.util.Map<String, Integer> staticContainerSizes(List<Class<?>> classes) {
        java.util.Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
        for (Class<?> type : classes) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(null);
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    continue;
                }
                String name = type.getName() + "." + field.getName();
                if (value instanceof java.util.Collection<?> collection) {
                    sizes.put(name, collection.size());
                } else if (value instanceof java.util.Map<?, ?> map) {
                    sizes.put(name, map.size());
                } else if (value != null && value.getClass().isArray()) {
                    sizes.put(name, java.lang.reflect.Array.getLength(value));
                }
            }
        }
        return sizes;
    }

    /**
     * Every class of the two packages compilation is implemented in, nested classes included.
     * <p>
     * Read off the source tree rather than off a classpath scan: the test module has no classpath-scanner
     * dependency, and {@code SpecDocuments} already establishes reading {@code src/} directly as this
     * suite's way of asking a question about the whole tree. A file that does not resolve to a class is
     * skipped rather than failing - a package-private helper compiled into another file's class would
     * otherwise turn a widening of the walk into a failure about nothing.
     */
    private static List<Class<?>> compilationClasses() {
        List<Class<?>> classes = new ArrayList<>();
        for (String pkg : List.of("dev.krona.urbex.format.palette",
                "dev.krona.urbex.format.palette.traits")) {
            java.nio.file.Path dir = dev.krona.urbex.format.SpecDocuments.repoRoot()
                    .resolve("src/main/java").resolve(pkg.replace('.', '/'));
            if (!java.nio.file.Files.isDirectory(dir)) {
                continue;
            }
            try (java.util.stream.Stream<java.nio.file.Path> listing = java.nio.file.Files.list(dir)) {
                listing.filter(file -> file.toString().endsWith(".java")).sorted().forEach(file -> {
                    String simple = file.getFileName().toString().replaceFirst("\\.java$", "");
                    try {
                        addWithNested(Class.forName(pkg + "." + simple), classes);
                    } catch (ClassNotFoundException | LinkageError absent) {
                        // Not a top-level type of its own file; nothing to scan.
                    }
                });
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return classes;
    }

    private static void addWithNested(Class<?> type, List<Class<?>> into) {
        into.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            addWithNested(nested, into);
        }
    }

    /**
     * {@code LOAD.011}: no compiled palette can raise a diagnostic during generation.
     * <p>
     * The rule is structural, and this asserts the structure instead of arguing it. Every public method
     * of every type generation holds - the palette, its entries, a resolved slot, a trait set, a compiled
     * trait, the marker index - is checked for a {@code Diagnostics}, a {@code Diag} or a
     * {@code DataResult} anywhere in its parameters or its return type. There is nowhere for a generation-time diagnostic to go because
     * there is no type in the surface that could carry one.
     * <p>
     * <b>The last assertion is what makes the rest a measurement.</b> An absence is only evidence when
     * the detector is known to fire, so the detector is pointed at {@link CompiledV2Palette#compile} -
     * which does take a collector, because it is load and not generation - and asserted to find it. A
     * scan that had stopped seeing {@code Diagnostics} at all would pass every other line of this test
     * and fail that one. The earlier version of this claim lived in a javadoc beside
     * {@code assertNotNull(first.at(...))}, which is an argument in a comment.
     */
    @Test
    @Rule("LOAD.011")
    void nothingAGeneratedPaletteExposesCanReportADiagnostic() {
        List<Class<?>> surface = List.of(CompiledV2Palette.class, CompiledEntry.class,
                CompiledEntry.Resolved.class, TraitSet.class, CompiledTrait.class, MarkerIndex.class);
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Class<?> type : surface) {
            for (java.lang.reflect.Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (type == CompiledV2Palette.class && "compile".equals(method.getName())) {
                    continue;
                }
                scanned++;
                if (reports(method)) {
                    offenders.add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "LOAD.011: a compiled palette's surface can carry a"
                + " diagnostic, so generation has somewhere to raise one: " + offenders);
        int methods = scanned;
        assertTrue(methods >= 20, () -> "only " + methods + " methods were scanned; the surface this"
                + " asserts an absence over is not being read");

        assertTrue(java.util.Arrays.stream(CompiledV2Palette.class.getMethods())
                        .filter(method -> "compile".equals(method.getName()))
                        .anyMatch(CompiledV2PaletteTest::reports),
                "compile takes a collector, and the check above must be able to see one - otherwise"
                        + " every absence it reports is an absence of looking");
    }

    /** Whether a diagnostic can pass through this method in either direction. */
    private static boolean reports(java.lang.reflect.Method method) {
        List<Class<?>> types = new ArrayList<>(List.of(method.getParameterTypes()));
        types.add(method.getReturnType());
        return types.stream().anyMatch(type -> type == dev.krona.urbex.format.Diagnostics.class
                || type == dev.krona.urbex.format.Diag.class
                || type == dev.krona.urbex.format.Diagnostics.Entry.class
                || type == com.mojang.serialization.DataResult.class);
    }

    /**
     * A block registry that answers exactly as the real one does, and counts being asked.
     * <p>
     * {@code HolderLookup.RegistryLookup.Delegate} supplies every method from {@link #parent()}, so the
     * two this overrides are the two {@code LOAD.003} and {@code LOAD.042} are about: an element by key,
     * and a tag. Nothing else about the lookup changes, which is what makes the count a measurement of
     * the compiler rather than of the wrapper.
     */
    private static final class CountingBlocks
            implements HolderLookup.RegistryLookup.Delegate<Block> {

        private final HolderLookup.RegistryLookup<Block> parent;
        private int reads;

        CountingBlocks(HolderLookup.RegistryLookup<Block> parent) {
            this.parent = parent;
        }

        @Override
        public HolderLookup.RegistryLookup<Block> parent() {
            return parent;
        }

        @Override
        public Optional<Holder.Reference<Block>> get(ResourceKey<Block> key) {
            reads++;
            return HolderLookup.RegistryLookup.Delegate.super.get(key);
        }

        @Override
        public Optional<HolderSet.Named<Block>> get(TagKey<Block> tag) {
            reads++;
            return HolderLookup.RegistryLookup.Delegate.super.get(tag);
        }
    }

    /**
     * {@code CHAR.031}: the dense index is built once per compiled palette, not per chunk or per part.
     * <p>
     * The rule is about <em>when</em> the remap is built, which order-independence does not touch - a
     * remap rebuilt on every lookup would be order-independent and would still break this rule. So this
     * asserts identity: the palette hands back the same {@link MarkerIndex} object however many times it
     * is asked, and the object is unchanged after ten thousand resolutions have gone through it.
     */
    @Test
    @Rule("CHAR.031")
    void theDenseIndexIsBuiltOncePerPaletteAndNotPerLookup() {
        CompiledV2Palette palette = compiled();
        MarkerIndex index = palette.markerIndex();
        assertSame(index, palette.markerIndex());

        for (int at = 0; at < 10_000; at++) {
            assertNotNull(palette.at('#', 3L, at, 64, at));
        }
        assertSame(index, palette.markerIndex(), "resolving did not rebuild it");
        assertEquals(4, index.size(), "and it still holds exactly the markers it was built from");
    }

    /**
     * {@code LOAD.001}: the stages run in the order the table gives, and each completes before the next.
     * <p>
     * The order is observable at exactly one place, and it is the one the specification argues about:
     * exclusion (stage 4) runs before tag expansion (stage 5), and both run before apportionment (stage
     * 6). The palette below has a {@code when} that does not hold beside a tag, so if expansion ran
     * first the excluded choice would still have divided the tag's share. What the slots show instead is
     * the whole node given to the tag's two members.
     */
    @Test
    @Rule("LOAD.001")
    void theStagesRunInTheOrderTheTableGivesAndExclusionPrecedesExpansion() {
        TraitContext epoch = TraitContext.of(BuiltInRegistries.BLOCK)
                .withTags(tag -> List.of("minecraft:oak_planks", "minecraft:spruce_planks"));
        CompiledV2Palette palette = TraitTest.compileWith("""
                { "version": 2, "palette": { "p": { "kind": "weighted", "choices": [
                    { "weight": 1, "block": "minecraft:stone", "when": { "mod": "create" } },
                    { "weight": 1, "kind": "tag", "tag": "#minecraft:planks" } ] } } }
                """, epoch);

        CompiledEntry entry = palette.entry('p');
        Set<String> placed = new LinkedHashSet<>();
        for (int slot = 0; slot < entry.slotCount(); slot++) {
            placed.add(entry.slot(slot).state().getBlock().toString());
        }
        assertEquals(2, placed.size(),
                "the excluded choice left before the tag divided what remained");
        assertFalse(placed.toString().contains("minecraft:stone}"), placed.toString());
    }

    // ---- The report ------------------------------------------------------------------------------

    /**
     * {@code LOAD.050} and {@code LOAD.051}: the fully resolved form of a marker, printed.
     * <p>
     * The rule lists four things the report must contain and this asserts all four: the kind, the slots
     * with their <em>exact</em> shares, every trait with the node it was inherited from, and the
     * reference chain each came through. The third and fourth are the ones a reader cannot reconstruct
     * from the file - a trait may have been written three files up the {@code extends} chain and reached
     * through an import alias - and they are why the rule's {@code > Why} says that without this "the
     * format is harder to work with than the one it replaces".
     */
    @Test
    @Rule("LOAD.050")
    @Rule("LOAD.051")
    void theLoaderCanPrintAMarkersFullyResolvedFormWithItsSharesAndItsTraitProvenance() {
        NodeResolver.ResolvedPalette resolved = TraitTest.link("""
                { "version": 2,
                  "$defs": {
                    "rubble":     { "kind": "weighted", "choices": [
                                      { "share": 0.5, "block": "minecraft:cobweb" },
                                      { "rest": true, "block": "minecraft:iron_bars" } ] },
                    "damageable": { "traits": { "urbex:damaged": { "into": { "$ref": "rubble" } } } } },
                  "palette": { "#": { "$ref": "damageable", "kind": "weighted", "choices": [
                        { "share": 0.25, "block": "minecraft:cracked_stone_bricks" },
                        { "share": 0.25, "block": "minecraft:mossy_stone_bricks" },
                        { "rest": true,  "block": "minecraft:stone_bricks",
                          "traits": { "urbex:rotatable": false } } ] } } }
                """);
        String report = ResolutionReport.of(new Marker('#'), resolved.palette().get(new Marker('#')));

        assertTrue(report.contains("weighted"), report);
        assertTrue(report.contains("minecraft:cracked_stone_bricks"), report);
        // The exact share the file wrote, and the slots it rounds to - both, so the rounding is
        // visible rather than substituted for the intent.
        assertTrue(report.contains("0.25 (32/128 slots)"),
                () -> "the exact share and its rounding are missing:\n" + report);
        assertTrue(report.contains("0.5 (64/128 slots)"),
                () -> "the rest's share is missing:\n" + report);

        assertTrue(report.contains("trait urbex:damaged — inherited from"),
                () -> "LOAD.050 asks for the node a trait was inherited from:\n" + report);
        assertTrue(report.contains("trait urbex:rotatable — declared here"),
                () -> "and for the ones that were not:\n" + report);
        assertTrue(report.contains("via 'damageable'"),
                () -> "LOAD.051 asks for the chain it came through:\n" + report);
        assertTrue(report.contains("minecraft:iron_bars"),
                () -> "the satellite is resolved in the report too:\n" + report);
    }

    /**
     * A share the file wrote that no decimal expansion can hold is printed as the rational it is.
     * <p>
     * {@code WEIGHT.052} requires exact rational arithmetic over the whole tree, and a report that
     * printed {@code 0.333…} would be a summary of the arithmetic rather than the arithmetic. Three
     * equal weights divide a node into thirds and the report says so; the slot counts beside them are
     * 43, 43 and 42, which is {@code WEIGHT.060}'s tie break and is exactly what an author asking "why
     * is one of these rarer" needs to see.
     */
    @Test
    @Rule("LOAD.050")
    @Rule("WEIGHT.052")
    void aShareNoDecimalCanHoldIsPrintedAsTheRationalItIs() {
        NodeResolver.ResolvedPalette resolved = TraitTest.link("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 1, "block": "minecraft:stone" },
                    { "weight": 1, "block": "minecraft:cobweb" },
                    { "weight": 1, "block": "minecraft:iron_bars" } ] } } }
                """);
        String report = ResolutionReport.of(new Marker('#'), resolved.palette().get(new Marker('#')));
        assertFalse(report.contains("0.333"), () -> "a third is not a decimal:\n" + report);

        // The counts are the palette's, not a per-leaf rounding of the share: three equal thirds are
        // 43, 43 and 42, and they sum to the 128 slots the node actually has. Re-deriving each one
        // independently printed 43 three times - 129 slots - and so could not answer the only question
        // a slot count is for, which is which of three equal choices the tie break made rarer.
        assertEquals(2, occurrences(report, "1/3 (43/128 slots)"), () -> report);
        assertEquals(1, occurrences(report, "1/3 (42/128 slots)"), () -> report);
        assertEquals(Apportion.SLOTS, 43 + 43 + 42);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    // ----------------------------------------------------------------------------------------------

    private static CompiledV2Palette compiled() {
        return TraitTest.compile(PALETTE, Set.of());
    }
}
