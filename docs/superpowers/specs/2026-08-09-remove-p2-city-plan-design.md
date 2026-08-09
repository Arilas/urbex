# Remove the unused P2 city planner

Date: 2026-08-09
Status: approved

## Purpose

Remove the unused P2 city-planning prototype from `main` so near-term improvements can build on the
smaller Urbex 0.1.0 foundation without carrying an abandoned road model or JSON contract.

## Scope

Revert merge commit `96b2f27d` using its first parent as the mainline. This removes the complete P2
change set: the pure planning package, planner tests, P2 plan and design documents, JSON fixtures,
HTML viewer, and the `runPlanDump` Gradle task.

P2 extracted deterministic hash calculations from `Rng` into `dev.krona.urbex.plan.Hash` so the pure
planner could use them without depending on Minecraft. With the planner removed, restore the same
calculations directly inside `Rng` and remove `Hash`. The constants and operation order must remain
unchanged, preserving all addressed-randomness outputs.

The experimental `feat/p3-road-system` branch is outside this change and remains untouched.

## History strategy

Create a normal revert commit on `main`. Do not reset or force-push the published branch. This keeps
the P2 experiment and its removal visible in history while restoring the pre-P2 implementation.

## Resulting state

Apart from this decision record, the post-revert tree must match `v0.1.0` (`2e820249`). The retained
product is the Fabric 26.2 Urbex fork with deterministic, parallel world generation and the inherited
Lost Cities chunk-grid layout. No P2 planner source, test, fixture, viewer, or build task may remain.

## Verification

1. Compare the post-revert tree with `v0.1.0`, excluding this decision record.
2. Search for P2 planner packages, viewer assets, and `runPlanDump`; expect no matches or files.
3. Run the complete Gradle test and build lifecycle.
4. Confirm `main` contains the design commit and the P2 revert commit and has no uncommitted changes.
