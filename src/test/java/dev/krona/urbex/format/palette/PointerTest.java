package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Outcome;
import dev.krona.urbex.format.Rule;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link Pointer#parse} reads out of the text a file wrote.
 * <p>
 * Parsing is separated from resolving, so these are the rules that need no other asset:
 * {@code REF.040}'s four forms, {@code REF.081}'s textual alias expansion, {@code REF.082}'s built-in,
 * {@code REF.083}'s refusal of an undeclared one, and {@code REF.084}'s domain for a bare name. What a
 * parsed pointer then <em>finds</em> is {@link NodeResolverTest}'s.
 */
class PointerTest {

    private static final String WHERE = "this palette marker 'X'";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- The four forms ------------------------------------------------------------------------

    @Test
    @Rule("REF.040")
    @Rule("REF.011")
    void aNameWithNoColonAndNoFragmentIsADefinitionOfThisFile() {
        assertEquals(new Pointer.Local("rubble", List.of()), parse("rubble"));
    }

    @Test
    @Rule("REF.041")
    @Rule("REF.010")
    void aNameWithAColonAndNoFragmentIsADefinitionsAsset() {
        assertEquals(new Pointer.Registry(Identifier.parse("urbex:rubble")), parse("urbex:rubble"));
    }

    @Test
    @Rule("REF.042")
    void aPointerWithAFragmentIsAnAssetIdAndAJsonPointerIntoIt() {
        assertEquals(new Pointer.Fragment(Pointer.DEFAULT_FRAGMENT_REGISTRY,
                        Identifier.parse("urbex:common"), List.of("$defs", "rubble")),
                parse("urbex:common#/$defs/rubble"));
    }

    /**
     * An asset id may itself contain {@code /}, which is why {@code #} rather than a slash separates
     * the id from the path into it - {@code REF.042}'s own {@code > Why}.
     */
    @Test
    @Rule("REF.042")
    void anAssetIdMayContainASlashBecauseTheFragmentDelimiterIsAHash() {
        assertEquals(new Pointer.Fragment(Pointer.DEFAULT_FRAGMENT_REGISTRY,
                        Identifier.parse("urbex:bricks/standard"), List.of("palette", "X")),
                parse("urbex:bricks/standard#/palette/X"));
    }

    @Test
    @Rule("REF.043")
    void aFragmentPointersRegistryDefaultsToPalettesAndAPrefixOverridesIt() {
        Pointer.Fragment noPrefix =
                assertInstanceOf(Pointer.Fragment.class, parse("urbex:common#/$defs/rubble"));
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "palettes"), noPrefix.registry());

        Pointer.Fragment prefixed =
                assertInstanceOf(Pointer.Fragment.class, parse("definitions/urbex:rubble#/traits"));
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "definitions"), prefixed.registry());
        assertEquals(Identifier.parse("urbex:rubble"), prefixed.asset());
        assertEquals(List.of("traits"), prefixed.path());

        // And the prefix is only a prefix when its slash comes before the colon, or every asset id
        // with a slash in its path would lose its first segment.
        assertEquals(Identifier.parse("urbex:bricks/standard"),
                assertInstanceOf(Pointer.Fragment.class,
                        parse("urbex:bricks/standard#/palette/X")).asset());
    }

    /** {@code REF.061}: {@code $super} may be the base of a fragment. */
    @Test
    @Rule("REF.061")
    void superMayCarryAFragmentAfterIt() {
        assertEquals(new Pointer.Super(List.of()), parse("$super"));
        assertEquals(new Pointer.Super(List.of("choices")), parse("$super#/choices"));
    }

    // ---- Aliases -------------------------------------------------------------------------------

    /**
     * {@code REF.081}: expansion is textual, and nothing is inserted at the join - so an alias may
     * stand for a whole asset id, or for an asset and a fragment.
     * <p>
     * An alias name ends at the first {@code /} or {@code #}, which is what {@code REF.081}'s second
     * {@code > Why} states. So {@code $half} followed by {@code able} is one alias named
     * {@code halfable} rather than the {@code half} prefix and a suffix - the case that {@code > Why}
     * used to claim for aliases ("any prefix of a path") and, since ruling 7 of this task's review, no
     * longer does. Reaching it would need longest-match expansion, and that costs {@code REF.083}: a file
     * declaring {@code mat} and writing {@code $matt} would expand to the {@code mat} prefix followed by
     * a stray {@code t} instead of naming the misspelt import, which is the failure {@code REF.083}
     * exists to prevent.
     */
    @Test
    @Rule("REF.081")
    void anAliasIsSubstitutedTextuallyBeforeThePointerIsParsed() {
        Map<String, String> imports = Map.of(
                "mat", "urbex:common#/$defs",
                "brick", "urbex:bricks_standard#/palette",
                "common", "urbex:common");

        assertEquals(new Pointer.Fragment(Pointer.DEFAULT_FRAGMENT_REGISTRY,
                        Identifier.parse("urbex:common"), List.of("$defs", "Damageable")),
                parse("$mat/Damageable", imports));
        // The marker '$' after a join, from REF.081's own fixture: the alias name ends at the '/'.
        assertEquals(new Pointer.Fragment(Pointer.DEFAULT_FRAGMENT_REGISTRY,
                        Identifier.parse("urbex:bricks_standard"), List.of("palette", "$")),
                parse("$brick/$", imports));
        // An alias standing for a whole asset id, with the fragment written after it - the join is
        // textual, so the '#' the file wrote is the '#' the grammar reads.
        assertEquals(new Pointer.Fragment(Pointer.DEFAULT_FRAGMENT_REGISTRY,
                        Identifier.parse("urbex:common"), List.of("palette", "X")),
                parse("$common#/palette/X", imports));
    }

    /**
     * {@code REF.082}: {@code $super} is built in to every file and may not be declared in
     * {@code $imports}.
     * <p>
     * {@code DIAG.070} means a file declaring one never loads, so this is the second lock rather than
     * the first: even handed an {@code imports} map that declares {@code super}, the built-in wins. A
     * shadowed {@code $super} would change what one file in a pack means by inheritance, which is the
     * one thing {@code REF.063} promises stays true.
     */
    @Test
    @Rule("REF.082")
    void superCannotBeShadowedByAnImport() {
        Map<String, String> shadowing = Map.of("super", "urbex:common#/palette");
        assertEquals(new Pointer.Super(List.of()), parse("$super", shadowing));
        assertEquals(new Pointer.Super(List.of("choices")), parse("$super#/choices", shadowing));
    }

    /**
     * {@code REF.083}: an undeclared alias is refused, and is not read as a local name.
     * <p>
     * The rule's {@code > Why} is what this asserts: treating it as a bare name "would report the
     * failure as a missing definition and never mention the misspelt import". So the assertion is not
     * only that it fails - it is that it fails as {@code DIAG.039}, naming the alias, and not as
     * {@code DIAG.030}, naming a definition.
     */
    @Test
    @Rule("REF.083")
    void anUnknownAliasIsRefusedRatherThanReadAsALocalName() {
        String message = refuse("$mats/Damageable", Map.of("mat", "urbex:common#/$defs"));
        assertTrue(Diag.DIAG_039.matches(message), message);
        assertTrue(message.contains("'$mats'"), message);
        // DIAG.039's row offers the nearest declared alias, and this is the typo it is for.
        assertTrue(message.contains("the closest declared is '$mat'"), message);
    }

    /** {@code REF.084}: no local name can be mistaken for an alias, because it may not begin with one. */
    @Test
    @Rule("REF.084")
    void aBareNameMayNotContainASlashOrBeginWithADollar() {
        String slashed = refuse("mat/Damageable", Map.of());
        assertTrue(Diag.DIAG_034.matches(slashed), slashed);

        String dollared = refuse("$nosuch", Map.of());
        assertTrue(Diag.DIAG_039.matches(dollared), dollared);
        // Nothing is declared, so there is no closest alias to offer and the clause is absent.
        assertFalse(dollared.contains("closest"), dollared);
    }

    /**
     * {@code REF.085}: a diagnostic about an expanded pointer shows both spellings.
     * <p>
     * Both, because either alone leaves the author guessing: the written form does not say where the
     * loader looked, and the expansion does not say which line to edit.
     */
    @Test
    @Rule("REF.085")
    void aDiagnosticAboutAnExpandedPointerShowsTheExpansionAndTheWrittenForm() {
        Pointer pointer = parse("$mat/Damageable", Map.of("mat", "urbex:common#/$defs"));
        String described = Pointer.describe("$mat/Damageable", pointer);
        assertTrue(described.contains("'$mat/Damageable'"), described);
        assertTrue(described.contains("'urbex:common#/$defs/Damageable'"), described);

        // A pointer that was not expanded is quoted once, not twice with itself.
        assertEquals("'rubble'", Pointer.describe("rubble", parse("rubble")));
    }

    /**
     * {@code REF.042}: the fragment is an RFC 6901 JSON Pointer, so it begins with {@code /} and its
     * two escapes are honoured.
     * <p>
     * Nothing in the shipped corpus needs {@code ~1} - a marker is one codepoint and no definition name
     * carries a slash - but splitting a {@code ~1} in half would name a node nobody wrote, silently,
     * which is the failure mode this format exists to remove.
     */
    @Test
    @Rule("REF.042")
    void aFragmentIsAnRfc6901PointerWithItsTwoEscapes() {
        assertEquals(List.of("a/b", "c~d"),
                assertInstanceOf(Pointer.Fragment.class,
                        parse("urbex:common#/a~1b/c~0d")).path());
        assertEquals("urbex:common#/a~1b/c~0d", parse("urbex:common#/a~1b/c~0d").expanded());

        String missingSlash = refuse("urbex:common#$defs", Map.of());
        assertTrue(Diag.DIAG_034.matches(missingSlash), missingSlash);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static Pointer parse(String written) {
        return parse(written, Map.of());
    }

    private static Pointer parse(String written, Map<String, String> imports) {
        Outcome<Pointer> parsed = Pointer.parse(written, imports, WHERE);
        return parsed.result().orElseThrow(() -> new AssertionError(
                written + " should parse, got " + parsed));
    }

    /** The message a pointer that must not parse is refused with. */
    private static String refuse(String written, Map<String, String> imports) {
        Outcome<Pointer> parsed = Pointer.parse(written, imports, WHERE);
        assertTrue(parsed.result().isEmpty(), () -> written + " should not parse");
        return ((Outcome.Failed<Pointer>) parsed).message();
    }
}
