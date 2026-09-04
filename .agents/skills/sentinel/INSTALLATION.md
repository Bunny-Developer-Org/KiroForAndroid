# Installing Sentinel

Sentinel is not a collectible badge for your global skills folder. Its value
comes from being wired into a repository's definition of “done”: project rules,
quality commands, architecture boundaries, and an explicit review loop.

## Recommended installation

Open the target repository in Cursor and use the
[agent-assisted Sentinel guide](../../docs/install-sentinel-with-an-agent.md).
The installing agent should run the script, customize the checklist from
verified project facts, reconcile governance, and prove automatic activation.

From a local Skill Issue checkout, use the project installer:

```bash
python3 path/to/skill-issue/scripts/install-sentinel.py \
  --target path/to/project
```

The installer copies the skill and seeds a marked quality-gate block in the
project's `AGENTS.md`. The installed `VERSION` starts at `1.0.0`. Reruns compare
that marker with the catalog, do nothing at the same version, and automatically
upgrade an older version while preserving the project-owned `CHECKLIST.md`.

If only the remote catalog is available, install the skill files directly:

```bash
npx skills add ssh://git@bitbucket.regnology.net:7999/orange/skill-issue.git \
  --skill sentinel \
  --agent cursor \
  --copy
```

For Cursor, the skill normally lands under:

```text
.agents/skills/sentinel/
```

Do not rely on a global install as the team-wide gate. A global copy can help
you experiment, but teammates and CI-facing agents need the same committed
contract.

## Embed it into project governance

Installing the files is step one. The important bit is making the repository
tell agents when Sentinel is mandatory.

Add a section like this to the root `AGENTS.md`:

```markdown
## Sentinel quality gate

For every bug fix, feature improvement, behavior-changing refactor, schema/API
change, or deployment/CI/runtime configuration change:

1. Record the starting Git boundary before implementation.
2. Complete focused implementation and tests.
3. Run `.agents/skills/sentinel/SKILL.md` against the exact task change set.
4. Apply P1 and in-scope P2 findings, rerun affected checks, and run Sentinel
   again.
5. Do not declare completion without the Sentinel attestation.

Use `.agents/skills/sentinel/CHECKLIST.md` for this repository's architecture,
quality, security, and deployment contracts.
```

If the repository already has an end-of-task quality section, integrate the
Sentinel steps there instead of creating competing “done” definitions.

Also consider:

- adding package-level guidance where modules have different gates
- linking the checklist from architecture and contributor docs
- requiring the attestation in PR templates
- teaching orchestration prompts to record the starting SHA
- ignoring `.sentinel/reviews/` if review reports should stay local

## Customize the checklist

The included `CHECKLIST.md` is a starting frame, not a universal truth tablet.
Follow [CHECKLIST-GUIDE.md](CHECKLIST-GUIDE.md) and replace generic prompts with
the repository's real:

- module ownership and boundaries
- quality commands and scope rules
- schema/API/generated-code contracts
- runtime and deployment invariants
- security and secret-handling rules
- language/framework constraints
- commit, ticket, and release policy

Keep the checklist committed beside the skill. Review it whenever architecture
or delivery policy changes.

## Choose a report location

Sentinel defaults to:

```text
.sentinel/reviews/
```

If reports are local-only, add:

```gitignore
.sentinel/reviews/
```

If review reports are intended as tracked evidence, configure a repository
location in `AGENTS.md` and do not ignore it.

## Smoke test the adoption

Before calling the installation done:

1. Make a harmless test change on a throwaway branch.
2. Ask the agent to implement and finish it without mentioning Sentinel.
3. Confirm the repository guidance causes Sentinel to run automatically.
4. Confirm it reads the customized checklist and invokes the correct project
   checks.
5. Confirm the final response includes the attestation and pass count.

If Sentinel only runs when someone remembers its name, it is installed but not
embedded. That is the skill equivalent of putting a smoke detector in a drawer.

## Updating

Catalog updates may improve the shared workflow. Run the normal installer
again; if the catalog's `VERSION` is newer, managed files update automatically.
The project checklist and governance integration remain preserved and must be
reconciled with any upstream behavior changes.

If you intentionally customize a catalog-managed Sentinel file, change
`.agents/skills/sentinel/VERSION` from, for example, `1.0.0` to
`1.0.0-custom`. Any `-custom` version is protected even when the catalog is
newer. The installer stops and requires the user to explicitly approve:

```bash
python3 path/to/skill-issue/scripts/install-sentinel.py \
  --target path/to/project \
  --force
```

Force replaces managed files and resets `VERSION` to the catalog version. It
still preserves `CHECKLIST.md`; resetting project-owned files additionally
requires `--replace-project-files`, which itself requires `--force`.
