package dev.krona.urbex.config;

import dev.krona.urbex.plan.grid.GridSettings;

/**
 * Reads a preset's road-grid fields into the {@link GridSettings} the planner takes.
 *
 * <p>It sits here, on the configuration side, rather than as a {@code fromPreset} factory on
 * {@code GridSettings} - which is where it used to be, written with a fully-qualified parameter type
 * so that an import scan would not notice. {@code dev.krona.urbex.plan} is the dependency-free
 * planning module: a pure function of seed, dimension id, coordinates and settings, exercisable with
 * no game running and no configuration loaded. A conversion knows both sides, so it belongs to the
 * side that already knows the other (issue #129).</p>
 *
 * <p>A straight read: {@link GridSettings}'s constructor is the only validation, and it stays strict.
 * A preset whose {@code secondaryRoadMinCountX} exceeds its {@code secondaryRoadMaxCountX} is a
 * preset nobody can generate the world they wrote down from, and quietly widening the pair here would
 * hand them a different world with no diagnostic. Callers that cannot afford the exception - the
 * settings preview, which rebuilds one of these on every keystroke and can therefore see a
 * half-finished edit - are the ones that handle it.</p>
 */
public final class PresetRoadGrid {

    private PresetRoadGrid() {
    }

    /**
     * @throws IllegalArgumentException if the preset's road settings contradict each other
     */
    public static GridSettings of(Preset preset) {
        return new GridSettings(
                preset.PRIMARY_ROAD_SPACING_X,
                preset.PRIMARY_ROAD_SPACING_Z,
                preset.PRIMARY_ROAD_OPTIONAL_CHANCE,
                preset.PRIMARY_ROAD_FORCE_EVERY,
                preset.SECONDARY_ROAD_MIN_COUNT_X,
                preset.SECONDARY_ROAD_MAX_COUNT_X,
                preset.SECONDARY_ROAD_MIN_COUNT_Z,
                preset.SECONDARY_ROAD_MAX_COUNT_Z,
                preset.MINIMUM_ROAD_SEPARATION,
                preset.MINIMUM_ROAD_EDGE_DISTANCE,
                preset.TERTIARY_ROAD_CHANCE,
                preset.TERTIARY_ROAD_MIN_LENGTH,
                preset.TERTIARY_ROAD_MAX_LENGTH);
    }
}
