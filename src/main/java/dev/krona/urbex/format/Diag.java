package dev.krona.urbex.format;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The diagnostic catalogue of {@code docs/format/palette/08-errors.md} §4, as code.
 * <p>
 * One constant per catalogue row, carrying that row's message with its {@code <…>} placeholders
 * turned into {@code %s}. The catalogue is the normative text and this enum is a copy of it, so the
 * copy is drift-guarded rather than trusted: {@code DiagCatalogueTest} fails if the two sets of
 * identifiers differ (the promise {@code DIAG.910} makes about identifier permanence) and if a
 * template uses a word its catalogue row does not. That is the same shape of check
 * {@code PresetSchemaTest} already runs for {@code docs/schema/preset.schema.json} - the reason it
 * exists is measured, and is the whole of {@code docs/format/README.md} §1: version 1's claims each
 * lived in exactly one place, so nothing could disagree with them out loud.
 * <p>
 * <b>Why the templates are hand-written rather than parsed out of the document.</b> Eight rows carry
 * alternative clauses ({@code <a / b / c>}) and five carry a clause that appears only sometimes, and
 * a parser that turned those into format strings would have to understand English brackets. The
 * direction that is mechanical is the one this class takes: hand-write the message, and check every
 * word of it against the row.
 * <p>
 * <b>The leading placeholder is the location, not just the asset.</b> {@code 08-errors.md} §2 gives
 * the shape {@code <asset> [marker '<m>'] [via <chain>]: …}, where the bracketed parts appear only
 * when they apply. Rather than give each template three leading placeholders and pass empty strings
 * for the absent ones, every template here begins with one placeholder holding whichever of the
 * three apply, assembled by the caller. That is what makes a template's remaining literal text
 * <em>always</em> present in a produced message, which is what {@link #matches(String)} relies on.
 */
public enum Diag {

    // ---- File and shape (001-019) ---------------------------------------------------------------

    /** {@code MODEL.002}: args are the location and the version found. */
    DIAG_001("%s: declares version %s, which this Urbex does not know."
            + " Write \"version\": 2."),

    /**
     * {@code VER.018}: the argument is the location.
     *
     * <p>Separate from {@code DIAG.001} because the two cases read differently to the author. A file
     * declaring version 9 wrote a number this Urbex does not know; a file declaring version 1, or
     * declaring nothing, wrote a format this Urbex used to have and removed. Only the second has a
     * conversion as its remedy, and {@code DIAG.001} naming "version 1" for a file that declared no
     * version at all would be a message about a key the author never wrote.
     */
    DIAG_066("%s: is written in palette format version 1, which Urbex no longer loads."
            + " Convert it with the 'convertPalettes' task, which rewrites a version 1 pack as"
            + " version 2, or write \"version\": 2 and the version 2 keys by hand."),

    /** {@code MERGE.007}: the argument is the location. */
    DIAG_002("%s: declares no 'palette', and neither does anything it extends."
            + " Add one, or extend a palette that has one."),

    /** {@code MODEL.004}: args are the location, the offending key, and the context it appeared in. */
    DIAG_003("%s: '%s' is not a key of %s."
            + " Version 2 palettes refuse keys they do not define; check the spelling against the schema."),

    /** {@code MODEL.012}: args are the location and the kind found. */
    DIAG_004("%s: kind %s does not exist."
            + " The kinds are block, weighted, tag, alias and light_socket."),

    /** {@code MODEL.033}: args are the location and the trait field the satellite stands in. */
    DIAG_005("%s: a %s replacement cannot be a light_socket, because it is written at a position"
            + " already chosen. Name a block, or a weighted list of them."),

    /** {@code MODEL.043}: args are the location and the block expression. */
    DIAG_006("%s: %s names a block this game has, with a property it does not have."
            + " Installing a mod will not fix this; correct the property expression."),

    /** {@code MODEL.045}: the argument is the location. */
    DIAG_007("%s: a weighted node declares no choices. Give it at least one."),

    /** {@code MODEL.053}: args are the location and the tag. */
    DIAG_008("%s: tag %s contains no blocks."
            + " An empty tag has nothing to place; name a tag with members, or name blocks directly."),

    /** {@code MODEL.062}: args are the location and the alias target. */
    DIAG_009("%s: aliases '%s', which no palette in this context defines."
            + " Alias a marker that exists, or give this one a block of its own."),

    /** {@code MODEL.072}: the argument is the location. */
    DIAG_010("%s: a light_socket declares no candidate in floor, wall, ceiling or free."
            + " Give it at least one."),

    /**
     * {@code MODEL.081}: args are the location and one clause saying what is missing and whose fault it
     * is.
     * <p>
     * <b>One slot for the whole clause, not two.</b> The clause has to name a different subject in
     * different cases, and getting that wrong prints something false about a real file. The row's five
     * alternatives are the five cases: the definition declares only traits (the partial-definition case
     * {@code REF.020} is about); the definition declares a kind and not its key; the definition declares
     * neither; a {@code $only} or {@code $without} dropped the key the definition <em>did</em> declare;
     * and the marker itself declared the kind. The two that were missing until this round are the last
     * two, and both were reported as the definition's fault - {@code $without: ["block"]} against a
     * definition that declares {@code block} said "declares no 'block'", which is false of it.
     * <p>
     * The fixed wording "declares only traits" also made this class raise {@code DIAG.007} for a weighted
     * node with no {@code choices}, to avoid printing something false. A diagnostic that forces the code
     * to name the wrong rule is worse than a slightly vaguer one.
     * <p>
     * <b>The remedy is a slot for the same reason the clause is.</b> {@code MODEL.081} gained a fourth
     * position - a block-valued trait field - and "give this <em>marker</em> a 'block', 'choices', 'tag'
     * or 'alias' as well" is false there: the marker has a block, and the thing that needs one is the
     * satellite. That is the identical defect this enum's {@link #DIAG_023} note describes, one
     * amendment later and in the amendment that introduced it, which is why the rule is stated here
     * rather than only observed: <b>amending a rule means amending every clause of its row that the
     * amendment can reach</b>, including the sentence that looked like boilerplate.
     */
    DIAG_011("%s: resolves to no block. %s; %s."),

    /** {@code MODEL.051}: args are the location and the tag as written. */
    DIAG_012("%s: tag %s has no leading '#'."
            + " A block tag reference is written '#namespace:path'."),

    // ---- Traits (020-029) ----------------------------------------------------------------------

    /**
     * {@code TRAIT.003}: args are the location, the trait id, and the row's
     * {@code <, and nothing loaded registers the namespace '<ns>'>} clause.
     * <p>
     * The third slot was missing until this task, exactly as {@link #DIAG_045}'s was: an optional clause
     * contributes no words for {@code DiagCatalogueTest}'s two word-subset guards to compare, so a
     * template could drop one and stay green. Nothing raises this row yet - {@code TRAIT.003} needs the
     * trait registry - so unlike {@code DIAG.045} it never reached a user; it is fixed here rather than
     * left for Task 6 to inherit, because the guard that now catches the class has to find the catalogue
     * clean.
     */
    DIAG_020("%s: no trait %s is registered%s. Check the id, or the mod that provides it."),

    /**
     * {@code TRAIT.021}, {@code TRAIT.031}: args are the location, the trait, the field, the id and
     * the registry that does not hold it.
     * <p>
     * <b>The field and the registry are slots because {@code TRAIT.090} makes them values.</b> A trait
     * declares "which of its fields are references into which registry", so a row that hardcoded
     * {@code pool} and {@code conditions} was true only of the two traits this repository happens to
     * ship. The first addon trait naming, say, a {@code styles} asset from a field called {@code table}
     * would have been refused with a message naming neither - which is the shape {@code DIAG.030} had
     * before Task 3 gave it the operand, and the shape this catalogue keeps producing wherever a
     * mechanism is generic and its message is not.
     */
    DIAG_021("%s: %s.%s names %s, which is not a loaded %s asset."
            + " Generation dereferences it, so it must exist."),

    /** {@code TRAIT.041}: args are the location and the block. */
    DIAG_022("%s: %s has no block entity, so its 'urbex:block_entity' nbt would never be written."
            + " Remove the trait, or name a block that has one."),

    /**
     * {@code TRAIT.052}: args are the location of the node that <em>declared</em> the trait, and which
     * of the row's two clauses applies.
     * <p>
     * <b>Two clauses because the rule is evaluated per slot and reported per declaration.</b>
     * {@code TRAIT.052} refuses {@code urbex:light} on any node carrying it that cannot light, which by
     * {@code TRAIT.005} includes an alternative that inherited it - and {@code LOAD.021} is why: traits
     * are a property of the slot, so a marker declaring a light over a lantern and a stone block has a
     * stone slot that is exactly what the rule forbids. But the location stays the declaring node's,
     * because the author wrote that line and did not write the slot; the second clause is what makes
     * the sentence true there, by naming the alternative rather than claiming the marker never lights.
     */
    DIAG_023("%s: declares 'urbex:light', but %s."
            + " It would roll a density and place the same dark block either way."),

    /** {@code TRAIT.053}: the argument is the location. */
    DIAG_024("%s: an unlit replacement emits light."
            + " Name a block that does not, so the marker looks different when the light is off."),

    /** {@code TRAIT.064}: the argument is the location. */
    DIAG_025("%s: carries both 'urbex:light' and 'urbex:optional'."
            + " A marker rolls one density; 'urbex:light' is the lighting one."),

    /**
     * {@code TRAIT.042}: args are the location and the loader-supplied keys the file wrote.
     * <p>
     * <b>The catalogue's second warning, and the first one that is about a silence rather than a
     * shape.</b> {@code DIAG.046} reports a structural change a condition made; this reports something
     * the loader <em>discards</em>. The four keys cannot be honoured - the loader knows the position
     * and the type and the file does not - so refusing would refuse a pack whose block entities are
     * written correctly, and dropping them without a word is the version 1 behaviour {@code MODEL.004}
     * exists to remove. {@code DIAG.904} allows exactly one level between those two.
     */
    DIAG_026("%s: 'urbex:block_entity' nbt declares %s, which the loader supplies and this drops."
            + " Remove them; the position and the type are not the file's to choose."),

    // ---- References and merging (030-039) ------------------------------------------------------

    /**
     * {@code REF.013}: args are the location, the operand, the name, and the tier searched.
     * <p>
     * The operand is a slot because a pointer is "the value of {@code $ref}, of {@code $spread}, and of
     * any future operand that has to say which node" ({@code 03-pointers.md} §1), and all of them fail
     * in this tier the same way. The template named {@code $ref} outright until this task, so a
     * {@code $spread} whose bare name named nothing was reported as a {@code $ref}.
     */
    DIAG_030("%s: %s %s names no %s definition."
            + " A name with a colon is looked up in the definitions registry;"
            + " one without, in this file's $defs and those it inherits."),

    /** {@code MERGE.009}: args are the owner, the id it named, and the owner again. */
    DIAG_031("%s: the inline palette declares 'extends' %s, but an inline palette is not a registry"
            + " entry and nothing can resolve that. Use 'refpalette', or put 'extends' on %s itself."),

    /** {@code REF.032}: args are the location and the cycle, in declaration order. */
    DIAG_032("%s: reference cycle %s. One of these must not reference the next."),

    /** {@code REF.015}: args are the location and the unqualified name. */
    DIAG_033("%s: a definitions asset references %s, which has no namespace."
            + " A registry definition has no file to resolve local names against; qualify it."),

    /** {@code REF.045}: args are the location, the pointer, which half failed, and the explanation. */
    DIAG_034("%s: pointer %s names %s. %s"),

    /** {@code REF.053}: the argument is the location. */
    DIAG_035("%s: carries both '$only' and '$without'."
            + " Name the keys to keep, or the keys to drop, not both."),

    /** {@code REF.062}: args are the location and what it inherits nothing from. */
    DIAG_036("%s: '$super' names what this entry inherits, and %s."
            + " Remove '$super', or extend something that defines it."),

    /** {@code REF.071}: args are the location, the pointer, and what it named instead of a list. */
    DIAG_037("%s: '$spread' %s names a %s, not a list."
            + " A spread element can only be replaced by list elements."),

    /**
     * {@code MERGE.010}, {@code VER.005}: args are the asset, its version, the id it extends, and
     * that asset's version.
     */
    DIAG_038("%s (version %s) extends %s (version %s)."
            + " An extends chain cannot cross format versions; convert one of them."),

    /**
     * {@code REF.083}: args are the location, the undeclared alias, and the nearest declared one.
     * <p>
     * The third slot is the row's {@code <, and the closest declared is '$<near>'>} clause, which went
     * unimplemented while nothing raised this row - Task 3 is where an alias is first parsed, and where
     * the clause becomes reachable. It is the same hint {@link #DIAG_072} carries for a misspelt filter
     * key, computed the same way, and it is empty when no declared alias is close.
     */
    DIAG_039("%s: '$%s' is not an import of this file%s."
            + " Declare it in '$imports', or write the pointer in full."),

    // ---- Weights (040-049) ---------------------------------------------------------------------

    /**
     * {@code WEIGHT.002}: args are the location, the choice index, and what was found - one of the
     * three alternatives the catalogue row lists, since a choice states its size in one of three
     * spellings and each is wrong in its own way.
     */
    DIAG_040("%s choice %s: %s. Each choice states its size exactly once."),

    /** {@code WEIGHT.013}: args are the location and what was found. */
    DIAG_041("%s: %s. 'rest' is the single choice that takes what the shares leave;"
            + " weighted choices already divide that between them."),

    /**
     * Retired in draft; see {@code 08-errors.md}'s tombstone. It is here, holding the row's own
     * {@code —}, for one reason: {@code DIAG.910} makes an identifier permanent, and the only way to
     * prove this number is not silently reused is for the enum and the catalogue to be provably the
     * same set of identifiers. Nothing raises it, and nothing may: the over-allocation case that
     * survives is {@link #DIAG_045}.
     */
    DIAG_042("—"),

    /**
     * {@code WEIGHT.024}, {@code WEIGHT.032}: args are the location, the when count, the absent count.
     * <p>
     * "Alternative" rather than "choice", because the row is raised about a {@code light_socket} as well
     * as a {@code weighted} node and a socket has candidates, not choices - and because a one-element
     * list reading "every choice was excluded - 3 by absent blocks" said something the reader had to
     * reconcile. The counts are of causes across the whole subtree, which is what makes three of them
     * possible under one choice.
     */
    DIAG_043("%s: every alternative was excluded - %s by 'when', %s by absent blocks."
            + " The marker would generate as air; give it an alternative that always applies."),

    /**
     * {@code WEIGHT.063}: args are the location and the flattened alternative count.
     * <p>
     * <b>The remedy was false and is rewritten.</b> It read "Reduce the list, or nest the rare choices
     * under one weighted choice", which worked while {@code WEIGHT.063} counted one list's elements and
     * does nothing now that it counts the flattened tree: 150 leaves are 150 leaves however they are
     * grouped, so the message told an author to do the thing they had already done. {@code DIAG.900}
     * requires a remedy, and a remedy that cannot work is not one. What does work is having fewer
     * alternatives, or giving some of them a marker of their own - 128 is a budget per node, not per
     * palette.
     */
    DIAG_044("%s: %s alternatives, flattened, exceed the 128 slots available,"
            + " so one of them could not be given a slot."
            + " Reduce the number of alternatives, or move some of them to a marker of their own."),

    /**
     * {@code WEIGHT.014}, {@code WEIGHT.019}: args are the location, the total, the clause naming where
     * the total came from, and which of the two requirements was broken - a list with a {@code weight}
     * or {@code rest} must leave something for it, and a list without one must total exactly 1.
     * <p>
     * <b>The third slot is the row's {@code < — <a> written here and <b> spread from '<id>'>} clause,</b>
     * and it went unimplemented while nothing could expand a {@code $spread}. {@code WEIGHT.019} is the
     * rule that needs it: a spread that brings a list's shares to 1 is refused "naming the incoming and
     * inherited totals separately", because - in that rule's own words - "Shares total 1.15" sends an
     * author looking through their own four lines for a number that came from a file they did not write.
     * It is empty whenever every share in the sum was written where the diagnostic points.
     */
    DIAG_045("%s: shares total %s%s. %s."),

    /**
     * {@code WEIGHT.026}: args are the location of the removed node, its kind, the when count and the
     * absent count.
     * <p>
     * <b>The only warning in the catalogue, and the reason it is one.</b> {@code WEIGHT.024}'s cascade
     * is the single structural change a load-time condition can make that would otherwise leave no
     * trace: dropping a choice shows up in what generates, and dropping the node the choices were
     * nested under makes a pack look, from the inside, like a pack that never had them. Refusing would
     * refuse a pack that is working as written ({@code WEIGHT.030}), so by {@code DIAG.904} this is the
     * other level - it does not refuse the world, and it does not reach {@link Diagnostics#asError()}.
     */
    DIAG_046("%s: a nested %s lost every alternative - %s by 'when', %s by absent blocks - and was"
            + " itself removed from the list it is a choice of. The choices around it divide its"
            + " share; remove it, or name content this installation has."),

    // ---- Characters (050-059) ------------------------------------------------------------------

    /** {@code CHAR.003}: args are the location, the marker as written, and its codepoint count. */
    DIAG_050("%s: marker %s is %s codepoints. A marker is exactly one."),

    /** {@code CHAR.004}: args are the location and the codepoint, in hex. */
    DIAG_051("%s: marker U+%s is not an assigned Unicode codepoint."
            + " It was most likely produced by an exporter walking codepoints in sequence; reassign it."),

    /**
     * {@code CHAR.005}: args are the location, the codepoint in hex, the category, and why that
     * category cannot be a marker.
     */
    DIAG_052("%s: marker U+%s is %s, which cannot be a marker. %s"),

    /** {@code CHAR.011}: args are the part, the slice, the row, the count found, the declared width. */
    DIAG_053("%s slice %s row %s: %s codepoints, but the part declares a width of %s."
            + " Correct the row, or the declared width, so the two agree."),

    /** {@code CHAR.022}: args are the command, the markers needed, and the alphabet size. */
    DIAG_054("%s: this part needs %s markers and the assignment alphabet holds %s."
            + " Split the part, or reuse markers already in its palette."),

    // ---- Versioning (060-069) ------------------------------------------------------------------

    /** {@code VER.010}: args are the location, the retired key, and the key that replaced it. */
    DIAG_060("%s: '%s' was retired in version 2. Write '%s' instead."),

    /** {@code VER.011}: args are the location, the deleted key, and what to do instead. */
    DIAG_061("%s: '%s' was deleted, not renamed. %s"),

    /**
     * Retired in draft with {@code VER.014}; see {@code 08-errors.md}'s tombstone.
     * <p>
     * It refused an inline palette declaring a version other than 1, while nothing could read one.
     * {@code MERGE.011} now reads an inline palette by the version it declares, so there is nothing left
     * to refuse: a version 2 one decodes through the same dispatcher a registered palette does. The
     * constant stays, holding the row's own {@code —}, for the reason {@link #DIAG_042} records - a
     * number is permanent by {@code DIAG.910}, and the only way to prove it is not silently reused is
     * for this enum and the catalogue to be provably the same set of identifiers. Nothing raises it, and
     * nothing may.
     */
    DIAG_062("—"),

    /**
     * Retired in draft with {@code VER.015}; see {@code 08-errors.md}'s tombstone.
     * <p>
     * It refused a version 2 palette where it was compiled, registered or inline, while the loader could
     * decode one and not use it. Version 2 palettes compile now, so {@code VER.002} - "{@code \"version\":
     * 2} selects this specification in full" - is simply true and there is nothing left to refuse. The
     * constant stays, holding the row's own {@code —}, for the reason {@link #DIAG_042} records: a number
     * is permanent by {@code DIAG.910}, and the only way to prove it is not silently reused is for this
     * enum and the catalogue to be provably the same set of identifiers. Nothing raises it, and nothing
     * may.
     * <p>
     * What survives beside it is {@link #DIAG_065} - the inline palettes along one owner's chain
     * disagreeing about their version - which is {@code VER.007}, a permanent rule with a different
     * message and a different remedy, and which was unenforceable while this row existed.
     */
    DIAG_063("—"),

    /**
     * {@code VER.007}: args are the owner, the version some link declares, and the version the leaf
     * declares.
     * <p>
     * New with {@code VER.015}'s retirement rather than before it, and that is the whole of why
     * {@code VER.007} carried a "Why it is stated and not yet checked" block: an inline version 2
     * palette was refused outright, so no mixed stack survived long enough to be merged and nothing
     * could observe the rule being broken. Once version 2 compiles, a part whose ancestor writes a
     * version 1 inline palette and which writes a version 2 one is a thing an author can express, and
     * this is what it gets.
     */
    DIAG_065("%s: the inline palettes along this asset's 'extends' chain declare format version %s and"
            + " version %s. An owner's inline palettes are merged by marker, so they are all of one"
            + " format version; convert one of them."),

    /**
     * Retired in draft with {@code VER.016}; see {@code 08-errors.md}'s tombstone.
     * <p>
     * It refused any of the four operands written inside a {@code traits} value, while a trait payload
     * was opaque and nothing could say which of a trait's fields hold nodes. {@code TRAIT.090} makes a
     * registered trait declare exactly that, so {@code TRAIT.009}'s satellites resolve like any other
     * node and there is nothing left to refuse. The half that was never transitional - {@code REF.022},
     * an operand on the trait <em>object</em> - is {@link #DIAG_074}, which is looked for before the key
     * check so that {@code DIAG.003} never fires for it. The constant stays, holding the row's own
     * {@code —}, for the reason {@link #DIAG_042}
     * records: a number is permanent by {@code DIAG.910}, and the only way to prove it is not silently
     * reused is for this enum and the catalogue to be provably the same set of identifiers. Nothing
     * raises it, and nothing may.
     */
    DIAG_064("—"),

    // ---- References and merging, continued (070-079; 030-039 is full) ---------------------------

    /** {@code REF.082}: the argument is the location. */
    DIAG_070("%s: '$imports' declares 'super', which is a built-in alias naming what this entry"
            + " inherits and cannot be redeclared. Remove it, or choose another alias name."),

    /** {@code REF.019}: args are the location and what the document declared instead of version 2. */
    DIAG_071("%s: a definitions asset %s. The definitions registry is new in palette format version 2"
            + " and has no version 1 form, so an absent 'version' is not one;"
            + " write \"version\": 2."),

    /**
     * {@code REF.055}: args are the location, the operand, the offending key, and the nearest real key
     * if there is one.
     */
    DIAG_072("%s: %s names %s, which is not a key of a node%s."
            + " The keys a filter may name are kind, block, choices, tag, of, floor, wall, ceiling,"
            + " free and traits."),

    /**
     * {@code REF.056}: args are the location and the operand written without a {@code $ref}.
     * <p>
     * A row of its own rather than a sixth alternative on {@link #DIAG_072}, although both refuse a
     * filter that cannot do anything. A shared row would have had to put the whole message in
     * placeholders - the two halves have different remedies, and {@code 08-errors.md} §2 requires the
     * remedy - which would leave {@link #matches(String)} almost no literal text to identify the row by,
     * and {@code DiagCatalogueTest} proves no two templates match each other's messages.
     */
    DIAG_073("%s: %s is written with no '$ref', so there is nothing for it to filter."
            + " Remove it, or name the definition whose keys it selects."),

    /**
     * {@code REF.022}: args are the location, the trait id, and the operand written on its object.
     * <p>
     * <b>A row of its own rather than {@link #DIAG_003}, and the remedy is the whole reason.</b> The
     * refusal is reachable from {@code MODEL.004} alone, because no trait's declared key set
     * ({@code TRAIT.090}) contains an operand - but {@code DIAG.003} says "check the spelling against
     * the schema", and nothing here is misspelt. The author wanted to share something, and there are
     * two ways to do that: put the reference on a block-valued field of the trait, which is a node and
     * may carry one ({@code TRAIT.009}), or share the whole trait with a partial definition
     * ({@code REF.020}). A rejection whose remedy names neither costs the author the search this
     * specification exists to remove.
     * <p>
     * It is the narrow half of the retired {@code VER.016}, and it was written before that rule was
     * deleted - the wide scan was the only thing enforcing {@code REF.022}, so deleting it first would
     * have reopened a silent misreading rather than a loud one.
     */
    DIAG_074("%s: trait %s carries %s, and a trait's value is data rather than a node."
            + " Put the reference on a block-valued field of the trait,"
            + " or share the whole trait with a partial definition.");

    private static final Map<String, Diag> BY_ID = byId();

    private final String template;

    Diag(String template) {
        this.template = template;
    }

    /** The catalogue identifier, e.g. {@code "DIAG.003"}. */
    public String id() {
        return name().replace('_', '.');
    }

    /** This row's message, with its {@code %s} placeholders left as they are. */
    public String template() {
        return template;
    }

    /**
     * The message, with the placeholders filled in order.
     * <p>
     * Fails loudly on the wrong number of arguments rather than producing
     * {@code MissingFormatArgumentException} out of a decode: a diagnostic that cannot be formatted
     * is a bug in the caller, and it must not be reported as a malformed datapack.
     */
    public String message(Object... args) {
        int placeholders = placeholderCount(template);
        if (args.length != placeholders) {
            throw new IllegalArgumentException(id() + " takes " + placeholders + " arguments, got "
                    + args.length + ": " + Arrays.toString(args));
        }
        return String.format(Locale.ROOT, template, args);
    }

    /**
     * Whether {@code produced} is a message of this diagnostic.
     * <p>
     * {@code docs/format/README.md} §4.1 requires a {@code reject=} fixture's message to be checked
     * "against the catalogue entry […] not against a literal in the test", so this is how a test
     * decides <em>which</em> diagnostic a decode produced. It looks for this template's literal
     * segments, in order, in the produced message - the placeholders match whatever stands in their
     * place, and text before and after is ignored, because DFU concatenates and prefixes the errors
     * of nested codecs and a decode may report several diagnostics at once ({@code DIAG.903}).
     * <p>
     * Not a whole-string equality and not an id embedded in the message: §2 fixes the message shape
     * and it has no room for an identifier, so the prose <em>is</em> the identification.
     */
    public boolean matches(String produced) {
        String haystack = normalise(produced);
        int at = 0;
        for (String segment : literalSegments(template)) {
            String needle = normalise(segment);
            if (needle.isEmpty()) {
                continue;
            }
            int found = haystack.indexOf(needle, at);
            if (found < 0) {
                return false;
            }
            at = found + needle.length();
        }
        return true;
    }

    /** The catalogue row with this identifier. */
    public static Diag of(String id) {
        Diag diag = BY_ID.get(id);
        if (diag == null) {
            throw new IllegalArgumentException("no diagnostic " + id + " in the catalogue");
        }
        return diag;
    }

    /**
     * A message's fixed text, with the quoting the catalogue spells in Markdown and this enum spells
     * in single quotes removed, and runs of whitespace collapsed.
     * <p>
     * The two spellings are the reason this exists. A catalogue row writes a key as
     * <code>`&lt;key&gt;`</code>, in backticks, because it is Markdown; a message printed to a log
     * writes it as {@code 'key'}, because the surrounding text is prose and the house style quotes
     * keys that way ({@code RetiredKeys} already does). Comparing either against the other without
     * dropping both quotings compares typography, not wording.
     */
    static String normalise(String text) {
        return text.replace("`", "").replace("*", "").replace("'", "")
                .replaceAll("\\s+", " ").trim();
    }

    /** A template split on its placeholders: the text a produced message must contain. */
    static List<String> literalSegments(String template) {
        return List.of(template.split("%s", -1));
    }

    private static int placeholderCount(String template) {
        return literalSegments(template).size() - 1;
    }

    private static Map<String, Diag> byId() {
        Map<String, Diag> byId = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (Diag diag : values()) {
            if (byId.put(diag.id(), diag) != null) {
                duplicates.add(diag.id());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("duplicate diagnostic identifiers: " + duplicates);
        }
        return Map.copyOf(byId);
    }
}
