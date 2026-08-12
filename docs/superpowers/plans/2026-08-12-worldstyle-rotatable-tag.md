# World Style `rotatable` Tag Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a world style name the block tag that decides which blocks rotate with their part, instead of every world style sharing the hardcoded `urbex:rotatable`.

**Architecture:** A new optional `rotatable` field on `WorldStyleRE`, holding a `#namespace:path` block tag reference. `WorldStyle` folds it along the `extends` chain exactly as `outsidestyle` is folded, defaulting to `urbex:rotatable` when nothing in the chain declares one. `CityGenerator.transformBlockState` reads the resolved `TagKey<Block>` from the active world style rather than the constant.

**Tech Stack:** Java 25, Minecraft 26.2, Fabric Loom 1.17.17, Mojang DataFixerUpper codecs, JUnit 5.

## Global Constraints

- Minecraft `26.2`, Fabric Loader `0.19.3`, Java `25` — from `gradle.properties`; do not change them.
- **`digest.golden` and `digest-features.golden` must be byte-identical after this change.** They are the evidence that stock generation is untouched. If either moves, the change is wrong — do not accept a new golden.
- The field is **optional**. No existing asset, in this repo or any datapack, may change meaning.
- Every datapack reference names its namespace. A bare id is a load error, never a `minecraft:` default — see `DataTools.fromName`.
- Work on branch `feature/worldstyle-rotatable-tag`, never on `main`.
- Commit messages: imperative mood, lowercase after the type prefix, as in `git log`.

---

### Task 1: `rotatable` decodes, validates, and resolves along the chain

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/DataTools.java` (add `BLOCK_TAG_CODEC`)
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/regassets/WorldStyleRE.java`
- Modify: `src/main/java/dev/krona/urbex/worldgen/lost/cityassets/WorldStyle.java`
- Modify: `src/main/java/dev/krona/urbex/gui/NullDimensionInfo.java:164` (constructor call site)
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RequiredAfterResolutionTest.java:289`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/WiringRequiredTest.java:262`
- Modify: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RegistryChainResolutionTest.java:338,345`
- Test: `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/WorldStyleRotatableTagTest.java` (create)

**Interfaces:**
- Consumes: `DataTools.fromName(String)`, `Resolved.require`, `TestWiring.partSelector()`.
- Produces: `DataTools.BLOCK_TAG_CODEC` (a `Codec<TagKey<Block>>`), and `WorldStyle.getRotatableTag()` returning a non-null `TagKey<Block>`. Task 2 calls `getRotatableTag()`.

**Why not `TagKey.hashedCodec(Registries.BLOCK)`:** it exists and decodes the same `#ns:path` shape, but it parses the remainder with `Identifier.read`, which resolves a bare `#rotatable` to `minecraft:rotatable` instead of erroring. That is the exact defaulting `DataTools.STRICT_IDENTIFIER_CODEC` was written to prevent. Put this reasoning in the javadoc — the next person will reach for `hashedCodec`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/WorldStyleRotatableTagTest.java`:

```java
package dev.krona.urbex.worldgen.lost.cityassets;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world style may name the block tag that decides what rotates with its part.
 * <p>
 * The default is what matters most here: every asset written before this field existed, and every
 * asset that simply does not care, must keep resolving to {@code urbex:rotatable}. The field is
 * how a pack says "these blocks are rotatable <em>in my world style</em>" without shipping a file
 * in Urbex's namespace, which is a merge into every other style whether it wants it or not.
 */
class WorldStyleRotatableTagTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static TagKey<Block> tag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(namespace, path));
    }

    private static WorldStyleRE worldStyle(String name, Optional<TagKey<Block>> rotatable) {
        return new WorldStyleRE(Optional.empty(), Optional.of("urbex:outside"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(TestWiring.partSelector()),
                Optional.of(new Mergeable<>(true,
                        List.of(new CityStyleSelector(1.0f, "urbex:citystyle_common", null)))),
                Optional.empty(), rotatable)
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", name));
    }

    @Test
    void aChainDeclaringNothingResolvesToUrbexRotatable() {
        WorldStyle resolved = new WorldStyle(List.of(worldStyle("standard", Optional.empty())));

        assertEquals(tag("urbex", "rotatable"), resolved.getRotatableTag(),
                "every asset written before this field existed must keep the old behaviour");
    }

    @Test
    void whatTheChildDeclaresWins() {
        WorldStyle resolved = new WorldStyle(List.of(
                worldStyle("standard", Optional.empty()),
                worldStyle("zombie", Optional.of(tag("urbexza", "rotatable")))));

        assertEquals(tag("urbexza", "rotatable"), resolved.getRotatableTag());
    }

    @Test
    void aChildThatOmitsItInheritsRatherThanResettingToTheDefault() {
        WorldStyle resolved = new WorldStyle(List.of(
                worldStyle("standard", Optional.of(tag("urbexza", "rotatable"))),
                worldStyle("child", Optional.empty())));

        assertEquals(tag("urbexza", "rotatable"), resolved.getRotatableTag(),
                "absence means inherit, not revert");
    }

    @Test
    void aReferenceWithoutTheHashIsALoadError() {
        var result = WorldStyleRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"outsidestyle": "urbex:outside", "rotatable": "urbexza:rotatable"}
                """));

        assertTrue(result.isError(), "a tag reference is written with a leading '#'");
        assertTrue(result.error().orElseThrow().message().contains("#"),
                "the message should show the shape it wanted: " + result.error().orElseThrow().message());
    }

    @Test
    void anUnqualifiedReferenceIsALoadErrorRatherThanAMinecraftDefault() {
        var result = WorldStyleRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"outsidestyle": "urbex:outside", "rotatable": "#rotatable"}
                """));

        assertTrue(result.isError(),
                "TagKey.hashedCodec would silently make this minecraft:rotatable; we do not");
        assertTrue(result.error().orElseThrow().message().contains("rotatable"),
                result.error().orElseThrow().message());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*WorldStyleRotatableTagTest*'`
Expected: FAIL to compile — `WorldStyleRE` has no nine-argument constructor and `WorldStyle` has no `getRotatableTag()`.

- [ ] **Step 3: Add `BLOCK_TAG_CODEC` to `DataTools`**

Append to `DataTools`, after `STRICT_IDENTIFIER_CODEC`. Add imports for `net.minecraft.core.registries.Registries`, `net.minecraft.tags.TagKey`, `net.minecraft.world.level.block.Block`:

```java
    /**
     * Strict codec for a field that always names a block tag, written with the leading {@code #}
     * every other tag reference in the format uses ({@code biomes.if_any}, a {@code stuff}
     * matcher). Requiring the {@code #} rather than accepting a bare identifier is deliberate: a
     * field that only ever takes a tag would otherwise invite a block id, which would decode
     * cleanly and match nothing.
     * <p>
     * Not {@link TagKey#hashedCodec}, which decodes the same shape but parses the remainder with
     * {@code Identifier.read} - so {@code "#rotatable"} becomes {@code minecraft:rotatable} instead
     * of failing. Going through {@link #fromName} instead makes an unqualified tag reference the
     * same load error, with the same message, as any other unqualified reference.
     */
    public static final Codec<TagKey<Block>> BLOCK_TAG_CODEC = Codec.STRING.comapFlatMap(
            s -> {
                if (!s.startsWith("#")) {
                    return DataResult.error(() -> "Block tag reference '" + s
                            + "' must start with '#', e.g. '#" + Urbex.MODID + ":rotatable'");
                }
                try {
                    return DataResult.success(
                            TagKey.create(Registries.BLOCK, fromName(s.substring(1))));
                } catch (RuntimeException e) {
                    return DataResult.error(e::getMessage);
                }
            },
            tag -> "#" + tag.location());
```

- [ ] **Step 4: Add the field to `WorldStyleRE`**

Add imports for `net.minecraft.tags.TagKey` and `net.minecraft.world.level.block.Block`.

In the `RAW` codec group, append one line after the `citybiomemultipliers` entry (keep the trailing `)` on the group as it is):

```java
                    DataTools.BLOCK_TAG_CODEC.optionalFieldOf("rotatable").forGetter(l -> Optional.ofNullable(l.rotatable))
```

Add the field beside `outsideStyle`:

```java
    // Null means "not declared here", so the chain reads it from an ancestor; a chain that
    // declares none at all falls back to urbex:rotatable in WorldStyle.
    private final TagKey<Block> rotatable;
```

Append the parameter to the constructor and assign it:

```java
                        Optional<Mergeable<CityBiomeMultiplier>> cityBiomeMultipliers,
                        Optional<TagKey<Block>> rotatable) {
        ...
        this.rotatable = rotatable.orElse(null);
```

Add the getter beside `getOutsideStyle()`:

```java
    @Nullable
    public TagKey<Block> getRotatable() {
        return rotatable;
    }
```

- [ ] **Step 5: Fold it in `WorldStyle`**

Add imports for `net.minecraft.tags.TagKey`, `net.minecraft.world.level.block.Block` and `dev.krona.urbex.worldgen.UrbexTags`.

Add the field beside `outsideStyle`:

```java
    private final TagKey<Block> rotatableTag;
```

In the constructor, declare the accumulator beside `String outside = null;`:

```java
        TagKey<Block> rotatable = null;
```

Inside the `for` loop over `chainRootFirst`, beside the `getOutsideStyle()` block:

```java
            if (object.getRotatable() != null) {
                rotatable = object.getRotatable();
            }
```

After the loop, beside `this.outsideStyle = ...`. **Not** `Resolved.require` — this field is optional and has a default, which is the whole point:

```java
        // Optional, unlike outsidestyle: a chain that declares none keeps the behaviour every
        // world style had before the field existed.
        this.rotatableTag = rotatable == null ? UrbexTags.ROTATABLE_TAG : rotatable;
```

Add the getter beside `getOutsideStyle()`:

```java
    /** The block tag deciding what rotates with its part. Never null; defaults to {@code urbex:rotatable}. */
    public TagKey<Block> getRotatableTag() {
        return rotatableTag;
    }
```

- [ ] **Step 6: Update the five constructor call sites**

Each gains one trailing `Optional.empty()` argument. They are:

- `src/main/java/dev/krona/urbex/gui/NullDimensionInfo.java` — `placeholderStyle()`
- `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RequiredAfterResolutionTest.java` — the `worldStyle(...)` helper
- `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/WiringRequiredTest.java` — the `worldStyle(...)` helper
- `src/test/java/dev/krona/urbex/worldgen/lost/cityassets/RegistryChainResolutionTest.java` — both `parent` and `child` in `worldStyleChildInheritsTheSelectorsAndSettingsItDoesNotDeclare`

Compile-driven: `./gradlew compileJava compileTestJava` names every one that is still short an argument.

- [ ] **Step 7: Run the new test and the whole asset suite**

Run: `./gradlew test --tests '*WorldStyleRotatableTagTest*' --tests '*RequiredAfterResolutionTest*' --tests '*RegistryChainResolutionTest*' --tests '*WiringRequiredTest*' --tests '*UrbexDataCodecTest*'`
Expected: PASS, all five classes.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/krona/urbex/worldgen/lost/regassets/data/DataTools.java src/main/java/dev/krona/urbex/worldgen/lost/regassets/WorldStyleRE.java src/main/java/dev/krona/urbex/worldgen/lost/cityassets/WorldStyle.java src/main/java/dev/krona/urbex/gui/NullDimensionInfo.java src/test/java/dev/krona/urbex/worldgen/lost/cityassets/
git commit -m "feat: let a world style name its own rotatable block tag

Optional, defaulting to urbex:rotatable, so nothing that exists changes
meaning. Without it a pack can only widen the set by shipping a file in
Urbex's namespace, which merges into every other world style too."
```

---

### Task 2: Generation reads the resolved tag

**Files:**
- Modify: `src/main/java/dev/krona/urbex/worldgen/CityGenerator.java:2055-2058`
- Test: `digest.golden` and `digest-features.golden` (unchanged — that is the test)

**Interfaces:**
- Consumes: `WorldStyle.getRotatableTag()` from Task 1, and the existing `provider.getWorldStyle()` (already used at `CityGenerator.java:442`).
- Produces: nothing new. This is the last task that touches Java.

- [ ] **Step 1: Record the goldens before touching anything**

```bash
cp digest.golden /tmp/digest.golden.before
cp digest-features.golden /tmp/digest-features.golden.before
```

- [ ] **Step 2: Replace the constant with the world style's tag**

In `transformBlockState`, change the single condition:

```java
    private BlockState transformBlockState(Transform transform, BlockState b) {
        if (Tools.hasTag(b.getBlock(), rotatableTag())) {
```

and add, next to `transformBlockState`:

```java
    /**
     * The block tag deciding what rotates with its part, from the active world style.
     * <p>
     * Resolved once and cached: this is read for every block of every part placed at a transform
     * other than {@code ROTATE_NONE}, and the world style cannot change under a running generator.
     * A world style that declares nothing resolves to {@code urbex:rotatable}, which is what this
     * method returned unconditionally before world styles could name their own.
     */
    private TagKey<Block> rotatableTag() {
        if (cachedRotatableTag == null) {
            cachedRotatableTag = provider.getWorldStyle().getRotatableTag();
        }
        return cachedRotatableTag;
    }
```

with the field beside the generator's other cached lookups:

```java
    private TagKey<Block> cachedRotatableTag;
```

Add imports for `net.minecraft.tags.TagKey` and `net.minecraft.world.level.block.Block` if the file does not already have them.

- [ ] **Step 3: Run the unit suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 4: Run both digest checks**

Run: `./gradlew digestCheck digestCheckFeatures`
Expected: PASS. Both compare against the committed goldens, and both world styles they exercise declare no `rotatable`, so an identical digest is the proof the default path is untouched.

- [ ] **Step 5: Verify the goldens did not move**

```bash
diff /tmp/digest.golden.before digest.golden && diff /tmp/digest-features.golden.before digest-features.golden && echo "goldens unchanged"
```

Expected: `goldens unchanged`. **If either differs, stop.** A moved golden means the default path changed, which this task is specifically not allowed to do. Do not accept the new value; find out why generation moved.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/krona/urbex/worldgen/CityGenerator.java
git commit -m "feat: rotate blocks by the world style's tag, not the constant

Both digest goldens are unchanged: the styles they exercise declare no
rotatable, so they still resolve urbex:rotatable and place the same blocks."
```

---

### Task 3: Document the field

**Files:**
- Modify: `docs/datapacks.md:37` (the `worldstyles` registry row) and the world style example under "A working example of each registry"
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the field name and shape from Task 1. Nothing consumes this task.

- [ ] **Step 1: Add the field to the registry table**

`docs/datapacks.md:37`, the `worldstyles` row — append to its "Key fields" cell so it reads:

```
`outsidestyle`, `citystyles`, `parts.highways` (all six), `parts.railways` (all sixteen), `rotatable` (optional)
```

- [ ] **Step 2: Document what it does**

Add a subsection after "### Absence means inherit" (`docs/datapacks.md:126`):

````markdown
### `rotatable`: which blocks turn with their part

A part placed at a rotation only turns the blocks named by a block tag; anything else keeps the
facing its palette entry authored. That tag is `urbex:rotatable` unless the world style names
another:

```json
{
  "outsidestyle": "urbex:outside",
  "rotatable": "#mypack:rotatable"
}
```

Written with the leading `#`, like every other tag reference, and fully qualified — `#rotatable`
is a load error rather than `minecraft:rotatable`.

**Declaring it replaces, it does not merge.** To keep Urbex's own set, name it from your tag:

```json
{ "values": ["#urbex:rotatable", "#minecraft:trapdoors", "minecraft:ladder"] }
```

The alternative to this field is shipping `data/urbex/tags/block/rotatable.json`, which merges into
`urbex:rotatable` itself and so changes *every* world style including `urbex:standard`, whether or
not the player selected yours.
````

- [ ] **Step 3: Add the changelog entry**

Add to `CHANGELOG.md` under the current unreleased heading, matching the surrounding entry style:

```markdown
- World styles may name their own `rotatable` block tag. Optional, defaulting to `urbex:rotatable`,
  so nothing that exists changes. Previously the only way to widen the rotatable set was a file in
  Urbex's namespace, which merged into every world style at once.
```

- [ ] **Step 4: Check the docs still match the code**

Run: `./gradlew test --tests '*DatapackGuideExamplesTest*' --tests '*DatapackReferenceIntegrityTest*'`
Expected: PASS. `DatapackGuideExamplesTest` parses the JSON examples in `docs/datapacks.md`, so a malformed snippet fails here.

- [ ] **Step 5: Commit**

```bash
git add docs/datapacks.md CHANGELOG.md
git commit -m "docs: describe the world style rotatable tag and why it replaces"
```

---

### Task 4: Publish the branch and file the issue

**Files:** none — this task only pushes and files.

- [ ] **Step 1: Confirm the full check suite is green**

Run: `./gradlew check digestCheck digestCheckFeatures`
Expected: PASS. Do not open the PR on a red run.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin feature/worldstyle-rotatable-tag
```

- [ ] **Step 3: Open the pull request**

```bash
gh pr create --repo Arilas/urbex --base main --head feature/worldstyle-rotatable-tag \
  --title "feat: let a world style name its own rotatable block tag" \
  --body "$(cat <<'BODY'
A world style may now declare `rotatable`, naming the block tag that decides which blocks rotate
with their part. Optional; a chain that declares none resolves `urbex:rotatable`, exactly as before.

Declaring it replaces rather than merges — a pack that wants Urbex's own set writes
`"#urbex:rotatable"` into its own tag's values, so the composition is visible in data.

Without this, the only way for a pack to widen the rotatable set is to ship
`data/urbex/tags/block/rotatable.json`, which merges into `urbex:rotatable` itself and therefore
changes generation in *every* world style, including `urbex:standard`, whether or not the player
selected the pack's style.

`digest.golden` and `digest-features.golden` are unchanged. Both styles they exercise declare no
`rotatable`, so an identical digest is the evidence the default path is untouched.
BODY
)"
```

- [ ] **Step 4: File the separate tag-contents issue**

This is a distinct defect from the feature above, and it is not fixed by it — stock Urbex hits it with no datapack involved.

```bash
gh issue create --repo Arilas/urbex \
  --title "urbex:rotatable names only #minecraft:stairs, so Urbex's own ladders and trapdoor mis-face under rotation" \
  --body "$(cat <<'BODY'
`data/urbex/tags/block/rotatable.json` names `#minecraft:stairs` and nothing else, but
`CityGenerator.transformBlockState` applies the mirror/rotate to any block in that tag. Banners,
trapdoors and ladders are exactly as directional as stairs.

Urbex's own assets place three directional states the tag does not cover:

| block state | file |
| --- | --- |
| `minecraft:ladder[facing=north]` | `data/urbex/urbex/palettes/common.json` |
| `minecraft:ladder[facing=east]` | `data/urbex/urbex/palettes/oilrig.json` |
| `minecraft:iron_trapdoor[facing=east,half=bottom,open=false,powered=false]` | `data/urbex/urbex/palettes/oilrig.json` |

So stock generation places mis-facing ladders and a mis-facing trapdoor in every rotated copy of
the parts holding them, with no datapack involved.

Suggested fix: widen the generated tag to `#minecraft:stairs`, `#minecraft:trapdoors`,
`#minecraft:banners`, `minecraft:ladder`.

Note this is independent of the world-style `rotatable` field: that lets a pack choose a different
tag, it does not change what Urbex's own tag contains.
BODY
)"
```

- [ ] **Step 5: Report both URLs**

Print the PR and issue URLs. The port's `PORTING-NOTES.md` links to them.

---

## Self-review

**Spec coverage** (§3 of the port's design doc): `WorldStyleRE` field — Task 1 step 4. `WorldStyle` chain fold with `urbex:rotatable` default — Task 1 step 5. `CityGenerator` resolving once — Task 2 step 2. Replace-not-merge — Task 1 (no merge code) and documented in Task 3 step 2. Digest goldens unchanged — Task 2 steps 1, 4, 5. `docs/schema` — **not applicable**: `docs/schema/` holds only `preset.schema.json`, there is no world style schema to update, so the spec's mention of it is satisfied by `docs/datapacks.md` alone. Issue on `Arilas/urbex` — Task 4 step 4.

**Placeholder scan:** no TBD/TODO; every code step shows the code; every run step names the command and the expected result.

**Type consistency:** `DataTools.BLOCK_TAG_CODEC` is `Codec<TagKey<Block>>` (Task 1 step 3), consumed by `WorldStyleRE`'s `optionalFieldOf("rotatable")` as `Optional<TagKey<Block>>` (step 4), stored `@Nullable TagKey<Block>` and read by `getRotatable()` (step 4), folded into non-null `TagKey<Block> rotatableTag` read by `getRotatableTag()` (step 5), consumed by `CityGenerator.rotatableTag()` returning `TagKey<Block>` (Task 2). Consistent throughout.
