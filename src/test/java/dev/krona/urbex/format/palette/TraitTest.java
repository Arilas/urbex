package dev.krona.urbex.format.palette;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.traits.BlockEntityNbt;
import dev.krona.urbex.format.palette.traits.Damaged;
import dev.krona.urbex.format.palette.traits.Light;
import dev.krona.urbex.format.palette.traits.Loot;
import dev.krona.urbex.format.palette.traits.OptionalTrait;
import dev.krona.urbex.format.palette.traits.Rotatable;
import dev.krona.urbex.format.palette.traits.Spawner;
import dev.krona.urbex.setup.CustomRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trait registry, trait inheritance, and every rule a particular trait states about itself.
 * <p>
 * The one to read first is
 * {@link #aSatelliteInheritsNothingSoAnUnlitReplacementIsNotItselfAnOptionalLight}, which is
 * {@code TRAIT.007} and is the rule this whole mechanism turns on: traits flow down
 * <em>alternative</em> edges and not down satellite ones. Without it "an {@code unlit} satellite would
 * inherit {@code urbex:light} from the node it replaces, and so be an optional light whose own
 * replacement is an optional light, without termination".
 */
class TraitTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Identifier DAMAGED = Identifier.parse("urbex:damaged");
    private static final Identifier LIGHT = Identifier.parse("urbex:light");
    private static final Identifier ROTATABLE = Identifier.parse("urbex:rotatable");

    // ---- Inheritance ---------------------------------------------------------------------------

    /**
     * {@code TRAIT.007}: a satellite inherits nothing, so the recursion the format would otherwise have
     * terminates at depth one.
     * <p>
     * <b>Written before anything else in this task, and this is why.</b> {@code TRAIT.005} makes an
     * alternative inherit its parent's traits; if a satellite did too, the {@code unlit} node of an
     * {@code urbex:light} would carry {@code urbex:light}, and <em>its</em> {@code unlit} would carry it
     * again. The inheritance is a tree walk, so a rule that let it descend a satellite edge would not
     * loop - it would silently make every replacement an optional light, and a marker would roll a
     * density against a block that rolls a density against a block. The assertion is the negative one
     * {@code MUST NOT} asks for: the satellite exists, it is reachable, and its trait map is empty.
     */
    @Test
    @Rule("TRAIT.007")
    @Rule("MODEL.031")
    void aSatelliteInheritsNothingSoAnUnlitReplacementIsNotItselfAnOptionalLight() {
        ResolvedNode lantern = resolve("""
                { "version": 2, "palette": { "e": { "block": "minecraft:lantern", "traits": {
                    "urbex:light": { "unlit": "minecraft:iron_bars" },
                    "urbex:damaged": { "into": "minecraft:cobweb" } } } } }
                """).palette().get(new Marker('e'));

        ResolvedNode unlit = lantern.traits().get(LIGHT).satellites().get(Light.UNLIT);
        assertEquals("minecraft:iron_bars", block(unlit));
        assertEquals(Map.of(), unlit.traits(),
                "a satellite begins with no traits - not urbex:light, and not urbex:damaged either");

        // And the other satellite likewise, so the rule is about satellites and not about this trait.
        assertEquals(Map.of(), lantern.traits().get(DAMAGED).satellites().get(Damaged.INTO).traits());
    }

    /**
     * {@code TRAIT.005} and {@code TRAIT.006}: an alternative inherits, and a declared trait replaces
     * the inherited one whole.
     * <p>
     * This is {@code 01-traits.md} §2's own fixture read at the resolved form rather than at the
     * document: "in that fixture every choice carries {@code urbex:damaged}, and only the second
     * additionally carries {@code urbex:light}. The {@code unlit} satellite inside it carries neither."
     * All three sentences are asserted, including the third, which is {@code TRAIT.007} arriving one
     * level down.
     */
    @Test
    @Rule("TRAIT.005")
    @Rule("TRAIT.006")
    void everyAlternativeInheritsItsParentsTraitsAndOnlyTheOneThatDeclaresALightHasOne() {
        ResolvedNode marker = resolve("""
                { "version": 2, "palette": { "#": { "kind": "weighted",
                    "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } },
                    "choices": [
                      { "share": 0.6,  "block": "minecraft:stone_bricks" },
                      { "share": 0.05, "block": "minecraft:wall_torch[facing=north]",
                        "traits": { "urbex:light": { "unlit": "minecraft:air" } } },
                      { "rest": true,  "block": "minecraft:cracked_stone_bricks" } ] } } }
                """).palette().get(new Marker('#'));

        List<ResolvedNode.Choice> choices =
                ((ResolvedNode.Source.Weighted) marker.source()).choices();
        for (ResolvedNode.Choice choice : choices) {
            assertTrue(choice.node().traits().containsKey(DAMAGED),
                    () -> block(choice.node()) + " should have inherited urbex:damaged");
            assertTrue(choice.node().traits().get(DAMAGED).provenance().inherited(),
                    "and it should know it inherited it");
        }
        assertEquals(List.of(false, true, false),
                choices.stream().map(choice -> choice.node().traits().containsKey(LIGHT)).toList(),
                "only the choice that declared urbex:light has one");
        assertFalse(choices.get(1).node().traits().get(LIGHT).provenance().inherited());
    }

    /**
     * {@code TRAIT.006} again, at the shape it was written for: a child's trait object replaces the
     * parent's whole, rather than merging field by field.
     * <p>
     * The parent's {@code unlit} is a lantern and the child restates only {@code urbex:light} with no
     * {@code unlit} at all. A deep merge would keep the parent's replacement; a keyed replace gives the
     * child {@code TRAIT.051}'s default, which is air. The rule's {@code > Why} is exactly that: "a
     * keyed replace has one answer to 'what survived'; a deep merge has one answer per field, and the
     * reader has to know the shape of the trait to predict it."
     */
    @Test
    @Rule("TRAIT.006")
    @Rule("TRAIT.055")
    void aDeclaredTraitReplacesTheInheritedOneWholeAndNotFieldByField() {
        ResolvedNode socket = resolve("""
                { "version": 2, "palette": { "T": { "kind": "light_socket",
                    "traits": { "urbex:light": { "unlit": "minecraft:iron_bars" } },
                    "floor": [ { "weight": 1, "block": "minecraft:torch" },
                               { "weight": 1, "block": "minecraft:lantern",
                                 "traits": { "urbex:light": {} } } ] } } }
                """).palette().get(new Marker('T'));

        List<ResolvedNode.Choice> floor =
                ((ResolvedNode.Source.Socket) socket.source()).placements().get(Kind.Placement.FLOOR);
        assertEquals("minecraft:iron_bars",
                block(floor.get(0).node().traits().get(LIGHT).satellites().get(Light.UNLIT)),
                "the candidate that declares nothing inherits the socket's replacement");
        assertEquals("minecraft:air",
                block(floor.get(1).node().traits().get(LIGHT).satellites().get(Light.UNLIT)),
                "the candidate that declares its own takes TRAIT.051's default, not the socket's block");
    }

    // ---- The registry --------------------------------------------------------------------------

    /**
     * {@code TRAIT.003} and {@code TRAIT.091}: an unregistered id is refused, and the message names the
     * namespace when nothing registers it.
     * <p>
     * <b>{@code DIAG.020}'s optional clause is raised here for the first time,</b> and it was found
     * broken one task before this one could raise it - the enum's template had dropped the clause
     * entirely, and both of {@code DiagCatalogueTest}'s word-comparison guards were blind to it because
     * a clause wholly inside a {@code <…>} group contributes no words to compare. So this asserts what
     * the row says rather than only which row it was: {@code urbex:damage} is a misspelling of a trait
     * this Urbex has and gets no namespace clause; {@code create:crushed} names a namespace nothing
     * registers and gets one. Those are the two remedies the row's last sentence offers - "Check the
     * id, or the mod that provides it" - and a message that could not tell them apart would be offering
     * a choice it had not made.
     */
    @Test
    @Rule("TRAIT.003")
    @Rule("TRAIT.091")
    void anUnregisteredTraitIsRefusedAndTheNamespaceClauseSaysWhichKindOfMistakeItWas() {
        String misspelt = refusal("""
                { "version": 2, "palette": { "X": { "block": "minecraft:stone",
                    "traits": { "urbex:damage": {} } } } }
                """);
        assertTrue(Diag.DIAG_020.matches(misspelt), misspelt);
        assertTrue(misspelt.contains("'urbex:damage'"), misspelt);
        assertFalse(misspelt.contains("registers the namespace"),
                "urbex is a namespace this Urbex registers traits in: " + misspelt);

        String foreign = refusal("""
                { "version": 2, "palette": { "X": { "block": "minecraft:stone",
                    "traits": { "create:crushed": {} } } } }
                """);
        assertTrue(Diag.DIAG_020.matches(foreign), foreign);
        assertTrue(foreign.contains("nothing loaded registers the namespace 'create'"), foreign);
    }

    /**
     * {@code TRAIT.090}: every registered trait declares its id, its schema, its block-valued fields and
     * its references - and the declarations are consistent with each other.
     * <p>
     * <b>This is the test that says the 48-name table is retired.</b> {@code TRAIT.022}'s {@code > Why}
     * measures what the table cost: "an addon's importer and its validator each kept a hand-written
     * 48-name table. They drifted, and 35-55% of real references went unchecked in both without either
     * failing." The replacement is only worth anything if the declaration is <em>complete</em> - a
     * block-valued field the type does not name is a satellite nothing resolves, and a reference field
     * it does not name is a reference nothing checks - so this asserts that every declared field is a
     * key of the schema, and that the satellites a value hands back are exactly the block-valued fields.
     */
    @Test
    @Rule("TRAIT.090")
    void everyRegisteredTraitDeclaresItsFieldsAndItsReferencesAndTheDeclarationsAgree() {
        assertEquals(7, Traits.all().size(), "01-traits.md §4 defines seven traits");
        for (TraitType<?> type : Traits.all()) {
            assertTrue(type.id().getNamespace().length() > 0, "TRAIT.002: a trait id is namespaced");
            assertTrue(type.keys().containsAll(type.blockValuedFields()),
                    () -> type.id() + " declares a block-valued field that is not a key of its schema");
            for (TraitType.ReferenceTarget reference : type.references()) {
                assertTrue(type.keys().contains(reference.field()),
                        () -> type.id() + " declares a reference in a field its schema does not have");
            }
            assertSame(type, Traits.of(type.id()).orElseThrow());
        }

        // The declarations, spelled out, so that changing one is a visible diff rather than a silent
        // widening. These four are what a schema generator and an addon importer read.
        assertEquals(Set.of(Damaged.INTO), Damaged.TYPE.blockValuedFields());
        assertEquals(Set.of(Light.UNLIT), Light.TYPE.blockValuedFields());
        assertEquals(Set.of(OptionalTrait.REPLACEMENT), OptionalTrait.TYPE.blockValuedFields());
        assertEquals(Set.of(), Rotatable.TYPE.blockValuedFields());

        assertEquals(List.of(new TraitType.ReferenceTarget(Loot.POOL,
                        CustomRegistries.CONDITIONS_REGISTRY_KEY)), Loot.TYPE.references());
        assertEquals(List.of(new TraitType.ReferenceTarget(Spawner.POOL,
                        CustomRegistries.CONDITIONS_REGISTRY_KEY)), Spawner.TYPE.references());
        assertEquals(List.of(), Damaged.TYPE.references());
    }

    /**
     * {@code TRAIT.022}: the declaration is what reference validation reads, rather than a table beside
     * it.
     * <p>
     * Driven from the declaration rather than from the trait: the id in the field is read through
     * {@link TraitType#referencedBy}, so a trait that declares a reference is checked and one that does
     * not is not. That is the property the hand-written table could not have - it was a second list,
     * and a field missing from it was a field checked nowhere with nothing failing.
     */
    @Test
    @Rule("TRAIT.022")
    @Rule("VER.013")
    void aTraitsReferenceIsCheckedThroughItsOwnDeclaration() {
        Loot.Value value = new Loot.Value(Identifier.parse("urbex:chestloot"));
        assertEquals(Map.of(new TraitType.ReferenceTarget(Loot.POOL,
                        CustomRegistries.CONDITIONS_REGISTRY_KEY),
                List.of(Identifier.parse("urbex:chestloot"))), Loot.TYPE.referenced(value));

        // VER.013: a version 2 palette references the conditions registry exactly as a version 1
        // palette does, and that registry is still version 1. The pool below is a real conditions
        // asset id and the compile accepts it; the one in TRAIT.021's fixture is not and is refused.
        assertTrue(compiles("""
                { "version": 2, "palette": { "C": { "block": "minecraft:chest[facing=north]",
                    "traits": { "urbex:loot": { "pool": "urbex:chestloot" } } } } }
                """, Set.of(Identifier.parse("urbex:chestloot"))));
        assertFalse(compiles("""
                { "version": 2, "palette": { "C": { "block": "minecraft:chest[facing=north]",
                    "traits": { "urbex:loot": { "pool": "urbex:chestloot" } } } } }
                """, Set.of()));
    }

    /**
     * {@code MODEL.004} inside a trait payload - the one level strict-key rejection did not reach.
     * <p>
     * The rule says "at every level of a version 2 palette file", and while a payload was opaque NBT it
     * was not every level: {@code {"urbex:damaged": {"inot": "minecraft:iron_bars"}}} decoded cleanly
     * and the marker was simply never damaged. That is the exact failure {@code MODEL.004}'s
     * {@code > Why} measures one level up - "three shipped palettes wrote {@code damaged} inside
     * {@code blocks[]} elements, where nothing read it, for the lifetime of the pack".
     */
    @Test
    @Rule("MODEL.004")
    void aKeyNoTraitDefinesIsRefusedInsideThePayload() {
        for (String payload : List.of(
                "{ \"inot\": \"minecraft:iron_bars\" }",
                "{ \"into\": \"minecraft:iron_bars\", \"unlit\": \"minecraft:air\" }")) {
            String message = refusal("""
                    { "version": 2, "palette": { "X": { "block": "minecraft:stone_bricks",
                        "traits": { "urbex:damaged": %s } } } }
                    """.formatted(payload));
            assertTrue(Diag.DIAG_003.matches(message), () -> payload + ": " + message);
            assertTrue(message.contains("'urbex:damaged' trait"),
                    () -> payload + " should be reported against the trait: " + message);
        }
    }

    /**
     * {@code REF.022}: an operand on a trait <em>object</em> is refused, and a satellite may carry one.
     * <p>
     * <b>This check existed before {@code VER.016} was deleted,</b> and the order is the whole point.
     * That rule's blanket scan was the only thing enforcing {@code REF.022}; deleting it first would
     * have turned a loud refusal into a silent misreading, where a {@code $ref} on a trait object is
     * neither expanded nor refused.
     * <p>
     * <b>Its own row rather than {@code DIAG.003}.</b> The refusal would fall out of the key check on
     * its own - no trait's declared key set contains an operand - but {@code DIAG.003} says "check the
     * spelling against the schema" and nothing here is misspelt. So the assertion is on the remedy as
     * well as on the row: the message has to name the two things the author can do, or it is a
     * rejection that leaves them searching.
     */
    @Test
    @Rule("REF.022")
    void anOperandOnATraitObjectIsRefusedWithARemedyAndASatelliteMayCarryOne() {
        for (String payload : List.of(
                "{ \"$ref\": \"rubble\" }",
                "{ \"$ref\": \"rubble\", \"$only\": [\"traits\"] }",
                "{ \"into\": \"minecraft:iron_bars\", \"$without\": [\"traits\"] }",
                "{ \"$spread\": \"rubble#/choices\" }")) {
            String message = refusal("""
                    { "version": 2, "$defs": { "rubble": "minecraft:iron_bars" },
                      "palette": { "X": { "block": "minecraft:stone_bricks",
                          "traits": { "urbex:damaged": %s } } } }
                    """.formatted(payload));
            assertTrue(Diag.DIAG_074.matches(message), () -> payload + ": " + message);
            assertTrue(message.contains("'urbex:damaged'"), () -> payload + ": " + message);
            assertFalse(Diag.DIAG_003.matches(message),
                    () -> payload + " must not be reported as a misspelling: " + message);
            assertTrue(message.contains("block-valued field"),
                    () -> "the remedy is the reason this row exists: " + message);
        }

        // A satellite may carry every one of those, because it is a node (TRAIT.009, MODEL.032). That
        // is the half REF.022 does not reach and VER.016 used to refuse.
        assertEquals("minecraft:iron_bars", block(resolve("""
                { "version": 2, "$defs": { "rubble": "minecraft:iron_bars" },
                  "palette": { "X": { "block": "minecraft:stone_bricks", "traits": {
                      "urbex:damaged": { "into": { "$ref": "rubble" } } } } } }
                """).palette().get(new Marker('X')).traits().get(DAMAGED).satellites().get("into")));
    }

    /**
     * {@code TRAIT.010} and {@code TRAIT.011}: the damage mapping is keyed by the marker carrying the
     * trait, not by the block state it resolves to.
     * <p>
     * <b>This is the defect the rule was written from, made unrepresentable.</b> Version 1 "kept one
     * {@code Map<BlockState, BlockState>} per palette, so two markers resolving to the same block shared
     * one mapping and the last compiled won". Both markers below are {@code minecraft:stone_bricks} and
     * they damage into different blocks, which is a palette version 1 could not hold and this one cannot
     * get wrong: the trait is on the entry, and the entry is the marker's.
     */
    @Test
    @Rule("TRAIT.010")
    @Rule("TRAIT.011")
    @Rule("TRAIT.093")
    void twoMarkersOnOneBlockKeepTheirOwnDamagedForms() {
        CompiledV2Palette palette = compile("""
                { "version": 2, "palette": {
                    "X": { "block": "minecraft:stone_bricks",
                           "traits": { "urbex:damaged": { "into": "minecraft:cobweb" } } },
                    "Y": { "block": "minecraft:stone_bricks",
                           "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } } } }
                """, Set.of());

        assertEquals(palette.at('X', 1L, 0, 0, 0).state(), palette.at('Y', 1L, 0, 0, 0).state(),
                "both markers place the same block");
        assertEquals("Block{minecraft:cobweb}", damagedInto(palette, 'X'));
        assertEquals("Block{minecraft:iron_bars}", damagedInto(palette, 'Y'));
    }

    private static String damagedInto(CompiledV2Palette palette, char marker) {
        return palette.at(marker, 1L, 0, 0, 0).traits().get(DAMAGED).orElseThrow()
                .satellite(Damaged.INTO).slot(0).state().getBlock().toString();
    }

    // ---- What each trait refuses ---------------------------------------------------------------

    /**
     * {@code TRAIT.041}: {@code urbex:block_entity} on a block with no block entity is refused, and the
     * message names the block the file wrote.
     * <p>
     * Version 1 accepted this, "scanned the block-entity registry for a type accepting the state, found
     * none, and wrote nothing", so the NBT an author supplied never appeared and nothing said so. The
     * second half of the test is the neighbouring case that must <em>not</em> be refused: a weighted
     * marker where one alternative has a block entity is a real shape, and refusing it would be the
     * over-rejection {@code ACCEPT} exists as a class to prevent.
     */
    @Test
    @Rule("TRAIT.041")
    void blockEntityNbtOnABlockWithNoBlockEntityIsRefusedAndNamesTheBlock() {
        String message = compileRefusal("""
                { "version": 2, "palette": { "X": { "block": "minecraft:stone_bricks",
                    "traits": { "urbex:block_entity": { "nbt": { "Items": [] } } } } } }
                """);
        assertTrue(Diag.DIAG_022.matches(message), message);
        assertTrue(message.contains("minecraft:stone_bricks"),
                "the message names the block as the file wrote it: " + message);

        assertTrue(compiles("""
                { "version": 2, "palette": { "X": { "kind": "weighted",
                    "traits": { "urbex:block_entity": { "nbt": { "Items": [] } } },
                    "choices": [ { "weight": 1, "block": "minecraft:chest[facing=north]" },
                                 { "weight": 1, "block": "minecraft:stone_bricks" } ] } } }
                """, Set.of()), "one alternative with a block entity is enough");
    }

    /**
     * {@code TRAIT.042}: the four keys the loader supplies are dropped, and the drop is reported.
     * <p>
     * <b>The catalogue's second {@code WARN}, and the three assertions that class asks for.</b>
     * {@code README.md} §3.2 says a {@code WARN} rule is proved by "feeding the input, asserting the
     * load succeeds, and asserting the cited {@code DIAG} is recorded at warning level" - and the
     * reason it is a class rather than a {@code MUST} with a diagnostic bolted on is that a test
     * checking only one of those halves passes while the other is broken. All three are here, plus the
     * behaviour the warning is about: the keys really are gone from what the loader would write.
     * <p>
     * <b>What makes it worth having as a warning rather than a refusal or a silence.</b> The four keys
     * cannot be honoured - the loader knows the position and the type and the file does not - so
     * refusing would refuse a pack whose block entities are written correctly. Dropping them without a
     * word is the version 1 behaviour {@code MODEL.004} exists to remove, whose documented symptom was
     * "(no message at all)".
     */
    @Test
    @Rule("TRAIT.042")
    void theFourPositionalKeysAreDroppedAndTheDropIsReported() {
        Diagnostics diagnostics = new Diagnostics();
        Optional<CompiledV2Palette> compiled = CompiledV2Palette.compile(resolve("""
                { "version": 2, "palette": { "C": { "block": "minecraft:chest[facing=north]",
                    "traits": { "urbex:block_entity": { "nbt": {
                        "id": "minecraft:chest", "x": 4, "y": 5, "z": 6,
                        "LootTable": "urbex:chest" } } } } } }
                """), installed(), TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                Diagnostics.DECODING_LOCATION, diagnostics);

        // The load succeeds - a warning refuses nothing (DIAG.904).
        assertTrue(compiled.isPresent(), () -> diagnostics.asError().orElse("?"));
        assertFalse(diagnostics.hasFatal());
        assertTrue(diagnostics.asError().isEmpty(),
                "a warning must not reach the message a decode fails with");

        // And the cited row is recorded, at warning level, naming the keys.
        List<Diagnostics.Entry> warnings = diagnostics.all().stream()
                .filter(entry -> entry.level() == Diagnostics.Level.WARN).toList();
        assertEquals(1, warnings.size(), () -> diagnostics.all().toString());
        assertEquals(Diag.DIAG_026, warnings.get(0).diag());
        assertTrue(Diag.DIAG_026.matches(warnings.get(0).message()), warnings.get(0).message());
        assertTrue(warnings.get(0).message().contains("'id', 'x', 'y', 'z'"),
                warnings.get(0).message());

        // And the behaviour the warning is about: the loader writes none of them.
        CompoundTag written = new CompoundTag();
        written.putString("id", "minecraft:chest");
        written.putInt("x", 4);
        written.putInt("y", 5);
        written.putInt("z", 6);
        written.putString("LootTable", "urbex:chest");
        CompoundTag initial = new BlockEntityNbt.Value(written).initialNbt();
        assertEquals(Set.of("LootTable"), initial.keySet());
        assertEquals(5, written.getIntOr("y", -1), "the value the file wrote is not mutated");

        // A file that writes none of them is not warned about, so the row cannot become noise.
        Diagnostics quiet = new Diagnostics();
        CompiledV2Palette.compile(resolve("""
                { "version": 2, "palette": { "C": { "block": "minecraft:chest[facing=north]",
                    "traits": { "urbex:block_entity": { "nbt": { "LootTable": "urbex:chest" } } } } } }
                """), installed(), TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                Diagnostics.DECODING_LOCATION, quiet);
        assertEquals(List.of(), quiet.all());
    }

    /**
     * {@code TRAIT.043}: where only some of a node's states have a block entity, the load succeeds.
     * <p>
     * The neighbouring acceptance {@code TRAIT.041} needs to be readable at all. A weighted marker of a
     * chest and one decorative block is a real shape, the nbt reaches one of the two, and refusing the
     * marker over the other is the over-rejection {@code ACCEPT} exists as a class to prevent.
     */
    @Test
    @Rule("TRAIT.043")
    void aNodeWhereOnlySomeStatesHaveABlockEntityLoads() {
        assertTrue(compiles("""
                { "version": 2, "palette": { "C": { "kind": "weighted",
                    "traits": { "urbex:block_entity": { "nbt": { "Items": [] } } },
                    "choices": [ { "weight": 1, "block": "minecraft:chest[facing=north]" },
                                 { "weight": 1, "block": "minecraft:stone_bricks" } ] } } }
                """, Set.of()));
    }

    /**
     * {@code TRAIT.052} and {@code TRAIT.053}: a light that can never look different is refused, from
     * either end.
     * <p>
     * And the two cases that must not be refused, which are the reason both checks ask their question of
     * a node that <em>has</em> states: a weighted marker with one emitting alternative is a light, and a
     * marker naming a cross-mod lamp this installation does not have resolves to no state at all -
     * {@code MODEL.042} - so refusing it would refuse a pack that works on the installs that have the
     * mod.
     */
    @Test
    @Rule("TRAIT.052")
    @Rule("TRAIT.053")
    void aLightThatCanNeverLookDifferentIsRefusedFromEitherEnd() {
        String dark = compileRefusal("""
                { "version": 2, "palette": { "L": { "block": "minecraft:stone",
                    "traits": { "urbex:light": {} } } } }
                """);
        assertTrue(Diag.DIAG_023.matches(dark), dark);

        String lit = compileRefusal("""
                { "version": 2, "palette": { "e": { "block": "minecraft:lantern",
                    "traits": { "urbex:light": { "unlit": "minecraft:glowstone" } } } } }
                """);
        assertTrue(Diag.DIAG_024.matches(lit), lit);
        assertTrue(lit.contains("urbex:light.unlit"),
                "LOAD.051: the message names the field it was reached through: " + lit);

        assertTrue(compiles("""
                { "version": 2, "palette": { "L": { "block": "create:no_such_lamp",
                    "traits": { "urbex:light": {} } } } }
                """, Set.of()), "MODEL.042: an absent block is not a block that fails to emit");
    }

    /**
     * {@code TRAIT.052} is evaluated per <b>slot</b>, and reported at the node that declared the trait.
     * <p>
     * A marker declaring {@code urbex:light} over a lantern and a stone block passes any check asked
     * only of the declaring node - one of its states emits - and its stone slot is then exactly what
     * the rule forbids: an optional light that can never look different. {@code LOAD.021} is why the
     * slot is the unit: two alternatives of one marker can differ, so a per-marker answer cannot
     * represent them.
     * <p>
     * <b>The message is asserted as carefully as the refusal,</b> because the two nodes are different
     * and only one of them is a line the author wrote. It names the marker, not the choice; it names
     * the alternative that cannot light; and it does <em>not</em> say "none of the blocks it resolves
     * to emit light", which would be false of a marker whose lantern lights. That is the seventh time
     * this stack has had to check that a diagnostic derived from a value is true of the value.
     * <p>
     * The mixed case is already sayable and {@code TRAIT.005}'s own fixture is how - the trait goes on
     * the choice that lights, not on the list - so the second half asserts that the correct spelling
     * compiles.
     */
    @Test
    @Rule("TRAIT.052")
    @Rule("LOAD.021")
    void aLightDeclaredOverAMixedListIsRefusedForTheSlotThatCannotLight() {
        String message = compileRefusal("""
                { "version": 2, "palette": { "L": { "kind": "weighted",
                    "traits": { "urbex:light": {} },
                    "choices": [ { "weight": 1, "block": "minecraft:lantern" },
                                 { "weight": 1, "block": "minecraft:stone" } ] } } }
                """);
        assertTrue(Diag.DIAG_023.matches(message), message);
        assertTrue(message.contains("the alternative 'minecraft:stone'"),
                () -> "the message names the alternative that cannot light: " + message);
        assertFalse(message.contains("choice 1"),
                () -> "and reports it at the node that declared the trait: " + message);
        assertFalse(message.contains("none of the blocks"),
                () -> "the marker does resolve to a block that lights: " + message);

        // TRAIT.005's own spelling of the same intent: the trait goes on the choice that lights.
        assertTrue(compiles("""
                { "version": 2, "palette": { "L": { "kind": "weighted", "choices": [
                    { "weight": 1, "block": "minecraft:lantern",
                      "traits": { "urbex:light": {} } },
                    { "weight": 1, "block": "minecraft:stone" } ] } } }
                """, Set.of()));
    }

    /**
     * {@code TRAIT.064}: a node carrying both {@code urbex:light} and {@code urbex:optional} is refused.
     * <p>
     * "Two densities would roll against one position, and which replacement is written would depend on
     * which trait was consulted first. {@code TRAIT.092} forbids traits that depend on application
     * order, so the pair has to be refused rather than ordered."
     * <p>
     * Asserted twice, and the second is the one a fixture cannot reach: the pair arriving by inheritance.
     * A node that declares {@code urbex:optional} under a parent that declares {@code urbex:light}
     * carries both by {@code TRAIT.005}, and the position rolls two densities just as squarely as if one
     * file had written them side by side.
     */
    @Test
    @Rule("TRAIT.064")
    @Rule("TRAIT.092")
    void carryingBothLightAndOptionalIsRefusedWhetherWrittenTogetherOrInherited() {
        String together = compileRefusal("""
                { "version": 2, "palette": { "e": { "block": "minecraft:lantern", "traits": {
                    "urbex:light": { "unlit": "minecraft:air" },
                    "urbex:optional": { "density": "stuff" } } } } }
                """);
        assertTrue(Diag.DIAG_025.matches(together), together);

        String inherited = compileRefusal("""
                { "version": 2, "palette": { "e": { "kind": "weighted",
                    "traits": { "urbex:light": { "unlit": "minecraft:air" } },
                    "choices": [ { "weight": 1, "block": "minecraft:lantern",
                        "traits": { "urbex:optional": { "density": "stuff" } } } ] } } }
                """);
        assertTrue(Diag.DIAG_025.matches(inherited), inherited);
    }

    /**
     * {@code TRAIT.071} and {@code TRAIT.072}: absent, a node is rotatable, and {@code false} is
     * meaningful.
     * <p>
     * <b>The version 1 block tag is not read on this path,</b> which is the substance of the rule rather
     * than a note about it. Version 1 answered this from a hand-maintained tag on the world style
     * "holding 16 tag-includes and 27 block ids and excluding nothing"; the default here is <em>on</em>
     * and comes from the trait alone, so a palette compiled with no world style in sight still answers
     * the question. The furnace below is in no tag this test binds and is rotatable anyway.
     */
    @Test
    @Rule("TRAIT.071")
    @Rule("TRAIT.072")
    void rotatableDefaultsToOnAndFalseIsMeaningful() {
        assertTrue(TraitSet.EMPTY.rotatable(), "a node with no traits at all is rotatable");
        assertTrue(TraitSet.of(Map.of()).rotatable());

        CompiledV2Palette compiled = compile("""
                { "version": 2, "palette": {
                    "F": { "block": "minecraft:furnace[facing=north]",
                           "traits": { "urbex:rotatable": false } },
                    "G": { "block": "minecraft:furnace[facing=north]" },
                    "H": { "block": "minecraft:furnace[facing=north]",
                           "traits": { "urbex:rotatable": true } } } }
                """, Set.of());
        assertFalse(compiled.at('F', 1L, 0, 0, 0).traits().rotatable());
        assertTrue(compiled.at('G', 1L, 0, 0, 0).traits().rotatable());
        assertTrue(compiled.at('H', 1L, 0, 0, 0).traits().rotatable());

        // TRAIT.072: the explicit true is kept rather than dropped as redundant, so a reader of the
        // compiled form can tell a decision from a default. G has no trait; H has one that says on.
        assertFalse(compiled.at('G', 1L, 0, 0, 0).traits().has(Rotatable.TYPE));
        assertTrue(compiled.at('H', 1L, 0, 0, 0).traits().has(Rotatable.TYPE));
    }

    /**
     * {@code TRAIT.001}: a trait's value is an object unless its schema defines a scalar shorthand, and
     * {@code urbex:rotatable} is the one that does.
     */
    @Test
    @Rule("TRAIT.001")
    void onlyRotatableHasAScalarShorthandAndItsKeySetIsEmpty() {
        assertEquals(Set.of(), Rotatable.TYPE.keys());
        for (TraitType<?> type : Traits.all()) {
            if (type != Rotatable.TYPE) {
                assertFalse(type.keys().isEmpty(),
                        () -> type.id() + " has no scalar shorthand, so its schema has keys");
            }
        }
    }

    /** The {@code urbex:conditions} key this package builds is the one the mod registers. */
    @Test
    @Rule("TRAIT.020")
    @Rule("TRAIT.030")
    void theConditionsRegistryTheTraitsNameIsTheOneTheModRegisters() {
        assertEquals(CustomRegistries.CONDITIONS_REGISTRY_KEY, TraitContext.conditionsRegistry());
        assertEquals(Loot.TYPE.references().get(0).registry(),
                Spawner.TYPE.references().get(0).registry());
    }

    // ----------------------------------------------------------------------------------------------

    /** A linked palette, for a sibling test that needs stage 3's output rather than the compiled form. */
    static NodeResolver.ResolvedPalette link(String json) {
        return resolve(json);
    }

    /** A compiled palette against a caller-supplied context - a tag epoch, or a set of assets. */
    static CompiledV2Palette compileWith(String json, TraitContext context) {
        Diagnostics diagnostics = new Diagnostics();
        return CompiledV2Palette.compile(resolve(json), installed(), context,
                        Diagnostics.DECODING_LOCATION, diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the palette to compile: " + diagnostics.asError().orElse("?")));
    }

    private static NodeResolver.ResolvedPalette resolve(String json) {
        Diagnostics diagnostics = new Diagnostics();
        return NodeResolver.resolve(decode(json), diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the palette to resolve: " + diagnostics.asError().orElse("?")));
    }

    private static PaletteV2Definition decode(String json) {
        DataResult<PaletteV2Definition> decoded =
                PaletteV2Definition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        return decoded.result().orElseThrow(() -> new AssertionError(
                "expected the palette to decode: " + decoded.error().orElseThrow().message()));
    }

    /** The message decoding or linking {@code json} is refused with. */
    private static String refusal(String json) {
        DataResult<PaletteV2Definition> decoded =
                PaletteV2Definition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        if (decoded.error().isPresent()) {
            return decoded.error().orElseThrow().message();
        }
        Diagnostics diagnostics = new Diagnostics();
        assertTrue(NodeResolver.resolve(decoded.result().orElseThrow(), diagnostics).isEmpty(),
                "expected a refusal, but the palette resolved");
        return diagnostics.asError().orElseThrow();
    }

    static CompiledV2Palette compile(String json, Set<Identifier> conditions) {
        Diagnostics diagnostics = new Diagnostics();
        Optional<CompiledV2Palette> compiled = CompiledV2Palette.compile(resolve(json),
                installed(), TraitContext.withConditions(BuiltInRegistries.BLOCK, conditions),
                Diagnostics.DECODING_LOCATION, diagnostics);
        return compiled.orElseThrow(() -> new AssertionError(
                "expected the palette to compile: " + diagnostics.asError().orElse("?")));
    }

    private static boolean compiles(String json, Set<Identifier> conditions) {
        Diagnostics diagnostics = new Diagnostics();
        CompiledV2Palette.compile(resolve(json), installed(),
                TraitContext.withConditions(BuiltInRegistries.BLOCK, conditions),
                Diagnostics.DECODING_LOCATION, diagnostics);
        return !diagnostics.hasFatal();
    }

    private static String compileRefusal(String json) {
        Diagnostics diagnostics = new Diagnostics();
        Optional<CompiledV2Palette> compiled = CompiledV2Palette.compile(resolve(json), installed(),
                TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                Diagnostics.DECODING_LOCATION, diagnostics);
        assertTrue(compiled.isEmpty(), "expected the compile to refuse the palette");
        return diagnostics.asError().orElseThrow();
    }

    static Exclusion.Presence installed() {
        return Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft"));
    }

    private static String block(ResolvedNode node) {
        return assertInstanceOf(ResolvedNode.Source.Block.class, node.source()).block();
    }

    /** Every node of a resolved palette, for a test that walks one. */
    static List<ResolvedNode> everyNode(ResolvedNode node) {
        List<ResolvedNode> nodes = new ArrayList<>();
        nodes.add(node);
        switch (node.source()) {
            case ResolvedNode.Source.Weighted weighted -> weighted.choices()
                    .forEach(choice -> nodes.addAll(everyNode(choice.node())));
            case ResolvedNode.Source.Socket socket -> socket.placements().values()
                    .forEach(list -> list.forEach(choice -> nodes.addAll(everyNode(choice.node()))));
            default -> {
            }
        }
        assertNotNull(nodes);
        return nodes;
    }
}
