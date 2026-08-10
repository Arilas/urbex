package dev.krona.urbex.plan.grid;

/**
 * Validated inputs to {@link GridRoadField}. Constructed once per dimension; a profile that cannot
 * produce a valid instance fails at load with a message naming the field.
 */
public record GridSettings(
        int primarySpacingX,
        int primarySpacingZ,
        float primaryOptionalChance,
        int primaryForceEvery,
        int secondaryMinCountX,
        int secondaryMaxCountX,
        int secondaryMinCountZ,
        int secondaryMaxCountZ,
        int minimumRoadSeparation,
        int minimumEdgeDistance,
        float tertiaryChance,
        int tertiaryMinLength,
        int tertiaryMaxLength
) {
    public GridSettings {
        if (primarySpacingX < 8 || primarySpacingX > 128) {
            throw new IllegalArgumentException("primaryRoadSpacingX must be 8..128, was " + primarySpacingX);
        }
        if (primarySpacingZ < 8 || primarySpacingZ > 128) {
            throw new IllegalArgumentException("primaryRoadSpacingZ must be 8..128, was " + primarySpacingZ);
        }
        if (primaryOptionalChance < 0 || primaryOptionalChance > 1) {
            throw new IllegalArgumentException("primaryRoadOptionalChance must be 0..1, was " + primaryOptionalChance);
        }
        if (primaryForceEvery < 1 || primaryForceEvery > 16) {
            throw new IllegalArgumentException("primaryRoadForceEvery must be 1..16, was " + primaryForceEvery);
        }
        if (secondaryMinCountX < 0 || secondaryMaxCountX > 128 || secondaryMinCountX > secondaryMaxCountX) {
            throw new IllegalArgumentException("secondaryRoadMinCountX/MaxCountX must satisfy 0 <= min <= max <= 128, were "
                    + secondaryMinCountX + "/" + secondaryMaxCountX);
        }
        if (secondaryMinCountZ < 0 || secondaryMaxCountZ > 128 || secondaryMinCountZ > secondaryMaxCountZ) {
            throw new IllegalArgumentException("secondaryRoadMinCountZ/MaxCountZ must satisfy 0 <= min <= max <= 128, were "
                    + secondaryMinCountZ + "/" + secondaryMaxCountZ);
        }
        if (minimumRoadSeparation < 2 || minimumRoadSeparation > 32) {
            throw new IllegalArgumentException("minimumRoadSeparation must be 2..32, was " + minimumRoadSeparation);
        }
        if (minimumEdgeDistance < 2 || minimumEdgeDistance > 32) {
            throw new IllegalArgumentException("minimumRoadEdgeDistance must be 2..32, was " + minimumEdgeDistance);
        }
        if (tertiaryChance < 0 || tertiaryChance > 1) {
            throw new IllegalArgumentException("tertiaryRoadChance must be 0..1, was " + tertiaryChance);
        }
        if (tertiaryMinLength < 1 || tertiaryMaxLength > 32 || tertiaryMinLength > tertiaryMaxLength) {
            throw new IllegalArgumentException("tertiaryRoadMinLength/MaxLength must satisfy 1 <= min <= max <= 32, were "
                    + tertiaryMinLength + "/" + tertiaryMaxLength);
        }
    }

    /** Upstream's defaults, used by tests and as the profile field defaults. */
    public static GridSettings defaults() {
        return new GridSettings(8, 8, 0.45f, 4, 0, 2, 0, 2, 4, 3, 0.40f, 2, 5);
    }
}
