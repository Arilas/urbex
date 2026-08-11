package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
