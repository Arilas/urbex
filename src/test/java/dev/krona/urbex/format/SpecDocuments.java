package dev.krona.urbex.format;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The parser and renderer for {@code docs/format/}, ported from {@code docs/format/conformance.py}.
 * <p>
 * The Python script was scaffolding, written before there was a Java tree to put the real parser in.
 * It read the same three grammars {@code docs/format/README.md} §3-§5 defines - the rule line, the
 * diagnostic catalogue row, the fixture fence - and rendered the checked-in
 * {@code palette/conformance.md}. This class replaces it as the single authority: {@link #read()}
 * and {@link #render(RuleSet)} are the parser and renderer, kept next to each other and next to the
 * regexes they share, so that a change to the grammar cannot update one without the other noticing.
 * There is deliberately no second implementation anywhere - two parsers of the same three-line
 * grammar are exactly the kind of duplicated claim {@code docs/format/README.md} §1 describes as
 * version 1's failure mode, and this system exists to not repeat it.
 * <p>
 * {@link #load()} re-parses the documents from disk on every call rather than caching; that is
 * deliberate, not an oversight - the documents are a handful of small text files read a handful of
 * times per test run, and a cache would be one more place for {@code renderIndex()}'s output to
 * silently disagree with what is on disk right now.
 */
public final class SpecDocuments {

    /**
     * Areas defined in {@code docs/format/README.md} §3.1's table. {@code DEMO} is reserved for that
     * document's own examples and is excluded here, not listed - a rule identifier whose area is not
     * one of these is a citation to something this specification never defines.
     */
    static final List<String> AREAS = List.of(
            "MODEL", "TRAIT", "REF", "MERGE", "WEIGHT", "CHAR", "LOAD", "DIAG", "VER");

    /** §3.1: reserved for examples in {@code README.md}; defines no rule and is ignored everywhere. */
    private static final String RESERVED_AREA = "DEMO";

    /**
     * §4.2 rule 4: the rule classes a fixture-completeness check applies to.
     * <p>
     * {@code WARN} is deliberately not among them, and §3.2 says why: a warning refuses nothing, so
     * there is no {@code reject=} fixture to write and an {@code accept} one would assert the half of
     * the rule that is not in doubt. §4.3's citing-test requirement is what covers the class instead -
     * a {@code WARN} rule is proved by loading the document, asserting it succeeded, and asserting the
     * cited row arrived at warning level, which is three assertions no fixture grammar can express.
     */
    public static final Set<String> NEEDS_FIXTURE = Set.of("ACCEPT", "REJECT", "DEFAULT", "EQUIV");

    /** Path of the generated index, relative to {@code docs/format}. */
    private static final String INDEX_RELATIVE_PATH = "palette/conformance.md";

    /**
     * The class assigned to a diagnostic-catalogue row (a table row in {@code palette/08-errors.md}
     * §4, not a rule definition line). It is not one of the rule classes {@code README.md} §3.2
     * defines - a catalogue row is data (an id and a message), not a normative statement - so it is
     * excluded wherever a check means "every rule": {@code DIAG.001} is exercised through the
     * {@code REJECT} rule that cites it, not by a test or fixture of its own.
     */
    static final String CATALOGUE_ROW_CLASS = "DIAG";

    /**
     * The class given to an identifier that exists only as a tombstone ({@code README.md} §3.4).
     * <p>
     * A retired rule's definition line is gone and its number is permanent, so something still has to
     * <em>define</em> the identifier: without this, the tombstone that {@code README.md} §3.4 requires
     * would itself be a citation of a rule no document defines, and {@code everyCitedRuleIdentifierIsDefined}
     * would fail on the very line the convention asks for. A retired diagnostic already had this by
     * accident - its catalogue row survives, holding {@code —}, which registers the id - and the first
     * retired <em>rule</em> ({@code VER.014}) is what showed that a rule had no equivalent.
     * <p>
     * It is not one of the classes {@code README.md} §3.2 defines, and it is excluded wherever a check means
     * "every rule", for the same reason {@link #CATALOGUE_ROW_CLASS} is: a tombstone states no
     * requirement, so it can have no fixture and needs no citing test - §3.4 says outright that "tests
     * citing DEMO.007 were deleted".
     */
    static final String TOMBSTONE_CLASS = "RETIRED";

    /**
     * The five keys {@code MODEL.001} names for the top level of a palette file: {@code version},
     * {@code extends}, {@code $imports}, {@code $defs} and {@code palette}, and no others. Hand-copied
     * rather than parsed from the rule's prose - the prose is English, not data, and parsing it back
     * out would be the fragile direction. This constant is what {@link FixtureWellFormednessTest}
     * checks fixtures against, and it is the one place in this file that must be updated by hand if
     * {@code MODEL.001} ever grows a key.
     */
    static final Set<String> MODEL_001_FILE_LEVEL_KEYS =
            Set.of("version", "extends", "$imports", "$defs", "palette");

    /** A rule definition line, e.g. {@code > **REF.032** · `REJECT` (`DIAG.032`) `[NO-FIXTURE: ...]`}. */
    private static final Pattern RULE = Pattern.compile(
            "^>\\s*\\*\\*([A-Z]+\\.\\d{3})\\*\\*\\s*·\\s*`([A-Z ]+)`"
                    + "(?:\\s*\\(`(DIAG\\.\\d{3})`\\))?");

    /**
     * {@code README.md} §4.3's marker, read off the rule line rather than anchored to it.
     * <p>
     * Its own pattern, and {@link #NOT_YET_REACHED} beside it, because a rule line may carry either
     * marker, both, or neither, in whichever order reads best - and expressing that as trailing optional
     * groups on {@link #RULE} means the second marker is silently unparsed whenever it is written first.
     * That is exactly how {@code [NOT-YET-REACHED]} came to be a convention with no tooling: it was never
     * in the pattern at all, so {@code TRAIT.011} parsed as an ordinary {@code MUST} and the index listed
     * two citing tests for a rule the specification declares unreached.
     */
    private static final Pattern NO_FIXTURE = Pattern.compile("`\\[NO-FIXTURE: ([^\\]]+)\\]`");

    /** {@code README.md} §3.3's third status marker, e.g. {@code `[NOT-YET-REACHED: issue #216]`}. */
    private static final Pattern NOT_YET_REACHED =
            Pattern.compile("`\\[NOT-YET-REACHED: ([^\\]]+)\\]`");

    /**
     * A tombstone line ({@code README.md} §3.4), e.g.
     * {@code > **VER.014** — *retired in draft.* …}. The em dash and the italicised {@code retired} are
     * what separate it from a rule definition line, which carries {@code ·} and a class instead.
     */
    private static final Pattern TOMBSTONE = Pattern.compile(
            "^>\\s*\\*\\*([A-Z]+\\.\\d{3})\\*\\*\\s*—\\s*\\*retired\\b");

    /** A row of the diagnostic catalogue table in {@code palette/08-errors.md}: id, raised-by, message. */
    private static final Pattern DIAG_ROW =
            Pattern.compile("^\\|\\s*`(DIAG\\.\\d{3})`\\s*\\|([^|]*)\\|(.*)\\|\\s*$");

    /** A fixture fence, e.g. {@code ```json fixture:MODEL.020 accept name=foo}. */
    private static final Pattern FIXTURE = Pattern.compile(
            "^```json fixture:([A-Z]+\\.\\d{3})\\s+"
                    + "(accept|reject=DIAG\\.\\d{3}|equiv=[\\w-]+|fragment)"
                    + "(?:\\s+name=([\\w-]+))?\\s*$");

    /** Any rule identifier mentioned in prose - a cross-reference from one rule's text to another. */
    private static final Pattern CITE = Pattern.compile("\\b([A-Z]+\\.\\d{3})\\b");

    /** A test source citing a rule via {@code @Rule("ID")}, possibly stacked with other annotations. */
    private static final Pattern RULE_ANNOTATION = Pattern.compile("@Rule\\(\"([A-Z]+\\.\\d{3})\"\\)");

    /** Strips a leading annotation so a stacked {@code @Rule("X") @Test void foo()} still finds {@code foo}. */
    private static final Pattern ANNOTATION = Pattern.compile("@\\w+(\\([^)]*\\))?");

    /** The identifier immediately before an opening paren - reliable for JUnit's always-zero-arg methods. */
    private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*\\(\\s*\\)");

    private final RuleSet ruleSet;

    private SpecDocuments(RuleSet ruleSet) {
        this.ruleSet = ruleSet;
    }

    /** Re-reads every specification document and test source file and parses them fresh. */
    public static SpecDocuments load() {
        return new SpecDocuments(read());
    }

    /** Every rule and diagnostic-catalogue entry, keyed by id, in the order the documents define them. */
    public Map<String, SpecRule> rules() {
        return ruleSet.rules;
    }

    /** Every fixture, in the order the documents declare them. */
    public List<Fixture> fixtures() {
        return ruleSet.fixtures;
    }

    /** Diagnostic id → its message template, from the catalogue table in {@code palette/08-errors.md}. */
    public Map<String, String> diagnostics() {
        return ruleSet.diagnostics;
    }

    /** The specification documents (relative to {@code docs/format}) whose first six lines carry {@code `[DRAFT]`}. */
    public Set<String> draftFiles() {
        return ruleSet.draftFiles;
    }

    /** Rule id → the {@code file:line} locations that mention it in prose, including its own definition. */
    public Map<String, List<String>> proseCitations() {
        return ruleSet.proseCitations;
    }

    /** Rule id → the {@code Class.method} test names whose source cites it via {@code @Rule}. */
    public Map<String, List<String>> citingTests() {
        return ruleSet.citingTests;
    }

    /** Renders {@code palette/conformance.md} from what {@link #load()} parsed. */
    public String renderIndex() {
        return render(ruleSet);
    }

    // ------------------------------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------------------------------

    private static RuleSet read() {
        Path formatDir = repoRoot().resolve("docs/format");
        List<String> files = discoverDocuments(formatDir);

        Map<String, SpecRule> rules = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        List<Fixture> fixtures = new ArrayList<>();
        Map<String, String> diagnostics = new LinkedHashMap<>();
        Set<String> draftFiles = new LinkedHashSet<>();
        Map<String, List<String>> proseCitations = new LinkedHashMap<>();

        for (String file : files) {
            List<String> lines = readLines(formatDir.resolve(file));

            for (int i = 0; i < Math.min(6, lines.size()); i++) {
                if (lines.get(i).startsWith("`[DRAFT]`")) {
                    draftFiles.add(file);
                    break;
                }
            }

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNumber = i + 1;

                Matcher ruleMatch = RULE.matcher(line);
                if (ruleMatch.lookingAt()) {
                    String id = ruleMatch.group(1);
                    if (!id.startsWith(RESERVED_AREA + ".")) {
                        Matcher noFixture = NO_FIXTURE.matcher(line);
                        Matcher notYetReached = NOT_YET_REACHED.matcher(line);
                        SpecRule rule = new SpecRule(id, file, ruleMatch.group(2).trim(),
                                Optional.ofNullable(ruleMatch.group(3)),
                                noFixture.find() ? Optional.of(noFixture.group(1)) : Optional.empty(),
                                notYetReached.find()
                                        ? Optional.of(notYetReached.group(1)) : Optional.empty(),
                                lineNumber);
                        if (!rules.containsKey(id)) {
                            order.add(id);
                        }
                        rules.put(id, rule);
                    }
                }

                Matcher tombstone = TOMBSTONE.matcher(line);
                if (tombstone.lookingAt()) {
                    String id = tombstone.group(1);
                    // Never over an existing definition: a retired diagnostic keeps its catalogue row,
                    // and that row is the more informative of the two.
                    if (!id.startsWith(RESERVED_AREA + ".") && !rules.containsKey(id)) {
                        order.add(id);
                        rules.put(id, new SpecRule(id, file, TOMBSTONE_CLASS, Optional.empty(),
                                Optional.empty(), Optional.empty(), lineNumber));
                    }
                }

                Matcher diagRow = DIAG_ROW.matcher(line);
                if (diagRow.lookingAt()) {
                    String id = diagRow.group(1);
                    if (!rules.containsKey(id)) {
                        order.add(id);
                        rules.put(id, new SpecRule(id, file, CATALOGUE_ROW_CLASS, Optional.empty(),
                                Optional.empty(), Optional.empty(), lineNumber));
                    }
                    diagnostics.put(id, diagRow.group(3).trim());
                }

                Matcher fixtureMatch = FIXTURE.matcher(line);
                if (fixtureMatch.lookingAt()) {
                    List<String> body = new ArrayList<>();
                    int j = i + 1;
                    while (j < lines.size() && !lines.get(j).startsWith("```")) {
                        body.add(lines.get(j));
                        j++;
                    }
                    fixtures.add(toFixture(fixtureMatch, String.join("\n", body), file, lineNumber));
                }

                Matcher cite = CITE.matcher(line);
                while (cite.find()) {
                    String id = cite.group(1);
                    String area = id.substring(0, id.indexOf('.'));
                    if (AREAS.contains(area)) {
                        proseCitations.computeIfAbsent(id, k -> new ArrayList<>()).add(file + ":" + lineNumber);
                    }
                }
            }
        }

        Map<String, List<String>> citingTests = scanCitingTests(repoRoot().resolve("src/test/java"));

        return new RuleSet(files, Collections.unmodifiableList(order), Collections.unmodifiableMap(rules),
                Collections.unmodifiableList(fixtures), Collections.unmodifiableMap(diagnostics),
                Collections.unmodifiableSet(draftFiles), Collections.unmodifiableMap(proseCitations),
                Collections.unmodifiableMap(citingTests));
    }

    private static Fixture toFixture(Matcher fixtureMatch, String json, String file, int line) {
        String ruleId = fixtureMatch.group(1);
        String token = fixtureMatch.group(2);
        Optional<String> name = Optional.ofNullable(fixtureMatch.group(3));

        Outcome outcome;
        Optional<String> diag = Optional.empty();
        Optional<String> equivSlug = Optional.empty();
        if ("accept".equals(token)) {
            outcome = Outcome.ACCEPT;
        } else if (token.startsWith("reject=")) {
            outcome = Outcome.REJECT;
            diag = Optional.of(token.substring("reject=".length()));
        } else if (token.startsWith("equiv=")) {
            outcome = Outcome.EQUIV;
            equivSlug = Optional.of(token.substring("equiv=".length()));
        } else if ("fragment".equals(token)) {
            outcome = Outcome.FRAGMENT;
        } else {
            // Unreachable: FIXTURE only matches these four spellings.
            throw new IllegalStateException("unrecognised fixture outcome: " + token);
        }
        return new Fixture(ruleId, outcome, diag, equivSlug, name, json, file, line);
    }

    /**
     * {@code sorted(glob.glob("palette/*.md")) + ["README.md"]}, minus the generated index -
     * {@code conformance.py}'s exact traversal order, which {@link #render} depends on to group rules
     * by document in the order the documents already appear in {@code docs/format/README.md} §8.
     */
    private static List<String> discoverDocuments(Path formatDir) {
        List<String> paletteFiles;
        try (Stream<Path> listing = Files.list(formatDir.resolve("palette"))) {
            paletteFiles = listing
                    .map(p -> "palette/" + p.getFileName())
                    .filter(name -> name.endsWith(".md") && !name.equals(INDEX_RELATIVE_PATH))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> files = new ArrayList<>(paletteFiles);
        files.add("README.md");
        return files;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A block comment ({@code /* ... *}{@code /}, including javadoc). {@link Pattern#DOTALL} is
     * confined to this pattern alone - it must span newlines, since a javadoc comment routinely does.
     */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * A line comment ({@code //...}), deliberately without {@link Pattern#DOTALL}. Stripping this
     * used to share one alternation with {@link #BLOCK_COMMENT} under a single {@code DOTALL} flag,
     * which applies to the whole alternation, not just the branch that needs it - the greedy
     * {@code .*} in {@code //.*} then matched past the end of the line, into every line that
     * followed. A test source line containing {@code //} that is not a comment at all - a URL inside
     * a string literal, e.g. {@code "https://example.com"} - silently deleted the remainder of the
     * file, including any {@code @Rule} citation after it. See
     * {@code CitingTestScannerTest#aUrlBeforeACitationDoesNotSwallowIt}, which pins this.
     */
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*");

    /**
     * Finds every {@code @Rule("ID")} citation in {@code src/test/java} by scanning source text, not
     * by loading classes. A classpath sweep would need a reflection dependency this module has no
     * other use for, and would still only find annotations retained at runtime on classes that happen
     * to be on the sweep's classpath; a regex over the {@code .java} files finds exactly the same
     * annotations the compiler will see, with nothing to configure.
     * <p>
     * Comments are stripped first - this class's own javadoc for {@link Rule.Rules} shows
     * {@code @Rule("A.001") @Rule("A.002")} as prose, and without stripping, that example was read
     * back as a citation to two rules that do not exist. A file's comments are not its code; scanning
     * text rather than compiling it means that distinction has to be made by hand.
     * <p>
     * The heuristic that follows an annotation to "the next method" is deliberately simple: strip any
     * annotation from the front of a line and look for an identifier immediately before an empty
     * parameter list, which every JUnit 5 test method has by contract. It does not need to be a full
     * Java parser - it only needs to survive the handful of citing-test shapes this codebase actually
     * writes, and {@link ConformanceIndexTest} fails loudly (a rule shows no citing test where a
     * developer expects one) if it ever does not.
     */
    private static Map<String, List<String>> scanCitingTests(Path testRoot) {
        Map<String, List<String>> citingTests = new LinkedHashMap<>();
        if (!Files.isDirectory(testRoot)) {
            return citingTests;
        }
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(testRoot)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path file : javaFiles) {
            scanCitingTestsInFile(file, citingTests);
        }
        return citingTests;
    }

    /**
     * Package-visible so {@code CitingTestScannerTest} can exercise it directly against a synthetic
     * file, rather than having to plant a fixture inside the real {@code src/test/java} tree that
     * {@link #scanCitingTests} walks.
     */
    static void scanCitingTestsInFile(Path file, Map<String, List<String>> citingTests) {
        String className = file.getFileName().toString().replaceFirst("\\.java$", "");
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Block comments first, since they may span lines; line comments second, one line at a time,
        // so a "//" that is not a comment - a URL in a string literal - cannot eat lines after it.
        String withoutBlockComments = BLOCK_COMMENT.matcher(source).replaceAll("");
        List<String> pending = new ArrayList<>();
        for (String rawLine : withoutBlockComments.split("\n", -1)) {
            String line = LINE_COMMENT.matcher(rawLine).replaceAll("");
            Matcher annotation = RULE_ANNOTATION.matcher(line);
            while (annotation.find()) {
                pending.add(annotation.group(1));
            }
            if (pending.isEmpty()) {
                continue;
            }
            String withoutAnnotations = ANNOTATION.matcher(line).replaceAll("");
            Matcher method = METHOD_NAME.matcher(withoutAnnotations);
            if (method.find()) {
                String testId = className + "." + method.group(1);
                for (String ruleId : pending) {
                    citingTests.computeIfAbsent(ruleId, k -> new ArrayList<>()).add(testId);
                }
                pending.clear();
            }
        }
    }

    /**
     * Walks up from the working directory until it finds {@code docs/format}. Tests must resolve
     * paths the same way whether Gradle runs them (working directory: the project root) or an IDE
     * runs a single test (working directory: sometimes the project root, sometimes the module root,
     * depending on the IDE) - this makes both cases find the same files instead of one of them
     * silently reading nothing.
     * <p>
     * Public because it is not only this package that asks the tree a question: {@code LOAD.031}'s guard
     * in {@code CompiledV2PaletteTest} enumerates the compiler's classes from {@code src/main/java}, and
     * a second copy of this walk would be a second answer to "where is the repository" to drift.
     */
    public static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("docs/format"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "could not find docs/format above " + Path.of("").toAbsolutePath());
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    private static String render(RuleSet ruleSet) {
        Map<String, SpecRule> rules = ruleSet.rules;
        Map<String, List<Fixture>> fixturesByRule = ruleSet.fixtures.stream()
                .collect(Collectors.groupingBy(Fixture::ruleId, LinkedHashMap::new, Collectors.toList()));

        StringBuilder out = new StringBuilder();
        out.append("# Conformance index\n\n");
        out.append("`[GENERATED]` — do not edit. Regenerate with `./gradlew regenerateConformance`;\n");
        out.append("`ConformanceIndexTest` fails if the checked-in copy differs from what the documents say.\n\n");
        out.append("Every rule in this specification, its class, its fixtures, and the tests that cite it. See\n");
        out.append("[the specification system](../README.md#5-the-conformance-index) for what this file is for.\n\n");

        renderTotals(out, rules, ruleSet.fixtures);
        renderOutstanding(out, ruleSet.order, rules, fixturesByRule, ruleSet.citingTests);
        renderRules(out, ruleSet.files, ruleSet.order, rules, fixturesByRule, ruleSet.citingTests);

        return out.toString();
    }

    private static void renderTotals(StringBuilder out, Map<String, SpecRule> rules, List<Fixture> fixtures) {
        out.append("## Totals\n\n| Area | Rules | Fixtures |\n|---|---:|---:|\n");
        for (String area : AREAS) {
            long ruleCount = rules.keySet().stream().filter(id -> id.startsWith(area + ".")).count();
            long fixtureCount = fixtures.stream().filter(fx -> fx.ruleId().startsWith(area + ".")).count();
            out.append("| `").append(area).append("` | ").append(ruleCount).append(" | ")
                    .append(fixtureCount).append(" |\n");
        }
        out.append("| **total** | **").append(rules.size()).append("** | **")
                .append(fixtures.size()).append("** |\n\n");
    }

    private static void renderOutstanding(StringBuilder out, List<String> order, Map<String, SpecRule> rules,
            Map<String, List<Fixture>> fixturesByRule, Map<String, List<String>> citingTests) {
        List<String> gaps = new ArrayList<>();
        List<String> noFixture = new ArrayList<>();
        List<String> notYetReached = new ArrayList<>();
        for (String id : order) {
            SpecRule rule = rules.get(id);
            if (rule.noFixtureReason().isPresent()) {
                noFixture.add(id);
            }
            if (rule.notYetReachedReason().isPresent()) {
                notYetReached.add(id);
            }
            // README.md §5: "any rule has no citing test and no fixture" - every rule, not only the
            // four classes §4.2 rule 4 can discharge with a fixture alone. A [NO-FIXTURE] rule is
            // still excluded here: it is tracked in the table below instead, and its citing-test
            // requirement (§4.3) is not subject to the draft suspension this list is named for.
            // Catalogue rows (cls DIAG) are excluded too - they are not rules under §3.2 at all, and
            // so are tombstones (cls RETIRED), which state no requirement to cover.
            if (CATALOGUE_ROW_CLASS.equals(rule.cls()) || TOMBSTONE_CLASS.equals(rule.cls())
                    || rule.noFixtureReason().isPresent()) {
                continue;
            }
            boolean hasFixture = fixturesByRule.containsKey(id) && !fixturesByRule.get(id).isEmpty();
            boolean hasCitingTest = citingTests.containsKey(id) && !citingTests.get(id).isEmpty();
            if (!hasFixture && !hasCitingTest) {
                gaps.add(id);
            }
        }

        out.append("## Outstanding\n\n");
        out.append("**Rules relying on the draft suspension of fixture-completeness (")
                .append(gaps.size()).append("):** ");
        if (gaps.isEmpty()) {
            out.append("none — this specification is ready to leave draft on this criterion.");
        } else {
            out.append(gaps.stream().map(id -> "`" + id + "`").collect(Collectors.joining(", ")));
        }
        out.append("\n\n");
        out.append("**Rules marked `[NO-FIXTURE]` (").append(noFixture.size())
                .append("), which must each be covered by a citing test:**\n\n");
        out.append("| Rule | Reason |\n|---|---|\n");
        for (String id : noFixture) {
            out.append("| `").append(id).append("` | ").append(rules.get(id).noFixtureReason().orElseThrow())
                    .append(" |\n");
        }
        // README.md §3.3's third status marker. Listed rather than left to the rule table alone,
        // because the thing a reader needs to know about one of these is precisely what the table
        // cannot show: the rule is current and its citing tests are real, and they cover the spelling
        // of a situation no code path reaches yet. TRAIT.011 sat in that table with two citing tests
        // beside it and nothing saying so.
        out.append("\n**Rules marked `[NOT-YET-REACHED]` (").append(notYetReached.size())
                .append("), whose citing tests can only cover the spelling until the issue named lands:**\n\n");
        out.append("| Rule | Reason |\n|---|---|\n");
        for (String id : notYetReached) {
            out.append("| `").append(id).append("` | ")
                    .append(rules.get(id).notYetReachedReason().orElseThrow()).append(" |\n");
        }

        // Was a flat "none yet" until Task 2 wrote the first citing tests, at which point the index
        // was asserting something about itself that had stopped being true. A count is the version of
        // that sentence which cannot go stale.
        long cited = order.stream().filter(id -> !citingTests.getOrDefault(id, List.of()).isEmpty())
                .count();
        out.append("\n**Tests:** ").append(cited).append(" of ").append(rules.size())
                .append(" identifiers have at least one citing test; the rest show `—` below.\n");
        out.append("`ConformanceIndexTest` will fail on any rule that still shows `—` once this document leaves draft.\n\n");
    }

    private static void renderRules(StringBuilder out, List<String> files, List<String> order,
            Map<String, SpecRule> rules, Map<String, List<Fixture>> fixturesByRule,
            Map<String, List<String>> citingTests) {
        out.append("## Rules\n\n");
        Set<String> seen = new LinkedHashSet<>();
        for (String file : files) {
            List<String> idsInFile = order.stream()
                    .filter(id -> rules.get(id).file().equals(file) && !seen.contains(id))
                    .toList();
            if (idsInFile.isEmpty()) {
                continue;
            }
            seen.addAll(idsInFile);
            out.append("### `").append(file).append("`\n\n");
            out.append("| Rule | Class | Diagnostic | Fixtures | Tests |\n|---|---|---|---|---|\n");
            for (String id : idsInFile) {
                SpecRule rule = rules.get(id);
                List<Fixture> fx = fixturesByRule.getOrDefault(id, List.of());
                String fixturesCell = fx.stream().map(f -> "`" + outcomeToken(f) + "`")
                        .collect(Collectors.joining(", "));
                if (fixturesCell.isEmpty()) {
                    fixturesCell = rule.noFixtureReason().isPresent()
                            ? "*n/a*"
                            : (NEEDS_FIXTURE.contains(rule.cls()) ? "*—*" : "");
                }
                String diagCell = rule.diag().map(d -> "`" + d + "`").orElse("");
                String classCell = "`" + rule.cls() + "`"
                        + (rule.notYetReachedReason().isPresent() ? " `[NOT-YET-REACHED]`" : "");
                List<String> tests = citingTests.getOrDefault(id, List.of());
                String testsCell = tests.isEmpty()
                        ? "—"
                        : tests.stream().map(t -> "`" + t + "`").collect(Collectors.joining(", "));
                out.append("| `").append(id).append("` | ").append(classCell).append(" | ")
                        .append(diagCell).append(" | ").append(fixturesCell).append(" | ")
                        .append(testsCell).append(" |\n");
            }
            out.append("\n");
        }
    }

    private static String outcomeToken(Fixture fixture) {
        return switch (fixture.outcome()) {
            case ACCEPT -> "accept";
            case REJECT -> "reject=" + fixture.diag().orElseThrow();
            case EQUIV -> "equiv=" + fixture.equivSlug().orElseThrow();
            case FRAGMENT -> "fragment";
        };
    }

    // ------------------------------------------------------------------------------------------
    // CLI entry point for the `regenerateConformance` Gradle task
    // ------------------------------------------------------------------------------------------

    /**
     * {@code ./gradlew regenerateConformance} runs this with {@code write} on the test runtime
     * classpath - see {@code build.gradle}'s {@code regenerateConformance} task.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !"write".equals(args[0])) {
            System.err.println("usage: SpecDocuments write");
            System.exit(1);
            return;
        }
        SpecDocuments spec = load();
        Path index = repoRoot().resolve("docs/format").resolve(INDEX_RELATIVE_PATH);
        Files.writeString(index, spec.renderIndex());
        System.out.println("wrote " + index + ": " + spec.rules().size() + " identifiers, "
                + spec.fixtures().size() + " fixtures");
    }

    // ------------------------------------------------------------------------------------------
    // Data types
    // ------------------------------------------------------------------------------------------

    /** The parse result: everything {@link #read()} extracted, immutable once built. */
    private record RuleSet(List<String> files, List<String> order, Map<String, SpecRule> rules,
            List<Fixture> fixtures, Map<String, String> diagnostics, Set<String> draftFiles,
            Map<String, List<String>> proseCitations, Map<String, List<String>> citingTests) {
    }

    /**
     * One rule or diagnostic-catalogue entry.
     *
     * @param id                the identifier, e.g. {@code "REF.032"} or {@code "DIAG.032"}
     * @param file              the document that defines it, relative to {@code docs/format}
     * @param cls               its class (§3.2), or {@code "DIAG"} for a catalogue row
     * @param diag              the {@code DIAG} it cites, present only for a {@code REJECT} rule
     * @param noFixtureReason   the reason text inside {@code [NO-FIXTURE: ...]}, if marked (§4.3)
     * @param notYetReachedReason the reason text inside {@code [NOT-YET-REACHED: ...]}, if marked (§3.3)
     * @param line              the 1-indexed line of its definition
     */
    public record SpecRule(String id, String file, String cls, Optional<String> diag,
            Optional<String> noFixtureReason, Optional<String> notYetReachedReason, int line) {
    }

    /** What a fixture's outcome tag (§4.1) declares about it. */
    public enum Outcome {
        ACCEPT, REJECT, EQUIV, FRAGMENT
    }

    /**
     * One fixture: a fenced JSON block demonstrating a rule.
     *
     * @param ruleId    the rule it demonstrates
     * @param outcome   what the fixture claims (§4.1)
     * @param diag      the diagnostic it expects, present only when {@code outcome} is {@link Outcome#REJECT}
     * @param equivSlug the group it compiles equal to, present only when {@code outcome} is {@link Outcome#EQUIV}
     * @param name      the {@code name=} slug, if the fixture is addressed directly rather than by ordinal
     * @param json      the fixture body, unparsed
     * @param file      the document it appears in, relative to {@code docs/format}
     * @param line      the 1-indexed line of its opening fence
     */
    public record Fixture(String ruleId, Outcome outcome, Optional<String> diag, Optional<String> equivSlug,
            Optional<String> name, String json, String file, int line) {
    }
}
