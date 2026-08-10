package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.plan.Hash;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.plan.grid.GridPurpose;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Carries a primary road over a short stretch of open water.
 * <p>
 * This deliberately lives in {@code worldgen.lost} rather than in {@code plan}: unlike the road
 * field it must ask the world questions - is this chunk city, is it water, how high is its terrain -
 * and the plan module may not see Minecraft. The raw mathematical road field stays independent of
 * city and terrain.
 * <p>
 * Every answer is addressed by the <em>span</em>, never by the chunk asking. A span is identified by
 * its two endpoint chunks, sorted, so a scan started from either end - or from any chunk in between -
 * reconstructs the same identity, takes the same roll, and reaches the same verdict without any
 * shared state between chunks. That is what lets a bridge be generated one chunk at a time, in any
 * order, by any thread.
 */
public final class PrimaryBridgePlanner {

    private PrimaryBridgePlanner() {
    }

    /**
     * A planned primary bridge. {@code from} is the lower endpoint along {@link #orientation} and
     * {@code to} the higher one; both are city chunks where the primary road renders, and every
     * chunk strictly between them carries the deck.
     */
    public record BridgeSpan(Orientation orientation, int fromX, int fromZ, int toX, int toZ) {
    }

    /** How one chunk looks to a bridge scan. */
    enum ChunkRole {
        /** Open water the road can be carried over: not city, water-like, on the raw primary field. */
        GAP,
        /** A city chunk where the primary road actually renders, at the base city level. */
        ENDPOINT,
        /** Anything else. A scan that reaches one of these has found no bridge. */
        OTHER
    }

    /**
     * The world facts a scan needs, one chunk at a time. An interface rather than direct provider
     * calls so the scan can be exercised against a hand-written map of chunks; each method is only
     * called when the classification actually reaches it, so a live world pays for a biome lookup
     * only on the chunks that got that far.
     */
    interface ChunkFacts {
        /** Whether the raw road field - before any city clipping - puts a primary road here. */
        boolean isRawPrimary(ChunkCoord coord);

        boolean isCity(ChunkCoord coord);

        /** Whether the primary road survives the city clip here, and so actually renders. */
        boolean isEffectivePrimary(ChunkCoord coord);

        int cityLevel(ChunkCoord coord);

        /** An ocean, river or beach biome, or terrain that falls below the water level. */
        boolean isWaterLike(ChunkCoord coord);
    }

    /**
     * The span claiming {@code coord}, if any. Empty for every chunk that is not open water on a
     * primary line, which is nearly all of them, and the first test is the cheap one.
     */
    public static Optional<BridgeSpan> spanAt(ChunkCoord coord, IDimensionInfo provider) {
        // Dimension-wide, not per-chunk: a span's length limit and acceptance chance must be the
        // same number for every chunk in it, and BuildingInfo.getProfile can hand back the inside
        // or the outside profile depending on where the chunk falls in a city-sphere world.
        UrbexProfile profile = provider.getProfile();
        float chance = profile.PLANNED_PRIMARY_BRIDGE_CHANCE;
        int maxGapLength = profile.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH;
        if (chance <= 0.0f) {
            return Optional.empty();
        }
        ChunkFacts facts = worldFacts(provider);
        if (!isGap(coord, facts)) {
            return Optional.empty();
        }
        long seed = provider.getSeed();
        BridgeSpan horizontal = acceptedSpan(coord, Orientation.X, facts, seed, maxGapLength, chance);
        BridgeSpan vertical = acceptedSpan(coord, Orientation.Z, facts, seed, maxGapLength, chance);
        // At most one of these can survive: each one's own chunk is a crossing the other contests,
        // and winsCrossing is total. Checking horizontal first is therefore not a tie-break.
        if (horizontal != null && survivesCrossings(horizontal, coord, facts, seed, maxGapLength, chance)) {
            return Optional.of(horizontal);
        }
        if (vertical != null && survivesCrossings(vertical, coord, facts, seed, maxGapLength, chance)) {
            return Optional.of(vertical);
        }
        return Optional.empty();
    }

    /**
     * The deck the whole span renders. Addressed at the span's lower endpoint, so every chunk in the
     * span draws the same part from a style that offers several. The stream is its own - a fresh
     * {@link Rng} address rather than a draw from the chunk's layout random - so selecting it cannot
     * shift what any other decision sees.
     */
    @Nullable
    public static BuildingPart deckPart(BridgeSpan span, ChunkCoord anyChunkInSpan, IDimensionInfo provider) {
        ChunkCoord anchor = new ChunkCoord(anyChunkInSpan.dimension(), span.fromX(), span.fromZ());
        CityStyle style = City.getCityStyle(anchor, provider, BuildingInfo.getProfile(anchor, provider));
        RandomSource rand = Rng.at(provider.getSeed(), anchor.chunkX(), anchor.chunkZ(), Rng.Purpose.LARGE_BRIDGE);
        String name = style.getRandomLargeBridge(rand, anchor);
        return name == null ? null : AssetRegistries.PARTS.getOrWarn(provider.getWorld(), name);
    }

    /**
     * At a crossing, the orientation whose span address hashes higher wins. Query order is
     * irrelevant: both spans are identified by their own endpoints, so either chunk of either span
     * computes the same two numbers and reaches the same verdict.
     */
    static boolean winsCrossing(long horizontalAddress, long verticalAddress) {
        return Long.compareUnsigned(horizontalAddress, verticalAddress) >= 0;
    }

    /**
     * The span identity: the two endpoints, sorted, plus the orientation. Sorting is what makes a
     * scan from either end produce the same address, and hence the same roll.
     *
     * <p>The orientation rides in the slot rather than in the key. Deriving a second key by adding
     * the ordinal to {@link GridPurpose#PLANNED_BRIDGE} would borrow the number one past it, and the
     * next constant appended to that enum - which is its documented safe edit - would silently
     * inherit the Z-oriented bridge's stream.
     *
     * <p>The slot is three disjoint bit fields, so it is injective: the low 31 bits of {@code maxX}
     * at bit 33, the low 31 bits of {@code maxZ} at bit 1, the orientation in bit 0, with bit 32
     * left clear so the two coordinate fields cannot run into each other. Thirty-one bits identify
     * a chunk coordinate exactly - a chunk coordinate is a block coordinate shifted right by four,
     * so it cannot reach 2^27 even before Minecraft's world border caps it four orders of magnitude
     * lower - and no two distinct {@code (maxX, maxZ, orientation)} triples can therefore share a
     * slot.
     */
    static long address(long seed, BridgeSpan span) {
        int minX = Math.min(span.fromX(), span.toX());
        int minZ = Math.min(span.fromZ(), span.toZ());
        int maxX = Math.max(span.fromX(), span.toX());
        int maxZ = Math.max(span.fromZ(), span.toZ());
        long slot = ((maxX & 0x7fffffffL) << 33)
                | ((maxZ & 0x7fffffffL) << 1)
                | span.orientation().ordinal();
        return Hash.atSlot(seed, minX, minZ, slot, GridPurpose.PLANNED_BRIDGE.key());
    }

    /**
     * A city chunk is an endpoint only when the road survives the city clip there and the city sits
     * at level zero: the deck is authored flat at the base street surface, so a higher endpoint
     * would need ramp assets that do not exist.
     */
    static ChunkRole roleOf(ChunkCoord coord, ChunkFacts facts) {
        if (!facts.isRawPrimary(coord)) {
            return ChunkRole.OTHER;
        }
        if (!facts.isCity(coord)) {
            return facts.isWaterLike(coord) ? ChunkRole.GAP : ChunkRole.OTHER;
        }
        return facts.isEffectivePrimary(coord) && facts.cityLevel(coord) == 0
                ? ChunkRole.ENDPOINT : ChunkRole.OTHER;
    }

    /**
     * Whether a scan may start or continue here. Separate from {@link #roleOf} because this is asked
     * of every chunk that generates, and it must not pay for the endpoint refinement to learn that a
     * city chunk is not open water.
     */
    private static boolean isGap(ChunkCoord coord, ChunkFacts facts) {
        return facts.isRawPrimary(coord) && !facts.isCity(coord) && facts.isWaterLike(coord);
    }

    /**
     * The span containing {@code origin}, before the acceptance roll, or {@code null} when the water
     * here is not bridgeable: the origin is not a gap, one side runs into something that is not a
     * primary-road city chunk at level zero, or the water is wider than {@code maxGapLength}.
     *
     * <p>Note there is no separate "is this chunk on the primary line for this orientation" test.
     * A span is a run of at least three consecutive raw-primary chunks along one axis, and primary
     * lines are at least eight chunks apart, so such a run can only exist along an actual primary
     * line. A chunk that sits on the perpendicular line alone fails at the very first step.
     */
    @Nullable
    static BridgeSpan scanSpan(ChunkCoord origin, Orientation orientation, ChunkFacts facts, int maxGapLength) {
        if (!isGap(origin, facts)) {
            return null;
        }
        ChunkCoord low = findEndpoint(origin, orientation, false, facts, maxGapLength);
        if (low == null) {
            return null;
        }
        ChunkCoord high = findEndpoint(origin, orientation, true, facts, maxGapLength);
        if (high == null) {
            return null;
        }
        int gapLength = high.getCoord(orientation) - low.getCoord(orientation) - 1;
        if (gapLength < 1 || gapLength > maxGapLength) {
            return null;
        }
        return new BridgeSpan(orientation, low.chunkX(), low.chunkZ(), high.chunkX(), high.chunkZ());
    }

    @Nullable
    private static ChunkCoord findEndpoint(ChunkCoord origin, Orientation orientation, boolean positive,
                                           ChunkFacts facts, int maxGapLength) {
        ChunkCoord cursor = origin;
        // One step past the limit, so a gap of exactly maxGapLength on this side still finds its
        // endpoint; the total is checked against the limit by the caller.
        for (int distance = 1; distance <= maxGapLength + 1; distance++) {
            cursor = positive ? cursor.higher(orientation) : cursor.lower(orientation);
            ChunkRole role = roleOf(cursor, facts);
            if (role == ChunkRole.ENDPOINT) {
                return cursor;
            }
            if (role != ChunkRole.GAP) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static BridgeSpan acceptedSpan(ChunkCoord origin, Orientation orientation, ChunkFacts facts,
                                           long seed, int maxGapLength, float chance) {
        BridgeSpan span = scanSpan(origin, orientation, facts, maxGapLength);
        if (span == null) {
            return null;
        }
        return Hash.unit(address(seed, span)) < chance ? span : null;
    }

    /**
     * Whether {@code span} beats every perpendicular span that contests one of its chunks.
     *
     * <p>Checking the whole span rather than only the chunk that asked is what keeps a bridge from
     * developing a hole: the verdict must be a property of the span alone, or the chunk under the
     * crossing would drop out while its neighbours carried on building a deck towards it.
     *
     * <p>The rival is taken at face value - whether the rival itself survives its own crossings is
     * not asked, because that question is not well founded. The cost is that a three-way contest can
     * leave a chunk with no bridge at all and both spans withdrawn, which reads as a primary road
     * that simply stops at the water, exactly as it did before bridges existed.
     *
     * <p>Package-private so a test can put two real spans across one another and check that exactly
     * one of them comes out. Only {@code anyChunkInSpan}'s dimension key is read.
     */
    static boolean survivesCrossings(BridgeSpan span, ChunkCoord anyChunkInSpan, ChunkFacts facts,
                                     long seed, int maxGapLength, float chance) {
        long ourAddress = address(seed, span);
        Orientation crossing = span.orientation().getOpposite();
        boolean alongX = span.orientation() == Orientation.X;
        int from = alongX ? span.fromX() : span.fromZ();
        int to = alongX ? span.toX() : span.toZ();
        for (int position = from + 1; position < to; position++) {
            ChunkCoord gap = alongX
                    ? new ChunkCoord(anyChunkInSpan.dimension(), position, span.fromZ())
                    : new ChunkCoord(anyChunkInSpan.dimension(), span.fromX(), position);
            BridgeSpan rival = acceptedSpan(gap, crossing, facts, seed, maxGapLength, chance);
            if (rival == null) {
                continue;
            }
            long rivalAddress = address(seed, rival);
            boolean won = alongX
                    ? winsCrossing(ourAddress, rivalAddress)
                    : !winsCrossing(rivalAddress, ourAddress);
            if (!won) {
                return false;
            }
        }
        return true;
    }

    private static ChunkFacts worldFacts(IDimensionInfo provider) {
        return new ChunkFacts() {
            @Override
            public boolean isRawPrimary(ChunkCoord coord) {
                return provider.roadField().typeAt(coord.chunkX(), coord.chunkZ()) == RoadType.PRIMARY;
            }

            @Override
            public boolean isCity(ChunkCoord coord) {
                return BuildingInfo.isCityRaw(coord, provider, BuildingInfo.getProfile(coord, provider));
            }

            @Override
            public boolean isEffectivePrimary(ChunkCoord coord) {
                UrbexProfile profile = BuildingInfo.getProfile(coord, provider);
                return BuildingInfo.effectiveRoadType(coord, provider, profile) == RoadType.PRIMARY;
            }

            @Override
            public int cityLevel(ChunkCoord coord) {
                return BuildingInfo.getCityLevel(coord, provider);
            }

            @Override
            public boolean isWaterLike(ChunkCoord coord) {
                // The biome tags catch oceans, rivers and beaches; the deterministic terrain height
                // also catches an inland lake whose biome is still plains or forest.
                if (CityGenerator.isWaterBiome(provider, coord)) {
                    return true;
                }
                int sealevel = BuildingInfo.getProfile(coord, provider).SEALEVEL;
                int waterLevel = sealevel == -1 ? Tools.getSeaLevel(provider.getWorld()) : sealevel;
                return provider.getHeightmap(coord).getHeight() < waterLevel;
            }
        };
    }
}
