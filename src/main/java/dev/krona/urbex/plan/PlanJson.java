package dev.krona.urbex.plan;

import dev.krona.urbex.plan.block.CityBlock;
import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.lot.Lot;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;
import dev.krona.urbex.plan.road.RoadNode;
import dev.krona.urbex.plan.terrain.CliffTerrain;
import dev.krona.urbex.plan.terrain.CoastTerrain;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.HillTerrain;
import dev.krona.urbex.plan.terrain.MeanderTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hand-rolled JSON for a {@link CityPlan}, so the viewer can look at a plan without this module
 * taking a dependency for the privilege - see {@code PurityTest}. Every string this writes is an
 * enum constant, so nothing here needs a general escaper: there is no free text, and enum names are
 * ASCII identifiers by construction.
 * <p>
 * A block-free plan (a hamlet or village - see {@link SettlementClass#usesSpine()}) serialises the
 * same way as any other: {@code "blocks":[]}. The viewer has to treat that as the normal shape of a
 * small settlement, not as a malformed document, so nothing here special-cases it either.
 */
public final class PlanJson {

    private PlanJson() {
    }

    public static String toJson(CityPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');

        appendSettlement(sb, plan.settlement());
        sb.append(',');
        appendNodes(sb, plan.roads());
        sb.append(',');
        appendEdges(sb, plan.roads());
        sb.append(',');
        appendBlocks(sb, plan.blocks(), plan.districts());
        sb.append(',');
        appendLots(sb, plan.lots());

        sb.append('}');
        return sb.toString();
    }

    private static void appendSettlement(StringBuilder sb, Settlement s) {
        sb.append("\"settlement\":{");
        sb.append("\"class\":\"").append(s.cls().name()).append("\",");
        sb.append("\"centerChunkX\":").append(s.centerChunkX()).append(',');
        sb.append("\"centerChunkZ\":").append(s.centerChunkZ()).append(',');
        sb.append("\"radiusBlocks\":").append(s.radiusBlocks());
        sb.append('}');
    }

    private static void appendNodes(StringBuilder sb, RoadGraph roads) {
        sb.append("\"nodes\":[");
        boolean first = true;
        for (RoadNode n : roads.nodes()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(n.id())
                    .append(",\"x\":").append(n.pos().x())
                    .append(",\"z\":").append(n.pos().z())
                    .append('}');
        }
        sb.append(']');
    }

    private static void appendEdges(StringBuilder sb, RoadGraph roads) {
        sb.append("\"edges\":[");
        boolean first = true;
        for (RoadEdge e : roads.edges()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"from\":").append(e.fromId())
                    .append(",\"to\":").append(e.toId())
                    .append(",\"class\":\"").append(e.cls().name()).append('"')
                    .append(",\"bridge\":").append(e.bridge())
                    .append(",\"waterSpanBlocks\":").append(e.waterSpanBlocks())
                    .append('}');
        }
        sb.append(']');
    }

    private static void appendBlocks(StringBuilder sb, List<CityBlock> blocks,
                                      Map<Integer, District> districts) {
        sb.append("\"blocks\":[");
        boolean first = true;
        for (CityBlock b : blocks) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            District d = districts.get(b.id());
            sb.append("{\"id\":").append(b.id())
                    .append(",\"district\":\"").append(d.name()).append('"')
                    .append(",\"ring\":[");
            boolean firstPoint = true;
            for (Vec2 v : b.outline().ring()) {
                if (!firstPoint) {
                    sb.append(',');
                }
                firstPoint = false;
                sb.append('[').append(v.x()).append(',').append(v.z()).append(']');
            }
            sb.append(']');
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendLots(StringBuilder sb, List<Lot> lots) {
        sb.append("\"lots\":[");
        boolean first = true;
        for (Lot l : lots) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(l.id())
                    .append(",\"minX\":").append(l.footprint().minX())
                    .append(",\"minZ\":").append(l.footprint().minZ())
                    .append(",\"maxX\":").append(l.footprint().maxX())
                    .append(",\"maxZ\":").append(l.footprint().maxZ())
                    .append(",\"district\":\"").append(l.district().name()).append('"')
                    .append(",\"sizeClass\":").append(l.sizeClass())
                    .append(",\"ground\":").append(l.groundHeight())
                    .append(",\"frontingEdge\":").append(l.frontingEdgeIndex())
                    .append(",\"waterSides\":").append(l.waterSides())
                    .append(",\"waterShape\":\"").append(l.waterShape().name()).append('"')
                    .append('}');
        }
        sb.append(']');
    }

    /**
     * Dumps one plan to a JSON file for the viewer, without a test run. Development tooling only -
     * the matching {@code runPlanDump} Gradle task is kept out of the {@code jar}/{@code build}
     * lifecycle in {@code build.gradle}, and nothing under {@code src/main} calls this at runtime.
     *
     * <pre>
     * ./gradlew -q runPlanDump --args="1337 TOWN viewer/plan-1337-town.json"
     * </pre>
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            System.err.println("usage: PlanJson <seed> <settlementClass> <outputPath> [terrain]");
            System.err.println("  terrain: flat (default), hill, river, meander, coast or cliff");
            System.exit(1);
            return;
        }
        long seed = Long.parseLong(args[0]);
        SettlementClass cls = SettlementClass.valueOf(args[1].toUpperCase(Locale.ROOT));
        Path out = Path.of(args[2]);

        Settlement settlement = new Settlement(cls, 0, 0);
        PlanParams params = PlanParams.defaults();
        TerrainSampler terrain = terrainFor(args.length > 3 ? args[3] : "flat");

        CityPlan plan = Planner.plan(seed, settlement, terrain, params);
        String json = toJson(plan);

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.out.println("wrote " + out + " (" + json.length() + " bytes): "
                + plan.roads().nodes().size() + " nodes, " + plan.roads().edges().size() + " edges, "
                + plan.blocks().size() + " blocks, " + plan.lots().size() + " lots");
    }

    /** A small fixed menu of the fake terrains, picked by name, for the dump tool's optional 4th argument. */
    private static TerrainSampler terrainFor(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "flat" -> new FlatTerrain(64);
            case "hill" -> new HillTerrain(64, 96, 128);
            case "river" -> new RiverTerrain(64, 0, 24);
            case "meander" -> new MeanderTerrain(64, 0, 40.0, 55.0, 24);
            case "coast" -> new CoastTerrain(64, 64);
            case "cliff" -> new CliffTerrain(64, 20, 0);
            default -> throw new IllegalArgumentException("unknown terrain: " + name
                    + " (expected flat, hill, river, meander, coast or cliff)");
        };
    }
}
