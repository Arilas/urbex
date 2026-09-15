package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.lost.cityassets.MarkerCapabilities;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoratorTargetTest {

    private record Decorator(String id, String pool, String primary, String alternative, Diag diagnostic) {
        String trait() {
            return "\"" + id + "\":{\"pool\":\"" + pool + "\"}";
        }
    }

    private static final List<Decorator> DECORATORS = List.of(
            new Decorator("urbex:loot", "urbex:chestloot", "minecraft:chest", "minecraft:barrel", Diag.DIAG_028),
            new Decorator("urbex:spawner", "urbex:easymobs", "minecraft:spawner", "minecraft:spawner", Diag.DIAG_029));
    private static final Set<Identifier> POOLS = Set.of(Identifier.parse("urbex:chestloot"),
            Identifier.parse("urbex:easymobs"));

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void capabilitiesMatchTheActualBlockEntityRatherThanAnyBlockEntity() {
        assertTrue(MarkerCapabilities.supportsLoot(Blocks.CHEST.defaultBlockState()));
        assertTrue(MarkerCapabilities.supportsLoot(Blocks.BARREL.defaultBlockState()));
        assertFalse(MarkerCapabilities.supportsLoot(Blocks.CAMPFIRE.defaultBlockState()));
        assertFalse(MarkerCapabilities.supportsLoot(Blocks.SPAWNER.defaultBlockState()));
        assertTrue(MarkerCapabilities.supportsSpawner(Blocks.SPAWNER.defaultBlockState()));
        assertFalse(MarkerCapabilities.supportsSpawner(Blocks.CHEST.defaultBlockState()));
        assertFalse(MarkerCapabilities.supportsSpawner(Blocks.CAMPFIRE.defaultBlockState()));
        assertSame(BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.parse("minecraft:chest")),
                MarkerCapabilities.blockEntityType(Blocks.CHEST.defaultBlockState()));
        assertSame(BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.parse("minecraft:mob_spawner")),
                MarkerCapabilities.blockEntityType(Blocks.SPAWNER.defaultBlockState()));
        assertNull(MarkerCapabilities.blockEntityType(Blocks.STONE.defaultBlockState()));
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    void knownIncompatibleTargetsAreRefusedWithTheBlockAndARemedy() {
        for (Decorator decorator : DECORATORS) {
            for (String block : List.of("minecraft:stone", "minecraft:campfire")) {
                String message = refused(node(block, decorator.trait()), decorator);
                assertTrue(message.contains(block), message);
                assertTrue(message.contains("Remove the trait"), message);
                assertTrue(message.contains(decorator.id()), message);
            }
            accepted(node(decorator.primary(), decorator.trait()));
            accepted(node(decorator.alternative(), decorator.trait()
                    + ",\"urbex:block_entity\":{\"nbt\":{\"Custom\":226}}"));
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    @Rule("MODEL.042")
    void unavailableCrossModTargetsRemainAccepted() {
        for (Decorator decorator : DECORATORS) {
            accepted(node("missing:container", decorator.trait()));
            accepted(optional(decorator, "\"missing:replacement\""));
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    void anIncompatibleWeightedOutcomeIsNotHiddenByASupportedSibling() {
        for (Decorator decorator : DECORATORS) {
            String weighted = """
                    {"kind":"weighted","traits":{%s},"choices":[
                      {"weight":1,"block":"%s"},{"weight":1,"block":"minecraft:stone"}]}
                    """.formatted(decorator.trait(), decorator.primary());
            String message = refused(weighted, decorator);
            assertTrue(message.contains("choice 1"), message);
            assertTrue(message.contains("minecraft:stone"), message);
            accepted(weighted.replace("minecraft:stone", decorator.alternative()));
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    void anIncompatibleTagMemberIsRefusedOnceAgainstTheAuthoredTag() {
        for (Decorator decorator : DECORATORS) {
            TraitContext context = context().withTags(tag -> List.of(decorator.primary(), "minecraft:stone"));
            String node = "{\"kind\":\"tag\",\"tag\":\"#urbex:targets\",\"traits\":{" + decorator.trait() + "}}";
            Diagnostics diagnostics = new Diagnostics();
            assertTrue(compile(node, context, diagnostics).isEmpty());
            List<Diagnostics.Entry> errors = diagnostics.all().stream()
                    .filter(entry -> entry.diag() == decorator.diagnostic()).toList();
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().message().contains("#urbex:targets"));
            accepted(node, context().withTags(tag -> List.of(decorator.primary(), decorator.alternative())));
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    @Rule("TRAIT.096")
    void optionalAndNestedSelectionOutcomesMustSupportTheirInheritedDecorator() {
        for (Decorator decorator : DECORATORS) {
            String message = refused(optional(decorator, "\"minecraft:stone\""), decorator);
            assertTrue(message.contains("urbex:optional.replacement"), message);
            String nested = node(decorator.alternative(),
                    "\"urbex:optional\":{\"density\":\"lootDensity\",\"replacement\":\"minecraft:stone\"}");
            refused(optional(decorator, nested), decorator);
            accepted(optional(decorator, nested.replace("minecraft:stone", decorator.primary())));
            refused(node(decorator.primary(), decorator.trait()
                    + ",\"urbex:optional\":{\"density\":\"lootDensity\"}"), decorator);
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    @Rule("TRAIT.096")
    void inPlaceLightReplacementsAreCheckedEvenWhenTheCrossModLightIsUnavailable() {
        for (Decorator decorator : DECORATORS) {
            String light = node("missing:light", decorator.trait()
                    + ",\"urbex:light\":{\"unlit\":\"minecraft:stone\"}");
            String message = refused(light, decorator);
            assertTrue(message.contains("urbex:light.unlit"), message);
            accepted(light.replace("minecraft:stone", decorator.alternative()));
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    @Rule("TRAIT.006")
    void explicitReplacementDecoratorsValidateTheirOwnNestedSelections() {
        for (Decorator decorator : DECORATORS) {
            String replacement = node(decorator.alternative(), decorator.trait()
                    + ",\"urbex:optional\":{\"density\":\"lootDensity\",\"replacement\":\"minecraft:stone\"}");
            refused(optional(decorator, replacement), decorator);
            accepted(optional(decorator, replacement.replace("minecraft:stone", decorator.primary())));
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    @Rule("TRAIT.007")
    void damageSatellitesValidateOnlyDecoratorsTheyDeclareThemselves() {
        for (Decorator decorator : DECORATORS) {
            accepted(node(decorator.primary(), decorator.trait()
                    + ",\"urbex:damaged\":{\"into\":\"minecraft:stone\"}"));
            String damaged = node("minecraft:stone", "\"urbex:damaged\":{\"into\":%s}");
            String message = refused(damaged.formatted(node("minecraft:campfire", decorator.trait())), decorator);
            assertTrue(message.contains("urbex:damaged.into"), message);
            accepted(damaged.formatted(node(decorator.primary(), decorator.trait())));
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    void socketCandidatesValidateBothDeclaredAndInheritedDecorators() {
        for (Decorator decorator : DECORATORS) {
            String socket = "{\"kind\":\"light_socket\",\"free\":[{\"weight\":1,\"block\":\"%s\",\"traits\":{%s}}]}";
            refused(socket.formatted("minecraft:campfire", decorator.trait()), decorator);
            accepted(socket.formatted(decorator.primary(), decorator.trait()));
            String inherited = """
                    {"kind":"light_socket","traits":{%s},"free":[
                      {"weight":1,"block":"%s"},{"weight":1,"block":"minecraft:campfire"}]}
                    """.formatted(decorator.trait(), decorator.primary());
            String message = refused(inherited, decorator);
            assertTrue(message.contains("candidate 1"), message);
            accepted(inherited.replace("minecraft:campfire", decorator.alternative()));
        }
    }

    @Test
    @Rule("TRAIT.023")
    @Rule("TRAIT.033")
    @Rule("TRAIT.044")
    void excludedReplacementBranchesCannotInvalidateInheritedDecorators() {
        for (Decorator decorator : DECORATORS) {
            String replacement = replacementWithExcludedSelection(decorator.alternative());
            accepted(optional(decorator, replacement));
            refused(optional(decorator, replacement.replace("missing_pack", "urbex")), decorator);
        }
        String nbt = node("minecraft:chest", "\"urbex:block_entity\":{\"nbt\":{\"Custom\":226}},"
                + "\"urbex:optional\":{\"density\":\"lootDensity\",\"replacement\":%s}");
        accepted(nbt.formatted(replacementWithExcludedSelection("minecraft:barrel")));
        Diagnostics diagnostics = new Diagnostics();
        assertTrue(compile(nbt.formatted(replacementWithExcludedSelection("minecraft:barrel")
                .replace("missing_pack", "urbex")), context(), diagnostics).isEmpty());
        assertTrue(diagnostics.all().stream().anyMatch(entry -> entry.diag() == Diag.DIAG_022));
    }

    private static String replacementWithExcludedSelection(String supported) {
        return """
                {"kind":"weighted","choices":[
                  {"weight":1,"block":"%s"},
                  {"weight":1,"block":"%s","when":{"pack":"missing_pack"},"traits":{
                    "urbex:optional":{"density":"lootDensity","replacement":"minecraft:stone"}}}]}
                """.formatted(supported, supported);
    }

    private static String node(String block, String traits) {
        return "{\"block\":\"" + block + "\",\"traits\":{" + traits + "}}";
    }

    private static String optional(Decorator decorator, String replacement) {
        return node(decorator.primary(), decorator.trait()
                + ",\"urbex:optional\":{\"density\":\"lootDensity\",\"replacement\":" + replacement + "}");
    }

    private static TraitContext context() {
        return TraitContext.withConditions(BuiltInRegistries.BLOCK, POOLS);
    }

    private static Optional<CompiledV2Palette> compile(String node, TraitContext context, Diagnostics diagnostics) {
        return CompiledV2Palette.compile(TraitTest.resolve("{\"version\":2,\"palette\":{\"X\":" + node + "}}"),
                TraitTest.installed(), context, "urbex:decorator_test", diagnostics);
    }

    private static void accepted(String node) {
        accepted(node, context());
    }

    private static void accepted(String node, TraitContext context) {
        Diagnostics diagnostics = new Diagnostics();
        assertTrue(compile(node, context, diagnostics).isPresent(), () -> diagnostics.asError().orElse("?"));
    }

    private static String refused(String node, Decorator decorator) {
        Diagnostics diagnostics = new Diagnostics();
        assertTrue(compile(node, context(), diagnostics).isEmpty(), node);
        String message = diagnostics.asError().orElseThrow();
        assertTrue(decorator.diagnostic().matches(message), message);
        assertTrue(message.contains("urbex:decorator_test"), message);
        return message;
    }
}
