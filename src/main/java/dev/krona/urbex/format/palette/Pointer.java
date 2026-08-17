package dev.krona.urbex.format.palette;

import com.mojang.serialization.DataResult;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.format.Diag;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A parsed pointer: what a {@code $ref} or a {@code $spread} names ({@code REF.040}).
 * <p>
 * {@code REF.040} gives a pointer three written forms - a bare name, an asset with a fragment, and an
 * alias - and the third is not a form of its own by the time it is parsed: {@code REF.081} makes alias
 * expansion <em>textual</em> and says it happens "before the pointer is parsed", so {@link #parse}
 * substitutes and then reads what is left. The four cases below are therefore what remains after
 * substitution, and they are distinguished by punctuation alone, which is {@code REF.040}'s whole
 * design: a leading {@code $} means an alias, a {@code #} means a path into an asset, a colon means the
 * registry, nothing means this file.
 * <p>
 * <b>Why parsing is separated from resolving.</b> Parsing needs the file's {@code $imports} and nothing
 * else; resolving needs every decoded document in the pack ({@code LOAD.025}). Splitting them is what
 * lets {@code REF.083} - a misspelt alias - be reported as a misspelt alias rather than as a missing
 * definition, which is exactly the confusion that rule's {@code > Why} names.
 */
public sealed interface Pointer permits Pointer.Local, Pointer.Registry, Pointer.Fragment,
        Pointer.Super {

    /**
     * The registry an asset id in a fragment pointer names when no {@code registry/} prefix is written
     * ({@code REF.043}).
     */
    Identifier DEFAULT_FRAGMENT_REGISTRY =
            Identifier.fromNamespaceAndPath(Urbex.MODID, "palettes");

    /** The registry a pointer with a colon and no fragment names ({@code REF.041}). */
    Identifier DEFINITIONS_REGISTRY =
            Identifier.fromNamespaceAndPath(Urbex.MODID, "definitions");

    /** The built-in alias {@code REF.082} reserves, which no {@code $imports} entry may shadow. */
    String SUPER = "super";

    /**
     * A definition in this file's {@code $defs}, or one inherited through {@code extends}
     * ({@code REF.011}).
     * <p>
     * <b>{@code path} is the case {@code REF.042} does not cover.</b> It is empty for the bare name
     * {@code REF.040}'s table describes. It is non-empty for {@code d#/block} - the pointer
     * {@code REF.071}'s own fixture writes - where {@code d} is a {@code $defs} name rather than an
     * asset id. See the task report: {@code REF.042} says the part before {@code #} is an asset id, so
     * that fixture is asking for a form no rule states, and the fixture is the only evidence of intent.
     * It is implemented because {@code REF.054} leaves no other way to reach inside a key of a local
     * definition ("to reach inside a key, point at it with a fragment") and because an inline palette
     * has no asset id to write.
     *
     * @param name the definition name, which by {@code REF.084} contains no {@code /} and no leading
     *             {@code $}
     * @param path the RFC 6901 path into that definition, empty unless the pointer carried a {@code #}
     */
    record Local(String name, List<String> path) implements Pointer {

        public Local(String name, List<String> path) {
            this.name = name;
            this.path = List.copyOf(path);
        }

        @Override
        public String expanded() {
            return name + fragmentSuffix(path);
        }
    }

    /**
     * An asset in the {@code definitions} registry, named by a pointer with a colon and no fragment
     * ({@code REF.041}, {@code REF.010}).
     *
     * @param asset the definitions asset, always namespaced
     */
    record Registry(Identifier asset) implements Pointer {

        @Override
        public String expanded() {
            return asset.toString();
        }
    }

    /**
     * A path into another asset's decoded document ({@code REF.042}).
     *
     * @param registry the registry the asset lives in - {@link #DEFAULT_FRAGMENT_REGISTRY} unless the
     *                 pointer wrote a {@code registry/} prefix ({@code REF.043})
     * @param asset    the asset id, which may itself contain {@code /}; that is why {@code #} rather
     *                 than a slash separates the id from the path into it
     * @param path     the RFC 6901 pointer, already unescaped
     */
    record Fragment(Identifier registry, Identifier asset, List<String> path) implements Pointer {

        public Fragment(Identifier registry, Identifier asset, List<String> path) {
            this.registry = registry;
            this.asset = asset;
            this.path = List.copyOf(path);
        }

        @Override
        public String expanded() {
            String prefix = registry.equals(DEFAULT_FRAGMENT_REGISTRY)
                    ? ""
                    : registry.getPath() + "/";
            return prefix + asset + fragmentSuffix(path);
        }
    }

    /**
     * What this entry inherited from its {@code extends} chain ({@code REF.060}).
     * <p>
     * A form of its own rather than an alias with a textual prefix, although {@code REF.082} calls it a
     * built-in alias: there is no text to substitute. {@code REF.063} says {@code $super} names the
     * <em>inherited value</em> and not a named ancestor, so what it stands for is a node the resolver
     * holds, not an id a pointer could spell.
     *
     * @param path the RFC 6901 pointer after it, as in {@code $super#/choices} ({@code REF.061})
     */
    record Super(List<String> path) implements Pointer {

        public Super(List<String> path) {
            this.path = List.copyOf(path);
        }

        @Override
        public String expanded() {
            return "$" + SUPER + fragmentSuffix(path);
        }
    }

    /**
     * This pointer in its expanded, canonical spelling.
     * <p>
     * {@code REF.085} requires a diagnostic about an alias to show "the expanded form as well as the
     * written one", and this is the expanded half. Rendered from the parsed parts rather than kept as
     * the substituted text, so that it is the same string for two spellings of one pointer - which is
     * what makes it usable as an identity in the reference graph.
     */
    String expanded();

    /**
     * Parses a pointer, expanding a leading alias textually first ({@code REF.081}).
     * <p>
     * <b>{@code imports} maps an alias name to its prefix as text,</b> not to a parsed pointer. That is
     * {@code REF.081}: "{@code $<name>} is replaced by its prefix before the pointer is parsed. Nothing
     * is inserted at the join", and its {@code > Why} spells out why the prefix has no grammar of its
     * own - "It can stand for an asset id, an asset and a fragment, or any prefix of a path". A prefix
     * that is only a prefix of a path (`urbex:common#/$defs/Damage`) is not a pointer, so a map of
     * parsed pointers could not hold one, and a substitution of parsed pointers could not join them.
     * The brief for this task specified {@code Map<String, Pointer>}; this is the deviation, and
     * {@link PaletteV2Definition#imports()} already holds the strings.
     * <p>
     * {@code $super} is recognised <em>before</em> {@code imports} is consulted, which is how
     * {@code REF.082} makes it unshadowable in this method as well as at decode: an entry named
     * {@code super} can lose the race here even though {@code DIAG.070} means it never reaches here.
     *
     * @param written  the pointer exactly as the file wrote it
     * @param imports  the file's {@code $imports}, alias name to prefix ({@code REF.080})
     * @param location what stands in a diagnostic's leading {@code <asset>} slot; see
     *                 {@code 08-errors.md} §2
     */
    static DataResult<Pointer> parse(String written, Map<String, String> imports, String location) {
        if (written.startsWith("$")) {
            String alias = aliasName(written);
            String rest = written.substring(1 + alias.length());
            if (alias.equals(SUPER)) {
                return parseSuper(written, rest, location);
            }
            String prefix = imports.get(alias);
            if (prefix == null) {
                // REF.083. Not a fall back to a local name: reporting this as a missing definition
                // would never mention the misspelt import, which is that rule's own > Why.
                return DataResult.error(() -> Diag.DIAG_039.message(location, alias));
            }
            return parseExpanded(written, prefix + rest, location);
        }
        return parseExpanded(written, written, location);
    }

    /** {@link #parse} with no imports declared - the common case, and every definitions asset. */
    static DataResult<Pointer> parse(String written, String location) {
        return parse(written, Map.of(), location);
    }

    /**
     * The alias name in a {@code $}-prefixed pointer: everything up to the first {@code /} or
     * {@code #}.
     * <p>
     * Those two characters and no others, because they are the two the pointer grammar itself uses as
     * delimiters ({@code REF.042}, {@code REF.043}). {@code $brick/$} - from {@code REF.081}'s own
     * fixture - is the alias {@code brick} and the remainder {@code /$}, so the marker {@code $} being
     * a legal marker character costs nothing here.
     */
    private static String aliasName(String written) {
        int end = 1;
        while (end < written.length() && written.charAt(end) != '/' && written.charAt(end) != '#') {
            end++;
        }
        return written.substring(1, end);
    }

    private static DataResult<Pointer> parseSuper(String written, String rest, String location) {
        if (rest.isEmpty()) {
            return DataResult.<Pointer>success(new Super(List.of()));
        }
        if (rest.startsWith("#")) {
            return jsonPointer(written, rest.substring(1), location)
                    .map(path -> (Pointer) new Super(path));
        }
        return DataResult.error(() -> Diag.DIAG_034.message(location, quote(written),
                "no node at '" + rest + "' in what this entry inherits",
                "'$super' takes a fragment after it, written '$super#/choices', or nothing at all."));
    }

    /**
     * Reads a pointer that has already had its alias substituted.
     * <p>
     * The three tests are in {@code REF.040}'s order and are exclusive by construction, which is what
     * {@code REF.012} requires: "There is no search order between the two. A name resolves in exactly
     * one tier, decided by the presence of a colon, and a failure in that tier is not retried in the
     * other."
     *
     * @param written  the form the file wrote, which a diagnostic names alongside the expansion
     *                 ({@code REF.085})
     * @param expanded the form to read
     */
    private static DataResult<Pointer> parseExpanded(String written, String expanded,
                                                     String location) {
        if (expanded.startsWith("$")) {
            // Only reachable through an $imports prefix that itself begins with $: expansion is one
            // pass, so the result is read as a pointer and not re-expanded. REF.084 makes a leading $
            // an alias and nothing else, so this is the same failure REF.083 describes.
            return DataResult.error(() -> Diag.DIAG_039.message(location, aliasName(expanded)));
        }
        int hash = expanded.indexOf('#');
        if (hash < 0) {
            return parseWholeEntry(written, expanded, location);
        }
        String base = expanded.substring(0, hash);
        DataResult<List<String>> path = jsonPointer(written, expanded.substring(hash + 1), location);
        if (path.error().isPresent()) {
            String message = path.error().get().message();
            return DataResult.error(() -> message);
        }
        return parseFragmentBase(written, expanded, base, path.result().orElseThrow(), location);
    }

    private static DataResult<Pointer> parseWholeEntry(String written, String expanded,
                                                      String location) {
        if (expanded.contains(":")) {
            return asset(written, expanded, expanded, location).map(id -> (Pointer) new Registry(id));
        }
        if (expanded.contains("/")) {
            return DataResult.error(() -> malformedBareName(written, expanded, location));
        }
        return DataResult.<Pointer>success(new Local(expanded, List.of()));
    }

    private static DataResult<Pointer> parseFragmentBase(String written, String expanded, String base,
                                                        List<String> path, String location) {
        if (base.isEmpty()) {
            return DataResult.error(() -> Diag.DIAG_034.message(location, quote(written),
                    "no asset before its '#'",
                    "A fragment pointer names the asset it reaches into, or a definition of this"
                            + " file; there is no pointer that means 'this document'."));
        }
        if (!base.contains(":")) {
            if (base.contains("/")) {
                return DataResult.error(() -> malformedBareName(written, base, location));
            }
            // The form REF.071's fixture writes; see Local's javadoc and the task report.
            return DataResult.<Pointer>success(new Local(base, path));
        }
        // REF.043: a registry prefix is a path segment before the asset id, so it is only a prefix
        // when its slash comes before the colon - `urbex:bricks/standard` is one asset id, and
        // `definitions/urbex:rubble` is a registry and an asset id.
        int slash = base.indexOf('/');
        int colon = base.indexOf(':');
        String registryName = slash >= 0 && slash < colon ? base.substring(0, slash) : null;
        String assetText = registryName == null ? base : base.substring(slash + 1);
        Identifier registry = registryName == null
                ? DEFAULT_FRAGMENT_REGISTRY
                : Identifier.fromNamespaceAndPath(Urbex.MODID, registryName);
        return asset(written, expanded, assetText, location)
                .map(asset -> (Pointer) new Fragment(registry, asset, path));
    }

    private static DataResult<Identifier> asset(String written, String expanded, String text,
                                                String location) {
        try {
            return DataResult.success(Identifier.parse(text));
        } catch (RuntimeException malformed) {
            return DataResult.error(() -> Diag.DIAG_034.message(location, describe(written, expanded),
                    "no asset '" + text + "'",
                    "An asset id is '<namespace>:<path>'; " + malformed.getMessage() + "."));
        }
    }

    private static String malformedBareName(String written, String name, String location) {
        return Diag.DIAG_034.message(location, quote(written), "no asset '" + name + "'",
                "A pointer with no colon names a definition of this file, and by REF.084 a definition"
                        + " name contains no '/'; write the namespace, or an alias.");
    }

    /**
     * An RFC 6901 JSON Pointer, unescaped ({@code REF.042}).
     * <p>
     * The escapes are the reason this is not {@link String#split}: a path segment may contain a
     * literal {@code /} or {@code ~}, written {@code ~1} and {@code ~0}, and unescaping after the split
     * is the order RFC 6901 §4 specifies. A marker is one codepoint and a definition name has no such
     * character, so nothing in the shipped corpus needs it - but a pointer that silently split a
     * {@code ~1} in half would name a node nobody wrote.
     */
    private static DataResult<List<String>> jsonPointer(String written, String text, String location) {
        if (text.isEmpty()) {
            return DataResult.success(List.of());
        }
        if (!text.startsWith("/")) {
            return DataResult.error(() -> Diag.DIAG_034.message(location, quote(written),
                    "no node at '" + text + "'",
                    "A fragment is an RFC 6901 JSON Pointer, so it begins with '/'."));
        }
        List<String> path = new ArrayList<>();
        for (String segment : text.substring(1).split("/", -1)) {
            path.add(segment.replace("~1", "/").replace("~0", "~"));
        }
        return DataResult.success(List.copyOf(path));
    }

    /** The {@code #}-suffix of an expanded pointer, escaped back into RFC 6901 form. */
    private static String fragmentSuffix(List<String> path) {
        if (path.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("#");
        for (String segment : path) {
            text.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
        }
        return text.toString();
    }

    /**
     * A pointer for a diagnostic: the written form, and the expansion too when they differ
     * ({@code REF.085}).
     */
    static String describe(String written, Pointer pointer) {
        return describe(written, pointer.expanded());
    }

    private static String describe(String written, String expanded) {
        return written.equals(expanded)
                ? quote(written)
                : quote(written) + " (which expands to " + quote(expanded) + ")";
    }

    private static String quote(String text) {
        return "'" + text + "'";
    }
}
