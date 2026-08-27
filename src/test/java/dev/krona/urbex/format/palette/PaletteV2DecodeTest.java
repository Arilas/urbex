package dev.krona.urbex.format.palette;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What decoding a version 2 palette produces, and what it refuses.
 * <p>
 * The specification's own fixtures are run by {@code FormatFixtureTest}; this covers the rules a
 * fixture cannot state. Two kinds live here. A {@code MUST} rule about the shape of the decoded value -
 * that a string node's block is its string and its {@code $ref} is empty, that the four placement lists
 * arrive keyed by placement - is a claim about what came out, and a fixture only says whether the
 * document was accepted. A {@code MUST NOT} rule is the negative of a {@code MUST}: it is proved by
 * exercising the situation and asserting the behaviour does not occur, which is not an outcome the
 * fixture grammar can declare either.
 */
class PaletteV2DecodeTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- The file ------------------------------------------------------------------------------

    @Test
    @Rule("MODEL.001")
    void aPaletteFileAcceptsTheFiveKeysAndNoOthers() {
        assertEquals(Set.of("version", "extends", "$imports", "$defs", "palette"),
                PaletteV2Definition.FILE_LEVEL_KEYS);

        PaletteV2Definition decoded = v2("""
                { "version": 2, "extends": "urbex:common",
                  "$imports": { "mat": "urbex:common#/$defs" },
                  "$defs": { "rubble": "minecraft:cobblestone" },
                  "palette": { "X": "minecraft:stone_bricks" } }
                """);
        assertEquals(Optional.of(Identifier.parse("urbex:common")), decoded.extendsId());
        assertEquals(Map.of("mat", "urbex:common#/$defs"), decoded.imports());
        assertEquals(Set.of("rubble"), decoded.defs().keySet());
        assertEquals(Set.of(new Marker('X')), decoded.palette().orElseThrow().keySet());

        assertRefused(Diag.DIAG_003, """
                { "version": 2, "paletteEntries": {}, "palette": { "X": "minecraft:stone" } }
                """);
    }

    /**
     * A decoded palette encodes back to a document that decodes to the same thing, and to one whose top
     * level holds only the keys {@code MODEL.001} names.
     * <p>
     * Worth its own test because the encode direction has a live caller - {@code /exportpart} writes a
     * palette out through this codec's version 1 sibling - and because two things here could only be
     * wrong on the way out. {@code version} is written back explicitly, without which the document would
     * read as version 1 by {@code VER.001}; and a bare-block node is written as a string again, which is
     * the {@code MODEL.020} shorthand surviving the round trip rather than being expanded.
     */
    @Test
    @Rule("MODEL.001")
    @Rule("MODEL.020")
    void aDecodedPaletteEncodesBackToADocumentThatDecodesToTheSameThing() {
        String document = """
                { "version": 2, "extends": "urbex:common",
                  "$imports": { "mat": "urbex:common#/$defs" },
                  "$defs": { "rubble": { "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } } },
                  "palette": {
                    "X": "minecraft:stone_bricks",
                    "#": { "kind": "weighted", "choices": [
                            { "share": 0.1, "block": "create:andesite_casing", "when": { "mod": "create" } },
                            { "rest": true, "block": "minecraft:stone_bricks" } ] },
                    "T": { "kind": "light_socket", "floor": [
                            { "weight": 2, "block": "minecraft:torch" } ] },
                    "@": { "kind": "alias", "of": "X" },
                    "p": { "kind": "tag", "tag": "#minecraft:planks" },
                    "r": { "$ref": "rubble", "block": "minecraft:deepslate_bricks" } } }
                """;
        PaletteV2Definition decoded = v2(document);

        JsonElement encoded = PaletteAssetDefinition.CODEC.encodeStart(JsonOps.INSTANCE, decoded)
                .getOrThrow();
        assertEquals(PaletteV2Definition.FILE_LEVEL_KEYS,
                encoded.getAsJsonObject().keySet());
        assertEquals(2, encoded.getAsJsonObject().get("version").getAsInt(),
                "without this the document reads back as version 1");
        assertEquals("minecraft:stone_bricks",
                encoded.getAsJsonObject().getAsJsonObject("palette").get("X").getAsString(),
                "a bare block goes back out as a string");

        assertEquals(decoded, v2(encoded.toString()));
    }

    /**
     * {@code MODEL.003}: {@code palette} maps each marker to a node, and is required somewhere in the
     * {@code extends} chain rather than in every file. The half of that a decode can hold is the
     * absence: a file that declares none is decoded, and the chain decides.
     */
    @Test
    @Rule("MODEL.003")
    void aFileMayDeclareNoPaletteBecauseTheChainIsWhatRequiresOne() {
        PaletteV2Definition decoded = v2("""
                { "version": 2, "extends": "urbex:common",
                  "$defs": { "wall": "minecraft:stone_bricks" } }
                """);
        assertTrue(decoded.palette().isEmpty());
    }

    /**
     * {@code MODEL.005}: markers are unique by construction, because {@code palette} is an object.
     * Version 1 held entries in a list, each carrying its own {@code char}, so a file declaring a
     * marker twice was accepted and the last declaration won silently.
     */
    @Test
    @Rule("MODEL.005")
    void aMarkerCannotBeDeclaredTwiceBecausePaletteIsAnObject() {
        PaletteV2Definition decoded = v2("""
                { "version": 2, "palette": { "X": "minecraft:stone", "X": "minecraft:andesite" } }
                """);
        assertEquals(1, decoded.palette().orElseThrow().size(),
                "one marker, whatever the file wrote twice - there is no second entry to lose");
    }

    // ---- The node ------------------------------------------------------------------------------

    /**
     * {@code MODEL.010}: one node type in every position. Four of the five are decodable here; the
     * fifth, a block-valued trait field, is a node once the trait registry exists (Task 6).
     */
    @Test
    @Rule("MODEL.010")
    void oneNodeTypeStandsInEveryPositionThatHoldsANode() {
        PaletteV2Definition decoded = v2("""
                { "version": 2,
                  "$defs": { "d": { "kind": "weighted", "choices": [ { "rest": true, "block": "minecraft:stone" } ] } },
                  "palette": {
                    "T": { "kind": "light_socket",
                           "floor": [ { "weight": 1, "block": "minecraft:torch" } ] },
                    "#": { "kind": "weighted",
                           "choices": [ { "rest": true, "block": "minecraft:stone_bricks" } ] } } }
                """);
        RawNode definition = decoded.defs().get("d");
        RawNode marker = decoded.palette().orElseThrow().get(new Marker('#'));
        RawNode socket = decoded.palette().orElseThrow().get(new Marker('T'));
        assertEquals(Optional.of(Kind.WEIGHTED), definition.kind());
        assertEquals(Optional.of("minecraft:stone_bricks"),
                marker.choices().orElseThrow().getFirst().node().block());
        assertEquals(Optional.of("minecraft:torch"),
                socket.placements().get(Kind.Placement.FLOOR).getFirst().node().block());
    }

    /** {@code MODEL.013}: a kind-specific key of one kind is refused on another, naming the key. */
    @Test
    @Rule("MODEL.013")
    void aKindSpecificKeyIsRefusedOnAnotherKindNamingTheKey() {
        for (String json : List.of(
                """
                { "version": 2, "palette": { "X": { "kind": "block", "block": "minecraft:stone",
                                                    "of": "Y" } } }
                """,
                """
                { "version": 2, "palette": { "X": { "kind": "tag", "tag": "#minecraft:planks",
                                                    "block": "minecraft:stone" } } }
                """,
                """
                { "version": 2, "palette": { "X": { "kind": "alias", "of": "Y",
                                                    "floor": [] } } }
                """)) {
            assertRefused(Diag.DIAG_003, json);
        }
    }

    /**
     * {@code MODEL.051}: a {@code tag} names a block tag with a leading {@code #}, and one without is
     * refused with {@code DIAG.012} rather than decoding cleanly and matching nothing.
     * <p>
     * The namespace half of the rule is deliberately not checked at decode: an unqualified tag is a
     * reference, and every other unqualified reference in this format is reported where references
     * resolve. The {@code #} is checkable from the text alone.
     */
    @Test
    @Rule("MODEL.051")
    void aTagWithoutItsLeadingHashIsRefused() {
        assertRefusedNaming(Diag.DIAG_012, "minecraft:planks", """
                { "version": 2, "palette": { "p": { "kind": "tag", "tag": "minecraft:planks" } } }
                """);
        assertAccepted("""
                { "version": 2, "palette": { "p": { "kind": "tag", "tag": "#minecraft:planks" } } }
                """);
    }

    /**
     * {@code MODEL.020} and {@code MODEL.021} together: a string is the node with kind {@code block} and
     * that string as its block, and it is <em>never</em> a reference.
     * <p>
     * The negative half is the one worth a test. A definition name and a block id are both strings and
     * both may carry a namespace, so if a bare string could be either, resolving
     * {@code "urbex:rubble"} would depend on which registry answered first.
     */
    @Test
    @Rule("MODEL.020")
    @Rule("MODEL.021")
    void aStringNodeIsABlockAndNeverAReferenceHoweverMuchItLooksLikeOne() {
        RawNode node = marker(v2("""
                { "version": 2, "palette": { "X": "urbex:rubble" } }
                """), 'X');
        assertEquals(Optional.of(Kind.BLOCK), node.kind());
        assertEquals(Optional.of("urbex:rubble"), node.block());
        assertTrue(node.ref().isEmpty(), "a string never populates $ref");
        assertTrue(node.isPlainBlock());
    }

    /** {@code MODEL.071}: the four placement lists, keyed by where they place. */
    @Test
    @Rule("MODEL.071")
    void aLightSocketDeclaresItsCandidatesInFourNamedLists() {
        RawNode socket = marker(v2("""
                { "version": 2, "palette": { "T": { "kind": "light_socket",
                    "floor":   [ { "weight": 1, "block": "minecraft:torch" } ],
                    "wall":    [ { "weight": 1, "block": "minecraft:wall_torch[facing=north]" } ],
                    "ceiling": [ { "weight": 1, "block": "minecraft:lantern[hanging=true]" } ],
                    "free":    [ { "weight": 1, "block": "minecraft:lantern" } ] } } }
                """), 'T');
        assertEquals(Set.of(Kind.Placement.FLOOR, Kind.Placement.WALL, Kind.Placement.CEILING,
                Kind.Placement.FREE), socket.placements().keySet());
    }

    /** {@code MODEL.076}: a placement list is a list like any other - it takes {@code when} and {@code $spread}. */
    @Test
    @Rule("MODEL.076")
    void aPlacementListTakesWhenAndSpreadLikeAnyOtherList() {
        RawNode socket = marker(v2("""
                { "version": 2, "extends": "urbex:common", "palette": { "T": { "kind": "light_socket",
                    "floor": [ { "$spread": "$super#/floor" },
                               { "weight": 1, "block": "create:andesite_casing",
                                 "when": { "mod": "create" } } ] } } }
                """), 'T');
        List<RawChoice> floor = socket.placements().get(Kind.Placement.FLOOR);
        assertEquals(Optional.of("$super#/floor"), floor.get(0).node().spread());
        assertEquals(Optional.of(new When(Optional.of("create"), Optional.empty())),
                floor.get(1).when());
    }

    // ---- Operands ------------------------------------------------------------------------------

    /**
     * {@code REF.050}: the {@code $} set is closed, and the file-level keys and the node operands are
     * not accepted in each other's positions.
     */
    @Test
    @Rule("REF.050")
    void theDollarKeysAreAClosedSetAndNeitherSetIsAcceptedInTheOthersPosition() {
        assertRefused(Diag.DIAG_003, """
                { "version": 2, "palette": { "X": { "block": "minecraft:stone", "$super": "X" } } }
                """);
        assertRefused(Diag.DIAG_003, """
                { "version": 2, "palette": { "X": { "block": "minecraft:stone", "$defs": {} } } }
                """);
        assertRefused(Diag.DIAG_003, """
                { "version": 2, "$ref": "rubble", "palette": { "X": "minecraft:stone" } }
                """);
    }

    /** {@code REF.072}: a {@code $spread} element carries no other key - to change what it spreads, point elsewhere. */
    @Test
    @Rule("REF.072")
    void aSpreadElementCarriesNoOtherKey() {
        assertRefused(Diag.DIAG_003, """
                { "version": 2, "extends": "urbex:common", "palette": { "#": { "kind": "weighted",
                    "choices": [ { "$spread": "$super#/choices", "block": "minecraft:stone" } ] } } }
                """);
    }

    // ---- Sizes ---------------------------------------------------------------------------------

    /**
     * {@code WEIGHT.001}, {@code WEIGHT.003} and {@code WEIGHT.004}: one spelling of size per choice,
     * read the same way in a {@code choices} list and in a placement list.
     */
    @Test
    @Rule("WEIGHT.001")
    @Rule("WEIGHT.003")
    @Rule("WEIGHT.004")
    void aChoiceStatesItsSizeOnceInOneOfThreeSpellings() {
        PaletteV2Definition decoded = v2("""
                { "version": 2, "palette": {
                    "#": { "kind": "weighted", "choices": [
                            { "share": 0.25, "block": "minecraft:cobweb" },
                            { "rest": true, "block": "minecraft:stone_bricks" } ] },
                    "T": { "kind": "light_socket", "floor": [
                            { "weight": 3, "block": "minecraft:torch" } ] } } }
                """);
        List<RawChoice> choices = marker(decoded, '#').choices().orElseThrow();
        assertEquals(Optional.of(new Size.Share(0.25)), choices.get(0).size());
        assertEquals(Optional.of(new Size.Rest()), choices.get(1).size());
        assertEquals(Optional.of(new Size.Weight(3)),
                marker(decoded, 'T').placements().get(Kind.Placement.FLOOR).getFirst().size());

        assertRefused(Diag.DIAG_040, """
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 2, "share": 0.5, "block": "minecraft:stone" } ] } } }
                """);
        assertRefused(Diag.DIAG_040, """
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "block": "minecraft:stone" } ] } } }
                """);
        assertRefused(Diag.DIAG_040, """
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "share": 1.5, "block": "minecraft:stone" } ] } } }
                """);
    }

    /**
     * {@code WEIGHT.004}: "{@code weight} is a positive integer" - and a fraction is refused rather than
     * truncated to one.
     * <p>
     * {@code "weight": 2.7} used to decode to a weight of 2. Only fractions below 1 were caught, because
     * truncating those reaches 0 and 0 was already refused, so the guard looked complete and was not.
     * A datapack meaning something other than what it says is the failure this format exists to remove,
     * and {@code Versioned} already refuses a fractional {@code version} for the same reason, so the
     * codebase disagreed with itself. Eight tasks build on {@link Size}.
     * <p>
     * A whole number spelled with a decimal point is accepted: JSON has one number type.
     */
    @Test
    @Rule("WEIGHT.004")
    void aFractionalWeightIsRefusedRatherThanTruncatedToAnInteger() {
        for (String weight : new String[]{"2.7", "0.4", "1.0001", "-3", "0"}) {
            assertRefusedNaming(Diag.DIAG_040, "positive integer",
                    "{ \"version\": 2, \"palette\": { \"#\": { \"kind\": \"weighted\", \"choices\": ["
                            + " { \"weight\": " + weight + ", \"block\": \"minecraft:stone\" } ] } } }");
        }

        RawChoice choice = marker(v2("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 3.0, "block": "minecraft:stone" } ] } } }
                """), '#').choices().orElseThrow().getFirst();
        assertEquals(Optional.of(new Size.Weight(3)), choice.size());
    }

    /**
     * A socket whose one candidate is malformed is told about the candidate, and is <em>not</em> told it
     * declared no candidate.
     * <p>
     * {@code MODEL.072} is "a light_socket declaring no candidate in any of the four lists is refused",
     * and the document below declares one. It used to draw both {@code DIAG.040} (true - the candidate
     * states no size) and {@code DIAG.010} (false - the candidate is right there), because
     * {@code RecordCodecBuilder} assembles a record from the fields that survived and validation then
     * described that assembly rather than the file. Telling an author to add something they have already
     * written is worse than saying nothing.
     */
    @Test
    @Rule("MODEL.072")
    void aSocketWithAMalformedCandidateIsNotToldItHasNoCandidate() {
        DataResult<PaletteAssetDefinition> result = decode("""
                { "version": 2, "palette": { "T": { "kind": "light_socket",
                    "floor": [ { "block": "minecraft:torch" } ] } } }
                """);
        String message = result.error().orElseThrow().message();
        assertTrue(Diag.DIAG_040.matches(message), message);
        assertFalse(Diag.DIAG_010.matches(message),
                () -> "the socket declares a floor candidate, so this is false: " + message);
    }

    /** {@code REF.082}: and the refusal is the catalogue's, not a literal in this test. */
    @Test
    @Rule("REF.082")
    void superCannotBeDeclaredAsAnImport() {
        assertRefused(Diag.DIAG_070, """
                { "version": 2, "$imports": { "super": "urbex:common#/palette" },
                  "palette": { "X": "minecraft:stone" } }
                """);
        assertAccepted("""
                { "version": 2, "$imports": { "mat": "urbex:common#/$defs" },
                  "palette": { "X": "minecraft:stone" } }
                """);
    }

    /** {@code MODEL.046}: a {@code choices} entry is a node carrying a size, and optionally {@code when}. */
    @Test
    @Rule("MODEL.046")
    void aChoiceIsANodeWithASizeBesideIt() {
        RawChoice choice = marker(v2("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 1, "kind": "tag", "tag": "#minecraft:planks",
                      "traits": { "urbex:rotatable": false }, "when": { "pack": "urbex" } } ] } } }
                """), '#').choices().orElseThrow().getFirst();
        assertEquals(Optional.of(Kind.TAG), choice.node().kind());
        assertEquals(Optional.of("#minecraft:planks"), choice.node().tag());
        assertEquals(Set.of(Identifier.parse("urbex:rotatable")), choice.node().traits().keySet());
        assertEquals(Optional.of(new Size.Weight(1)), choice.size());
        assertEquals(Optional.of(new When(Optional.empty(), Optional.of("urbex"))), choice.when());
    }

    /**
     * {@code WEIGHT.005}: every size rule is evaluated on the list as it stands after {@code $spread}
     * expansion and after exclusion, never on the choices as written.
     * <p>
     * Both documents below would be refused if the rule were read on the written list: the first totals
     * 1.2 in shares, the second declares {@code rest} beside a {@code weight}. Neither is refused,
     * because in the first the shares that matter arrive from a spread and in the second a choice may
     * leave the list before anything is apportioned.
     */
    @Test
    @Rule("WEIGHT.005")
    void aListCarryingASpreadOrAWhenIsNotSizeCheckedAsWritten() {
        assertAccepted("""
                { "version": 2, "extends": "urbex:common", "palette": { "#": { "$ref": "$super",
                    "choices": [ { "$spread": "$super#/choices" },
                                 { "share": 0.6, "block": "minecraft:cobweb" },
                                 { "share": 0.6, "block": "minecraft:stone" } ] } } }
                """);
        assertAccepted("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 3, "block": "create:andesite_casing", "when": { "mod": "create" } },
                    { "rest": true, "block": "minecraft:stone_bricks" } ] } } }
                """);
    }

    // ---- Traits --------------------------------------------------------------------------------

    /**
     * {@code TRAIT.001} and {@code TRAIT.002}: {@code traits} maps a namespaced trait id to that trait's
     * payload. An unqualified id is refused here rather than reaching the trait registry and being
     * reported as unregistered under a namespace the author never wrote.
     */
    @Test
    @Rule("TRAIT.001")
    @Rule("TRAIT.002")
    void aTraitIdIsNamespacedAndAnUnqualifiedOneIsRefused() {
        RawNode node = marker(v2("""
                { "version": 2, "palette": { "X": { "block": "minecraft:stone_bricks",
                    "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" },
                                "urbex:rotatable": false } } } }
                """), 'X');
        assertEquals(Set.of(Identifier.parse("urbex:damaged"), Identifier.parse("urbex:rotatable")),
                node.traits().keySet());
        assertEquals(Identifier.parse("urbex:damaged"),
                node.traits().get(Identifier.parse("urbex:damaged")).id());

        DataResult<PaletteAssetDefinition> result = decode("""
                { "version": 2, "palette": { "X": { "block": "minecraft:stone",
                    "traits": { "damaged": { "into": "minecraft:iron_bars" } } } } }
                """);
        assertTrue(result.error().isPresent(), () -> "expected a refusal, got " + result);
        assertTrue(result.error().orElseThrow().message().contains("must name their namespace"),
                result.error().orElseThrow().message());
    }

    // ---- The character domain ------------------------------------------------------------------

    /**
     * {@code CHAR.001} and {@code CHAR.002}: a marker is one codepoint, counted in codepoints rather
     * than in UTF-16 code units. Version 1 read markers with {@code charAt(0)} after checking
     * {@code length() == 1}, so an astral codepoint was refused for being "2 characters long".
     */
    @Test
    @Rule("CHAR.001")
    @Rule("CHAR.002")
    void aMarkerOutsideTheBasicMultilingualPlaneIsOneMarkerAndNotTwoCharacters() {
        int grinningFace = 0x1F600;
        assertEquals(2, new String(Character.toChars(grinningFace)).length(),
                "two UTF-16 code units, which is the trap");
        Map<Marker, RawNode> palette = v2(paletteWithMarkers(grinningFace)).palette().orElseThrow();
        assertEquals(Set.of(new Marker(grinningFace)), palette.keySet());
    }

    /**
     * {@code CHAR.005}: a marker in general category {@code Mn}, {@code Mc}, {@code Me}, {@code Cc},
     * {@code Cf}, {@code Cs} or {@code Co} is refused.
     * <p>
     * One codepoint per excluded category, where the rule's own fixture demonstrates one. The fixture
     * used to write U+037A GREEK YPOGEGRAMMENI, which is category {@code Lm} and is <em>not</em>
     * excluded - it is a spacing modifier letter, so it is assigned, visible, and occupies one column of
     * a slice, which is everything {@code CHAR.004} and {@code CHAR.005} ask of a marker. It now writes
     * U+0301, a combining mark, which is the case the rule's {@code > Why} is about.
     */
    @Test
    @Rule("CHAR.005")
    void aCombiningMarkOrControlOrFormatOrPrivateUseCodepointIsRefused() {
        for (int codepoint : new int[]{0x0300, 0x0001, 0x00AD, 0xE000}) {
            assertRefused(Diag.DIAG_052, paletteWithMarkers(codepoint));
        }
    }

    /** {@code CHAR.006}: U+0020 SPACE is a valid marker - it is category {@code Zs}, which CHAR.005 does not exclude. */
    @Test
    @Rule("CHAR.006")
    void spaceIsAValidMarkerBecauseEveryShippedPackUsesItForAir() {
        assertEquals(Set.of(new Marker(' ')),
                v2("{ \"version\": 2, \"palette\": { \" \": \"minecraft:air\" } }")
                        .palette().orElseThrow().keySet());
    }

    /**
     * {@code CHAR.007}: a marker is not normalised. U+212B ANGSTROM SIGN and U+00C5 LATIN CAPITAL LETTER
     * A WITH RING ABOVE are the same character after NFC and are two markers here, because normalising
     * would silently merge two markers an author distinguished.
     */
    @Test
    @Rule("CHAR.007")
    void twoMarkersDifferingOnlyByNormalisationStayTwoMarkers() {
        int angstromSign = 0x212B;
        int aWithRingAbove = 0x00C5;
        assertEquals(aWithRingAbove, java.text.Normalizer.normalize(
                        new String(Character.toChars(angstromSign)), java.text.Normalizer.Form.NFC)
                .codePointAt(0), "the two really do normalise together");

        Map<Marker, RawNode> palette =
                v2(paletteWithMarkers(angstromSign, aWithRingAbove)).palette().orElseThrow();
        assertEquals(Set.of(new Marker(angstromSign), new Marker(aWithRingAbove)), palette.keySet());
    }

    // ---- Retired keys --------------------------------------------------------------------------

    /**
     * {@code VER.010}: a version 1 key that version 2 <em>renamed</em> is refused with
     * {@code DIAG.060}, naming the replacement.
     * <p>
     * All three keys §3's table calls renamed, by enumeration, where the rule's fixture demonstrates one.
     * The fixture used to demonstrate {@code char}, which that table lists as <em>deleted</em> - so
     * {@code VER.011} and {@code DIAG.061} govern it, and {@link
     * #aDeletedVersionOneKeyIsRefusedSayingWhatToWriteInstead} is where it belongs. It now demonstrates
     * {@code random}, which {@code weight} replaced.
     */
    @Test
    @Rule("VER.010")
    void aRenamedVersionOneKeyIsRefusedNamingTheKeyThatReplacedIt() {
        assertRefusedNaming(Diag.DIAG_060, "weight", """
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "random": 4, "block": "minecraft:stone" } ] } } }
                """);
        assertRefusedNaming(Diag.DIAG_060, "choices", """
                { "version": 2, "palette": { "#": { "blocks": [] } } }
                """);
        assertRefusedNaming(Diag.DIAG_060, "of", """
                { "version": 2, "palette": { "#": { "frompalette": "X" } } }
                """);
    }

    /**
     * {@code VER.011}: a key version 2 <em>deleted</em> is refused with {@code DIAG.061}, which says what
     * to do instead because there is no key to point at.
     */
    @Test
    @Rule("VER.011")
    void aDeletedVersionOneKeyIsRefusedSayingWhatToWriteInstead() {
        assertRefusedNaming(Diag.DIAG_061, "object key", """
                { "version": 2, "palette": { "X": { "char": "X", "block": "minecraft:stone" } } }
                """);
        assertRefusedNaming(Diag.DIAG_061, "urbex:damaged", """
                { "version": 2, "palette": { "X": { "block": "minecraft:stone",
                                                    "damaged": "minecraft:cobblestone" } } }
                """);
        assertRefusedNaming(Diag.DIAG_061, "urbex:block_entity", """
                { "version": 2, "palette": { "X": { "block": "minecraft:chest",
                                                    "tag": { "Items": [] } } } }
                """);
        assertRefusedNaming(Diag.DIAG_061, "light_socket", """
                { "version": 2, "palette": { "L": { "block": "minecraft:lantern",
                                                    "lightSource": true } } }
                """);
    }

    /**
     * {@code VER.012}: a retired key is never silently accepted as an alias for its replacement, and
     * never silently ignored.
     * <p>
     * Every key in §3's table, in one sweep, so the claim is made by enumeration rather than by the four
     * examples above. {@code tag} is the interesting one: it is a live version 2 key on a {@code tag}
     * node and a retired one anywhere else, and both readings have to hold.
     */
    @Test
    @Rule("VER.012")
    void noRetiredKeyIsSilentlyAcceptedOrSilentlyIgnored() {
        for (String key : RetiredV2Keys.TABLE.keySet()) {
            String json = "{ \"version\": 2, \"palette\": { \"X\": { \"block\": \"minecraft:stone\","
                    + " \"" + key + "\": true } } }";
            DataResult<PaletteAssetDefinition> result = decode(json);
            assertTrue(result.error().isPresent(),
                    () -> "'" + key + "' is retired and was accepted: " + result);
            String message = result.error().orElseThrow().message();
            assertTrue(message.contains("'" + key + "'"),
                    () -> "the refusal of '" + key + "' does not name it: " + message);
        }

        assertAccepted("""
                { "version": 2, "palette": { "p": { "kind": "tag", "tag": "#minecraft:planks" } } }
                """);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static DataResult<PaletteAssetDefinition> decode(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        return PaletteAssetDefinition.CODEC.parse(JsonOps.INSTANCE, parsed);
    }

    /**
     * A palette declaring one marker per codepoint, written with JSON {@code \\u} escapes.
     * <p>
     * Escaped rather than pasted into the source: half of these codepoints are invisible or unassigned,
     * and a control character sitting literally inside a string literal is the kind of thing an editor,
     * a merge tool or a source encoding silently changes - which would make the test about the toolchain
     * instead of about {@code CHAR.005}.
     */
    private static String paletteWithMarkers(int... codepoints) {
        StringBuilder json = new StringBuilder("{ \"version\": 2, \"palette\": {");
        for (int index = 0; index < codepoints.length; index++) {
            json.append(index == 0 ? " " : ", ").append('"');
            for (char unit : Character.toChars(codepoints[index])) {
                json.append(String.format("\\u%04X", (int) unit));
            }
            json.append("\": \"minecraft:stone\"");
        }
        return json.append(" } }").toString();
    }

    private static PaletteV2Definition v2(String json) {
        DataResult<PaletteAssetDefinition> result = decode(json);
        assertTrue(result.result().isPresent(), () -> "expected a clean decode, got " + result);
        PaletteAssetDefinition decoded = result.result().orElseThrow();
        assertEquals(2, decoded.formatVersion());
        return (PaletteV2Definition) decoded;
    }

    private static RawNode marker(PaletteV2Definition definition, char marker) {
        return definition.palette().orElseThrow().get(new Marker(marker));
    }

    private static void assertAccepted(String json) {
        DataResult<PaletteAssetDefinition> result = decode(json);
        assertTrue(result.result().isPresent(), () -> "expected a clean decode, got " + result);
    }

    private static void assertRefused(Diag expected, String json) {
        DataResult<PaletteAssetDefinition> result = decode(json);
        assertTrue(result.error().isPresent(), () -> "expected " + expected.id() + ", got " + result);
        String message = result.error().orElseThrow().message();
        assertTrue(expected.matches(message),
                () -> "expected " + expected.id() + " but got: " + message);
        assertFalse(message.isBlank());
    }

    private static void assertRefusedNaming(Diag expected, String mustMention, String json) {
        DataResult<PaletteAssetDefinition> result = decode(json);
        assertTrue(result.error().isPresent(), () -> "expected " + expected.id() + ", got " + result);
        String message = result.error().orElseThrow().message();
        assertTrue(expected.matches(message),
                () -> "expected " + expected.id() + " but got: " + message);
        assertTrue(message.contains(mustMention),
                () -> "the message does not name '" + mustMention + "': " + message);
    }
}
