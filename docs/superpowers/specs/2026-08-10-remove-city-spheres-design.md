# Remove City Spheres and Sphere-Bound Monorails

**Status:** Approved design, awaiting written-spec review

**Date:** 2026-08-10

## 1. Decision

Remove city spheres completely. Do not modernize them and do not retain them as a
deprecated or hidden feature.

The current implementation does not justify its scope:

- Sphere placement and city placement are independent random decisions, so a
  generated shell is not guaranteed to contain a city.
- Shell blocks are selected indirectly through city-style palette characters
  rather than through a coherent sphere material model.
- The shell runs as a separate, later world-generation feature and does not
  participate in building damage, leaving ruined buildings inside pristine
  shells.
- The feature brings its own landscapes, inside/outside profile pairing, spawn
  rules, predefined assets, generation pass, cache, GUI settings, and monorail
  network.

Correcting those problems would amount to designing a new habitat-generation
subsystem. Urbex has no clear player experience that warrants that work.

## 2. Compatibility Policy

No backward compatibility is required. Urbex is currently used with disposable
test worlds and does not promise compatibility with upstream Lost Cities.

Consequently, the removal adds no migration, fallback, retired-id registry,
profile substitution, deprecation period, or legacy parsing. Old worlds,
profiles, configs, and datapacks are unsupported and may be deleted. The
implementation does not inspect, transform, preserve, or delete old user files.
The changelog tells testers to recreate their config and worlds when necessary.

## 3. Landscape and Profile Model

Remove the sphere-specific landscape enum values `SPACE`, `SPHERES`, and
`CAVERNSPHERES`, together with `isSpace()`, `isSpheres()`, and
`isVoidSpheres()`.

The landscape types that happen to remain after this change are `DEFAULT`,
`FLOATING`, and `CAVERN`. This is not a permanent closed set: validation and UI
code must derive available values from the enum or current profile collection
rather than assert that exactly these three values exist.

Remove the public presets `space`, `biosphere`, and `biosphere_caves`. Remove
the private sphere-only profiles `void_outside` and `bio_wasteland` because no
remaining profile consumes them.

Remove:

- the `cityspheres` profile category;
- every `CITYSPHERE_*` field and its config serialization;
- `SPAWN_SPHERE` and its config serialization;
- the Spheres settings category, descriptors, sections, labels, and tooltips;
- sphere preset icons and descriptions.

## 4. World-Generation Architecture

Delete the complete sphere generation path:

- `SphereFeature` and `Spheres`;
- `CitySphere` and its per-dimension cache;
- registration of the `urbex:spheres` feature;
- the configured- and placed-feature JSON resources;
- top-layer biome injection for the sphere feature.

Remove all sphere-specific branches from city membership, city radius and
level selection, building eligibility, terrain shaping, street supports,
building clearing, highways, railways, scattered generation, chunk-height
handling, previews, and debug output. Remaining branches express only the
behavior of currently supported landscapes.

Sphere generation is also the sole reason a dimension carries an inside and
outside profile. Remove `IDimensionInfo.getOutsideProfile()`, the outside-profile
state and constructor argument in `DefaultDimensionInfo`, the matching preview
stub, and the outside-profile lookup in `CityFeature`. After the change, a
dimension has one profile and all generation consumers read that profile.

The resulting generation flow is:

1. A dimension selects one Urbex profile.
2. `CityFeature` creates one dimension context and one `CityGenerator`.
3. The carver-stage city hook performs Urbex generation.
4. No later Urbex top-layer feature rewrites the chunk.

## 5. Asset and Datapack Surface

Remove the predefined-sphere registry and all related registry entries,
resource records, runtime wrappers, asset loading, and spawn lookup.

Remove the sphere-specific asset schemas:

- `SphereSettings` and `sphereblocks` from city styles;
- `CitySphereSettings` and `cityspheres` from world styles;
- sphere center-part placement;
- `issphere` from `ConditionTest`, the `ConditionPart` and `PartRef` codecs,
  and `ConditionContext`.

No compatibility behavior is provided for datapacks that still use these
fields.

## 6. Monorails

Remove the current monorail implementation with spheres.

Its only live generation call is inside sphere generation and is gated by the
`SPACE` landscape. Route decisions scan for enabled neighboring spheres, while
track and station selection depends on whether a chunk is inside, outside, or
on the border of a sphere. It is therefore not an independent transport
feature.

Delete:

- `Monorails`;
- monorail state and queries on `BuildingInfo` and `CitySphere`;
- monorail profile settings;
- `MonorailParts` and its `PartSelector` field;
- monorail entries in the standard world style;
- the three bundled monorail part assets.

A future elevated transit feature, if desired, will be designed independently
around actual city locations and the planning layer. It will not reuse this
sphere-bound route model by default.

## 7. Deterministic Randomness

Remove all active sphere RNG consumers. Do not renumber the unrelated RNG
purposes that follow the removed consumers: the `Purpose` ordinal is part of
the hash address, and shifting it would unnecessarily change buildings,
damage, vegetation, lighting, loot, and other generation.

Rename the three removed sphere-purpose positions to neutral reserved names and
leave them unused. Update the existing RNG order test accordingly. These slots
preserve the RNG addressing invariant; they are not sphere compatibility code.

## 8. Verification

Do not add tests that assert spheres or monorails are absent, scan for banned
words, or lock the landscape enum to an exact set. Such tests would obstruct
legitimate future features without protecting current behavior.

Verification consists of:

- deleting or updating existing test cases whose APIs or fixtures contain the
  removed feature;
- retaining generic settings-completeness and datapack-reference-integrity
  tests, updated to describe the current schemas;
- retaining the existing RNG order and golden-value tests with neutral reserved
  purpose names;
- using compilation and a one-time implementation audit to find dangling code
  and resource references;
- running the full unit-test suite and build.

The one-time reference audit is an implementation check, not a permanent test.
Historical documentation may continue to mention Lost Cities spheres.

## 9. Documentation

Add a breaking-change entry to `CHANGELOG.md` explaining that city spheres,
sphere landscapes and presets, predefined spheres, sphere settings, sphere
datapack fields, and the sphere-bound monorail implementation were removed.
State explicitly that test worlds and config directories may need to be
recreated.

Do not rewrite preserved upstream history documents.

## 10. Out of Scope

- A replacement habitat or dome generator.
- A new monorail or elevated-transit planner.
- Migration or compatibility for old worlds, profiles, configs, or datapacks.
- Unrelated changes to the remaining landscape types.
- Broader asset-format or world-generation refactors.

## 11. Acceptance Criteria

- The sphere generation feature and its biome injection no longer exist.
- Sphere landscapes, profiles, config fields, GUI controls, spawn rules,
  registries, models, caches, assets, and runtime branches are removed.
- The sphere-bound monorail generator and assets are removed.
- A dimension carries one profile rather than inside/outside profiles.
- Current non-sphere profiles load and remain selectable.
- Generic integrity tests, the full test suite, and the build pass.
- No compatibility or migration layer is introduced.
