---
name: sentinel
description: >-
  Runs a mandatory local review loop over the exact repository change set for
  correctness, security, tests, architecture, operations, and project-specific
  contracts. Use after implementing bug fixes, improvements, behavior-changing
  refactors, deployment changes, or other production-impacting work and before
  declaring the task complete.
---

# Sentinel

Sentinel is the final local quality gate for a change. It reviews the actual
Git change set—not the story we tell ourselves about it—then feeds actionable
findings back into implementation.

Sentinel is intentionally project-shaped. The shared workflow lives here, but
the repository must supply its own rules and checklist. A global install with
no project integration is a reviewer without a map: technically present,
practically lost.

The installed catalog version is recorded in [VERSION](VERSION). A marker
ending in `-custom` protects intentional changes to managed Sentinel files from
automatic installer upgrades; replacing it requires explicit user-approved
force.

Read [INSTALLATION.md](INSTALLATION.md) before adopting the skill and
[CHECKLIST-GUIDE.md](CHECKLIST-GUIDE.md) when creating the repository's
[CHECKLIST.md](CHECKLIST.md).

## Mandatory contract

Use Sentinel for:

- bug fixes and feature improvements
- behavior-changing refactors
- schema, API, deployment, CI, secret, or runtime configuration changes
- tests intended to prove production behavior

A task is not complete until:

1. implementation and focused tests are complete
2. Sentinel reviews the exact task change set
3. P1 findings and applicable P2 findings are corrected
4. affected checks are rerun
5. Sentinel re-reviews the corrections
6. any material deferral is explicitly accepted and tracked
7. the final response includes the Sentinel attestation

Do not silently waive the gate because the diff is small. If project governance
defines narrower applicability, follow that explicit policy.

The Sentinel review phase is read-only. It reports findings; the implementing
agent makes corrections.

## 1. Freeze the review boundary

At the beginning of implementation, record:

- starting `HEAD` SHA
- current branch
- pre-existing staged, unstaged, and untracked paths
- explicitly relevant ignored paths
- user-requested scope

At review time, assemble the task change set from:

- commits after the starting SHA
- staged and unstaged changes
- untracked files created for the task
- ignored local files explicitly requested by the user

Use Git status and diffs directly. Do not substitute memory or a prose summary.
Exclude unrelated pre-existing user work.

If Sentinel is loaded after implementation and no boundary was recorded:

1. inspect current status and recent branch commits
2. map files to the requested change
3. state the inferred boundary
4. ask once if unrelated changes cannot be separated safely

Record:

```text
Base SHA: <full SHA>
Head SHA: <full SHA>
Working tree included: <staged/unstaged/untracked paths>
Excluded pre-existing paths: <paths or none>
```

## 2. Load project policy

Before reviewing, read:

1. root `AGENTS.md`
2. the nearest package/module `AGENTS.md` for each changed area
3. applicable project rules
4. the installed Sentinel `CHECKLIST.md`
5. quality, architecture, security, and deployment docs referenced by those
   files

Repository policy and package quality gates are part of correctness. If the
project has not customized `CHECKLIST.md`, report that as an adoption gap and
use only the generic checks in this skill.

## 3. Build a change model

Summarize privately:

- requested user-visible or operator-visible outcome
- runtime path that implements it
- changed files and modules
- persistent, API, deployment, and cross-repository contracts touched
- failure scenario or limitation being addressed
- tests that prove the intended outcome

Map every changed path to that model. Flag unrelated edits, missing companion
changes, and generated output without its source change.

Identify generated or derived files early. Verify source/derived consistency
without spending the review budget reading repetitive generated content line by
line.

## 4. Review passes

### Pass A — correctness and contracts

Check:

- happy path, boundaries, and failure paths
- async races, stale writes, retries, idempotency, and partial success
- pagination, timeouts, cancellation, cleanup, and resource ownership
- validation at untyped or untrusted boundaries
- schema, migration, query, model, and serialization agreement
- producer/consumer agreement across APIs and packages
- configuration precedence and runtime environment agreement
- CLI and CI behavior from a clean checkout

Trace changed code into unchanged consumers where behavior depends on them.

### Pass B — security and operational safety

Check:

- plaintext secrets, unsafe logs, argv leakage, and sensitive examples
- authentication and authorization on changed endpoints/actions
- URL/protocol validation, path traversal, injection, and shell quoting
- least-privilege permissions and credential isolation
- fail-open defaults and destructive operations
- rollback, dry-run, cleanup, and recovery fidelity
- external calls without deadlines or bounded retries

Never include a real secret value in a finding. Cite only its key or location.

### Pass C — tests and quality gates

Require a meaningful test for the observable outcome that motivated the
change—not merely an intermediate implementation detail.

Check:

- tests fail when the original defect or limitation is restored
- happy, error, and relevant edge behavior are covered
- mocks exercise application behavior rather than themselves
- cross-package and deployment contracts have appropriate guards
- package scripts and CI invoke the relevant tests
- selected quality scope follows project policy

Do not request tests that add no signal. State the smallest useful regression
test.

### Pass D — architecture and maintainability

Check:

- logic remains behind the correct service/module boundary
- special-case branching does not spread through shared flows
- canonical helpers are reused instead of duplicated
- wrappers add meaningful abstraction
- complexity and file-size limits from project policy remain satisfied
- related state changes cannot be left half-applied
- independent work is not needlessly serialized
- docs and operational instructions match behavior

Behavioral correctness is not enough when the change creates an obvious
structural regression.

### Pass E — absence and anti-pattern sweep

Explicitly look for:

- swallowed errors or false success after partial failure
- missing auth, validation, or permission checks
- changed startup-read config without a rollout trigger
- enabled features with missing required configuration
- comments or docs contradicting implementation
- missing migration, schema, consumer, generated, or cleanup changes
- tests covering mechanism but not motivation
- accidental cache, debug, secret, or generated artifacts
- commit or branch policy violations defined by the project

## 5. Finding quality bar

Include a finding only when:

- the task introduced or materially worsened it
- the path is realistically reachable
- impact is concrete
- file/line anchors refer to the current working tree
- remediation addresses the root cause
- a stronger finding does not already cover it

| Severity | Meaning | Required action |
|---|---|---|
| P1 — blocker | Incorrect behavior, security exposure, data loss, broken contract, or unusable operational flow | Fix before completion |
| P2 — warning | Material test, maintainability, documentation, deployment, or structural risk | Fix when local; otherwise obtain an explicit tracked deferral |
| P3 — suggestion | Optional simplification or polish | Apply when clearly beneficial and in scope |

Do not invent findings to make the review look useful. A clean review is valid.

## 6. Feedback loop

After the first pass:

1. return a numbered finding list to the implementing agent
2. implement P1 and in-scope P2 fixes
3. add or improve regression tests where needed
4. rerun affected functional and quality checks
5. review the updated diff again
6. repeat until no blocking findings remain

Do not expand a focused fix into unrelated cleanup. Track material out-of-scope
risks separately.

## 7. Report

Use the project-configured review directory. If none is configured, use
`.sentinel/reviews/` and tell the user to add it to `.gitignore`.

```markdown
# Sentinel Review — <change title>

**Branch:** `<branch>`
**Base SHA:** `<base>`
**Head SHA:** `<head>`
**Working tree included:** <paths>
**Pass:** <N>

## Verdict
**<Clean / Feedback required / Blocked / Deferred by user>.**

<Decisive evidence and quality-check status.>

## Change summary
<Requested outcome, implementation path, and contracts touched.>

## Findings
### P1-01 — <actionable title>
**File:** `path/to/file.ext:<line>`
<Failure scenario, evidence, impact, and concrete remediation.>

## Tests and quality checks
- <command>: <result>

## Feedback applied
- <finding ID>: <correction and verification>

## Verified contracts
- <important contract checked and found consistent>

## Deferred items
- <ticket + explicit acceptance, or "None">
```

Omit empty finding subsections. Keep verified-contract notes concise.

## 8. Final attestation

Every completed in-scope change ends with:

> Sentinel was used. Its feedback was applied: <brief summary, or "no
> corrective feedback was required">.

Also report:

- Sentinel pass count
- checks rerun after feedback
- explicitly accepted tracked deferrals

Do not claim Sentinel was used unless the actual local change set was reviewed.
Do not claim feedback was applied while a finding remains unresolved.
