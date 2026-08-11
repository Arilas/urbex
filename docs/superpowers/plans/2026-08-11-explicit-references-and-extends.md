# Explicit References and Asset Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every Urbex datapack registry one `extends` key with one set of merge rules, resolved once at construction, and make every asset reference explicit so nothing resolves that no file wrote.

**Architecture:** A single pure chain resolver (generalised from the existing `Presets.resolve`) walks `extends` links root-first and hands the chain to the runtime asset's constructor, which applies each link in order. Runtime assets become immutable and fully resolved before generation starts, deleting `CityStyle`'s lazy-init machinery. Merge behaviour follows the *shape* of each field — scalars take the child's value, ordered lists are replaced unless the child opts into `{"replace": false}`, and palettes merge per character.

**Tech Stack:** Java 25, Fabric Loader, Minecraft 26.2, Mojang DataFixerUpper codecs (`RecordCodecBuilder`, `Codec.either`), JUnit 5, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-11-explicit-references-and-extends-design.md`

## Global Constraints

- **Branch base:** this work sits on top of `fix/citystyle-selector-inherit` (commit `1993627c`), which already made a city style's declared selector lists replace rather than append. Rebase `feature/explicit-references-and-extends` onto it before Task 1. Task 3 generalises the `CityStyle.Sel` / `declared` mechanism that commit introduced; do not re-derive it.
- **Worldgen output must not move.** `./gradlew runDigestCheck` must print `DRIVERDIGEST=414cb71424d5e53f` and `./gradlew runDigestCheckFeatures` must print `DRIVERDIGEST=c8267f7b4abfd44e`, both with `unsafeReads=0`, and `digest.golden` / `digest-features.golden` must stay untracked-clean in `git status`. **A shifted digest is a bug in the task, not a golden to regenerate.** If one moves, stop and diagnose.
- **Clean break, no compatibility shims.** Urbex has not shipped. `inherit` and `parent` are deleted, not aliased. No migration code, no deprecation window.
- **Every new user-visible behaviour change gets a `CHANGELOG.md` entry** under `## Unreleased`, in the established style: bold lead sentence, then what changed, why, and what a datapack author must do.
- **No `lostcit*` identifiers** in `src/main`. This fork removed the last one deliberately.
- Run `./gradlew test` at the end of every task. It must be green before committing.

---

### Task 1: Generic extends-chain resolution, applied to city styles

Replaces `CityStyleRE.inherit` with `extends`, and moves resolution out of `CityStyle.init()` into construction. This is the load-bearing task: every later task reuses `ExtendsChain`.

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/Extendable.java`
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/ExtendsChain.java`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/ExtendsChainTest.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/CityStyleRE.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/CityStyle.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/RegistryAssetRegistry.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetRegistries.java`
- Modify: `src/main/resources/data/urbex/urbex/citystyles/citystyle_common.json`, `citystyle_standard.json`, `citystyle_desert.json`, `citystyle_border.json`
- Modify: `src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java:62`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: `Extendable { Optional<Identifier> getExtends(); }` implemented by every `*RE`.
- Produces: `ExtendsChain.resolve(Identifier id, Function<Identifier, R> lookup)` returning `List<R>` **root-first** (index 0 is the furthest ancestor, last element is `id` itself), throwing `IllegalStateException` on a cycle or an unknown id.
- Produces: `RegistryAssetRegistry<T, R>` constructed with `Function<List<R>, T>` (root-first chain) instead of `Function<R, T>`.
- Produces: `CityStyle(List<CityStyleRE> chainRootFirst)`; `CityStyle.init(CommonLevelAccessor)` no longer exists.
- Consumes: `CityStyle.Sel`, `CityStyle.selectorList(Sel)`, `CityStyle.inheritSelectors(CityStyle)` from commit `1993627c`.

- [ ] **Step 1: Write the failing resolver test**

Create `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/ExtendsChainTest.java`:

```java
package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendsChainTest {

    /** Minimal stand-in for a registry entry: an id and an optional parent id. */
    private record Node(String id, String parent) {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("urbex", path);
    }

    /** Builds an id -> parent-id graph. A null parent means the entry is a chain root. */
    private static Map<String, String> graph(String... idThenParentPairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < idThenParentPairs.length; i += 2) {
            m.put(idThenParentPairs[i], idThenParentPairs[i + 1]);
        }
        return m;
    }

    private static List<String> resolveIds(Map<String, String> graph, String start) {
        return ExtendsChain.resolve(
                        id(start),
                        key -> graph.containsKey(key.getPath())
                                ? new Node(key.getPath(), graph.get(key.getPath()))
                                : null,
                        node -> Optional.ofNullable(node.parent()).map(ExtendsChainTest::id))
                .stream().map(Node::id).toList();
    }

    @Test
    void chainIsReturnedRootFirst() {
        Map<String, String> graph = graph("border", "common", "common", "config", "config", null);

        assertEquals(List.of("config", "common", "border"), resolveIds(graph, "border"),
                "the furthest ancestor is applied first, the requested asset last");
    }

    @Test
    void assetWithoutExtendsResolvesToItselfAlone() {
        assertEquals(List.of("config"), resolveIds(graph("config", null, "other", null), "config"));
    }

    @Test
    void cycleIsAnErrorNamingThePath() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolveIds(graph("a", "b", "b", "a"), "a"));
        assertTrue(e.getMessage().contains("urbex:a") && e.getMessage().contains("urbex:b"),
                "the message must name the chain so the author can find it: " + e.getMessage());
    }

    @Test
    void danglingExtendsIsAnErrorNamingBothEnds() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolveIds(graph("child", "nope"), "child"));
        assertTrue(e.getMessage().contains("urbex:nope") && e.getMessage().contains("urbex:child"),
                "the message must name the missing id and who referenced it: " + e.getMessage());
    }
}
```

Imports for this file: `net.minecraft.resources.Identifier`, `org.junit.jupiter.api.Test`, `java.util.HashMap`, `java.util.List`, `java.util.Map`, `java.util.Optional`, and static `assertEquals` / `assertThrows` / `assertTrue`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*ExtendsChainTest*'`
Expected: FAIL — `ExtendsChain` does not exist (compilation error).

- [ ] **Step 3: Write `Extendable` and `ExtendsChain`**

Create `src/main/java/dev/krona/urbex/worldgen/lost/regassets/Extendable.java`:

```java
package dev.krona.urbex.worldgen.lost.regassets;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * A registry entry that can build on another entry of the same registry via its {@code extends}
 * field. Resolution is {@link dev.krona.urbex.worldgen.lost.cityassets.ExtendsChain}'s job; this
 * interface only exposes the link.
 */
public interface Extendable {
    Optional<Identifier> getExtends();
}
```

Create `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/ExtendsChain.java`:

```java
package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Walks an {@code extends} chain and returns it root-first, so a runtime asset can apply each link
 * in order and let each descendant overwrite what its ancestors set.
 * <p>
 * Pure: it takes a lookup function rather than a registry, so it is testable without a level. This
 * generalises the resolution {@code Presets.resolve} did for preset {@code parent} chains alone.
 */
public class ExtendsChain {

    private ExtendsChain() {
    }

    /**
     * @param id        the asset being resolved
     * @param lookup    resolves an id to a registry entry, or null if the registry has no such entry
     * @param extendsOf reads an entry's {@code extends} link
     * @return the chain root-first: index 0 is the furthest ancestor, the last element is {@code id}
     * @throws IllegalStateException if the chain cycles, or a link names an id {@code lookup} does
     *                               not know
     */
    public static <R> List<R> resolve(Identifier id, Function<Identifier, R> lookup,
                                      Function<R, Optional<Identifier>> extendsOf) {
        List<R> chain = new ArrayList<>();       // leaf..root
        Set<Identifier> seen = new LinkedHashSet<>();
        Identifier cur = id;
        while (cur != null) {
            if (!seen.add(cur)) {
                String path = seen.stream().map(Identifier::toString).collect(Collectors.joining(" -> "));
                throw new IllegalStateException("'extends' cycle: " + path + " -> " + cur);
            }
            R entry = lookup.apply(cur);
            if (entry == null) {
                throw new IllegalStateException(
                        "Unknown asset '" + cur + "' (referenced from '" + id + "')");
            }
            chain.add(entry);
            cur = extendsOf.apply(entry).orElse(null);
        }
        java.util.Collections.reverse(chain);    // root..leaf
        return chain;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*ExtendsChainTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit the resolver**

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/regassets/Extendable.java \
        src/main/java/dev/krona/urbex/worldgen/lost/cityassets/ExtendsChain.java \
        src/test/java/dev/krona/urbex/worldgen/lost/cityassets/ExtendsChainTest.java
git commit -m "feat: pure extends-chain resolver, root-first with cycle detection"
```

- [ ] **Step 6: Switch `CityStyleRE` from `inherit` to `extends`**

In `CityStyleRE.java`, change the class declaration to `implements IAsset<CityStyleRE>, Extendable`, change the field `private final String inherit;` to `private final Optional<Identifier> extendsId;`, replace the codec line

```java
Codec.STRING.optionalFieldOf("inherit").forGetter(l -> Optional.ofNullable(l.inherit)),
```

with

```java
Identifier.CODEC.optionalFieldOf("extends").forGetter(CityStyleRE::getExtends),
```

update the constructor parameter from `Optional<String> inherit` to `Optional<Identifier> extendsId` and assign `this.extendsId = extendsId;`, delete `getInherit()`, and add:

```java
    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }
```

- [ ] **Step 7: Make `CityStyle` build from a chain**

In `CityStyle.java`:

Delete the fields `private final String inherit;`, `private volatile boolean initialized = false;` and `private static final ThreadLocal<Set<CityStyle>> RESOLVING`. Delete the whole `init(CommonLevelAccessor level)` method including its Javadoc, and the now-unused `CommonLevelAccessor` import.

Rename the existing single-entry constructor to a private `applyFrom(CityStyleRE object)` holding the body it has today, and add:

```java
    /**
     * Builds a fully resolved style from its {@code extends} chain, root first: each entry
     * overwrites what its ancestors set, and selector lists an entry declares replace the inherited
     * ones (see {@link #inheritSelectors}). Nothing mutates after this returns, so worldgen worker
     * threads share one immutable instance with no locking.
     */
    public CityStyle(List<CityStyleRE> chainRootFirst) {
        CityStyleRE leaf = chainRootFirst.get(chainRootFirst.size() - 1);
        name = leaf.getRegistryName();
        stuffTags.add("all");
        for (CityStyleRE re : chainRootFirst) {
            applyFrom(re);
        }
    }
```

`applyFrom` must stop assigning `name` and stop adding `"all"` to `stuffTags` (the constructor above does both once), and every scalar assignment in it becomes conditional on the incoming value being non-null, so a later chain entry that omits a field does not blank what an earlier one set. For selector lists, replace the `declare(...)` calls with: clear the list first when this entry declares that kind, then add. Concretely, change `declare` to:

```java
    /** A declared list replaces whatever an ancestor put there; an undeclared one leaves it alone. */
    private void declare(Sel kind, Optional<List<ObjectSelector>> values) {
        values.ifPresent(v -> {
            declared.add(kind);
            List<ObjectSelector> target = selectorList(kind);
            target.clear();
            target.addAll(v);
        });
    }
```

`inheritSelectors` is now unused by production code but stays: it is what `CityStyleInheritSelectorsTest` exercises. If that reads as dead code, instead delete `inheritSelectors` and rewrite `CityStyleInheritSelectorsTest` to build a two-entry chain (`new CityStyle(List.of(parentRe, childRe))`) and assert the same four behaviours. Prefer the rewrite.

- [ ] **Step 8: Make `RegistryAssetRegistry` chain-aware**

In `RegistryAssetRegistry.java`, change the field and constructor:

```java
    private final Function<List<R>, T> assetConstructor;

    public RegistryAssetRegistry(ResourceKey<Registry<R>> registryKey, Function<List<R>, T> assetConstructor) {
        this.registryKey = registryKey;
        this.assetConstructor = assetConstructor;
    }
```

In both `get(...)` overloads, replace `t = assetConstructor.apply(value);` with a chain resolve. For the `CommonLevelAccessor` overload:

```java
                Registry<R> registry = level.registryAccess().lookupOrThrow(registryKey);
                List<R> chain = ExtendsChain.resolve(name,
                        key -> {
                            R entry = registry.getValue(ResourceKey.create(registryKey, key));
                            if (entry instanceof IAsset asset) {
                                asset.setRegistryName(key);
                            }
                            return entry;
                        },
                        entry -> entry instanceof Extendable ext ? ext.getExtends() : Optional.empty());
                t = assetConstructor.apply(chain);
```

Apply the same shape to the `RegistryAccess` overload, and to `loadAll`. Delete the `if (t instanceof CityStyle cs) { cs.init(level); }` block and the Javadoc paragraph on the `RegistryAccess` overload that says CityStyle is unsupported through that path — it now is supported.

- [ ] **Step 9: Update every `AssetRegistries` constructor reference**

Each of the twelve entries changes from a method reference on a single RE to one on a list. For assets that do not yet support `extends` (everything except `CityStyle` until Task 4), adapt with a lambda taking the leaf:

```java
    public static final RegistryAssetRegistry<CityStyle, CityStyleRE> CITYSTYLES =
            new RegistryAssetRegistry<>(CustomRegistries.CITYSTYLES_REGISTRY_KEY, CityStyle::new);
    public static final RegistryAssetRegistry<Variant, VariantRE> VARIANTS =
            new RegistryAssetRegistry<>(CustomRegistries.VARIANTS_REGISTRY_KEY, chain -> new Variant(chain.get(chain.size() - 1)));
```

Apply the `chain.get(chain.size() - 1)` form to `VARIANTS`, `CONDITIONS`, `WORLDSTYLES`, `PARTS`, `BUILDINGS`, `MULTI_BUILDINGS`, `STYLES`, `PALETTES`, `SCATTERED`, `PREDEFINED_CITIES` and `STUFF`. Task 4 replaces each in turn.

- [ ] **Step 10: Rename the key in the four bundled city styles**

In `citystyle_common.json`, `citystyle_standard.json`, `citystyle_desert.json` and `citystyle_border.json`, rename the `"inherit"` key to `"extends"`. Values are already fully qualified (`"urbex:citystyle_common"`, `"urbex:citystyle_config"`); leave them.

- [ ] **Step 11: Update the integrity test's citystyle case**

In `DatapackReferenceIntegrityTest.java:62`, change `ref(src, d.get("inherit"), "citystyles");` to `ref(src, d.get("extends"), "citystyles");`.

- [ ] **Step 12: Run the full suite**

Run: `./gradlew test`
Expected: PASS. `CityStyleInheritSelectorsTest`, `ExtendsChainTest` and `DatapackReferenceIntegrityTest` all green.

- [ ] **Step 13: Verify worldgen did not move**

Run: `./gradlew runDigestCheck` — expect `URBEX-DIGEST-CHECK: OK DRIVERDIGEST=414cb71424d5e53f`, `unsafeReads=0`.
Run: `./gradlew runDigestCheckFeatures` — expect `URBEX-DIGEST-CHECK: OK DRIVERDIGEST=c8267f7b4abfd44e`, `unsafeReads=0`.
Run: `git status --short digest.golden digest-features.golden` — expect no output.

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "feat!: city styles resolve 'extends' at construction, not lazily

CityStyleRE.inherit becomes extends, typed as an Identifier. CityStyle is
built from its whole chain root-first and never mutates afterwards, so
init(), the volatile initialized flag, the synchronized block and the
ThreadLocal cycle guard are all gone. A cycle is now a load error naming the
chain rather than a silently half-built style."
```

---

### Task 2: `extends` for presets

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/PresetRE.java`
- Modify: `src/main/java/dev/krona/urbex/config/Presets.java`
- Modify: all eleven of `src/main/resources/data/urbex/urbex/presets/*.json` except `default.json`
- Modify: `docs/schema/preset.schema.json`
- Modify: `docs/presets.md`
- Modify: `src/test/java/dev/krona/urbex/config/PresetResolutionTest.java`, `PresetCodecTest.java`, `PresetSchemaTest.java`, `ShippedPresetsTest.java`, `PresetRoundTripTest.java`

**Interfaces:**
- Consumes: `ExtendsChain.resolve` from Task 1.
- Produces: `PresetRE implements Extendable`; `PresetRE.parent()` is replaced by `getExtends()`.

- [ ] **Step 1: Update the failing tests first**

In `PresetResolutionTest.java`, rename every `parent` usage to `extends` and every `PresetRE::parent` to `PresetRE::getExtends`. Add:

```java
    @Test
    void danglingExtendsNamesBothEnds() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Presets.resolve(Identifier.fromNamespaceAndPath("urbex", "child"),
                        id -> id.getPath().equals("child")
                                ? presetWithExtends(Identifier.fromNamespaceAndPath("urbex", "nope"))
                                : null));
        assertTrue(e.getMessage().contains("urbex:nope"));
        assertTrue(e.getMessage().contains("urbex:child"));
    }
```

where `presetWithExtends` builds a `PresetRE` with only the `extends` field set and `Optional.empty()` for the other fifteen constructor arguments.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*PresetResolutionTest*'`
Expected: FAIL — `getExtends` is not defined on `PresetRE`.

- [ ] **Step 3: Rename in `PresetRE`**

Change `KEYS` to start `Set.of("extends", "description", ...)`, change the codec line

```java
Identifier.CODEC.optionalFieldOf("parent").forGetter(PresetRE::parent),
```

to

```java
Identifier.CODEC.optionalFieldOf("extends").forGetter(PresetRE::getExtends),
```

rename the record component / field and accessor `parent()` to `getExtends()`, and declare `implements IAsset<PresetRE>, Extendable` with `@Override` on the accessor.

- [ ] **Step 4: Delegate `Presets.resolve` to `ExtendsChain`**

Replace the body of `Presets.resolve(Identifier, Function)` with:

```java
    public static Preset resolve(Identifier id, Function<Identifier, PresetRE> lookup) {
        List<PresetRE> chain = ExtendsChain.resolve(id, lookup, PresetRE::getExtends);
        Preset p = new Preset(id);
        for (PresetRE re : chain) {
            re.applyTo(p);
        }
        return p;
    }
```

Delete the now-unused imports (`LinkedHashSet`, `Set`, `Collectors`, `ArrayList` if unreferenced). The loop is forward now, not reversed, because `ExtendsChain` already returns root-first.

- [ ] **Step 5: Rename the key in the eleven bundled presets**

In `ancient.json`, `atlantis.json`, `cavern.json`, `floating.json`, `largecities.json`, `nodamage.json`, `onlycities.json`, `rarecities.json`, `safe.json`, `tallbuildings.json` and `wasteland.json`, rename `"parent": "urbex:default"` to `"extends": "urbex:default"`. `default.json` has no such key.

- [ ] **Step 6: Update schema and docs**

In `docs/schema/preset.schema.json`, rename the `parent` property to `extends`, keeping its type and description but rewording to "Asset this preset builds on. Fully qualified, e.g. `urbex:default`."

In `docs/presets.md`, replace every occurrence of `parent` with `extends` — including the prose in "Resolution rules" (which says "follows `parent` links"), the minimal-example datapack JSON, and the sentence "every shipped preset parents `urbex:default`".

- [ ] **Step 7: Run the suite**

Run: `./gradlew test`
Expected: PASS. `PresetSchemaTest` proves the schema and codec field sets still agree; `ShippedPresetsTest` proves all twelve still resolve.

- [ ] **Step 8: Verify worldgen did not move**

Run: `./gradlew runDigestCheck` and `./gradlew runDigestCheckFeatures`; expect `414cb71424d5e53f` and `c8267f7b4abfd44e`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat!: presets use 'extends', resolved through the shared chain walker

Presets.resolve delegates to ExtendsChain, so preset parents and city style
inheritance now fail identically on a cycle or an unknown id."
```

---

### Task 3: Mergeable list codec and the append opt-in

Adds `{"replace": false, "values": [...]}` alongside a plain array wherever a list can be inherited, and generalises the per-kind `declared` tracking from commit `1993627c` so it is not city-style-specific.

**Files:**
- Create: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/Mergeable.java`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/regassets/data/MergeableListCodecTest.java`
- Modify: `src/main/java/dev/krona/urbex/varia/Tools.java:123`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/Selectors.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/CityStyle.java`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/CityStyleInheritSelectorsTest.java`
- Modify: `docs/schema/preset.schema.json`

**Interfaces:**
- Produces: `record Mergeable<E>(boolean replace, List<E> values)` with `Mergeable.codec(Codec<E>)` accepting either a bare array (`replace = true`) or `{"replace": bool, "values": [...]}`.
- Produces: `Tools.mergeableListOrString(String fieldName, Function<T, Mergeable<String>> getter)` for `StreetParts`-style string lists.
- Produces: `Mergeable.apply(List<E> target, Mergeable<E> incoming)` — clears `target` first when `incoming.replace()`, then appends `incoming.values()`.

- [ ] **Step 1: Write the failing codec test**

Create `src/test/java/dev/krona/urbex/worldgen/lost/regassets/data/MergeableListCodecTest.java`:

```java
package dev.krona.urbex.worldgen.lost.regassets.data;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeableListCodecTest {

    private static final Codec<Mergeable<String>> CODEC = Mergeable.codec(Codec.STRING);

    private static Mergeable<String> decode(String json) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("decode failed: " + msg));
    }

    @Test
    void bareArrayReplaces() {
        Mergeable<String> m = decode("[\"a\", \"b\"]");
        assertTrue(m.replace(), "a plain array is the whole list");
        assertEquals(List.of("a", "b"), m.values());
    }

    @Test
    void objectFormCanOptIntoAppending() {
        Mergeable<String> m = decode("{\"replace\": false, \"values\": [\"c\"]}");
        assertEquals(false, m.replace());
        assertEquals(List.of("c"), m.values());
    }

    @Test
    void objectFormDefaultsToReplacing() {
        assertTrue(decode("{\"values\": [\"c\"]}").replace(),
                "omitting 'replace' means the same as a bare array");
    }

    @Test
    void applyReplacesOrAppendsAgainstInheritedEntries() {
        List<String> target = new ArrayList<>(List.of("inherited1", "inherited2"));
        Mergeable.apply(target, decode("[\"own\"]"));
        assertEquals(List.of("own"), target);

        List<String> target2 = new ArrayList<>(List.of("inherited1", "inherited2"));
        Mergeable.apply(target2, decode("{\"replace\": false, \"values\": [\"own\"]}"));
        assertEquals(List.of("inherited1", "inherited2", "own"), target2,
                "appended entries follow the parent's, so parent order is stable");
    }

    @Test
    void explicitlyEmptyArrayMeansEmpty() {
        List<String> target = new ArrayList<>(List.of("inherited"));
        Mergeable.apply(target, decode("[]"));
        assertTrue(target.isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*MergeableListCodecTest*'`
Expected: FAIL — `Mergeable` does not exist.

- [ ] **Step 3: Implement `Mergeable`**

Create `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/Mergeable.java`:

```java
package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * A list field that can either replace what an ancestor in the {@code extends} chain put there, or
 * be appended to it.
 * <p>
 * A bare JSON array replaces - that is the rule an author gets without asking for anything. The
 * object form opts into appending, mirroring the shape of vanilla tag files:
 * <pre>{ "replace": false, "values": [ ... ] }</pre>
 */
public record Mergeable<E>(boolean replace, List<E> values) {

    public static <E> Codec<Mergeable<E>> codec(Codec<E> element) {
        Codec<Mergeable<E>> object = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("replace", true).forGetter(Mergeable::replace),
                element.listOf().fieldOf("values").forGetter(Mergeable::values)
        ).apply(instance, Mergeable::new));

        return Codec.either(element.listOf(), object).xmap(
                either -> either.map(list -> new Mergeable<>(true, list), o -> o),
                m -> m.replace() ? Either.left(m.values()) : Either.right(m));
    }

    /** Applies this onto {@code target}, which already holds whatever the chain inherited. */
    public static <E> void apply(List<E> target, Mergeable<E> incoming) {
        if (incoming.replace()) {
            target.clear();
        }
        target.addAll(incoming.values());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*MergeableListCodecTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Use it for city style selectors**

In `Selectors.java`, change all nine fields and constructor parameters from `Optional<List<ObjectSelector>>` to `Optional<Mergeable<ObjectSelector>>`, and each codec entry from

```java
Codec.list(ObjectSelector.CODEC).optionalFieldOf("buildings").forGetter(...)
```

to

```java
Mergeable.codec(ObjectSelector.CODEC).optionalFieldOf("buildings").forGetter(...)
```

In `CityStyle.declare`, replace the clear-then-add body with `Mergeable.apply(selectorList(kind), v);` and keep the `declared.add(kind)` line — `declared` still records that the file mentioned this kind at all, which the reachability check in Task 6 uses.

- [ ] **Step 6: Extend `CityStyleInheritSelectorsTest` for appending**

Add to that test class:

```java
    @Test
    void appendModeAddsToTheInheritedEntriesInParentOrder() {
        CityStyle parent = style(sels("b1", "b2"), null, null);
        CityStyle child = styleAppendingBuildings(sels("b3"));

        CityStyle resolved = new CityStyle(List.of(parentRe(sels("b1", "b2")), childReAppending(sels("b3"))));

        assertEquals(List.of("b1", "b2", "b3"), values(resolved, CityStyle.Sel.BUILDING));
    }
```

Build `childReAppending` with `Optional.of(new Mergeable<>(false, sels("b3")))` for the buildings selector, and `parentRe` with `Optional.of(new Mergeable<>(true, ...))`. Adapt the existing helpers in that file from `List<ObjectSelector>` to `Mergeable<ObjectSelector>` accordingly; `style(...)` becomes a wrapper that passes `new Mergeable<>(true, list)`.

- [ ] **Step 7: Run the suite and the digests**

Run: `./gradlew test`, then `./gradlew runDigestCheck` and `./gradlew runDigestCheckFeatures`.
Expected: PASS; digests `414cb71424d5e53f` and `c8267f7b4abfd44e`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: list fields can opt into appending with {replace: false}

A bare array replaces what the extends chain inherited, which is the rule an
author gets by default; the tag-shaped object form opts into appending, with
appended entries following the parent's so parent order stays stable."
```

---

### Task 4: `extends` for the remaining eleven registries

**Files:**
- Modify: `BuildingRE.java`, `BuildingPartRE.java`, `PaletteRE.java`, `StyleRE.java`, `MultiBuildingRE.java`, `ScatteredRE.java`, `ConditionRE.java`, `VariantRE.java`, `StuffSettingsRE.java`, `PredefinedCityRE.java`, `WorldStyleRE.java` (all under `src/main/java/dev/krona/urbex/worldgen/lost/regassets/`)
- Modify: the matching runtime classes under `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/AssetRegistries.java`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/PaletteExtendsTest.java`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/BuildingPartExtendsTest.java`
- Modify: `src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java`

**Interfaces:**
- Consumes: `ExtendsChain.resolve`, `Extendable`, `Mergeable` from Tasks 1 and 3.
- Produces: every `*RE` implements `Extendable`; every runtime asset constructor takes `List<R> chainRootFirst`.
- Produces: `Palette(List<PaletteRE> chainRootFirst)` merging entries keyed by `PaletteEntry.getChar()`, later entries winning.
- Produces: `BuildingPart(List<BuildingPartRE> chainRootFirst)` inheriting `slices`, `xsize` and `zsize` from the nearest ancestor that declares them.

- [ ] **Step 1: Write the failing palette and part tests**

Create `PaletteExtendsTest.java` asserting that a child palette declaring only character `'S'` keeps every other character from its parent, and that the child's `'S'` wins:

```java
package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaletteExtendsTest {

    @Test
    void childOverridesOnlyTheCharactersItDeclares() {
        PaletteRE parent = new PaletteRE(List.of(
                entry('S', "minecraft:stone"),
                entry('b', "minecraft:bricks"),
                entry('g', "minecraft:glass")));
        PaletteRE child = new PaletteRE(List.of(entry('S', "minecraft:deepslate")));

        Palette resolved = new Palette(List.of(parent, child));

        assertEquals("minecraft:deepslate", blockOf(resolved, 'S'), "the child's character wins");
        assertEquals("minecraft:bricks", blockOf(resolved, 'b'), "characters it never mentions survive");
        assertEquals("minecraft:glass", blockOf(resolved, 'g'));
    }
}
```

Fill in `entry(char, String)` using `PaletteEntry`'s public constructor and `blockOf(Palette, char)` using whatever accessor `Palette` exposes for its entries — read `Palette.java` and `PaletteEntry.java` before writing these two helpers, and use the real signatures rather than inventing them.

Create `BuildingPartExtendsTest.java` asserting that a part declaring only `refpalette` keeps its parent's slices and dimensions, and that declaring an `xsize` inconsistent with inherited slices throws.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*ExtendsTest*'`
Expected: FAIL — the list-taking constructors do not exist.

- [ ] **Step 3: Add `extends` to the eleven RE codecs**

For each RE listed in **Files**, add `implements Extendable`, add the field `private final Optional<Identifier> extendsId;`, add the codec entry as the **first** group member:

```java
Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
```

add the matching leading constructor parameter, and add the `getExtends()` accessor. This is mechanical; do all eleven in one pass.

- [ ] **Step 4: Make `BuildingPartRE`'s inherited fields optional**

`xsize`, `zsize` and `slices` are currently `fieldOf` (required). Change them to `optionalFieldOf`, and have `BuildingPart(List<BuildingPartRE>)` take each from the last chain entry that declares it. A part whose whole chain never declares `slices` is a load error naming the part:

```java
        if (slices == null) {
            throw new IllegalStateException("Part '" + leaf.getRegistryName() + "' declares no slices, "
                    + "and neither does anything it extends");
        }
        if (declaredXSize != null && slices.get(0).length() != declaredXSize) {
            throw new IllegalStateException("Part '" + leaf.getRegistryName() + "' declares xsize "
                    + declaredXSize + " but its inherited slices are " + slices.get(0).length() + " wide");
        }
```

- [ ] **Step 5: Convert the runtime constructors**

Change each runtime asset constructor to take `List<R> chainRootFirst` and apply entries in order, mirroring `CityStyle` from Task 1: scalars take the value of the last entry that declares one, list fields go through `Mergeable.apply`, and `Palette` merges by character into a `LinkedHashMap<Character, PaletteEntry>` so later entries overwrite earlier ones at the same key.

- [ ] **Step 6: Drop the leaf-adapting lambdas in `AssetRegistries`**

Every entry returns to a plain method reference, e.g. `Variant::new`, `Palette::new`, `BuildingPart::new` — each now taking the chain.

- [ ] **Step 7: Check `extends` in the integrity test for every category**

In `DatapackReferenceIntegrityTest.checkFile`, before the `switch`, add:

```java
        ref(src, d.get("extends"), category);
```

so an `extends` in any category is checked to be namespaced and to resolve within that same registry directory.

- [ ] **Step 8: Run the suite and the digests**

Run: `./gradlew test`, then both digest tasks.
Expected: PASS; `414cb71424d5e53f` and `c8267f7b4abfd44e`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: extends on all thirteen registries

Palettes merge per character so a child repaints two of thirty and keeps the
rest; a part inherits its ancestor's slices and dimensions, so extending a
building and swapping its palette is a two-line file."
```

---

### Task 5: Unqualified references become load errors

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/DataTools.java:30`
- Modify: `src/main/resources/data/urbex/urbex/presets/largecities.json`
- Modify: `src/test/java/dev/krona/urbex/data/DatapackReferenceIntegrityTest.java`
- Create: `src/test/java/dev/krona/urbex/worldgen/lost/regassets/data/DataToolsStrictNameTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: `DataTools.fromName(String)` throws `IllegalArgumentException` for a string without `:`.

- [ ] **Step 1: Write the failing test**

Create `DataToolsStrictNameTest.java`:

```java
package dev.krona.urbex.worldgen.lost.regassets.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataToolsStrictNameTest {

    @Test
    void qualifiedNamesParse() {
        assertEquals("urbex:street_straight", DataTools.fromName("urbex:street_straight").toString());
        assertEquals("urbexmt:street_straight", DataTools.fromName("urbexmt:street_straight").toString());
    }

    @Test
    void unqualifiedNameIsRejectedAndSuggestsTheFix() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataTools.fromName("street_straight"));
        assertTrue(e.getMessage().contains("street_straight"),
                "the message must name the offending string: " + e.getMessage());
        assertTrue(e.getMessage().contains("urbex:street_straight"),
                "and show what it should have looked like: " + e.getMessage());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*DataToolsStrictNameTest*'`
Expected: FAIL — `fromName("street_straight")` returns `urbex:street_straight` instead of throwing.

- [ ] **Step 3: Make `fromName` strict**

```java
    public static Identifier fromName(String name) {
        if (!name.contains(":")) {
            throw new IllegalArgumentException("Unqualified datapack reference '" + name
                    + "': references must name their namespace, e.g. '" + Urbex.MODID + ":" + name + "'");
        }
        return Identifier.parse(name);
    }
```

- [ ] **Step 4: Qualify the last bundled reference**

In `presets/largecities.json`, change `"cityStyleAlternative": "citystyle_border"` to `"cityStyleAlternative": "urbex:citystyle_border"`.

- [ ] **Step 5: Close the integrity test's coverage gap**

In `DatapackReferenceIntegrityTest.checkFile`, add a `presets` case and turn the fall-through into a failure:

```java
            case "presets" -> {
                ref(src, d.get("extends"), "presets");
                JsonObject cities = asObject(d.get("cities"));
                if (cities != null) {
                    ref(src, cities.get("cityStyleAlternative"), "citystyles");
                }
            }
            case "variants" -> { /* only palette-entry refs, handled below */ }
            default -> problems.add(file + ": category '" + category
                    + "' has no reference checks; add a case to this switch");
```

- [ ] **Step 6: Run the suite**

Run: `./gradlew test`
Expected: PASS. If `DatapackReferenceIntegrityTest` reports further unqualified references, qualify them in the datapack — do not weaken the test.

- [ ] **Step 7: Run the digests**

Run both digest tasks; expect `414cb71424d5e53f` and `c8267f7b4abfd44e`.

- [ ] **Step 8: Add the changelog entry and commit**

Add under `## Unreleased`, matching the file's established style: a bold lead sentence saying unqualified references are now a load error, the reasoning (a reference that no file wrote is unfindable when it misbehaves), the fact that `presets` was never covered by the integrity test and `largecities.json` carried the last one, and what a third-party pack author must do (qualify everything; bare names no longer default to `urbex:`).

```bash
git add -A
git commit -m "feat!: unqualified datapack references are a load error

The integrity test switched on category and let 'presets' fall through
unchecked, which is how largecities.json kept the pack's last bare name. The
default arm is now a failure, so a new registry cannot silently skip coverage."
```

---

### Task 6: Delete the thirty wiring defaults

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/StreetParts.java`, `HighwayParts.java`, `RailwayParts.java`, `PartSelector.java`
- Modify: `src/main/java/dev/krona/urbex/varia/Tools.java:123`
- Modify: `src/main/resources/data/urbex/urbex/citystyles/citystyle_common.json`, `src/main/resources/data/urbex/urbex/worldstyles/standard.json`
- Create: `src/test/java/dev/krona/urbex/data/NoAssetReferenceDefaultsTest.java`
- Create: `src/test/java/dev/krona/urbex/data/WorldStyleCompletenessTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `Mergeable` and `Mergeable.apply` from Task 3.
- Produces: `Tools.listOrStringList(String fieldName, Function<T, Mergeable<String>> getter)` — the `defaultVal` parameter is gone, the field is required, and the codec accepts a bare string, a bare array, or `{"replace": false, "values": [...]}`.
- Produces: `StreetParts.merge(StreetParts base, StreetParts incoming)` and the equivalent on `HighwayParts` and `RailwayParts`, folding an incoming instance onto an accumulated one per field.

- [ ] **Step 1: Write the guard test**

Create `NoAssetReferenceDefaultsTest.java`, which reads the four source files and fails if any `listOrStringList` call still passes a literal default:

```java
package dev.krona.urbex.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No asset reference may have a code-side default: a reference no datapack file wrote is
 * unfindable when it misbehaves, and it is what let a third-party world style silently inherit
 * Urbex's own wide-road parts for road classes it never mentioned.
 */
class NoAssetReferenceDefaultsTest {

    private static final Path DATA = Path.of("src/main/java/dev/krona/urbex/worldgen/lost/regassets/data");

    /** listOrStringList("field", "some_default", Getter::x) - three arguments means a default. */
    private static final Pattern WITH_DEFAULT =
            Pattern.compile("listOrStringList\\(\\s*\"[^\"]+\"\\s*,\\s*\"[^\"]+\"\\s*,");

    @Test
    void noWiringFieldCarriesADefaultAssetName() throws IOException {
        List<String> problems = new ArrayList<>();
        try (var files = Files.walk(DATA)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    Matcher m = WITH_DEFAULT.matcher(Files.readString(p));
                    while (m.find()) {
                        problems.add(p + ": " + m.group());
                    }
                } catch (IOException e) {
                    problems.add(p + ": unreadable: " + e.getMessage());
                }
            });
        }
        assertTrue(problems.isEmpty(),
                () -> "asset reference fields with code-side defaults:\n" + String.join("\n", problems));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*NoAssetReferenceDefaultsTest*'`
Expected: FAIL, listing 30 matches across `StreetParts.java`, `HighwayParts.java` and `RailwayParts.java`.

- [ ] **Step 3: Make the helper three-arm, and drop the default parameter**

> **Plan amendment (2026-08-11, after the Task 3 review).** Spec §4 line 91 lists
> `streetblocks.parts.straight` as an ordered list whose append is opt-in, and §4.1 line 114 says
> `listOrStringList` "grows a third arm". As originally written, neither Task 3 nor this task
> delivered that — Task 3 wired `Mergeable` into `Selectors` only. Building it here was chosen over
> a Task 3 fix round because this task rewrites all thirty call sites anyway. The ripple is real
> and is Step 3a below: `StreetParts` is currently swapped across a chain as a whole-object scalar,
> so per-field append needs it merged field by field.

In `Tools.java`, replace `listOrStringList` with a `Mergeable`-returning three-arm version — a bare
string, a bare array, or the `{"replace": false, "values": [...]}` object — and no default:

```java
    public static <T> RecordCodecBuilder<T, Mergeable<String>> listOrStringList(
            String fieldName, Function<T, Mergeable<String>> getter) {
        return Codec.either(Codec.STRING, Mergeable.codec(Codec.STRING))
                .fieldOf(fieldName)
                .xmap(either -> either.map(s -> new Mergeable<>(true, List.of(s)), Function.identity()),
                        m -> m.replace() && m.values().size() == 1
                                ? Either.left(m.values().get(0))
                                : Either.right(m))
                .forGetter(getter);
    }
```

`Mergeable.codec` already handles the array-versus-object arms, so this only adds the bare-string
shorthand on top of it. Import `dev.krona.urbex.worldgen.lost.regassets.data.Mergeable`.

- [ ] **Step 3a: Merge part-wiring records field by field**

`StreetParts`, `HighwayParts` and `RailwayParts` become records of `Mergeable<String>` rather than
`List<String>`. Today `CityStyle` swaps a whole `StreetParts` in or out (`streetParts ==
StreetParts.DEFAULT`), which cannot express "append two street variants to the ones I inherit". Give
each of the three records a method that folds an incoming instance onto an accumulated one, applying
`Mergeable.apply` per component:

```java
    /** Applies {@code incoming}'s declared components onto {@code base}, per field. */
    public static StreetParts merge(StreetParts base, StreetParts incoming) {
        return new StreetParts(
                mergeOne(base.straight(), incoming.straight()),
                mergeOne(base.end(), incoming.end()),
                mergeOne(base.bend(), incoming.bend()),
                mergeOne(base.t(), incoming.t()),
                mergeOne(base.none(), incoming.none()),
                mergeOne(base.all(), incoming.all()),
                mergeOne(base.connector(), incoming.connector()),
                mergeOne(base.stair(), incoming.stair()));
    }

    private static Mergeable<String> mergeOne(Mergeable<String> base, Mergeable<String> incoming) {
        if (incoming == null) {
            return base;
        }
        if (base == null || incoming.replace()) {
            return incoming;
        }
        List<String> combined = new ArrayList<>(base.values());
        combined.addAll(incoming.values());
        return new Mergeable<>(true, combined);
    }
```

In `CityStyle.applyFrom`, replace the whole-object swap with `streetParts = StreetParts.merge(streetParts, s.getParts())` and the same for `largeStreetParts` and `tertiaryStreetParts`. Do the equivalent in `WorldStyle` for `PartSelector`'s highway and railway groups.

Consumers that read these components (`CityStyle.getStreetParts()` and callers in `CityGenerator`, `Highways`, `Railways`) want a plain `List<String>`; expose `.values()` at the accessor boundary rather than pushing `Mergeable` into generation code.

- [ ] **Step 3b: Test the append opt-in on a string part list**

Add to `CityStyleInheritSelectorsTest` (or a sibling test class, whichever reads better) a case proving a child city style that declares

```json
"streetblocks": { "parts": { "straight": { "replace": false, "values": ["urbex:street_straight_alt"] } } }
```

resolves to its parent's `straight` entries followed by `urbex:street_straight_alt`, and that a bare array in the same position replaces them instead. Assert with order-sensitive `List.of(...)` equality, not set membership or size.

- [ ] **Step 4: Update all thirty call sites and delete the DEFAULT constants**

Remove the middle argument from every `listOrStringList` call in `StreetParts.java` (8), `HighwayParts.java` (6) and `RailwayParts.java` (16). Delete `StreetParts.DEFAULT`, `HighwayParts.DEFAULT`, `RailwayParts.DEFAULT` and `PartSelector.DEFAULT`, and the `get()` methods that compare against them. In `WorldStyleRE`'s constructor, `partSelector.orElse(PartSelector.DEFAULT)` becomes a required field; in `CityStyle`, the `streetParts == StreetParts.DEFAULT` comparisons are already gone via Step 3a.

Keep `MultiSettings.DEFAULT` and `WorldSettings.DEFAULT` — they hold numbers and enums, not asset references.

- [ ] **Step 5: Declare the wiring in the bundled datapack**

`citystyle_common.json` already declares `parts` and `largeparts`; add the `tertiaryparts` block if the codec now requires it. `worldstyles/standard.json` already declares `highways` and `railways` in full. Run the suite after this step and fix whatever the codec reports as missing — the errors name the field.

- [ ] **Step 6: Add the reachability test**

Create `WorldStyleCompletenessTest.java`, which walks `src/main/resources/data/urbex/urbex/worldstyles/*.json`, follows each `citystyles[].citystyle` reference and each `extends` chain, and asserts that every street/highway/railway wiring field resolves to at least one part id somewhere in the chain. Assert on the *union* over the chain, not on any single file: `citystyle_border` declares no `parts` and correctly takes `citystyle_common`'s.

- [ ] **Step 7: Run the suite and the digests**

Run: `./gradlew test`, then both digest tasks.
Expected: PASS; `414cb71424d5e53f` and `c8267f7b4abfd44e`. This is the task most likely to move worldgen — if a digest shifts, a default that was silently in use has been replaced by a different value, so find it rather than regenerating.

- [ ] **Step 8: Add the changelog entry and commit**

Record that street, highway and railway part wiring is now required rather than defaulted; that a world style or city style which omits it fails at load naming the field instead of silently taking Urbex's own parts; and that this is what stops a third-party pack inheriting `urbex:street_large_*` for road classes it never mentioned.

```bash
git add -A
git commit -m "feat!: street, highway and railway wiring must be declared

Thirty listOrStringList call sites carried a bare default asset name, so a
world style that never mentioned primary roads still generated Urbex's own
wide-road parts. The fields are required now and the DEFAULT constants are
gone; numeric settings keep their defaults."
```

---

### Task 7: Datapack authoring guide

**Files:**
- Create: `docs/datapacks.md`
- Modify: `README.md`
- Modify: `docs/presets.md`

- [ ] **Step 1: Write `docs/datapacks.md`**

Cover, in this order, with a working example for each: the registry directory layout (`data/<namespace>/urbex/<registry>/<name>.json`) and the thirteen registry names; `extends`, including a cross-namespace example (`urbexmt:citystyle_common` extending `urbex:citystyle_common`) and the root-first application order; the three merge shapes from spec §4 as a table, with the `{"replace": false}` opt-in and the note that appended entries follow the parent's; the palette per-character merge with a two-character override example; `extends` plus `refpalette` on a part, using the `urbex:radiotower` repaint as the worked example; the rule that references must be fully qualified and what the load error looks like; the rule that street/highway/railway wiring must be declared somewhere in the chain; and a "common errors" section pairing each load-error message with its fix.

Write it as this repository's other docs are written: prose that explains why, not a field dump. `docs/presets.md` is the model to match for tone and depth.

- [ ] **Step 2: Link it**

Add a line to `README.md` under **Status** pointing at `docs/datapacks.md` for datapack authoring, alongside the existing pointers to `docs/superpowers/specs/` and `docs/superpowers/plans/`.

In `docs/presets.md`, add a line near the top noting that presets follow the same `extends` rules as every other registry, and linking to `docs/datapacks.md`.

- [ ] **Step 3: Verify every example**

Every JSON snippet in `docs/datapacks.md` must be one a real datapack could ship. Check each against the codecs it claims to satisfy — field names, whether a field is required, and whether the id is qualified. A doc example that would fail to load is worse than no example.

- [ ] **Step 4: Commit**

```bash
git add docs/datapacks.md docs/presets.md README.md
git commit -m "docs: datapack authoring guide for extends and explicit references"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §3 `extends` across 13 registries | 1 (citystyles), 2 (presets), 4 (remaining eleven) |
| §3.1 `refpalette` stays and composes | 4 |
| §4 three merge shapes | 3 (ordered lists), 4 (keyed palettes, part slices) |
| §4.1 replace by default, `{"replace": false}` opt-in | 3 |
| §4.3 part slice/size inheritance and contradiction error | 4 |
| §5 resolution at construction, immutable assets | 1 |
| §5.1 deletes `init()`, `RESOLVING`, `initialized`, `synchronized` | 1 |
| §5.3 assets constructed with their worldstyle | 1 (`RegistryAssetRegistry` takes the chain; no dimension-global lookup added) |
| §6.1 unqualified references are load errors | 5 |
| §6.2 thirty wiring defaults deleted | 6 |
| §6.3 numeric settings keep defaults | 6, step 4 |
| §6.4 requiredness applies after resolution | 6, step 6 |
| §7.1 `citystyle_border` bug | already fixed in `1993627c`; Task 3 generalises the mechanism |
| §7.2 rename in bundled pack, qualify `largecities` | 1 (citystyles), 2 (presets), 5 (`largecities`) |
| §7.3 digests must not move | Global Constraints, and a verification step in every task |
| §9.1 integrity test coverage gap | 5 |
| §9.2 new tests | 1, 3, 4, 5, 6 |
| §9.3 schema and docs | 2 (schema, `presets.md`), 7 (`datapacks.md`) |

**Known rough edges to resolve during implementation, not by guessing:**

- Task 4 step 1 needs `PaletteEntry`'s real constructor and `Palette`'s real accessor. Read both files before writing those helpers.
- Task 3 step 6 rewrites helpers in an existing test file; adapt the file that is actually there rather than the sketch here.
- Task 1 step 7 offers two ways to keep `CityStyleInheritSelectorsTest` meaningful. Prefer rewriting it against the chain constructor.
- Task 6 step 5 says to run the suite and fix what the codec reports. That is deliberate: the exact set of newly-required fields depends on choices made in Tasks 3 and 4.
