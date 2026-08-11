package dev.krona.urbex.worldgen.lost.regassets.data;

import java.util.List;
import java.util.Optional;

/**
 * The part wiring a city style or world style has to declare somewhere in its {@code extends} chain,
 * for tests that are about something else.
 * <p>
 * Since the thirty code-side defaults were deleted, street, highway and railway wiring is required
 * of the resolved chain rather than filled in from Java, so a {@code CityStyle} or {@code WorldStyle}
 * built in a test needs one entry that declares it or the constructor raises the load error. The ids
 * here are deliberately not the bundled pack's: a test that accidentally depends on these will name
 * {@code urbex:test_*} in its failure rather than looking like a real street part.
 */
public final class TestWiring {

    private TestWiring() {
    }

    private static Optional<Mergeable<String>> one(String value) {
        return Optional.of(new Mergeable<>(true, List.of(value)));
    }

    /** A complete street family, every component a single {@code urbex:test_<prefix>_*} part. */
    public static StreetParts.Decl streetParts(String prefix) {
        return new StreetParts.Decl(
                one("urbex:test_" + prefix + "_straight"),
                one("urbex:test_" + prefix + "_end"),
                one("urbex:test_" + prefix + "_bend"),
                one("urbex:test_" + prefix + "_t"),
                one("urbex:test_" + prefix + "_none"),
                one("urbex:test_" + prefix + "_all"),
                one("urbex:test_" + prefix + "_connector"),
                one("urbex:test_" + prefix + "_stair"));
    }

    /** A {@code streetblocks} block declaring nothing but a complete {@code parts} family. */
    public static StreetSettings streetSettings() {
        return streetSettings(streetParts("street"));
    }

    /** A {@code streetblocks} block declaring {@code parts} exactly as given. */
    public static StreetSettings streetSettings(StreetParts.Decl parts) {
        return new StreetSettings(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(parts), Optional.empty(), Optional.empty());
    }

    /** A complete {@code parts} block for a world style: both groups, every field. */
    public static PartSelector.Decl partSelector() {
        return new PartSelector.Decl(Optional.of(new HighwayParts.Decl(
                one("urbex:test_highway_tunnel"),
                one("urbex:test_highway_open"),
                one("urbex:test_highway_bridge"),
                one("urbex:test_highway_tunnel_bi"),
                one("urbex:test_highway_open_bi"),
                one("urbex:test_highway_bridge_bi"))),
                Optional.of(new RailwayParts.Decl(
                        one("urbex:test_station_underground"),
                        one("urbex:test_station_open"),
                        one("urbex:test_station_openroof"),
                        one("urbex:test_station_underground_stairs"),
                        one("urbex:test_station_staircase"),
                        one("urbex:test_station_staircase_surface"),
                        one("urbex:test_rails_horizontal"),
                        one("urbex:test_rails_horizontal_end"),
                        one("urbex:test_rails_horizontal_water"),
                        one("urbex:test_rails_vertical"),
                        one("urbex:test_rails_vertical_water"),
                        one("urbex:test_rails_3split"),
                        one("urbex:test_rails_bend"),
                        one("urbex:test_rails_flat"),
                        one("urbex:test_rails_down1"),
                        one("urbex:test_rails_down2"))));
    }
}
