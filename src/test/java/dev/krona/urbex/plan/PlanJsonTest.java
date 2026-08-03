package dev.krona.urbex.plan;

import dev.krona.urbex.plan.terrain.FlatTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanJsonTest {

    private static final PlanParams P = PlanParams.defaults();

    @Test
    void jsonIsBalancedAndCountsEveryEntry() {
        CityPlan plan = Planner.plan(1337L, new Settlement(SettlementClass.TOWN, 0, 0), new FlatTerrain(64), P);
        String json = PlanJson.toJson(plan);

        assertBalanced(json);
        assertEquals(plan.roads().nodes().size(), countObjects(section(json, "nodes")));
        assertEquals(plan.roads().edges().size(), countObjects(section(json, "edges")));
        assertEquals(plan.blocks().size(), countObjects(section(json, "blocks")));
        assertEquals(plan.lots().size(), countObjects(section(json, "lots")));

        // A TOWN on flat ground has real blocks and lots, so this exercises the non-empty case; the
        // block-free case is covered separately below.
        assertTrue(plan.blocks().size() > 0, "expected the TOWN fixture to have blocks");
        assertTrue(plan.lots().size() > 0, "expected the TOWN fixture to have lots");
    }

    /**
     * A hamlet or village's plan has no blocks at all (see {@code SettlementClass#usesSpine}) - the
     * schema has to treat that as a normal, valid document ({@code "blocks":[]}), not a special case
     * the viewer or a consumer has to guard against.
     */
    @Test
    void blockFreePlanStillProducesValidJson() {
        CityPlan plan = Planner.plan(1337L, new Settlement(SettlementClass.VILLAGE, 0, 0), new FlatTerrain(64), P);
        assertEquals(0, plan.blocks().size(), "expected the VILLAGE fixture to have no blocks");

        String json = PlanJson.toJson(plan);
        assertBalanced(json);
        assertTrue(json.contains("\"blocks\":[]"), "expected an explicit empty blocks array, got: " + json);
        assertEquals(plan.roads().nodes().size(), countObjects(section(json, "nodes")));
        assertEquals(plan.roads().edges().size(), countObjects(section(json, "edges")));
        assertEquals(0, countObjects(section(json, "blocks")));
        assertEquals(plan.lots().size(), countObjects(section(json, "lots")));
        assertTrue(plan.lots().size() > 0, "expected the VILLAGE fixture to still have roadside lots");
    }

    private static void assertBalanced(String json) {
        assertEquals(count(json, '{'), count(json, '}'), "unbalanced braces in: " + json);
        assertEquals(count(json, '['), count(json, ']'), "unbalanced brackets in: " + json);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * Extracts the array value of {@code "key":[...]} at the top level, tracking bracket depth so
     * nested arrays (the block ring's {@code [[x,z],...]}) don't confuse where the top-level array
     * ends.
     */
    private static String section(String json, String key) {
        String marker = "\"" + key + "\":[";
        int start = json.indexOf(marker);
        assertTrue(start >= 0, "no \"" + key + "\" section in: " + json);
        int i = start + marker.length() - 1; // at the opening '['
        int depth = 0;
        int end = -1;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        assertTrue(end >= 0, "unterminated \"" + key + "\" array in: " + json);
        return json.substring(start + marker.length(), end);
    }

    /**
     * Counts top-level objects in an extracted array body by counting {@code '{'} at bracket depth 0
     * relative to the body itself. Every element this module ever emits (node, edge, block, lot) is
     * exactly one {@code '{'} at that depth - a block's nested {@code "ring"} contributes only
     * {@code '['}/{@code ']'}, never a brace - so this is exact, not an approximation.
     */
    private static int countObjects(String arrayBody) {
        int depth = 0;
        int count = 0;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '{' && depth == 0) {
                count++;
            }
        }
        return count;
    }
}
