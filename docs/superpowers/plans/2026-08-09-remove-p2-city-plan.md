# Remove the Unused P2 City Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the unused P2 city-planning prototype from `main` while retaining published history and preserving Urbex 0.1.0 world-generation behavior.

**Architecture:** Revert the complete P2 merge against its first parent so Git removes the experiment at its original boundary. Keep the approved cleanup design and implementation plan as the only intentional differences from the `v0.1.0` tree, then verify both structural absence and the full Gradle lifecycle before committing the revert.

**Tech Stack:** Git, Java 25, Gradle, Fabric/Minecraft 26.2

## Global Constraints

- Revert merge commit `96b2f27d6ed36cb3bff153656b4fbd94bea71fe8` with mainline parent 1.
- Preserve published history; do not reset or force-push `main`.
- Restore deterministic hash calculations directly inside `Rng` with unchanged constants and operation order.
- Remove all P2 planner source, tests, documents, viewer files, fixtures, and the `runPlanDump` Gradle task.
- Leave `feat/p3-road-system` untouched.
- Preserve the existing untracked `.superpowers/` and `logs/` directories.

---

### Task 1: Revert and verify the complete P2 merge

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/java/dev/krona/urbex/varia/Rng.java`
- Delete: `docs/superpowers/plans/2026-08-03-urbex-p2-city-plan.md`
- Delete: `docs/superpowers/specs/2026-08-03-urbex-p2-city-plan-design.md`
- Delete: `src/main/java/dev/krona/urbex/plan/**`
- Delete: `src/test/java/dev/krona/urbex/plan/**`
- Delete: `viewer/**`
- Preserve: `docs/superpowers/specs/2026-08-09-remove-p2-city-plan-design.md`
- Preserve: `docs/superpowers/plans/2026-08-09-remove-p2-city-plan.md`

**Interfaces:**
- Consumes: Git merge `96b2f27d` and its first parent `2e820249` (`v0.1.0`).
- Produces: `main` with the P2 change set reversed, `Rng` self-contained again, and no plan/viewer runtime or development interface.

- [ ] **Step 1: Confirm the execution boundary**

Run:

```bash
git branch --show-current
git show -s --format='%H %P %s' 96b2f27d
git status --short --branch
```

Expected: current branch is `main`; the merge has first parent `2e820249`; only the two approved 2026-08-09 decision/plan documents may be tracked beyond `origin/main`; `.superpowers/` and `logs/` may appear as untracked and must remain untouched.

- [ ] **Step 2: Apply the merge reversal without committing it**

Run:

```bash
git revert --no-commit -m 1 96b2f27d6ed36cb3bff153656b4fbd94bea71fe8
```

Expected: Git stages the inverse of the P2 merge without conflicts and leaves the two 2026-08-09 documents present.

- [ ] **Step 3: Prove that the staged tree has the intended boundary**

Run:

```bash
git diff --cached --check
git diff --exit-code v0.1.0 -- . ':(exclude)docs/superpowers/specs/2026-08-09-remove-p2-city-plan-design.md' ':(exclude)docs/superpowers/plans/2026-08-09-remove-p2-city-plan.md'
git diff --cached --name-status
```

Expected: both diff checks exit 0. The staged name list contains the inverse of the 68-file P2 merge and does not contain either 2026-08-09 document.

- [ ] **Step 4: Prove that no P2 implementation or tool remains**

Run:

```bash
test ! -d src/main/java/dev/krona/urbex/plan
test ! -d src/test/java/dev/krona/urbex/plan
test ! -d viewer
! rg -n 'runPlanDump|dev\.krona\.urbex\.plan|PlanJson|PlanQuery|CityPlan' build.gradle src/main src/test
```

Expected: every command exits 0. `Rng.java` has no import from `dev.krona.urbex.plan` and owns the deterministic mixing logic directly.

- [ ] **Step 5: Run the complete verification lifecycle**

Run:

```bash
./gradlew test build
```

Expected: Gradle exits 0; all tests pass and the production jar is built successfully.

- [ ] **Step 6: Commit the P2 reversal**

Run:

```bash
git commit -m 'Revert "Merge P2: the city plan"' -m 'This reverts commit 96b2f27d6ed36cb3bff153656b4fbd94bea71fe8, reversing changes made to 2e820249d7c6ed688c2f340784f21feb676a1fae.'
```

Expected: Git creates one revert commit containing only the inverse P2 changes.

- [ ] **Step 7: Verify the committed result**

Run:

```bash
git log --oneline --decorate -4
git diff --exit-code v0.1.0..HEAD -- . ':(exclude)docs/superpowers/specs/2026-08-09-remove-p2-city-plan-design.md' ':(exclude)docs/superpowers/plans/2026-08-09-remove-p2-city-plan.md'
git status --short --branch
```

Expected: the first two commands show the design/plan records and P2 revert while the diff exits 0. `main` is ahead of `origin/main`; only the pre-existing untracked `.superpowers/` and `logs/` directories remain.
