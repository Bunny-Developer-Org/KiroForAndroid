# Customizing the Sentinel Checklist

The shared Sentinel workflow knows *how* to review. Your checklist teaches it
what your project can break.

A useful checklist is an executable map of repository contracts. It should turn
“review carefully” into concrete questions such as “did this API change
regenerate the TypeScript client?” or “does this ConfigMap change trigger a pod
rollout?”

## 1. Gather project evidence

Build the checklist from facts already enforced or relied upon:

1. root and package-level `AGENTS.md`
2. contributor, architecture, security, and operations docs
3. CI pipelines and local quality scripts
4. package manifests and build tooling
5. schema/migration conventions
6. deployment templates and environment overlays
7. recurring production incidents and review findings

Do not copy generic style advice merely to make the checklist longer. Sentinel
already performs generic correctness, security, test, and architecture passes.
The project checklist should add facts the model cannot safely infer.

## 2. Map the repository

Start with module ownership and quality scope:

```markdown
## Module and quality-scope mapping

- `api/` — public REST contract; run `pnpm --filter api test` and regenerate
  `clients/typescript/` when the OpenAPI schema changes.
- `database/` — shared schema; assess all consumers and run the full integration
  suite.
- `web/` — React UI; API calls must use the generated client.
- `deploy/helm/` — render every changed environment overlay.
```

For each module, answer:

- What does it own?
- Which other modules consume its output?
- Which command proves it still works?
- When does a local edit become cross-cutting?
- Which generated or deployment artifacts must move with it?

## 3. Capture hard contracts

Write checks as observable invariants:

```markdown
- Database migrations are forward-safe for existing rows.
- New API fields appear in the schema, server DTO, generated client, and
  consumer tests.
- Startup-read ConfigMap values have a rollout checksum.
- External HTTP calls use the shared timeout policy.
```

Avoid vague prompts:

```markdown
- Follow best practices.
- Make sure security is good.
- Check the architecture.
```

If a reviewer cannot point to evidence for pass/fail, rewrite the item.

## 4. Organize by risk surface

Use only sections that fit the repository. Common choices:

- repository-wide policy
- module and quality-scope mapping
- language/framework constraints
- API and generated clients
- database and migrations
- async jobs, retries, and state machines
- frontend data flow
- shell and CLI
- Kubernetes, Terraform, or other deployment tooling
- CI, release, and rollback
- secrets, authentication, and IAM
- cross-repository contracts
- final stopping check

Keep shared rules in one place. Do not repeat the same invariant under five
modules unless each module has a distinct failure mode.

## 5. Encode quality commands precisely

Name the real command and when it applies:

```markdown
- A change isolated to `service-a/` runs
  `pnpm --filter service-a lint && pnpm --filter service-a test`.
- Changes to `packages/contracts/` run `pnpm test:all` because every service
  consumes those types.
- Helm changes run `./scripts/render-chart.sh` for each touched overlay.
```

Include:

- focused test command
- lint/type/build command
- full-workspace trigger
- generated-code validation
- deployment/render/smoke tests
- known exclusions that need a separate command

Do not turn the checklist into a second CI implementation. It tells Sentinel
which commands and evidence matter; project scripts remain the source of
execution logic.

## 6. Add lessons from real failures

The highest-value items often come from incidents:

```markdown
- A terminal job cannot be overwritten by a late retry event.
- Optional secrets stay optional through generation, validation, and runtime.
- Cancellation removes both persistent state and the owned external resource.
```

Add a check when:

- the same defect class appeared more than once
- the contract crosses files or services and is easy to miss
- CI cannot cheaply enforce it
- a reviewer needs domain knowledge to recognize the risk

Prefer adding an automated test or static check when the invariant is fully
machine-verifiable. Sentinel complements automation; it should not cosplay as a
slower linter.

## 7. Set project policy in `AGENTS.md`

`CHECKLIST.md` describes what to inspect. `AGENTS.md` defines when Sentinel is
required and what blocks completion.

Document:

- which changes trigger Sentinel
- whether docs-only or test-only changes are included
- required severity handling
- how deferrals are tracked and approved
- required quality scope
- review report location
- final attestation wording, if the project needs extra fields

Repository governance wins if it conflicts with the generic skill.

## 8. Review checklist quality

Before committing the customized checklist:

- [ ] Every item is project-specific or points to concrete project evidence.
- [ ] Commands exist and run from the documented working directory.
- [ ] Module paths and ownership are current.
- [ ] Generated-source relationships are explicit.
- [ ] Cross-package and deployment contracts are represented.
- [ ] Secret values or internal credentials are never embedded.
- [ ] Duplicates and generic filler are removed.
- [ ] The checklist is short enough to load during every review.
- [ ] `AGENTS.md` makes the gate part of the completion lifecycle.

## 9. Maintain it

Update the checklist when:

- modules or quality commands change
- architecture boundaries move
- a new deployment/runtime contract is introduced
- a Sentinel finding reveals a recurring blind spot
- a checklist item becomes automated and can be removed or shortened

Review it periodically like any other production control. Stale guidance is not
harmless—it sends reviewers confidently in the wrong direction, which is a
particularly fancy way to trip over the same rake twice.
