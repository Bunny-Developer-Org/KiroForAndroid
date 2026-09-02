# ADR-004: How the app selects the work repository for a cloud session

- **Status:** Proposed
- **Date:** 2026-09
- **Depends on:** [ADR-001](ADR-001-cloud-session-access.md) (bridge topology). Refines assumption **A4**, which ADR-001 left as a one-line risk.
- **Scope:** How a repository gets bound to a cloud session, and therefore what the app's repository picker can actually be built out of. Does not decide bridge hosting (see [ADR-005](ADR-005-bridge-hosting-and-availability.md)).

---

## 1. The question

[F-11](../FEATURES.md) — the New Cloud Session flow, the feature the app exists for — asks for "repository multi-select from the user's connected GitHub/GitLab account." ADR-001 recorded this as **assumption A4** ("repository selection is reachable programmatically") and moved on.

That was too fast. A4 conflates two questions that turn out to have different answers:

1. **Binding** — can the app cause a cloud session to be created against repositories X and Y? 
2. **Enumeration** — can the app find out what X and Y *are*, so the user picks from a list instead of typing?

The answer to the first is yes, cleanly. The answer to the second is no, not through any documented interface. This ADR separates them and decides what the picker does about it.

---

## 2. What we found: how `kiro-cli` actually selects the work repo

### 2.1 Evidence

| Fact | Source |
|---|---|
| A cloud session is started with `kiro-cli --cloud` (equivalently `kiro-cli chat --cloud`); repositories are attached with `--repo`, or with the `/repo` picker once the session is running | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/), [CLI changelog 2.17](https://kiro.dev/changelog/cli/2-17/) |
| `/repo` "adds repositories to the current cloud session" and is an interactive picker; one or several repositories may be chosen | [Slash commands](https://kiro.dev/docs/reference/slash-commands/), [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |
| Repositories are identified as `owner/repo` | [Cloud sessions blog](https://kiro.dev/blog/cloud-sessions/) |
| Cloud sessions require **a connected GitHub or GitLab account** and a Pro-or-higher plan | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |
| GitHub is connected in **Kiro's settings → Agent tab → Connect GitHub**, which authorizes the **Kiro Agent GitHub App**. The App is installed once per account or organization; an org owner chooses which repositories it can reach | [GitHub — connect your repositories](https://kiro.dev/docs/web/github/), [github.com/apps/kiro-agent](https://github.com/apps/kiro-agent) |
| A user sees a repository only where **both** hold: the Kiro Agent App is installed and authorized for it, **and** their own GitHub account has access to it. Tasks can only be assigned where the user has **write** permission | [GitHub — connect your repositories](https://kiro.dev/docs/web/github/) |
| GitLab is connected with a **personal access token** pasted into Kiro's settings. `Project (Read)` must be granted under the *User* section or Kiro cannot list projects. Self-managed instances must be reachable from the internet | [GitLab — connect your repositories](https://kiro.dev/docs/web/gitlab/) |
| GitHub and GitLab repositories can be **mixed in one session**; the agent opens a PR on one and an MR on the other as appropriate | [Coordinating changes across GitLab and GitHub](https://kiro.dev/blog/coordinating-changes-across-gitlab-and-github-in-one-session/) |
| Kiro provisions the sandbox, **clones the selected repositories**, and runs the prompt there. The agent creates a feature branch from the default branch, commits, pushes, and opens a PR/MR | [Working with the agent](https://kiro.dev/docs/web/using-the-agent/), [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |
| Project configuration under `.kiro/` (steering, specs, custom agents, hooks, MCP servers) applies in cloud sessions **because the sandbox clones the repo** | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |
| The session's files live in the sandbox, **not in your workspace**; affordances that operate on local files — file/folder context pickers — are unavailable in cloud sessions | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |
| Local sessions are stored in a **per-directory** database and are scoped to the current working directory. Sessions started with `--cloud` are stored in **your account's cloud session store** instead, and resume from any machine | [Session management](https://kiro.dev/docs/cli/chat/session-management/) |
| Cloud sessions appear alongside local ones in `--list-sessions` and the `/sessions` picker, with columns for **environment** and **status** | [Session management](https://kiro.dev/docs/cli/chat/session-management/) |
| The repository set is **fixed at creation** (the CLI may add more later with `/repo`); to work on different repositories, start a new session. **No branch can be chosen** when attaching — ask the agent to check one out once running | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |

> **Source status — read this before trusting the table above.**
>
> `kiro.dev` is blocked by this environment's egress proxy (gateway 403 on CONNECT), so those pages were reached only through search-result *summaries*, never fetched. **No flag syntax above has been read verbatim.**
>
> Worse, the docs appear to lag the CLI. A snapshot of `docs/reference/cli-commands` mirrored into a public repo lists `--resume`, `--resume-picker`, `--list-sessions` and `--delete-session` for `chat` — and **no `--cloud`, `--repo`, `--resume-id`, or `acp`** — while user reports put the current CLI at **2.20.1** ([kirodotdev/Kiro#11127](https://github.com/kirodotdev/Kiro/issues/11127)) and cloud sessions at 2.17. That snapshot also gives one verbatim line worth keeping: `--list-sessions` is *"List all saved chat sessions **for the current directory**"*, which is the directory scoping §2.2 relies on.
>
> **`kiro-cli --help` is the tiebreaker, not the docs.** Where this ADR and the CLI disagree, the CLI wins and this ADR is wrong. F-01 must capture `kiro-cli --help`, `kiro-cli chat --help` and `kiro-cli acp --help` verbatim and correct §2.1 from them.

### 2.2 The model this implies

Three separate things, and keeping them separate is the whole insight:

```
  ①  Provider connection            ②  Session binding              ③  Sandbox clone
     (account-level, one-time)          (per session, at creation)      (server-side)

  Kiro settings → Agent tab          kiro-cli --cloud --repo o/r     Kiro clones o/r into
  → Connect GitHub  (App install)    kiro-cli chat  → /repo picker   the sandbox, agent works,
  → GitLab PAT                                                       pushes a branch, opens a PR
     ↓                                    ↓                                ↓
  server-side allow-list of          list of owner/repo strings       git credentials are the
  repos on the Kiro account          sent to Kiro's cloud             App's / the PAT's, in the
                                                                      cloud — never on any client
```

**The decisive finding: for a cloud session, `kiro-cli` does not derive the work repo from the current directory, a local checkout, or a local git remote.** It is not a workspace-relative operation at all. The CLI passes `owner/repo` names; the repositories are resolved against the *account's* connected provider and cloned server-side. Local sessions are the directory-scoped ones — cloud sessions are explicitly stored account-side and resume from any machine.

This is the opposite of what we assumed when writing ADR-001, and it matters a great deal.

---

## 3. Would it work for our mobile app?

**Yes — and this is the most favourable finding of the project so far.** Three things follow directly:

- **The phone needs no checkout.** Selecting a work repo is choosing a name from a server-side list, not pointing at a directory. A device with no filesystem access to the user's code loses nothing here.
- **The bridge host needs no checkout either, and no git credentials.** ADR-001 implicitly imagined the bridge sitting next to the user's source tree. It doesn't have to. Cloning and pushing happen in Kiro's sandbox using the Kiro Agent App's or the PAT's credentials. This materially shrinks what the bridge is — see [ADR-005](ADR-005-bridge-hosting-and-availability.md).
- **The bridge's working directory is irrelevant to cloud sessions.** Cloud sessions live in the account store, not the per-directory database, so the bridge can be started from anywhere without hiding sessions. (It should still pin one stable `cwd` so that *local* sessions from a stray directory never leak into our list; filter on the `environment` column regardless.)

The parts that do **not** work cleanly are narrower, and all three are about *enumeration and setup*, not about binding:

**Blocker 1 — there is no documented way to list the user's repositories.** `--repo` accepts names; nothing documented hands them out. The interactive `/repo` picker clearly has the list, but a TUI picker is not an API. Without enumeration, F-11's "repository multi-select" degrades to a text field, which on a phone keyboard, for a string like `some-org/service-payments-api`, is a genuinely bad experience and a rich source of typos.

**Blocker 2 — connecting a provider cannot be done from the app or the CLI.** Installing the Kiro Agent GitHub App, or pasting a GitLab PAT, happens in Kiro's own settings UI. Neither our app nor `kiro-cli` can perform it. A user who has never used Kiro Web will have an empty repository list and no in-app way to fix it.

This one is survivable: it is a *browser* step, and we already open browsers for sign-in (F-08). Sending the user to Kiro's settings page in a Custom Tab is the same pattern, on the same phone. It is one-time, and it is Kiro's own UI holding Kiro's own credentials — which is exactly where that should happen.

**Blocker 3 — `/repo` is interactive.** If binding at creation via `--repo` turns out not to be drivable from the bridge (ADR-001 A1/A2), the fallback is a TUI picker, and driving a TUI by writing keystrokes into a pty is precisely the brittleness ADR-001 exists to avoid. This is a real risk to F-11 and F-01 must settle it.

---

## 4. Options for the picker

The binding half is settled: **pass `owner/repo` names at creation**. What follows are the options for the enumeration half.

### Option A — Free text `owner/repo`, no list

The user types the repository. Validate the shape client-side; let the server reject unknown ones.

- **Pro:** zero dependency on undocumented surfaces. Works the day the bridge works. Trivially testable.
- **Con:** poor on a phone; typo-prone; gives no discovery for a user who does not remember exact casing or the org prefix.
- **Verdict:** **Accepted as the floor.** Never the only affordance, never removed — it is what still works when everything else is unavailable.

### Option B — Drive the `/repo` picker through the commands extension

[ACP-INTEGRATION §5](../ACP-INTEGRATION.md#5-kiro-extensions) documents `commands/available`, `commands/options` (autocomplete for a partial command) and `commands/execute`. If `/repo` participates, `commands/options` returns candidate repositories and `commands/execute` attaches them — a real list and a real binding, over the documented ACP extension surface.

- **Pro:** the only path to a genuine picker that does not invent a credential or a private endpoint. Reuses machinery F-19 builds anyway.
- **Con:** the extensions are documented as experimental and subject to change, and even the namespace prefix is [contradictory in Kiro's own docs](ADR-001-cloud-session-access.md#1-the-problem). Whether `/repo` exposes options at all is unknown. May only work *after* a session exists, which inverts F-11's flow (create, then attach) relative to `--repo` at creation.
- **Verdict:** **Preferred when it works.** Strictly an enhancement layered on Option A.

### Option C — Scrape the interactive TUI

Run the CLI under a pty, drive `/repo`, parse the rendered picker.

- **Pro:** would work today against whatever the CLI does.
- **Con:** parsing a TUI is parsing a UI. It breaks on a padding change. It is the same class of fragility ADR-001 rejected in Option A, minus the terms-of-service problem.
- **Verdict:** **Rejected.** If B fails and the only route to a list is screen-scraping, ship Option A and say why.

### Option D — Query GitHub/GitLab directly with the user's own credential

Have the app (or bridge) hold a separate GitHub OAuth token or GitLab PAT and list repositories from the provider's public, documented API.

- **Pro:** documented, stable APIs; a rich picker; offline-cacheable.
- **Con:** and this is the killer — **it lists the wrong set.** Kiro shows the intersection of "the Kiro Agent App can reach it" and "you can reach it"; GitHub's API shows only the second. Every repository where the App is not installed would appear pickable and fail at session creation, and on an org-owned repository that is the *common* case, not the edge case. It also introduces a second credential with repository scope into an app whose whole security story ([ADR-001 §3](ADR-001-cloud-session-access.md#3-decision)) is that it holds none.
- **Verdict:** **Rejected for v1.** Revisit only if the provider API can be filtered by App installation (`GET /user/installations/{id}/repositories` is the candidate for GitHub) *and* the user opts in explicitly. Even then it is a convenience layer over Option A, never the source of truth.

### Option E — Recent repositories, derived from the user's own sessions

Cloud sessions are listable and carry their repository bindings. Every session the user has ever created is evidence of a repository they can actually use.

- **Pro:** no new surface, no new credential, and it is *verified* data — a repository that worked before will work again. On a phone, "the four repos you actually use" beats an alphabetical list of two hundred.
- **Con:** empty for a new user; never complete.
- **Verdict:** **Accepted, always on.** The cheapest large UX win available, and it degrades to Option A on first run.

---

## 5. Decision

**Bind by `owner/repo` name at session creation. Build the picker as three layers over one interface, and let it lose layers without losing function.**

```kotlin
// core/src/main/kotlin/dev/kiro/core/model/SourceRepo.kt
data class SourceRepo(
    val owner: String,
    val name: String,
    val provider: Provider,          // GITHUB | GITLAB | UNKNOWN
) { val slug: String get() = "$owner/$name" }

// core/src/main/kotlin/dev/kiro/core/session/RepoCatalog.kt
interface RepoCatalog {
    /** Candidates for a partial query. MAY be empty — that is not an error. */
    suspend fun suggest(query: String): List<SourceRepo>
    /** Repos bound to sessions this account has already created. */
    suspend fun recent(): List<SourceRepo>
    /** Shape-only. Cannot prove the repo exists or is reachable. */
    fun parse(input: String): Result<SourceRepo>
}
```

Three implementations, tried in order, each falling through:

1. **`CommandsRepoCatalog`** (Option B) — `suggest()` via `commands/options`. Returns empty and logs one metric if the extension is absent, mis-prefixed, or errors. Never throws into the UI.
2. **`SessionHistoryRepoCatalog`** (Option E) — `recent()` from `listSessions()`, deduplicated, most-recently-used first. Persisted so it survives a cold start with no bridge.
3. **`ManualEntryCatalog`** (Option A) — `parse()` only, and always reachable in the UI as "enter a repository."

Four rules that go with it:

- **Manual entry is never hidden behind a failure state.** It is a permanent affordance in the picker, not a fallback screen. A picker that only appears when the list fails teaches users the app is broken.
- **Validation is honest about what it can prove.** `parse()` checks shape — `owner/repo`, sane characters, no leading slash, no `.git` suffix, no URL. It must not imply the repository exists or that Kiro can reach it. Only the server can say that, and its rejection is what the user sees.
- **A rejected repository names the likely reason.** The overwhelmingly probable cause is "the Kiro Agent App is not installed for that repository," not "you typed it wrong." The error surface says so and offers the Custom Tab to Kiro's settings.
- **Onboarding includes provider connection as an explicit step,** with a Custom Tab into Kiro's settings → Agent tab and a re-check on return. An empty repository list is a *setup* state with an action, never an empty list with a shrug.

And two things the UI must not offer, because Kiro cannot honour them:

- **No branch picker.** Branches cannot be selected at attach time. Show the default-branch behaviour as a fact and offer a "ask the agent to work on a new branch" affordance in the first prompt instead — which is the documented workaround, turned into a button.
- **No repository editing after creation.** The set is fixed at creation. `/repo` may extend it, but "change the repositories" is "start a new session," and the UI should say that rather than presenting a set that looks editable.

---

## 6. What this costs us

- **The picker is only as good as an experimental extension.** If `commands/options` does not cover `/repo`, most users see recents plus a text field. That is a real UX regression against Kiro Web's pill-based picker, and F-11 should not pretend otherwise in its copy.
- **First-run is the worst run.** A new user has no recents and possibly no connected provider, so their first encounter with the headline feature is a text field and a trip to a browser. Onboarding has to carry that weight deliberately.
- **We inherit an invisible permission model.** "Installed and authorized for that repository" is org-owner state we cannot see, cannot change, and cannot explain precisely. The best we can do is fail with the right guess about why.
- **Multi-provider is a presentation problem we get for free and should not squander.** Sessions can mix GitHub and GitLab; a picker that shows an undifferentiated list of slugs will confuse. Provider is part of the identity, so it is part of the pill.

---

## 7. Assumptions to verify — extends ADR-001 §5, same numbering

`A4` in ADR-001 is hereby **superseded** by A7–A12. F-01's brief grows accordingly.

> **Two of these were answered incidentally by the F-01 spike ([PROTOCOL-FINDINGS.md](../PROTOCOL-FINDINGS.md)), which ran before this ADR was merged but was investigating adjacent ground.** Verdicts folded into the table below; the rest of A7–A12 is unrun.

| # | Assumption | Risk if wrong |
|---|---|---|
| A7 | `--repo` accepts multiple repositories in one invocation, and the exact syntax is known (comma-separated vs. repeated flag). [ADR-001 §1](ADR-001-cloud-session-access.md#what-is-publicly-documented) already asserts `--repo owner/repo,...`, which is plausible but was not read verbatim either | Low — trivially discoverable from `--help`; only affects how the bridge builds the argv. **Get it verbatim; both tables currently infer it.** Note this may be moot: F-01 bound sessions via ACP dispatch metadata, not `--repo`, and never needed the flag — see A8. |
| A8 | A cloud session can be created with repositories bound **non-interactively**, i.e. `--repo` alone suffices and no TUI picker step is forced | **High** — if creation always drops into `/repo`, F-11 depends on driving a TUI (Option C, rejected) and must be redesigned. **Still open.** F-01 exercised `session/list` and `session/load` on existing cloud sessions but never called `session/new` with `executionTarget: cloud-sandbox` and a repository attached — this is the one binding-at-creation experiment nobody has run yet. |
| A9 | `commands/options` covers `/repo` and returns repository candidates | Medium — decides whether the picker is a picker. Falls back to A + E by design, so it degrades rather than blocks. **Superseded, favourably.** F-01 found a better mechanism already live: `_kiro/sourceProviders/list` and `/listResources` return the full repository catalog directly, with `visibility` and `defaultBranch`, no slash command involved. Build `CommandsRepoCatalog` against these methods instead of `commands/options` — Option B in §4 stands, its implementation is just simpler than assumed. |
| A10 | Repository bindings are readable back from a session (via `session/load`, `--list-sessions`, or an extension) | Medium — without it, Option E has no source and the session list cannot show which repos a session touches. **Verified.** `session/list` and `session/load` both return `_meta.kiro.repositories[]` (`providerType`, `name`, `url`) on every cloud session record. |
| A11 | A repository unknown to the account fails at **creation** with a distinguishable error, rather than silently or deep inside the first turn | Medium — decides whether we can point at the real cause; a generic failure makes the "App not installed" hint a guess. **Still open** — untested. |
| A12 | Nothing about cloud-session creation depends on the CLI's working directory or on a local git checkout | Low, but load-bearing — §2.1 says cloud sessions live in the account store, and the whole "no checkout on the bridge" conclusion rests on it. Cheap to confirm: run `--cloud --repo` from an empty `/tmp` directory. **Still open** — untested, though consistent with F-01's finding that dispatch is entirely metadata-driven with no filesystem interaction observed. |

Remaining for F-01's follow-up: confirm A8 with an actual `session/new` bound to a repository; confirm A11's error shape for an unreachable repository; confirm A12 from an empty directory. A7 and A9 may be moot given the ACP-native mechanisms F-01 already found — check those first before spending time on the `--repo`/`/repo` CLI path at all.

---

## 8. Consequences

- **F-01** gains four concrete experiments (A7–A12 above) and loses the vague "determine how repositories are bound programmatically."
- **F-11** is unblocked for binding and conditionally blocked for enumeration. It can be built end-to-end today against `ManualEntryCatalog` + `SessionHistoryRepoCatalog`, with `CommandsRepoCatalog` slotting in when F-01 reports on A9.
- **F-03** (bridge) needs neither a checkout nor git credentials nor a meaningful `cwd`. See [ADR-005](ADR-005-bridge-hosting-and-availability.md).
- **F-05** grows `RepoCatalog` alongside `CloudSessionGateway`; both live behind the same seam so a future official API replaces both at once.
- **Onboarding** grows a provider-connection step. It is a Custom Tab, so it costs one screen, not an architecture.
- **README** should stop implying the bridge sits next to the user's code. It doesn't need to.

---

*External facts are cited inline and paraphrased. See the source-status note in §2.1 — kiro.dev was unreachable, nothing was read verbatim, and the docs appear to lag the CLI. Treat `kiro-cli --help` as authoritative over both the docs and this ADR.*
