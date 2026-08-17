# Conformance index

`[GENERATED]` — do not edit. Regenerate with `./gradlew regenerateConformance`;
`ConformanceIndexTest` fails if the checked-in copy differs from what the documents say.

Every rule in this specification, its class, its fixtures, and the tests that cite it. See
[the specification system](../README.md#5-the-conformance-index) for what this file is for.

## Totals

| Area | Rules | Fixtures |
|---|---:|---:|
| `MODEL` | 42 | 22 |
| `TRAIT` | 41 | 16 |
| `REF` | 55 | 14 |
| `MERGE` | 12 | 4 |
| `WEIGHT` | 37 | 11 |
| `CHAR` | 14 | 4 |
| `LOAD` | 24 | 0 |
| `DIAG` | 54 | 0 |
| `VER` | 24 | 3 |
| **total** | **303** | **74** |

## Outstanding

**Rules relying on the draft suspension of fixture-completeness (101):** `MODEL.030`, `MODEL.032`, `MODEL.040`, `MODEL.041`, `MODEL.052`, `MODEL.060`, `MODEL.061`, `MODEL.063`, `MODEL.064`, `MODEL.070`, `MODEL.073`, `MODEL.074`, `MODEL.075`, `MODEL.080`, `TRAIT.007`, `TRAIT.008`, `TRAIT.010`, `TRAIT.011`, `TRAIT.020`, `TRAIT.022`, `TRAIT.030`, `TRAIT.032`, `TRAIT.040`, `TRAIT.042`, `TRAIT.050`, `TRAIT.054`, `TRAIT.055`, `TRAIT.060`, `TRAIT.061`, `TRAIT.063`, `TRAIT.065`, `TRAIT.070`, `TRAIT.072`, `TRAIT.073`, `TRAIT.090`, `TRAIT.091`, `TRAIT.092`, `TRAIT.093`, `REF.001`, `REF.005`, `REF.017`, `REF.035`, `REF.074`, `REF.075`, `WEIGHT.012`, `WEIGHT.015`, `WEIGHT.016`, `WEIGHT.018`, `WEIGHT.022`, `WEIGHT.023`, `WEIGHT.025`, `WEIGHT.031`, `WEIGHT.040`, `WEIGHT.041`, `WEIGHT.042`, `WEIGHT.043`, `WEIGHT.050`, `WEIGHT.051`, `WEIGHT.052`, `WEIGHT.053`, `WEIGHT.060`, `WEIGHT.061`, `WEIGHT.062`, `WEIGHT.064`, `CHAR.010`, `CHAR.020`, `CHAR.021`, `CHAR.030`, `CHAR.031`, `LOAD.001`, `LOAD.002`, `LOAD.003`, `LOAD.010`, `LOAD.011`, `LOAD.012`, `LOAD.014`, `LOAD.020`, `LOAD.021`, `LOAD.022`, `LOAD.023`, `LOAD.024`, `LOAD.025`, `LOAD.030`, `LOAD.031`, `LOAD.040`, `LOAD.041`, `LOAD.042`, `LOAD.043`, `LOAD.044`, `LOAD.050`, `LOAD.051`, `DIAG.902`, `VER.007`, `VER.009`, `VER.020`, `VER.021`, `VER.022`, `VER.023`, `VER.030`, `VER.031`, `VER.041`

**Rules marked `[NO-FIXTURE]` (14), which must each be covered by a citing test:**

| Rule | Reason |
|---|---|
| `REF.043` | a second asset |
| `REF.045` | a second asset |
| `REF.062` | a parent palette |
| `MERGE.010` | a version 1 and a version 2 file |
| `MERGE.012` | a part carrying an inline palette |
| `WEIGHT.019` | a parent palette to spread from |
| `WEIGHT.063` | a generated 129-choice list |
| `CHAR.011` | a part file, not a palette |
| `CHAR.022` | a command invocation |
| `LOAD.013` | a style with several palette groups |
| `VER.005` | a version 1 and a version 2 file |
| `VER.006` | a style and two palettes |
| `VER.013` | a palette and a conditions asset |
| `VER.015` | an entry that must be compiled, not a document |

**Tests:** 109 of 303 identifiers have at least one citing test; the rest show `—` below.
`ConformanceIndexTest` will fail on any rule that still shows `—` once this document leaves draft.

## Rules

### `palette/00-model.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `MODEL.001` | `MUST` |  | `accept` | `PaletteV2DecodeTest.aPaletteFileAcceptsTheFiveKeysAndNoOthers`, `PaletteV2DecodeTest.aDecodedPaletteEncodesBackToADocumentThatDecodesToTheSameThing` |
| `MODEL.002` | `REJECT` | `DIAG.001` | `reject=DIAG.001` | `VersionDispatchTest.aVersionThatIsNotOneOrTwoIsRefusedAndSoIsOneThatIsNotANumber` |
| `MODEL.003` | `MUST` |  |  | `PaletteV2DecodeTest.aFileMayDeclareNoPaletteBecauseTheChainIsWhatRequiresOne` |
| `MODEL.004` | `REJECT` | `DIAG.003` | `reject=DIAG.003` | — |
| `MODEL.005` | `MUST` |  |  | `PaletteV2DecodeTest.aMarkerCannotBeDeclaredTwiceBecausePaletteIsAnObject` |
| `MODEL.010` | `MUST` |  |  | `PaletteV2DecodeTest.oneNodeTypeStandsInEveryPositionThatHoldsANode` |
| `MODEL.011` | `DEFAULT` |  | `equiv=default-kind`, `equiv=default-kind` | `NodeResolverTest.aNodeWithNoKindTakesItsKindFromItsReferenceAndOnlyThenTheDefault` |
| `MODEL.012` | `REJECT` | `DIAG.004` | `reject=DIAG.004` | — |
| `MODEL.013` | `MUST` |  | `reject=DIAG.003` | `NodeResolverTest.aKeyOfAnotherKindIsRefusedOnceTheReferenceHasDecidedTheKind`, `NodeResolverTest.aDiagnosticNamingSeveralPlacementListsOrdersThemByPlacement`, `PaletteV2DecodeTest.aKindSpecificKeyIsRefusedOnAnotherKindNamingTheKey` |
| `MODEL.020` | `EQUIV` |  | `equiv=stone-brick-marker`, `equiv=stone-brick-marker` | `PaletteV2DecodeTest.aDecodedPaletteEncodesBackToADocumentThatDecodesToTheSameThing`, `PaletteV2DecodeTest.aStringNodeIsABlockAndNeverAReferenceHoweverMuchItLooksLikeOne` |
| `MODEL.021` | `MUST NOT` |  |  | `PaletteV2DecodeTest.aStringNodeIsABlockAndNeverAReferenceHoweverMuchItLooksLikeOne` |
| `MODEL.030` | `MUST` |  |  | — |
| `MODEL.031` | `MUST` |  |  | `NodeResolverTest.aSatellitesNodeIsNotCompletedAsAnAlternative` |
| `MODEL.032` | `MUST` |  |  | — |
| `MODEL.033` | `REJECT` | `DIAG.005` | `reject=DIAG.005` | — |
| `MODEL.040` | `MUST` |  |  | — |
| `MODEL.041` | `MUST` |  |  | — |
| `MODEL.042` | `ACCEPT` |  | `accept` | — |
| `MODEL.043` | `REJECT` | `DIAG.006` | `reject=DIAG.006` | — |
| `MODEL.044` | `MUST` |  | `accept` | `ImportsTest.aDefinitionsAssetIsAVariantThatMayAlsoCarryTraitsAndAnyKind` |
| `MODEL.045` | `REJECT` | `DIAG.007` | `reject=DIAG.007` | `NodeResolverTest.aWeightedNodeWhoseChoicesSpreadToNothingIsRefused` |
| `MODEL.046` | `MUST` |  |  | `PaletteV2DecodeTest.aChoiceIsANodeWithASizeBesideIt` |
| `MODEL.047` | `ACCEPT` |  | `accept` | — |
| `MODEL.050` | `MUST` |  | `accept` | — |
| `MODEL.051` | `MUST` |  | `reject=DIAG.012` | `PaletteV2DecodeTest.aTagWithoutItsLeadingHashIsRefused` |
| `MODEL.052` | `MUST` |  |  | — |
| `MODEL.053` | `REJECT` | `DIAG.008` | `reject=DIAG.008` | — |
| `MODEL.060` | `MUST` |  |  | — |
| `MODEL.061` | `MUST` |  |  | — |
| `MODEL.062` | `REJECT` | `DIAG.009` | `reject=DIAG.009` | — |
| `MODEL.063` | `MUST` |  |  | — |
| `MODEL.064` | `MUST` |  |  | — |
| `MODEL.070` | `MUST` |  |  | — |
| `MODEL.071` | `MUST` |  |  | `PaletteV2DecodeTest.aLightSocketDeclaresItsCandidatesInFourNamedLists` |
| `MODEL.072` | `REJECT` | `DIAG.010` | `reject=DIAG.010` | `NodeResolverTest.aKindArrivingWithoutItsRequiredListIsRefusedByTheRuleThatOwnsThatList`, `PaletteV2DecodeTest.aSocketWithAMalformedCandidateIsNotToldItHasNoCandidate` |
| `MODEL.073` | `MUST` |  |  | — |
| `MODEL.074` | `MUST` |  |  | — |
| `MODEL.075` | `MUST` |  |  | — |
| `MODEL.076` | `MUST` |  |  | `NodeResolverTest.aSocketPlacementListAcceptsASpread`, `PaletteV2DecodeTest.aPlacementListTakesWhenAndSpreadLikeAnyOtherList` |
| `MODEL.080` | `MUST` |  |  | — |
| `MODEL.081` | `REJECT` | `DIAG.011` | `reject=DIAG.011` | `NodeResolverTest.aMarkerResolvingToNoBlockSourceIsRefused`, `NodeResolverTest.aCompletenessDiagnosticNamesTheMarkerAndTheDefinitionItCameFrom`, `NodeResolverTest.aKindArrivingWithoutItsRequiredListIsRefusedByTheRuleThatOwnsThatList`, `NodeResolverTest.aCompletenessDiagnosticBlamesTheFilterWhenTheFilterDroppedTheSource` |
| `MODEL.082` | `ACCEPT` |  | `accept` | `NodeResolverTest.aDefinitionMayCarryOnlyTraits` |

### `palette/01-traits.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `TRAIT.001` | `MUST` |  |  | `PaletteV2DecodeTest.aTraitIdIsNamespacedAndAnUnqualifiedOneIsRefused` |
| `TRAIT.002` | `MUST` |  |  | `PaletteV2DecodeTest.aTraitIdIsNamespacedAndAnUnqualifiedOneIsRefused` |
| `TRAIT.003` | `REJECT` | `DIAG.020` | `reject=DIAG.020` | — |
| `TRAIT.004` | `MUST` |  | `accept` | — |
| `TRAIT.005` | `MUST` |  | `accept` | — |
| `TRAIT.006` | `MUST` |  |  | `NodeResolverTest.traitsBesideARefMergeByIdAndReplaceWhole` |
| `TRAIT.007` | `MUST NOT` |  |  | — |
| `TRAIT.008` | `MUST` |  |  | — |
| `TRAIT.009` | `MUST` |  | `accept` | — |
| `TRAIT.010` | `MUST` |  |  | — |
| `TRAIT.011` | `MUST` |  |  | — |
| `TRAIT.012` | `ACCEPT` |  | `accept` | — |
| `TRAIT.020` | `MUST` |  |  | — |
| `TRAIT.021` | `REJECT` | `DIAG.021` | `reject=DIAG.021` | — |
| `TRAIT.022` | `MUST` |  |  | — |
| `TRAIT.030` | `MUST` |  |  | — |
| `TRAIT.031` | `REJECT` | `DIAG.021` | `reject=DIAG.021` | — |
| `TRAIT.032` | `MUST` |  |  | — |
| `TRAIT.040` | `MUST` |  |  | — |
| `TRAIT.041` | `REJECT` | `DIAG.022` | `reject=DIAG.022` | — |
| `TRAIT.042` | `MUST NOT` |  |  | — |
| `TRAIT.050` | `MUST` |  |  | — |
| `TRAIT.051` | `DEFAULT` |  | `equiv=absent-unlit`, `equiv=absent-unlit` | — |
| `TRAIT.052` | `REJECT` | `DIAG.023` | `reject=DIAG.023` | — |
| `TRAIT.053` | `REJECT` | `DIAG.024` | `reject=DIAG.024` | — |
| `TRAIT.054` | `MUST` |  |  | — |
| `TRAIT.055` | `MUST` |  |  | — |
| `TRAIT.060` | `MUST` |  |  | — |
| `TRAIT.061` | `MUST` |  |  | — |
| `TRAIT.062` | `DEFAULT` |  | `equiv=absent-replacement`, `equiv=absent-replacement` | — |
| `TRAIT.063` | `MUST` |  |  | — |
| `TRAIT.064` | `REJECT` | `DIAG.025` | `reject=DIAG.025` | — |
| `TRAIT.065` | `MUST` |  |  | — |
| `TRAIT.070` | `MUST` |  |  | — |
| `TRAIT.071` | `DEFAULT` |  | `accept` | — |
| `TRAIT.072` | `MUST` |  |  | — |
| `TRAIT.073` | `MUST` |  |  | — |
| `TRAIT.090` | `MUST` |  |  | — |
| `TRAIT.091` | `MUST` |  |  | — |
| `TRAIT.092` | `MUST NOT` |  |  | — |
| `TRAIT.093` | `MUST` |  |  | — |

### `palette/02-references.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `REF.001` | `MUST` |  |  | — |
| `REF.002` | `MUST` |  |  | `NodeResolverTest.aKeyBesideARefReplacesTheReferencedNodesValueForThatKey` |
| `REF.003` | `MUST` |  | `accept` | `NodeResolverTest.aKeyBesideARefReplacesTheReferencedNodesValueForThatKey` |
| `REF.004` | `MUST` |  |  | `NodeResolverTest.traitsBesideARefMergeByIdAndReplaceWhole` |
| `REF.005` | `MUST` |  |  | — |
| `REF.010` | `MUST` |  |  | `ImportsTest.theDefinitionsRegistryIsRegisteredAndVariantsStillIs`, `NodeResolverTest.aQualifiedRefNamesADefinitionsAsset`, `PointerTest.aNameWithAColonAndNoFragmentIsADefinitionsAsset` |
| `REF.011` | `MUST` |  |  | `PointerTest.aNameWithNoColonAndNoFragmentIsADefinitionOfThisFile` |
| `REF.012` | `MUST NOT` |  |  | `NodeResolverTest.aNameResolvesInOneTierAndTheOtherIsNeverTried` |
| `REF.013` | `REJECT` | `DIAG.030` | `reject=DIAG.030` | — |
| `REF.014` | `MUST` |  |  | `ImportsTest.aDefinitionsAssetIsOneNodeWithTheFileLevelKeysAroundIt`, `ImportsTest.aDefinitionsAssetRoundTripsThroughItsOwnCodec` |
| `REF.015` | `MUST NOT` |  |  | `ImportsTest.aDefinitionsAssetMayNotReferenceAnUnqualifiedName` |
| `REF.016` | `MUST` |  |  | `NodeResolverTest.aQualifiedRefNamesADefinitionsAsset` |
| `REF.017` | `MUST` |  |  | — |
| `REF.018` | `MUST` |  |  | `ImportsTest.aDefinitionsAssetCarriesImportsAndNotDefs` |
| `REF.019` | `REJECT` | `DIAG.071` | *—* | `ImportsTest.aDefinitionsAssetDeclaresVersionTwoAndNothingElse` |
| `REF.020` | `ACCEPT` |  | `accept` | `NodeResolverTest.aDefinitionMayCarryOnlyTraits` |
| `REF.021` | `MUST` |  |  | `NodeResolverTest.aDefinitionMayCarryOnlyTraits` |
| `REF.022` | `MUST NOT` |  |  | `NodeResolverTest.anyOperandAnywhereInsideATraitIsRefused` |
| `REF.030` | `MUST` |  |  | `NodeResolverTest.noResolvedNodeHoldsAPointerOrADefinitionName` |
| `REF.031` | `MUST` |  |  | `NodeResolverTest.aChainResolvesWhateverOrderItsLinksAreDeclaredIn` |
| `REF.032` | `REJECT` | `DIAG.032` | `reject=DIAG.032` | `NodeResolverTest.aReferenceCycleIsRefusedNamingEveryNodeInIt`, `NodeResolverTest.aSelfReferenceIsACycleOfOne`, `V2ChainTest.aCycleThroughRefAndExtendsTogetherIsOneCycle` |
| `REF.033` | `MUST` |  |  | `V2ChainTest.aCycleThroughRefAndExtendsTogetherIsOneCycle` |
| `REF.034` | `INVARIANT` |  |  | `NodeResolverTest.noResolvedNodeHoldsAPointerOrADefinitionName` |
| `REF.035` | `INVARIANT` |  |  | — |

### `palette/03-pointers.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `REF.040` | `MUST` |  |  | `PointerTest.aNameWithNoColonAndNoFragmentIsADefinitionOfThisFile` |
| `REF.041` | `MUST` |  |  | `PointerTest.aNameWithAColonAndNoFragmentIsADefinitionsAsset` |
| `REF.042` | `MUST` |  | `accept` | `PointerTest.aPointerWithAFragmentIsAnAssetIdAndAJsonPointerIntoIt`, `PointerTest.anAssetIdMayContainASlashBecauseTheFragmentDelimiterIsAHash`, `PointerTest.aFragmentIsAnRfc6901PointerWithItsTwoEscapes` |
| `REF.043` | `DEFAULT` |  | *n/a* | `NodeResolverTest.aFragmentPointerWithoutARegistryPrefixNamesAPalette`, `PointerTest.aFragmentPointersRegistryDefaultsToPalettesAndAPrefixOverridesIt` |
| `REF.044` | `MUST` |  |  | `NodeResolverTest.aPointedAtNodeResolvesItsOwnDocumentsNamesAndImports` |
| `REF.045` | `REJECT` | `DIAG.034` | *n/a* | `NodeResolverTest.aPointerNamingNoAssetOrNoNodeAtThatPathIsRefused` |
| `REF.046` | `MUST` |  |  | `NodeResolverTest.aCycleThroughAnotherAssetIsStillACycle` |
| `REF.050` | `MUST` |  |  | `PaletteV2DecodeTest.theDollarKeysAreAClosedSetAndNeitherSetIsAcceptedInTheOthersPosition` |
| `REF.051` | `MUST` |  | `accept` | `NodeResolverTest.onlyContributesJustTheNamedKeysOfTheTarget` |
| `REF.052` | `MUST` |  |  | `NodeResolverTest.withoutContributesEveryKeyOfTheTargetExceptThoseNamed` |
| `REF.053` | `REJECT` | `DIAG.035` | `reject=DIAG.035` | `NodeResolverTest.onlyAndWithoutTogetherAreRefused` |
| `REF.054` | `MUST NOT` |  |  | `NodeResolverTest.aFilterNamesTopLevelKeysAndNotPathsIntoThem` |
| `REF.055` | `REJECT` | `DIAG.072` | `reject=DIAG.072` | `NodeResolverTest.aFilterNamesTopLevelKeysAndNotPathsIntoThem`, `NodeResolverTest.aFilterKeyThatNamesNoKeyOfANodeIsRefused` |
| `REF.056` | `REJECT` | `DIAG.073` | `reject=DIAG.073` | `NodeResolverTest.aFilterWithNoReferenceIsRefused` |
| `REF.060` | `MUST` |  |  | `NodeResolverTest.superResolvesToTheInheritedValue`, `V2ChainTest.anEntryReplacesWhatItInheritsUnlessItNamesItWithSuper` |
| `REF.061` | `MUST` |  |  | `PointerTest.superMayCarryAFragmentAfterIt`, `V2ChainTest.superIsUsableAsTheBaseOfAFragment`, `V2ChainTest.superFollowsADeepChainOneLayerAtATime` |
| `REF.062` | `REJECT` | `DIAG.036` | *n/a* | `NodeResolverTest.superInAnEntryThatInheritsNothingIsRefused`, `V2ChainTest.superInAnEntryNoAncestorDeclaresIsRefused`, `V2ChainTest.theTwoSentencesOfDiag036AreChosenByTheFileThatWroteTheEntry` |
| `REF.063` | `MUST NOT` |  |  | `V2ChainTest.superNamesWhatIsInheritedRatherThanANamedAncestor` |
| `REF.070` | `MUST` |  | `accept` | `NodeResolverTest.aSpreadIsReplacedByTheElementsOfTheListItNames`, `V2ChainTest.superIsUsableAsTheBaseOfAFragment` |
| `REF.071` | `REJECT` | `DIAG.037` | `reject=DIAG.037` | `NodeResolverTest.aSpreadNamingSomethingOtherThanAListIsRefused` |
| `REF.072` | `MUST` |  |  | `NodeResolverTest.aSpreadElementCarriesNoOtherKey`, `PaletteV2DecodeTest.aSpreadElementCarriesNoOtherKey` |
| `REF.073` | `MUST` |  |  | `NodeResolverTest.elementsBeforeAndAfterASpreadKeepTheirPlaces` |
| `REF.074` | `MUST` |  |  | — |
| `REF.075` | `MUST` |  |  | — |
| `REF.080` | `MUST` |  |  | `ImportsTest.anAliasResolvesToWhatTheSamePointerWrittenInFullResolvesTo` |
| `REF.081` | `MUST` |  | `accept` | `ImportsTest.anAliasResolvesToWhatTheSamePointerWrittenInFullResolvesTo`, `PointerTest.anAliasIsSubstitutedTextuallyBeforeThePointerIsParsed` |
| `REF.082` | `MUST` |  | `reject=DIAG.070` | `ImportsTest.superMayNotBeDeclaredAsAnImportOfAPaletteOrOfADefinitionsAsset`, `PaletteV2DecodeTest.superCannotBeDeclaredAsAnImport`, `PointerTest.superCannotBeShadowedByAnImport` |
| `REF.083` | `REJECT` | `DIAG.039` | `reject=DIAG.039` | `PointerTest.anUnknownAliasIsRefusedRatherThanReadAsALocalName` |
| `REF.084` | `MUST NOT` |  |  | `PointerTest.aBareNameMayNotContainASlashOrBeginWithADollar` |
| `REF.085` | `MUST` |  |  | `PointerTest.aDiagnosticAboutAnExpandedPointerShowsTheExpansionAndTheWrittenForm` |
| `REF.086` | `MUST` |  |  | `ImportsTest.anAliasIsNotLentToTheFileAPointerReachesInto`, `NodeResolverTest.aPointedAtNodeResolvesItsOwnDocumentsNamesAndImports`, `V2ChainTest.importsAreNotInheritedThoughAnAncestorsOwnPointersStillResolve` |

### `palette/04-merging.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `MERGE.001` | `MUST` |  |  | `V2ChainTest.aChildRepaintsTheMarkersItDeclaresAndLeavesTheRestAlone`, `V2ChainTest.superFollowsADeepChainOneLayerAtATime` |
| `MERGE.002` | `MUST` |  |  | `V2ChainTest.aChildRepaintsTheMarkersItDeclaresAndLeavesTheRestAlone` |
| `MERGE.003` | `MUST` |  |  | `V2ChainTest.definitionsMergeByNameTheSameWayMarkersDo` |
| `MERGE.004` | `MUST` |  |  | `V2ChainTest.anOverriddenMarkerTakesItsTraitsWithIt` |
| `MERGE.005` | `MUST` |  | `accept` | `NodeResolverTest.superResolvesToTheInheritedValue`, `V2ChainTest.anEntryReplacesWhatItInheritsUnlessItNamesItWithSuper` |
| `MERGE.006` | `MUST` |  | `accept` | `V2ChainTest.redefiningADefinitionRepaintsEveryMarkerThatReferencesIt` |
| `MERGE.007` | `REJECT` | `DIAG.002` | `reject=DIAG.002` | `V2ChainTest.aPaletteIsRequiredSomewhereInTheChainAndNotInEveryFile` |
| `MERGE.008` | `MUST` |  |  | `V2ChainTest.anOverriddenMarkerTakesItsTraitsWithIt` |
| `MERGE.009` | `REJECT` | `DIAG.031` | `reject=DIAG.031` | `VersionDispatchTest.extendsInsideAnInlineVersionTwoPaletteIsRefused` |
| `MERGE.010` | `REJECT` | `DIAG.038` | *n/a* | `RegistryChainResolutionTest.aPaletteChainMayNotCrossFormatVersions` |
| `MERGE.011` | `MUST` |  |  | `VersionDispatchTest.anInlinePaletteIsReadByTheVersionItDeclares` |
| `MERGE.012` | `ACCEPT` |  | *n/a* | `VersionDispatchTest.anInlinePaletteMayCarryImportsAndDefs` |

### `palette/05-weights.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `WEIGHT.001` | `MUST` |  |  | `PaletteV2DecodeTest.aChoiceStatesItsSizeOnceInOneOfThreeSpellings` |
| `WEIGHT.002` | `REJECT` | `DIAG.040` | `reject=DIAG.040` | — |
| `WEIGHT.003` | `MUST` |  |  | `PaletteV2DecodeTest.aChoiceStatesItsSizeOnceInOneOfThreeSpellings` |
| `WEIGHT.004` | `MUST` |  |  | `PaletteV2DecodeTest.aChoiceStatesItsSizeOnceInOneOfThreeSpellings`, `PaletteV2DecodeTest.aFractionalWeightIsRefusedRatherThanTruncatedToAnInteger` |
| `WEIGHT.005` | `MUST` |  |  | `PaletteV2DecodeTest.aListCarryingASpreadOrAWhenIsNotSizeCheckedAsWritten` |
| `WEIGHT.010` | `MUST` |  | `accept` | — |
| `WEIGHT.011` | `MUST` |  | `accept` | — |
| `WEIGHT.012` | `MUST` |  |  | — |
| `WEIGHT.013` | `REJECT` | `DIAG.041` | `reject=DIAG.041` | — |
| `WEIGHT.014` | `REJECT` | `DIAG.045` | `reject=DIAG.045` | — |
| `WEIGHT.015` | `INVARIANT` |  |  | — |
| `WEIGHT.016` | `MUST` |  |  | — |
| `WEIGHT.017` | `MUST` |  | `accept` | — |
| `WEIGHT.018` | `MUST` |  |  | — |
| `WEIGHT.019` | `REJECT` | `DIAG.045` | *n/a* | — |
| `WEIGHT.020` | `MUST` |  | `accept` | — |
| `WEIGHT.021` | `MUST` |  | `accept` | — |
| `WEIGHT.022` | `MUST` |  |  | — |
| `WEIGHT.023` | `MUST` |  |  | — |
| `WEIGHT.024` | `REJECT` | `DIAG.043` | `reject=DIAG.043` | — |
| `WEIGHT.025` | `MUST NOT` |  |  | — |
| `WEIGHT.030` | `ACCEPT` |  | `accept` | — |
| `WEIGHT.031` | `MUST` |  |  | — |
| `WEIGHT.032` | `REJECT` | `DIAG.043` | `reject=DIAG.043` | — |
| `WEIGHT.040` | `MUST` |  |  | — |
| `WEIGHT.041` | `MUST` |  |  | — |
| `WEIGHT.042` | `INVARIANT` |  |  | — |
| `WEIGHT.043` | `MUST` |  |  | — |
| `WEIGHT.050` | `MUST` |  |  | — |
| `WEIGHT.051` | `MUST` |  |  | — |
| `WEIGHT.052` | `MUST` |  |  | — |
| `WEIGHT.053` | `INVARIANT` |  |  | — |
| `WEIGHT.060` | `MUST` |  |  | — |
| `WEIGHT.061` | `INVARIANT` |  |  | — |
| `WEIGHT.062` | `MUST` |  |  | — |
| `WEIGHT.063` | `REJECT` | `DIAG.044` | *n/a* | — |
| `WEIGHT.064` | `MUST` |  |  | — |

### `palette/06-characters.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `CHAR.001` | `MUST` |  |  | `PaletteV2DecodeTest.aMarkerOutsideTheBasicMultilingualPlaneIsOneMarkerAndNotTwoCharacters` |
| `CHAR.002` | `MUST` |  |  | `PaletteV2DecodeTest.aMarkerOutsideTheBasicMultilingualPlaneIsOneMarkerAndNotTwoCharacters` |
| `CHAR.003` | `REJECT` | `DIAG.050` | `reject=DIAG.050` | — |
| `CHAR.004` | `REJECT` | `DIAG.051` | `reject=DIAG.051` | — |
| `CHAR.005` | `REJECT` | `DIAG.052` | `reject=DIAG.052` | `PaletteV2DecodeTest.aCombiningMarkOrControlOrFormatOrPrivateUseCodepointIsRefused` |
| `CHAR.006` | `ACCEPT` |  | `accept` | `PaletteV2DecodeTest.spaceIsAValidMarkerBecauseEveryShippedPackUsesItForAir` |
| `CHAR.007` | `MUST NOT` |  |  | `PaletteV2DecodeTest.twoMarkersDifferingOnlyByNormalisationStayTwoMarkers` |
| `CHAR.010` | `MUST` |  |  | — |
| `CHAR.011` | `REJECT` | `DIAG.053` | *n/a* | — |
| `CHAR.020` | `MUST` |  |  | — |
| `CHAR.021` | `MUST` |  |  | — |
| `CHAR.022` | `REJECT` | `DIAG.054` | *n/a* | — |
| `CHAR.030` | `INVARIANT` |  |  | — |
| `CHAR.031` | `INVARIANT` |  |  | — |

### `palette/07-compilation.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `LOAD.001` | `MUST` |  |  | — |
| `LOAD.002` | `MUST` |  |  | — |
| `LOAD.003` | `MUST` |  |  | — |
| `LOAD.004` | `MUST` |  |  | `NodeResolverTest.everyFailureInOneNodeIsCollectedRatherThanTheFirst` |
| `LOAD.010` | `MUST` |  |  | — |
| `LOAD.011` | `INVARIANT` |  |  | — |
| `LOAD.012` | `MUST` |  |  | — |
| `LOAD.013` | `ACCEPT` |  | *n/a* | — |
| `LOAD.014` | `INVARIANT` |  |  | — |
| `LOAD.020` | `MUST` |  |  | — |
| `LOAD.021` | `MUST` |  |  | — |
| `LOAD.022` | `INVARIANT` |  |  | — |
| `LOAD.023` | `MUST` |  |  | — |
| `LOAD.024` | `INVARIANT` |  |  | — |
| `LOAD.025` | `MUST` |  |  | — |
| `LOAD.030` | `INVARIANT` |  |  | — |
| `LOAD.031` | `MUST NOT` |  |  | — |
| `LOAD.040` | `INVARIANT` |  |  | — |
| `LOAD.041` | `INVARIANT` |  |  | — |
| `LOAD.042` | `INVARIANT` |  |  | — |
| `LOAD.043` | `INVARIANT` |  |  | — |
| `LOAD.044` | `MUST` |  |  | — |
| `LOAD.050` | `MUST` |  |  | — |
| `LOAD.051` | `MUST` |  |  | — |

### `palette/08-errors.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `DIAG.900` | `MUST` |  |  | `DiagCatalogueTest.everyCatalogueRowNamesWhatItIsAboutAndWhatToWriteInstead`, `V2ChainTest.theTwoSentencesOfDiag036AreChosenByTheFileThatWroteTheEntry` |
| `DIAG.901` | `MUST` |  |  | `NodeResolverTest.aCompletenessDiagnosticNamesTheMarkerAndTheDefinitionItCameFrom` |
| `DIAG.902` | `MUST` |  |  | — |
| `DIAG.903` | `MUST` |  |  | `DiagCatalogueTest.severalDiagnosticsAreReportedTogetherRatherThanOneAtATime`, `NodeResolverTest.everyDiagnosticResolutionReportsCarriesItsCatalogueRow`, `NodeResolverTest.everyFailureInOneNodeIsCollectedRatherThanTheFirst`, `NodeResolverTest.aDiagnosticNamingSeveralPlacementListsOrdersThemByPlacement` |
| `DIAG.904` | `MUST` |  |  | `DiagCatalogueTest.aDiagnosticIsAnErrorOrAWarningAndThereIsNoThirdLevel` |
| `DIAG.001` | `DIAG` |  |  | — |
| `DIAG.002` | `DIAG` |  |  | — |
| `DIAG.003` | `DIAG` |  |  | — |
| `DIAG.004` | `DIAG` |  |  | — |
| `DIAG.005` | `DIAG` |  |  | — |
| `DIAG.006` | `DIAG` |  |  | — |
| `DIAG.007` | `DIAG` |  |  | — |
| `DIAG.008` | `DIAG` |  |  | — |
| `DIAG.009` | `DIAG` |  |  | — |
| `DIAG.010` | `DIAG` |  |  | — |
| `DIAG.011` | `DIAG` |  |  | — |
| `DIAG.012` | `DIAG` |  |  | — |
| `DIAG.020` | `DIAG` |  |  | — |
| `DIAG.021` | `DIAG` |  |  | — |
| `DIAG.022` | `DIAG` |  |  | — |
| `DIAG.023` | `DIAG` |  |  | — |
| `DIAG.024` | `DIAG` |  |  | — |
| `DIAG.025` | `DIAG` |  |  | — |
| `DIAG.030` | `DIAG` |  |  | — |
| `DIAG.031` | `DIAG` |  |  | — |
| `DIAG.032` | `DIAG` |  |  | — |
| `DIAG.033` | `DIAG` |  |  | — |
| `DIAG.034` | `DIAG` |  |  | — |
| `DIAG.035` | `DIAG` |  |  | — |
| `DIAG.036` | `DIAG` |  |  | — |
| `DIAG.037` | `DIAG` |  |  | — |
| `DIAG.039` | `DIAG` |  |  | — |
| `DIAG.038` | `DIAG` |  |  | — |
| `DIAG.070` | `DIAG` |  |  | — |
| `DIAG.071` | `DIAG` |  |  | — |
| `DIAG.072` | `DIAG` |  |  | — |
| `DIAG.073` | `DIAG` |  |  | — |
| `DIAG.040` | `DIAG` |  |  | — |
| `DIAG.041` | `DIAG` |  |  | — |
| `DIAG.045` | `DIAG` |  |  | — |
| `DIAG.042` | `DIAG` |  |  | — |
| `DIAG.043` | `DIAG` |  |  | — |
| `DIAG.044` | `DIAG` |  |  | — |
| `DIAG.050` | `DIAG` |  |  | — |
| `DIAG.051` | `DIAG` |  |  | — |
| `DIAG.052` | `DIAG` |  |  | — |
| `DIAG.053` | `DIAG` |  |  | — |
| `DIAG.054` | `DIAG` |  |  | — |
| `DIAG.060` | `DIAG` |  |  | — |
| `DIAG.061` | `DIAG` |  |  | — |
| `DIAG.062` | `DIAG` |  |  | — |
| `DIAG.063` | `DIAG` |  |  | — |
| `DIAG.064` | `DIAG` |  |  | — |
| `DIAG.910` | `MUST` |  |  | `DiagCatalogueTest.theDiagEnumCoversExactlyTheCatalogue`, `DiagCatalogueTest.everyDiagTemplateIsWordedAsItsCatalogueRowIs`, `DiagCatalogueTest.everyWordACatalogueRowStatesOutrightAppearsInItsTemplate`, `DiagCatalogueTest.aFormattedMessageIsRecognisedAsItsOwnDiagnostic`, `DiagCatalogueTest.everyCatalogueIdentifierIsLookedUpByItsId` |

### `palette/09-migration.md`

| Rule | Class | Diagnostic | Fixtures | Tests |
|---|---|---|---|---|
| `VER.001` | `MUST` |  |  | `VersionDispatchTest.aFileWithNoVersionIsReadAsVersionOne` |
| `VER.002` | `MUST` |  |  | `ImportsTest.aDefinitionsAssetDeclaresVersionTwoAndNothingElse`, `VersionDispatchTest.versionTwoSelectsTheVersionTwoRulesInFull` |
| `VER.003` | `MUST` |  |  | `VersionDispatchTest.aVersionTwoDocumentIsNeverHandedToTheVersionOneCodec` |
| `VER.004` | `MUST NOT` |  |  | `ImportsTest.theDefinitionsRegistryIsRegisteredAndVariantsStillIs`, `VersionDispatchTest.versionOneStillIgnoresAnUnknownKeyRatherThanRefusingIt` |
| `VER.005` | `REJECT` | `DIAG.038` | *n/a* | `RegistryChainResolutionTest.aPaletteChainMayNotCrossFormatVersions` |
| `VER.006` | `ACCEPT` |  | *n/a* | — |
| `VER.007` | `MUST NOT` |  |  | — |
| `VER.013` | `ACCEPT` |  | *n/a* | — |
| `VER.015` | `REJECT` | `DIAG.063` | *n/a* | `VersionDispatchTest.aVersionTwoPaletteIsRefusedWhereItIsCompiledRegisteredOrInline` |
| `VER.016` | `REJECT` | `DIAG.064` | `reject=DIAG.064` | `NodeResolverTest.aSatellitesNodeIsNotCompletedAsAnAlternative`, `NodeResolverTest.anyOperandAnywhereInsideATraitIsRefused` |
| `VER.008` | `MUST` |  |  | `ImportsTest.aDefinitionsAssetIsAVariantThatMayAlsoCarryTraitsAndAnyKind` |
| `VER.009` | `MUST` |  |  | — |
| `VER.010` | `REJECT` | `DIAG.060` | `reject=DIAG.060` | `PaletteV2DecodeTest.aRenamedVersionOneKeyIsRefusedNamingTheKeyThatReplacedIt` |
| `VER.011` | `REJECT` | `DIAG.061` | `reject=DIAG.061` | `PaletteV2DecodeTest.aDeletedVersionOneKeyIsRefusedSayingWhatToWriteInstead` |
| `VER.012` | `MUST NOT` |  |  | `PaletteV2DecodeTest.noRetiredKeyIsSilentlyAcceptedOrSilentlyIgnored` |
| `VER.020` | `MUST` |  |  | — |
| `VER.021` | `MUST` |  |  | — |
| `VER.022` | `MUST` |  |  | — |
| `VER.023` | `MUST` |  |  | — |
| `VER.030` | `MUST` |  |  | — |
| `VER.031` | `MUST` |  |  | — |
| `VER.040` | `MUST` |  |  | `VersionDispatchTest.theVersionMechanismIsRegistryAgnostic` |
| `VER.041` | `MUST` |  |  | — |
| `VER.014` | `RETIRED` |  |  | — |

