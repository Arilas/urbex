package dev.krona.urbex.format.palette;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code VER.020}: the version 1 to version 2 palette converter, and the tool that runs it over a pack.
 *
 * <h2>Why it translates text rather than records</h2>
 *
 * <p>The obvious implementation decodes with {@code PaletteDefinition.CODEC} and re-encodes through
 * {@code PaletteV2Definition.CODEC}. It is the wrong one, for a measured reason: the version 1 codec
 * <em>discards keys it does not know</em> ({@code VER.004} keeps it that way), so a decode is exactly
 * the step that loses the information {@code VER.022} requires this tool to report. Three shipped
 * palettes once wrote {@code damaged} inside {@code blocks[]} elements, where nothing read it, for the
 * lifetime of the pack — that is {@code MODEL.004}'s own {@code > Why}, and a converter built on a
 * decode would have dropped those keys a second time, silently, on the way to a format that exists to
 * refuse them.
 *
 * <p>So the translation runs over the parsed JSON document. What the codecs are used for instead is
 * <em>checking</em>: {@link #paletteFile} decodes its own output with {@code PaletteV2Definition.CODEC}
 * and reports a failure as a blocker, so a translation that produces a file version 2 refuses is
 * caught here rather than by the pack author.
 *
 * <h2>Weights are the slot counts version 1 computed, not the numbers it wrote</h2>
 *
 * <p>Version 1's {@code random} is an absolute slot count, filled in declaration order until 128 slots
 * are full ({@link CompiledPalette#distributeSlots}); version 2 has no counts at all, and its
 * {@code weight}s divide the node in proportion ({@code WEIGHT.011}). The two agree on exactly one
 * input: the counts version 1 actually apportioned. Those sum to 128, so version 2 apportioning them
 * over 128 slots returns them unchanged, and the trailing sentinel — the huge weight that meant "fill
 * the remainder" — arrives here as its clipped value, restated as a size. This calls
 * {@code distributeSlots} rather than re-deriving it, because a second implementation of an
 * apportionment is the drift {@code docs/format/README.md} §1 is entirely about.
 *
 * <h2>What it will not do</h2>
 *
 * <p>{@code VER.030}: it produces a correct file, not an idiomatic one. It invents no definition,
 * extracts no shared trait and collapses no near-duplicate file, because each of those is a judgement
 * about intent. {@code VER.031}: it counts them anyway, and {@link Survey} is what it prints.
 */
public final class V1ToV2 {

    /** {@code MODEL.001}: the keys a version 1 palette file may carry, and nothing else reads. */
    private static final Set<String> FILE_KEYS = Set.of("extends", "palette");

    /** The keys {@code PaletteEntry}'s codec declares; anything else in an entry went unread. */
    private static final Set<String> ENTRY_KEYS = Set.of("char", "block", "variant", "frompalette",
            "blocks", "damaged", "mob", "loot", "torch", "light", "lightSource", "tag");

    private static final Set<String> BLOCKS_KEYS = Set.of("random", "block");

    private static final Set<String> LIGHT_SOURCE_KEYS =
            Set.of("floor", "wall", "ceiling", "free", "unlit", "unlitBlocks");

    private static final Set<String> CANDIDATE_KEYS = Set.of("weight", "block", "unlit");

    /** {@code MODEL.071}, in the order {@code Kind.Placement} declares them. */
    private static final List<String> PLACEMENTS = List.of("floor", "wall", "ceiling", "free");

    /**
     * The version 1 block-source ladder, in the order {@code Palette.compile}'s {@code else if} chain
     * takes them — which is what {@code VER.009} requires the translation to preserve.
     */
    private static final List<String> LADDER =
            List.of("block", "variant", "frompalette", "blocks", "lightSource");

    /**
     * {@code disableHtmlEscaping} is not cosmetic here. A block string carries a property expression —
     * {@code minecraft:oak_stairs[facing=north]} — and Gson escapes {@code =} to {@code =} by
     * default, which produces a file that still decodes and that nobody can read or grep.
     */
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private V1ToV2() {
    }

    // ---- Findings ----------------------------------------------------------------------------

    /** What a finding costs: a blocker stops the conversion, a warning only reports it. */
    public enum Severity {

        /**
         * {@code VER.022}: a construct that could not be translated without a decision. The tool exits
         * non-zero rather than guessing.
         */
        BLOCKER,

        /**
         * {@code VER.009} and its neighbours: the translation is determined, and the author should
         * still learn that their file said something it never meant.
         */
        WARNING
    }

    /**
     * One thing the converter has to say about one asset.
     *
     * @param severity  whether this stops the conversion
     * @param rule      the rule this finding is owed to, e.g. {@code "VER.009"}
     * @param asset     the file it is about, as a path relative to the pack root
     * @param where     the marker or entry it is about, or {@code ""} for the whole file
     * @param detail    what happened, and what the author should do about it
     */
    public record Finding(Severity severity, String rule, String asset, String where, String detail) {

        /** One line, in the shape {@code 08-errors.md} §2 fixes for a diagnostic: subject, then remedy. */
        public String describe() {
            String at = where.isEmpty() ? asset : asset + " marker '" + where + "'";
            return severity.name().toLowerCase(Locale.ROOT) + " " + rule + " " + at + ": " + detail;
        }
    }

    /**
     * A converted asset: the text to write, and everything the converter has to say about it.
     *
     * @param json     the version 2 document, pretty-printed and newline-terminated
     * @param findings every {@link Finding} this file produced, in the order they were made
     */
    public record Converted(String json, List<Finding> findings) {

        public Converted(String json, List<Finding> findings) {
            this.json = json;
            this.findings = List.copyOf(findings);
        }

        /** Whether {@code VER.022} refuses this file. */
        public boolean blocked() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.BLOCKER);
        }
    }

    // ---- The translation ---------------------------------------------------------------------

    /**
     * Translates one version 1 palette file, or returns a version 2 one unchanged.
     *
     * <p>{@code VER.023} is the first branch and it is byte-for-byte: a file that already declares
     * version 2 comes back as the string that went in, not as a re-encoding of it. Re-encoding would
     * be idempotent in the format's terms and not in the file's, and the property this tool needs is
     * the file's — a pack half-converted and re-run must show a diff only where a diff is owed.</p>
     *
     * <p>{@code VER.003} decides the version by inspecting the raw document, so this asks the same
     * question the loader does, in the same place, before any codec sees it.</p>
     *
     * @param source the file's text
     * @param asset  the path to name in findings
     */
    public static Converted paletteFile(String source, String asset) {
        List<Finding> findings = new ArrayList<>();
        JsonObject v1 = JsonParser.parseString(source).getAsJsonObject();
        if (alreadyVersion2(v1)) {
            return new Converted(source, findings);
        }

        JsonObject out = new JsonObject();
        out.addProperty("version", 2);
        unreadKeys(v1, FILE_KEYS, findings, asset, "", "the palette file");
        if (v1.has("extends")) {
            out.add("extends", v1.get("extends"));
        }
        if (v1.has("palette")) {
            out.add("palette", markers(v1.getAsJsonArray("palette"), findings, asset));
        } else {
            // MODEL.003: 'palette' is required somewhere in the chain, not in every file. A version 1
            // file with none inherits its ancestor's, and the version 2 form says the same by being
            // silent in the same place.
            findings.add(new Finding(Severity.WARNING, "MODEL.003", asset, "",
                    "declares no 'palette' of its own; the converted file inherits one through "
                            + "'extends' exactly as the original did"));
        }
        String json = write(out);
        checkOutputDecodes(json, findings, asset);
        return new Converted(json, findings);
    }

    /**
     * Translates one version 1 {@code variants} asset into the {@code definitions} asset that replaces
     * it.
     *
     * <p>{@code MODEL.044}: "A named weighted node in the definitions registry is what a {@code variant}
     * was". The {@code variants} registry itself is left in place — nothing in the version 1 half of a
     * half-converted pack would load without it — so this <em>adds</em> a definitions asset rather than
     * moving one.</p>
     */
    public static Converted variantFile(String source, String asset) {
        List<Finding> findings = new ArrayList<>();
        JsonObject v1 = JsonParser.parseString(source).getAsJsonObject();
        if (alreadyVersion2(v1)) {
            return new Converted(source, findings);
        }

        JsonObject out = new JsonObject();
        // REF.019: a definitions asset declares "version": 2. An absent version is refused there
        // rather than read as version 1, because the registry has no version 1 form.
        out.addProperty("version", 2);
        unreadKeys(v1, Set.of("extends", "blocks", "append"), findings, asset, "", "a variant");
        if (v1.has("extends")) {
            out.add("extends", v1.get("extends"));
        }
        if (v1.has("blocks")) {
            weighted(v1.getAsJsonArray("blocks"), out, findings, asset, "");
        }
        return new Converted(write(out), findings);
    }

    /** {@code VER.003}: the version is read off the raw document, before anything decodes it. */
    private static boolean alreadyVersion2(JsonObject document) {
        JsonElement version = document.get("version");
        return version != null && version.isJsonPrimitive()
                && version.getAsJsonPrimitive().isNumber() && version.getAsInt() == 2;
    }

    /**
     * The version 1 {@code palette} list as a version 2 {@code palette} object.
     *
     * <p>{@code MODEL.005} is why this can lose an entry: version 1 held entries in a list, each
     * carrying its own {@code char}, and a file declaring one character twice was accepted with the
     * last declaration winning silently. An object cannot express that, so the last declaration wins
     * here too — which preserves the behaviour — and the collision is reported, because it is precisely
     * the silence version 2 exists to close.</p>
     */
    private static JsonObject markers(JsonArray entries, List<Finding> findings, String asset) {
        Map<String, JsonElement> byMarker = new LinkedHashMap<>();
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("char")) {
                findings.add(new Finding(Severity.BLOCKER, "VER.022", asset, "",
                        "an entry declares no 'char', so it has no marker to become a key of "
                                + "'palette'. Give it one, or delete it."));
                continue;
            }
            String marker = entry.get("char").getAsString();
            JsonElement node = entryNode(entry, marker, findings, asset);
            if (byMarker.put(marker, node) != null) {
                findings.add(new Finding(Severity.WARNING, "MODEL.005", asset, marker,
                        "is declared more than once. Version 1 kept the last declaration silently and "
                                + "so does this conversion; delete the ones that never applied."));
            }
        }
        JsonObject palette = new JsonObject();
        byMarker.forEach(palette::add);
        return palette;
    }

    /**
     * One version 1 entry as a version 2 node.
     *
     * <p>The whole of {@code VER.009} lives here. Version 1 chose a block source with an {@code if} /
     * {@code else if} ladder over five keys and dropped the rest without a word, and version 2 has no
     * ladder at all — {@code MODEL.013}: "{@code kind} selects exactly one block source". So the
     * translation takes what the ladder would have taken and names what it dropped.</p>
     */
    private static JsonElement entryNode(JsonObject entry, String marker,
                                         List<Finding> findings, String asset) {
        unreadKeys(entry, ENTRY_KEYS, findings, asset, marker, "a palette entry");

        List<String> sources = new ArrayList<>();
        for (String key : LADDER) {
            if (key.equals("lightSource")) {
                if (isSocket(entry.get("lightSource"))) {
                    sources.add(key);
                }
            } else if (declares(entry, key)) {
                sources.add(key);
            }
        }
        if (sources.isEmpty()) {
            findings.add(new Finding(Severity.BLOCKER, "VER.022", asset, marker,
                    "names no block source at all. Version 1 refused this entry at compile time; "
                            + "give it a 'block', 'blocks', 'variant', 'frompalette' or a "
                            + "'lightSource' with candidates."));
            return new JsonObject();
        }
        String chosen = sources.getFirst();
        if (sources.size() > 1) {
            findings.add(new Finding(Severity.WARNING, "VER.009", asset, marker,
                    "declares " + sources.size() + " block sources; version 1's ladder took '" + chosen
                            + "' and silently dropped " + quoted(sources.subList(1, sources.size()))
                            + ". The conversion keeps the behaviour and drops them too — delete them, "
                            + "or move them to a marker of their own."));
        }

        JsonObject node = new JsonObject();
        switch (chosen) {
            case "block" -> node.addProperty("block", entry.get("block").getAsString());
            // VER's §2 table: the 'variants' registry becomes 'definitions', and a variant reference
            // becomes a $ref into it. The id is unchanged, so REF.010's colon puts it in the registry
            // tier and the converted definitions asset sits at the path the variant did.
            case "variant" -> node.addProperty("$ref", entry.get("variant").getAsString());
            case "frompalette" -> alias(entry, node, marker, findings, asset);
            case "blocks" -> weighted(entry.getAsJsonArray("blocks"), node, findings, asset, marker);
            case "lightSource" -> socket(entry.getAsJsonObject("lightSource"), node,
                    marker, findings, asset);
            default -> throw new IllegalStateException(chosen);
        }

        JsonObject traits = traits(entry, chosen, marker, findings, asset);
        if (!traits.isEmpty()) {
            node.add("traits", traits);
        }
        // MODEL.020: wherever a node is expected, a JSON string is that node with kind 'block'. The
        // shorthand is used only where the node has nothing else to say, so no information about the
        // entry is carried by the choice of spelling.
        if (node.size() == 1 && node.has("block")) {
            return new JsonPrimitive(node.get("block").getAsString());
        }
        return node;
    }

    /**
     * {@code frompalette} as an {@code alias}.
     *
     * <p>{@code MODEL.061} requires {@code of} to be exactly one character, and version 1 read exactly
     * one whatever the file wrote — {@code CompiledPalette}'s fixed point loop takes {@code charAt(0)}.
     * A longer value therefore meant its first character and nothing else, which is the behaviour this
     * preserves and reports.</p>
     */
    private static void alias(JsonObject entry, JsonObject node, String marker,
                              List<Finding> findings, String asset) {
        String written = entry.get("frompalette").getAsString();
        node.addProperty("kind", "alias");
        String target = written.substring(0, written.offsetByCodePoints(0, 1));
        node.addProperty("of", target);
        if (!target.equals(written)) {
            findings.add(new Finding(Severity.WARNING, "MODEL.061", asset, marker,
                    "aliases '" + written + "', of which version 1 read only '" + target
                            + "'. The conversion keeps that reading; write the marker you meant."));
        }
    }

    /**
     * A version 1 weighted list — {@code blocks} or {@code unlitBlocks} — as a {@code weighted} node.
     *
     * <p>The weights emitted are {@link CompiledPalette#distributeSlots}' output, for the reason this
     * class's javadoc gives. Two things fall out of that and both are reported rather than assumed:</p>
     *
     * <ul>
     *   <li>a choice apportioned <b>zero</b> slots is dropped, because {@code WEIGHT.002} refuses a
     *       zero weight and because zero slots is version 1 never placing it. Four lists in the
     *       measured corpus reach 128 before their last entry, so this is a real shape and not a
     *       hypothetical one;</li>
     *   <li>the trailing sentinel becomes its clipped value. It cannot become {@code "rest": true} —
     *       {@code WEIGHT.013} refuses a {@code rest} in a list that also carries a {@code weight}, and
     *       every other choice of such a list is a {@code weight} by the row above it in the table.</li>
     * </ul>
     */
    private static void weighted(JsonArray blocks, JsonObject node, List<Finding> findings,
                                 String asset, String marker) {
        node.addProperty("kind", "weighted");
        if (blocks.isEmpty()) {
            findings.add(new Finding(Severity.BLOCKER, "VER.022", asset, marker,
                    "declares an empty weighted list, which version 2 refuses (MODEL.045) and "
                            + "version 1 read as no list at all. Name at least one block, or delete "
                            + "the key."));
            node.add("choices", new JsonArray());
            return;
        }

        int[] weights = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            JsonObject block = blocks.get(i).getAsJsonObject();
            unreadKeys(block, BLOCKS_KEYS, findings, asset, marker, "a weighted choice");
            weights[i] = block.get("random").getAsInt();
        }
        int[] slots = CompiledPalette.distributeSlots(weights, CompiledPalette.SLOTS);

        JsonArray choices = new JsonArray();
        for (int i = 0; i < blocks.size(); i++) {
            JsonObject block = blocks.get(i).getAsJsonObject();
            if (slots[i] == 0) {
                findings.add(new Finding(Severity.WARNING, "WEIGHT.002", asset, marker,
                        "choice " + (i + 1) + " (" + block.get("block").getAsString() + ", weight "
                                + weights[i] + ") received no slot at all: the choices before it "
                                + "already fill 128. Version 1 never placed it and the conversion "
                                + "drops it; delete it, or lower the weights before it."));
                continue;
            }
            JsonObject choice = new JsonObject();
            choice.addProperty("weight", slots[i]);
            choice.addProperty("block", block.get("block").getAsString());
            choices.add(choice);
        }
        node.add("choices", choices);
    }

    /**
     * A version 1 {@code lightSource} carrying placement lists, as a {@code light_socket}.
     *
     * <p>The candidate weights are written out as the file wrote them, and that is the one place this
     * converter knowingly does not preserve the world. Version 1 draws a socket candidate with
     * {@code LightPool.weightedOrder}, a ticket below the <em>authored</em> total; version 2
     * apportions the placement list to 128 slots like any other list ({@code WEIGHT.043}) and
     * {@code V2Sockets} counts the slots back into weights. A list of {@code [6, 3, 1]} therefore
     * becomes {@code [77, 38, 13]} on the far side, and no choice of numbers here avoids it: the
     * counts always total 128, and 6/10 is not a number of 128ths. It is reported per socket rather
     * than papered over.</p>
     */
    private static void socket(JsonObject settings, JsonObject node, String marker,
                               List<Finding> findings, String asset) {
        unreadKeys(settings, LIGHT_SOURCE_KEYS, findings, asset, marker, "a 'lightSource'");
        node.addProperty("kind", "light_socket");
        for (String placement : PLACEMENTS) {
            if (!settings.has(placement) || settings.getAsJsonArray(placement).isEmpty()) {
                continue;
            }
            JsonArray candidates = new JsonArray();
            for (JsonElement element : settings.getAsJsonArray(placement)) {
                JsonObject written = element.getAsJsonObject();
                unreadKeys(written, CANDIDATE_KEYS, findings, asset, marker, "a light candidate");
                JsonObject candidate = new JsonObject();
                candidate.addProperty("weight", written.get("weight").getAsInt());
                candidate.addProperty("block", written.get("block").getAsString());
                if (written.has("unlit")) {
                    // TRAIT.055, via TRAIT.005 and TRAIT.006 rather than a mechanism of its own: a
                    // candidate is an alternative, so it inherits the socket's traits, and one
                    // declaring its own urbex:light replaces the inherited one whole.
                    candidate.add("traits", light(written.get("unlit").getAsString()));
                }
                candidates.add(candidate);
            }
            node.add(placement, candidates);
        }
        findings.add(new Finding(Severity.WARNING, "VER.021", asset, marker,
                "is a light_socket. Version 1 draws its candidates on their authored weights and "
                        + "version 2 apportions the list to 128 slots first, so the two do not place "
                        + "the same light at the same position. Nothing this converter writes avoids "
                        + "that; see the task 9 report."));
        if (settings.has("unlit") || settings.has("unlitBlocks")) {
            socketReplacement(settings, node, marker, findings, asset);
        }
    }

    /**
     * A socket-level {@code unlit}, which version 1 used as the fallback for every candidate that
     * named none.
     *
     * <p>The version 2 spelling is {@code urbex:light} on the socket itself: by {@code TRAIT.005} every
     * candidate inherits it, and by {@code TRAIT.006} a candidate declaring its own replaces it whole —
     * which is exactly {@code LightSource.unlitFor}'s two branches, performed by inheritance instead of
     * at placement time. §2's table does not have a row for this combination; the derivation is
     * recorded here and in the task report.</p>
     */
    private static void socketReplacement(JsonObject settings, JsonObject node, String marker,
                                          List<Finding> findings, String asset) {
        if (settings.has("unlit") && settings.has("unlitBlocks")) {
            findings.add(new Finding(Severity.BLOCKER, "VER.022", asset, marker,
                    "declares both 'unlit' and 'unlitBlocks'. Version 1 refused this at decode; "
                            + "it has one replacement, so name it once."));
            return;
        }
        JsonObject traits = new JsonObject();
        JsonObject light = new JsonObject();
        if (settings.has("unlit")) {
            light.addProperty("unlit", settings.get("unlit").getAsString());
        } else {
            JsonObject unlit = new JsonObject();
            weighted(settings.getAsJsonArray("unlitBlocks"), unlit, findings, asset, marker);
            light.add("unlit", unlit);
            findings.add(new Finding(Severity.WARNING, "VER.021", asset, marker,
                    "gives a light_socket a weighted 'unlitBlocks'. Version 1 draws that replacement "
                            + "per position; version 2 gives a socket one replacement state, its "
                            + "first alternative. Name a single block to keep the behaviour."));
        }
        traits.add("urbex:light", light);
        node.add("traits", traits);
    }

    /**
     * Every version 1 metadata field of one entry, as version 2 traits.
     *
     * <p>{@code TRAIT.004} is what makes this a loop rather than a ladder: version 1 read these four
     * fields as an {@code else if} chain at generation time, so an entry declaring both
     * {@code lightSource} and {@code mob} placed the light and discarded the spawner. Composing them is
     * the fix, and it moves no golden because the shipped corpus holds zero entries carrying two.</p>
     *
     * <p>Two of them are dropped rather than translated, and both drops preserve version 1's behaviour
     * exactly. {@code Palette.compile} records a {@code damaged} mapping only where it has a resolved
     * state to key it by, so an entry whose source is {@code frompalette} or a socket never had one —
     * and writing {@code urbex:damaged} on the converted alias or socket would <em>add</em> a damaged
     * form the original did not have, because {@code MODEL.063} gives an alias its target's traits and
     * its own.</p>
     */
    private static JsonObject traits(JsonObject entry, String chosen, String marker,
                                     List<Finding> findings, String asset) {
        JsonObject traits = new JsonObject();
        if (declares(entry, "damaged")) {
            if (chosen.equals("frompalette") || chosen.equals("lightSource")) {
                findings.add(new Finding(Severity.WARNING, "VER.009", asset, marker,
                        "declares 'damaged' beside a '" + chosen + "', where version 1 had no resolved "
                                + "state to key the mapping by and dropped it. The conversion drops "
                                + "it too; delete it, or damage the marker it names instead."));
            } else {
                traits.add("urbex:damaged", field("into", entry.get("damaged").getAsString()));
            }
        }
        if (declares(entry, "loot")) {
            traits.add("urbex:loot", field("pool", entry.get("loot").getAsString()));
        }
        if (declares(entry, "mob")) {
            traits.add("urbex:spawner", field("pool", entry.get("mob").getAsString()));
        }
        if (entry.has("tag") && entry.getAsJsonObject("tag").size() > 0) {
            JsonObject blockEntity = new JsonObject();
            blockEntity.add("nbt", entry.get("tag"));
            traits.add("urbex:block_entity", blockEntity);
        }
        if (entry.has("lightSource") && !chosen.equals("lightSource")) {
            inPlaceLight(entry.get("lightSource"), traits, marker, findings, asset);
        }
        return traits;
    }

    /**
     * A {@code lightSource} that is not a socket: the entry's own block is the light, and the field
     * says what stands in its place when the roll rejects it.
     *
     * <p>{@code MODEL.075} is the split this reads: a socket is a kind because it selects the block,
     * and {@code urbex:light} is a trait because it states that a block already selected is an optional
     * light. Version 1 spelled both {@code lightSource} and told them apart by whether any placement
     * list was non-empty, which is the same test made here.</p>
     */
    private static void inPlaceLight(JsonElement settings, JsonObject traits, String marker,
                                     List<Finding> findings, String asset) {
        if (settings.isJsonPrimitive()) {
            if (!settings.getAsBoolean()) {
                findings.add(new Finding(Severity.BLOCKER, "VER.022", asset, marker,
                        "declares \"lightSource\": false, which version 1 refused as saying nothing. "
                                + "Omit the field instead."));
                return;
            }
            traits.add("urbex:light", new JsonObject());
            return;
        }
        JsonObject object = settings.getAsJsonObject();
        unreadKeys(object, LIGHT_SOURCE_KEYS, findings, asset, marker, "a 'lightSource'");
        if (object.has("unlit") && object.has("unlitBlocks")) {
            findings.add(new Finding(Severity.BLOCKER, "VER.022", asset, marker,
                    "declares both 'unlit' and 'unlitBlocks'. Version 1 refused this at decode; "
                            + "it has one replacement, so name it once."));
            return;
        }
        if (object.has("unlit")) {
            traits.add("urbex:light", field("unlit", object.get("unlit").getAsString()));
        } else if (object.has("unlitBlocks")) {
            // TRAIT.009: every trait field that names a block holds a node, so the replacement that
            // needed a second key in version 1 is the same weighted node any other position takes.
            JsonObject unlit = new JsonObject();
            weighted(object.getAsJsonArray("unlitBlocks"), unlit, findings, asset, marker);
            JsonObject light = new JsonObject();
            light.add("unlit", unlit);
            traits.add("urbex:light", light);
        } else {
            // TRAIT.051: an absent 'unlit' is air, which is what version 1's compileUnlit returns for
            // a lightSource declaring neither field.
            traits.add("urbex:light", new JsonObject());
        }
    }

    private static JsonObject light(String unlit) {
        JsonObject traits = new JsonObject();
        traits.add("urbex:light", field("unlit", unlit));
        return traits;
    }

    private static JsonObject field(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    /** Whether {@code key} carries a value version 1 would have read; an empty one it would not. */
    private static boolean declares(JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        if (value == null || value.isJsonNull()) {
            return false;
        }
        // PaletteEntry's deduplicateList/deduplicateTag return null for an empty list or tag, so an
        // empty 'blocks' or 'tag' decoded to nothing at all and fell straight through the ladder.
        if (value.isJsonArray()) {
            return !value.getAsJsonArray().isEmpty();
        }
        if (value.isJsonObject()) {
            return value.getAsJsonObject().size() > 0;
        }
        return true;
    }

    /** {@code LightSourceSettings.isSocket}: any of the four placement lists carrying a candidate. */
    private static boolean isSocket(JsonElement settings) {
        if (settings == null || !settings.isJsonObject()) {
            return false;
        }
        JsonObject object = settings.getAsJsonObject();
        return PLACEMENTS.stream().anyMatch(p -> object.has(p) && !object.getAsJsonArray(p).isEmpty());
    }

    /**
     * Reports every key at this level that the version 1 codec never read.
     *
     * <p>A blocker, and this is the clearest case {@code VER.022} covers. Version 1 discarded such a key
     * silently, so <em>dropping</em> it preserves the behaviour and <em>translating</em> it gives the
     * marker something the original never had — and the author wrote it meaning the second. Neither
     * reading is the converter's to pick, so it names the key and stops.</p>
     */
    private static void unreadKeys(JsonObject object, Set<String> known, List<Finding> findings,
                                   String asset, String marker, String context) {
        for (String key : object.keySet()) {
            if (known.contains(key) || key.equals("version")) {
                continue;
            }
            findings.add(new Finding(Severity.BLOCKER, "VER.022", asset, marker,
                    "'" + key + "' is not a key of " + context + ", and version 1 discarded it "
                            + "without reading it. Dropping it keeps the behaviour and translating it "
                            + "changes it, so this conversion will not choose: delete the key, or "
                            + "write what it was meant to say."));
        }
    }

    /**
     * Decodes the converted document, so that a translation version 2 refuses fails here.
     *
     * <p>{@code VER.008} makes the translation total — "every version 1 palette has exactly one version
     * 2 form, and the tool in §4 produces it" — and a claim of totality that nothing checks is a claim
     * about the author's confidence. This is the check: the output goes through the same codec the
     * loader uses, and a rejection is reported with the message the catalogue produced.</p>
     */
    private static void checkOutputDecodes(String json, List<Finding> findings, String asset) {
        PaletteV2Definition.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString(json))
                .ifError(error -> findings.add(new Finding(Severity.BLOCKER, "VER.008", asset, "",
                        "the converted file does not decode as version 2, which is a defect in this "
                                + "converter rather than in the pack: " + error.message())));
    }

    private static String write(JsonObject document) {
        return GSON.toJson(document) + "\n";
    }

    private static String quoted(List<String> keys) {
        return keys.stream().map(k -> "'" + k + "'").reduce((a, b) -> a + ", " + b).orElse("");
    }

    // ---- The declined opportunities ----------------------------------------------------------

    /**
     * {@code VER.031}: what the converter saw and would not act on.
     *
     * <p>{@code VER.030} forbids acting on any of it — inventing a definition, extracting a shared
     * trait or collapsing a near-duplicate file is a judgement about intent, and a file whose author
     * did not write it is a file they cannot maintain. Counting is not judging, and the counts are
     * worth naming: the rule's own {@code > Why} names three of them.</p>
     *
     * @param damagedValues     each distinct {@code damaged} block, and how many entries name it
     * @param inlineEntries     palette entries written inline in a part or building
     * @param distinctInline    how many of those are distinct, by their whole text
     * @param rotationFamilies  groups of markers whose entries differ only by a directional property
     * @param markersInFamilies how many markers those groups hold between them
     */
    public record Survey(Map<String, Integer> damagedValues, int inlineEntries, int distinctInline,
                         int rotationFamilies, int markersInFamilies) {

        /** The report {@code VER.031} owes, one line per opportunity, with its measured size. */
        public List<String> describe() {
            List<String> lines = new ArrayList<>();
            int damagedUses = damagedValues.values().stream().mapToInt(Integer::intValue).sum();
            lines.add("declined VER.030 'damaged': " + damagedUses + " uses of "
                    + damagedValues.size() + " distinct value(s) "
                    + damagedValues.entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                            .limit(3).map(e -> e.getKey() + " x" + e.getValue()).toList()
                    + " — one partial definition in $defs would replace every one of them (REF.020).");
            lines.add("declined VER.030 inline palette entries: " + inlineEntries + " written in "
                    + "parts and buildings, " + distinctInline + " of them distinct, so "
                    + (inlineEntries - distinctInline) + " are repetitions of an entry already "
                    + "written elsewhere. A shared palette, or one definition per repeated entry, "
                    + "would replace them.");
            lines.add("declined VER.030 rotation families: " + markersInFamilies + " markers in "
                    + rotationFamilies + " families differing only by a directional property. "
                    + "TRAIT.071 makes a node rotatable by default, so one marker plus the part's "
                    + "own rotation may be all of them.");
            return lines;
        }
    }

    /**
     * Measures the three opportunities over a whole pack.
     *
     * <p>Over the pack rather than over one file, because two of the three are only visible there: an
     * entry repeated across two parts is not repeated within either, and {@code VER.031}'s own numbers
     * are per pack.</p>
     */
    public static Survey survey(Path packRoot) {
        Map<String, Integer> damaged = new TreeMap<>();
        Map<String, Integer> inline = new LinkedHashMap<>();
        Map<String, Set<String>> families = new LinkedHashMap<>();
        int[] inlineCount = new int[1];
        for (Path file : jsonUnder(packRoot)) {
            JsonElement document = read(file);
            boolean registered = packRoot.relativize(file).startsWith("palettes");
            collect(document, packRoot.relativize(file).toString(), registered, damaged, inline,
                    inlineCount, families);
        }
        int familyCount = 0;
        int inFamilies = 0;
        for (Set<String> markers : families.values()) {
            if (markers.size() > 1) {
                familyCount++;
                inFamilies += markers.size();
            }
        }
        return new Survey(damaged, inlineCount[0], inline.size(), familyCount, inFamilies);
    }

    private static void collect(JsonElement element, String file, boolean registered,
                                Map<String, Integer> damaged, Map<String, Integer> inline,
                                int[] inlineCount, Map<String, Set<String>> families) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child ->
                    collect(child, file, registered, damaged, inline, inlineCount, families));
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement palette = object.get("palette");
        if (palette != null && palette.isJsonArray()) {
            for (JsonElement entry : palette.getAsJsonArray()) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject fields = entry.getAsJsonObject();
                if (fields.has("damaged")) {
                    damaged.merge(fields.get("damaged").getAsString(), 1, Integer::sum);
                }
                if (!registered) {
                    inlineCount[0]++;
                    inline.merge(GSON.toJson(fields), 1, Integer::sum);
                }
                family(file, fields, families);
            }
        }
        object.entrySet().forEach(child ->
                collect(child.getValue(), file, registered, damaged, inline, inlineCount, families));
    }

    /** The block properties a part's own rotation and mirroring already move, by {@code TRAIT.070}. */
    private static final java.util.regex.Pattern DIRECTIONAL = java.util.regex.Pattern.compile(
            "\\b(facing|axis|rotation|shape|half|hanging|face|orientation)=[a-z_0-9]+");

    /**
     * Groups an entry with the others in its file that differ from it only by a directional property.
     *
     * <p>The key is the whole entry minus its marker, with every directional property value erased, so
     * {@code oak_stairs[facing=north]} and {@code [facing=south]} land together while two entries
     * differing in their {@code damaged} or their block do not. Per file, because a family is something
     * one palette could collapse.</p>
     *
     * <p><b>This definition is this converter's, not the specification's.</b> {@code VER.031}'s
     * {@code > Why} reports "152 markers in 54 families" for Modern Tweaks without saying what a family
     * is, and no reading of "differing only by a directional property" reproduces that pair: this one
     * measures 149 markers in 48 families, and a search over every subset of the eight properties above,
     * under both per-file and per-pack grouping, produced no combination giving 54 and 152. What is
     * printed is therefore what was measured, by the rule stated here, so a reader can check it.
     * {@code VER.030} forbids acting on any of it either way.</p>
     */
    private static void family(String file, JsonObject entry, Map<String, Set<String>> families) {
        if (!entry.has("char")) {
            return;
        }
        JsonObject withoutMarker = entry.deepCopy();
        withoutMarker.remove("char");
        String text = GSON.toJson(withoutMarker);
        if (!DIRECTIONAL.matcher(text).find()) {
            return;
        }
        families.computeIfAbsent(file + " " + DIRECTIONAL.matcher(text).replaceAll(""),
                key -> new LinkedHashSet<>()).add(entry.get("char").getAsString());
    }

    // ---- The tool ----------------------------------------------------------------------------

    /**
     * Converts a pack's palettes and variants, prints every finding, and exits non-zero when
     * {@code VER.022} refuses one.
     *
     * <p>Usage: {@code V1ToV2 <pack-root> [<out-root>]}, where a pack root is the directory holding
     * {@code palettes/} and {@code variants/} — {@code data/&lt;namespace&gt;/urbex}. With no out root
     * the conversion is written back over the pack, which is what {@code VER.023} makes safe to run
     * twice.</p>
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: V1ToV2 <pack-root> [<out-root>]");
            System.exit(2);
            return;
        }
        Path in = Path.of(args[0]);
        Path out = args.length == 2 ? Path.of(args[1]) : in;
        if (!Files.isDirectory(in)) {
            System.err.println(in + " is not a directory");
            System.exit(2);
            return;
        }

        List<Finding> findings = new ArrayList<>();
        int palettes = convertTree(in.resolve("palettes"), out.resolve("palettes"),
                V1ToV2::paletteFile, in, findings);
        int variants = convertTree(in.resolve("variants"), out.resolve("definitions"),
                V1ToV2::variantFile, in, findings);

        findings.stream().sorted(Comparator.comparing(Finding::severity)
                        .thenComparing(Finding::asset).thenComparing(Finding::where))
                .map(Finding::describe).forEach(System.out::println);
        survey(in).describe().forEach(System.out::println);
        System.out.println("converted " + palettes + " palette(s) and " + variants
                + " variant(s) into definitions, from " + in + " into " + out);

        long blockers = findings.stream().filter(f -> f.severity() == Severity.BLOCKER).count();
        if (blockers > 0) {
            System.err.println("VER.022: " + blockers + " construct(s) could not be translated "
                    + "without a decision; nothing was guessed.");
            System.exit(1);
        }
    }

    /** One registry directory, converted file by file into the directory that replaces it. */
    private static int convertTree(Path from, Path to, Translator translator, Path packRoot,
                                   List<Finding> findings) throws IOException {
        if (!Files.isDirectory(from)) {
            return 0;
        }
        int count = 0;
        for (Path file : jsonUnder(from)) {
            String asset = packRoot.relativize(file).toString();
            Converted converted = translator.convert(Files.readString(file, StandardCharsets.UTF_8),
                    asset);
            findings.addAll(converted.findings());
            if (converted.blocked()) {
                continue;
            }
            Path target = to.resolve(from.relativize(file));
            Files.createDirectories(target.getParent());
            Files.writeString(target, converted.json(), StandardCharsets.UTF_8);
            count++;
        }
        return count;
    }

    private interface Translator {
        Converted convert(String source, String asset);
    }

    /** Every {@code .json} under {@code root}, in a fixed order so two runs report identically. */
    public static List<Path> jsonUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonElement read(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
