# Feature backlog

Work items for **KiroForAndroid** — an Android client for [Kiro](https://kiro.dev) cloud sessions.

Each item is scoped to be picked up independently by one agent. Read [§ How to pick up an item](#how-to-pick-up-an-item) before starting, and read [ADR-001](adr/ADR-001-cloud-session-access.md) first — it constrains every item here. [ADR-004](adr/ADR-004-work-repo-selection.md) (how a work repository is chosen) and [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md) (where the bridge runs, and what the app does when it is unreachable) refine it and touch F-01, F-03, F-07, F-11, F-15 and F-16.

**Legend** — Size: `S` <1d · `M` 1–3d · `L` 1–2w · `XL` 2w+. `∥` = safe to run in parallel with its phase-mates.

---

## Phase 0 — Unblock (nothing downstream is trustworthy until this is done)

### F-00 · Pursue official third-party API access · `S` ∥
**Not a code task.** ADR-001 rejects reverse-engineering Kiro's private cloud API, which leaves the bridge topology as the only legitimate option. Official access would remove that constraint entirely, so it is worth asking early even though the answer may be no or silence.

- **Prior art — three open requests, and demand is fragmented across them** (surveyed 2026-09-02):

  | Issue | Surface | State |
  |---|---|---|
  | [#6099](https://github.com/kirodotdev/Kiro/issues/6099) | IDE | Open, 19 reactions, labelled `keep-open`. Highest demand signal of the three. |
  | [#7993](https://github.com/kirodotdev/Kiro/issues/7993) | **CLI** | Open, labelled `pending-maintainer-response`. **Closest to our brief — join this one.** |
  | [#9460](https://github.com/kirodotdev/Kiro/issues/9460) | IDE | Open. Maintainer replied **2026-07-16**: added to the backlog for future consideration, with a request to upvote. |

  Two corrections to what this item previously said. First, #9460 is **no longer unanswered** — "acknowledged and backlogged" is a weaker signal than a commitment but a stronger one than silence, and Option C's timeline should be re-read in that light. Second, #9460 is the *wrong* ticket to join: it is explicitly about the **IDE** session and explicitly says it does **not** want the `kiro-cli` + ACP path. #7993 asks for remote CLI sessions from web/mobile, which is our surface.

  Note also that Kiro's duplicate-detection bot has repeatedly tried to merge these three into each other and been talked out of it each time. The practical effect is that demand never accumulates into one number a PM would act on — worth saying out loud when we comment.
- **Do:** join [#7993](https://github.com/kirodotdev/Kiro/issues/7993) (and upvote #6099) rather than filing a fourth ticket; ask specifically about third-party client / API access for **cloud** sessions, which none of the three requests covers; ask on the [Kiro Discord](https://discord.gg/kirodotdev); check whether an Android app is on the public roadmap.
- **Done when:** the outcome is recorded in ADR-001 §2 Option C with a date, whatever the outcome. "No reply after N weeks" is a valid, recorded result.
- **Depends on:** nothing.

### F-01 · Protocol spike: verify assumptions, capture golden fixtures
`M` · ✅ **A1–A6 DONE 2026-09-02** · **A7–A18 open**

<!-- Keep the status out of this heading: three other documents link to its anchor,
     and every status edit would silently break them. -->

**Was the highest-value item in this backlog**, and it paid — for the six assumptions ADR-001 §5 originally listed. Report: **[PROTOCOL-FINDINGS.md](PROTOCOL-FINDINGS.md)**. Verdicts are in [ADR-001 §5](adr/ADR-001-cloud-session-access.md#5-assumptions--resolved-by-f-01-on-2026-09-02); fixtures are in [`core/src/test/resources/fixtures/`](../core/src/test/resources/fixtures/); the probe that produced them is [`tools/acp-probe/`](../tools/acp-probe/).

**Outcome in one line:** the architecture holds. A1, A2, A4, A5 verified; A3 refuted harmlessly (the prefix is `_kiro/`, and it is discoverable from the handshake); A6 partially refuted (sign-in is *more* interactive than assumed).

**Read PROTOCOL-FINDINGS before starting anything below.** Three of its findings change work already scoped here:

1. **`kiro-cli acp` needs `--agent-engine v3 --auth-method cli`.** The default engine cannot see cloud sessions at all — a client using default flags would wrongly conclude the whole approach is dead.
2. **KAS already implements much of F-03** — WebSocket ACP transport, multi-client multiplexing, permission correlation across reconnects, pending-permission re-send on attach.
3. **The documented streaming update list is wrong and incomplete.** Real kinds are `snake_case`, there is no `TurnEnd` kind, and a large set of `_kiro/*` notifications is undocumented entirely.

**Left open from the original six:** A5 was verified on a *local* session only — the cloud path is argued from KAS's design, not observed. F-03 confirms it on its first cloud turn.

**Extended, not yet run:** written in parallel with the spike above and merged afterward, [ADR-004 §7](adr/ADR-004-work-repo-selection.md#7-assumptions-to-verify--extends-adr-001-5-same-numbering) adds **A7–A12** (repository binding and enumeration — supersede A4) and [ADR-005 §7](adr/ADR-005-bridge-hosting-and-availability.md#7-assumptions-to-verify--extends-adr-001-5-and-adr-004-7) adds **A13–A18**. These were *not* exercised by the run above and remain open. One is already partly answered by PROTOCOL-FINDINGS without anyone having asked it that way: **A9** speculates that `commands/options` might expose `/repo` candidates, but the spike found something better already live — `_kiro/sourceProviders/list` and `/listResources` return the full repository catalog directly, no slash command needed (see [PROTOCOL-FINDINGS §3 A4](PROTOCOL-FINDINGS.md#a4--repositories-are-bindable-programmatically--verified-and-better-than-assumed)). ADR-004's Option B should be read in that light before anyone builds a `commands/options`-based catalog. Still genuinely open: **A8** (can repos be bound at creation with no forced TUI step?), and A13–A17 (multi-bridge fungibility, permission durability on a cloud session, headless credential survival, concurrent-session memory, on-device feasibility, and `KIRO_API_KEY` on the `acp` surface).

- **Do, for [ADR-004](adr/ADR-004-work-repo-selection.md) (A7, A8, A10–A12):** capture `--repo`'s syntax verbatim from `--help`; confirm a session can be created with repos bound non-interactively, with no forced `/repo` step; run that creation from an empty `/tmp` directory to confirm no working-directory or checkout dependency; confirm bindings are readable back from a loaded session; capture the error frame for a repository the account cannot reach.
- **Do, for [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md) (A13–A16):** attach to one session from **two** hosts signed in as the same account; leave a permission request unanswered with no client attached on a **cloud** session, then reattach and check it is re-presented; leave a headless host idle overnight and confirm the CLI is still signed in; measure memory for several concurrent supervised sessions.
- **A18 — `KIRO_API_KEY` · ✅ VERIFIED 2026-09-02.** Answer and consequences: [PROTOCOL-FINDINGS §4b](PROTOCOL-FINDINGS.md#4b-a18--kiro_api_key-authenticates-the-acp-surface--verified), [ADR-005 §7](adr/ADR-005-bridge-hosting-and-availability.md#7-assumptions-to-verify--extends-adr-001-5-and-adr-004-7), [AUTHENTICATION §3b](AUTHENTICATION.md#3b-alternative-for-auth-2--api-key-provisioning-verified-2026-09-02). Original brief, kept for the record:
- **Do, for A18 — `KIRO_API_KEY`:** headless mode shipped in **Kiro CLI 2.0 (2026-04-13)** with API-key auth via the `KIRO_API_KEY` environment variable ([docs](https://kiro.dev/docs/cli/headless/), [kirodotdev/Kiro#5938](https://github.com/kirodotdev/Kiro/issues/5938)). The docs describe it only for `chat --no-interactive`. Establish the two things they do not say: **(a)** does `kiro-cli acp --agent-engine v3` authenticate from `KIRO_API_KEY` alone, with no prior `kiro-cli login`? **(b)** if it does, does that session reach **cloud** sessions, or only local? Run it in a container with no `~/.kiro` credential state to be sure nothing is falling back to a cached login.
- **Why A18 matters:** a yes to both would let a bridge be provisioned by pasting one key, removing the pty-driven provider-picker TUI that [A6](PROTOCOL-FINDINGS.md#a6--login---use-device-flow-is-scriptable--partially-refuted) forces on F-03 and F-08, and would largely dissolve A15. It is not a replacement for the browser-OAuth product requirement — the key authenticates the *bridge host*, not the user's phone — and it carries a real cost: the key is long-lived with no documented scoping, TTL, or rotation, a tradeoff argued at length by `clouatre` on #5938. Pro/Pro+/Pro Max/Power only, and admin-managed accounts must have generation enabled. Record the verdict in ADR-005 §7 and the consequences in [AUTHENTICATION.md](AUTHENTICATION.md).
- **Done when:** each remaining assumption gets a verified/refuted verdict written back into the ADR that owns it — A7–A12 in ADR-004 §7, A13–A16 in ADR-005 §7. (A17 is F-24's job, not this one's.)
- **Depends on:** access to `kiro-cli` + a Pro account — already established; reuse the probe in [`tools/acp-probe/`](../tools/acp-probe/) rather than starting over.

### F-24 · Spike: can `kiro-cli` run on the device? · `S` ∥
Timeboxed, non-blocking, high upside. [ADR-005 §4 Option E](adr/ADR-005-bridge-hosting-and-availability.md#option-e--the-bridge-on-the-phone) notes that `kiro-cli` ships for Linux `aarch64`, and Termux with a `proot-distro` userland is Linux `aarch64`. If it runs there, the bridge requirement — ADR-001's single largest cost — disappears without needing official API access.

- **Do:** install `kiro-cli` under Termux + `proot-distro`; sign in; create a cloud session; leave it running while the screen is off. Stop at the first hard blocker.
- **Done when:** ADR-005 assumption A17 is answered yes or no in writing, with the blocker named. A one-paragraph "no, because X" is a complete and valuable result.
- **Scope out:** productising it. Even a success changes an ADR before it changes any code — Doze, battery, and holding a signed-in Kiro account on a phone are separate questions.
- **Depends on:** nothing.

### F-25 · Transcript and picker defects found on-device (2026-09-03) · `M` · 🟡 **5 of 6 DONE 2026-09-03**
Six defects observed in a live run on an emulator against a real cloud session
(`Bunny-Developer-Org/KiroForAndroid`, prompt answered end to end). Grouped as
one item because they are all in the create-session and transcript screens.

- **Do:**
  1. **Repo picker is a flat always-open list.** It should collapse: picking a
     repo closes the list and shows the selection; a `+` control adds another.
     Today [`CreateSessionScreen`](../app/src/main/kotlin/dev/kiro/android/ui/create/CreateSessionScreen.kt)
     renders every repository inline, so the whole form is buried under it.
  2. **The transcript never shows which model is in use.** Nothing on screen
     names it, so the operator cannot tell what a turn cost or what answered.
  3. **The model cannot be changed.** The plumbing exists —
     [`BridgeGateway.setModel`](../core/src/main/kotlin/dev/kiro/core/session/BridgeGateway.kt)
     and `AcpMethods.SESSION_SET_MODEL` — but **no UI calls it**: `grep -rn
     setModel app/src/main/kotlin` returns nothing. A picker should set the
     model for the operator's next message.
  4. **Too much dead space under the composer** at the bottom of the
     transcript.
  5. **Agent responses are rendered as raw text, not Markdown.** `##`, `**`
     and backticks appear literally on screen — confirmed in the live run, and
     `grep -rni markdown app/src/main core/src/main` finds no handling at all.
     Needs a renderer that is safe on partial input, because text arrives in
     chunks (see F-08's streaming contract) and must not flicker mid-token.
  6. **The session list shows archived sessions.** `grep -rni archiv` over
     `core/src/main` and `app/src/main` finds nothing, so nothing is filtered
     and no archive state is modelled anywhere yet.
- **Status:** 1–5 fixed and verified on an emulator against a live session.
  6 is **blocked upstream** and cannot be fixed here: the backend has the state
  (`SpaceSummary.status`, enum `ACTIVE`/`INACTIVE`) but KAS calls `ListSpaces`
  with no filter and `spaceToSummary()` drops the field, so it never reaches any
  client. Either KAS forwards a filter or it carries `space.status` into
  `_meta.kiro`; both are changes in `kiro-cli`/KAS. See PROTOCOL-FINDINGS §4e.
  *Unverified:* that `INACTIVE` is what the web UI calls "archived".
- **Found while fixing this, not in the original list:** the create screen's
  mode chips never took effect. Nothing was selected by default, and a selected
  mode was sent as a top-level `agentMode` while KAS reads `_meta.kiro.modeId` —
  so every cloud session this app has ever created ran the server's default.
  Fixed with the rest.
- **Done when:** each of the six is either fixed or, where it needs a protocol
  affordance we do not have, written up with the specific missing piece named.
- **Scope out:** a full Markdown feature set. Headings, bold, inline code,
  lists and fenced blocks cover what the agent actually emits; tables and HTML
  can wait.
- **Depends on:** nothing. All six are app-layer.

---

## Phase 1 — Foundation

### F-02 · Project scaffold, modules, CI · `M` · ✅ **DONE 2026-09-02**
- **Do:** create the three-module Gradle project exactly as specified in [ADR-003 §2](adr/ADR-003-tech-stack.md#2-module-layout) (`app/`, `core/`, `bridge/`), with the version catalog from ADR-003 §1. Add CI: build, unit tests, ktlint/detekt, and **a check that fails if `core/` contains any `android.*` or `androidx.*` import** (ADR-003's one hard rule — enforce it mechanically). Pin `JAVA_HOME` to JDK 17 or 21 in CI; AGP rejects JDK 22+.
- **Done when:** `./gradlew build` is green from a clean clone, CI runs on PRs, and the core-purity check demonstrably fails when someone adds an Android import.
- **Also:** this item **fixes the DI pattern** for the project (manual `ServiceLocator` or Hilt). Whatever it picks, record it in ADR-003 §1 so later items don't diverge.
- **Depends on:** nothing. Start immediately, in parallel with F-01.

### F-03 · Bridge service (MVP) · `L` · ✅ **DONE 2026-09-02** (except reconnect-mid-turn replay, which needs a real turn)
The host-side process from ADR-001. Not a dev convenience — it is the product's backend. [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md) decides its shape: it is a **thin relay** — no checkout, no git credentials, no meaningful working directory (pin one anyway so stray *local* sessions never enter our list, and filter on the `environment` column) — and it ships as a **multi-arch container image** with a safe-by-default network posture.

- **Start by reading [PROTOCOL-FINDINGS §4](PROTOCOL-FINDINGS.md#4-the-larger-finding-kas-already-solves-most-of-f-03).** This item was sized `L` partly to build reconnect, replay, and cross-connection permission correlation from scratch. KAS ships all three. **Do not build them before establishing what you can reuse** — the honest first task here is a day of reading, not a week of coding.
- **Do:** a process that runs where `kiro-cli` is installed and: spawns/supervises `kiro-cli acp --agent-engine v3 --auth-method cli` (the flags are mandatory — see PROTOCOL-FINDINGS §2); exposes JSON-RPC over an authenticated WebSocket; issues single-use pairing codes and long-lived revocable device tokens; and preserves ordered replay across reconnects.
- **Replay: try `messageId` before inventing sequence numbers.** Every update already carries `_meta.kiro.messageId` and `timestamp`, and `session/load` replays history in order. The `_bridge/…` scheme in [ACP-INTEGRATION §7](ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros) is our design, not Kiro's, and may now be redundant. Record which way you went and why.
- **Login needs a pty, not a pipe.** `kiro-cli login --use-device-flow` opens an interactive provider-picker TUI before printing anything parseable (PROTOCOL-FINDINGS A6). Budget for it.
- **Confirm A5 on your first cloud turn** and update ADR-001 §5.
- **Security requirements** (from [AUTHENTICATION §4](AUTHENTICATION.md#4-auth-1-pairing-the-app-to-the-bridge), all mandatory): binds `127.0.0.1` by default with explicit opt-in to `0.0.0.0`; TLS required for non-loopback; pairing codes single-use and ~5 min TTL; rate-limited pairing; revocable device list.
- **Done when:** a WebSocket client can pair, create a cloud session, send a prompt, receive streamed updates, disconnect mid-turn, reconnect with `lastSeq`, and receive exactly the missed updates with no gap and no duplication.
- **Decide and record:** bridge language (Kotlin/JVM vs Node/TS) per [ADR-003 §2](adr/ADR-003-tech-stack.md#bridge-language--open-decision). Write the outcome back into that ADR.
- **Depends on:** F-01 (needs verified CLI behaviour).

### F-04 · ACP protocol layer in `core/` · `M` ∥ · ✅ **DONE 2026-09-02**
- **Do:** JSON-RPC 2.0 codec (request / response / notification / server-initiated request), `AcpTransport` interface, `AcpClient` handling correlation, timeouts, and cancellation. Implement the handshake from [ACP-INTEGRATION §2](ACP-INTEGRATION.md#2-handshake) — including advertising `fs` and `terminal` as **false**, which is intentional and differs from Kiro's editor example. `ignoreUnknownKeys = true` throughout.
- **Derive the extension prefix from `initialize`**, don't match constants. The handshake enumerates the agent's extension methods and the namespace is server-configurable. (`_kiro/` is what 2.19.2 sends.)
- **Model the real update set, not the documented one.** Most session state arrives as `session_info_update` discriminated by `_meta.kiro.kind` — `turn_start`, `turn_end`, `context_usage`, `focus_update`, `pendingInteraction`, `interactionResolved`, `promptTurnSummaries`, `displayError`. There is no `TurnEnd` update kind. See [PROTOCOL-FINDINGS §5](PROTOCOL-FINDINGS.md#5-corrections-to-the-documented-protocol).
- **Done when:** unit tests pass against a fake transport and against F-01's golden fixtures in [`core/src/test/resources/fixtures/`](../core/src/test/resources/fixtures/); an unknown method or malformed frame is logged and dropped without terminating the session (test this explicitly — F-01 found a large undocumented `_kiro/*` notification set, so this is load-bearing, not polish).
- **Depends on:** F-02. Fixtures are ready.

### F-05 · `CloudSessionGateway` + `BridgeGateway` · `M` · ✅ **DONE 2026-09-02**
- **Do:** the interface every feature codes against — `createSession`, `listSessions`, `loadSession`, `prompt`, `cancel`, `setModel`, `setMode`, `respondToPermission`, `respondToUserInput`, and `updates: Flow<SessionUpdate>`. Plus `BridgeGateway`, implementing it over F-04.
- **Shapes fixed by F-01:** `listSessions` takes a source (`local`/`remote`/`all`) and a scope (`workspace`/`user`/`both`); a session carries **two** statuses — `status` (is the agent working) and `instanceStatus` (is the sandbox VM up) — and they must stay distinct all the way to the UI. `respondToUserInput` exists because `_kiro/userInput` is a second human-in-the-loop channel alongside permissions.
- **Why it matters:** this is the seam that makes ADR-001's decision reversible. If official API access ever lands, it is a second implementation, not a rewrite.
- **Done when:** no UI code anywhere references a transport, a socket, or the bridge directly; a `FakeGateway` exists so UI items can be built and tested without a bridge.
- **Depends on:** F-04.

### F-06 · Secure credential storage · `S` · ✅ **DONE 2026-09-02**
- **Do:** `TokenStore` interface in `core/`, implemented in `app/` with **AndroidKeyStore + DataStore**. Do **not** use `androidx.security:security-crypto` — it is deprecated and its own guidance points at AndroidKeyStore.
- **Done when:** device tokens survive restart, are absent from cloud backup and device transfer (`dataExtractionRules`), are wiped on logout, and are never written to logs.
- **Depends on:** F-02.

---

## Phase 2 — Sign in (the OAuth-via-web-link requirement)

Read [AUTHENTICATION.md](AUTHENTICATION.md) in full before starting any of these. The two-authentication split (app↔bridge vs bridge↔Kiro) is the thing to get right.

### F-07 · Bridge pairing UX · `M` · 🟡 **PARTIAL** — manual entry, honest onboarding copy, distinct error states, and the multi-bridge list (add/remove/switch) done 2026-09-02; QR scan is not
- **Do:** onboarding that pairs the app to a bridge — QR scan (bridge prints a QR of `wss://host:port` + pairing code) plus manual entry fallback. Clear, non-generic error states for unreachable host, bad code, expired code, TLS failure.
- **Done when:** a user can pair by scanning, the token persists via F-06, and a wrong/expired code produces a message that says what to do next.
- **Multi-bridge list shipped 2026-09-02**: `BridgeListScreen` (add another bridge, remove with confirmation, switch active bridge) against the already-built `BridgeRegistry`/`DataStoreBridgeRegistry`. QR scanning remains undone — it needs a new camera dependency (no CameraX/ML-Kit/ZXing in the catalog today) and is a separately-scoped follow-up.
- **Onboarding must state honestly that a bridge host is required** (ADR-001 §4) — on the first screen, not buried in a help page. Follow the five-step order in [ADR-005 §5.4](adr/ADR-005-bridge-hosting-and-availability.md#54-onboarding-tells-the-truth-in-this-order): state the requirement → recommend an always-on host and name what a workstation-only bridge costs → pair → sign in to Kiro → **connect a source provider** in Kiro's own settings via Custom Tab. Skipping the last step leaves F-11's repository picker empty with nothing to explain why.
- **Pair with a *list* of bridges, not one** ([ADR-005 §5.2](adr/ADR-005-bridge-hosting-and-availability.md#52-multiple-bridges-are-a-supported-configuration-not-an-accident)). Sessions live in the Kiro account, so any bridge signed in as the same account can reach them; store bridges with a per-bridge last-seen.
- **Depends on:** F-03, F-06.

### F-08 · Kiro sign-in via device flow, relayed through the app · `M`
**This is the user-facing "sign in with your Kiro account via a web link" feature.**

- **Do:** implement the relay in [AUTHENTICATION §3](AUTHENTICATION.md#3-primary-flow--device-authorization-relayed-through-the-app). App asks the bridge to start sign-in **and names the provider**; bridge drives `kiro-cli login --use-device-flow` over a **pty**, answers its provider-picker TUI, and returns the verification URI + user code; app opens the URI in a **Custom Tab** and displays the code prominently with copy-to-clipboard; bridge polls; app reflects completion.
- **Corrected by F-01 (A6):** provider selection happens in the *CLI*, not the browser, so the app must present the four providers itself — Builder ID, Google, GitHub, Your Organization. The verification URI already carries the user code as a query parameter, so the Custom Tab can arrive pre-filled. And `login` **refuses while already signed in** — re-auth requires an explicit `kiro-cli logout`, which must be a deliberate user action, never an automatic retry.
- **Hard requirements:** Custom Tabs only — **never a WebView** ([RFC 8252 §8.12](https://datatracker.ietf.org/doc/html/rfc8252#section-8.12)). Handle `authorization_pending`, `slow_down`, `access_denied`, `expired_token`. Probe `kiro-cli whoami` first and skip the flow if already signed in. The user code must stay visible when returning to the foreground.
- **Done when:** a signed-out bridge host can be brought to signed-in entirely from the phone, and no Kiro credential is ever stored in the app.
- **Depends on:** F-03, F-07. Output format and its interactive prelude are pinned by [PROTOCOL-FINDINGS A6](PROTOCOL-FINDINGS.md#a6--login---use-device-flow-is-scriptable--partially-refuted).

### F-09 · Auth & entitlement state machine · `S`
- **Do:** the seven-state machine in [AUTHENTICATION §6](AUTHENTICATION.md#6-session-and-error-states-the-ui-must-handle), driving navigation.
- **Do not skip `NotEntitled`.** Cloud sessions require Pro or higher, and Identity Center orgs additionally require an admin to enable the preview. Without this state, an unentitled user gets an opaque failure at session creation and no idea why.
- **You cannot pre-check entitlement.** F-01 found `whoami --format json` returns only `accountType` and `email` (and `{"account":null}` when signed out) — no plan or entitlement field. So `NotEntitled` has to be derived from the error a cloud create actually returns, not from a capability probe.
- **Done when:** every state has a distinct screen or banner and a stated next action; state survives process death.
- **Depends on:** F-07, F-08.

---

## Phase 3 — Create and run a cloud session

### F-10 · Session list, resume, delete · `M` · ✅ **DONE 2026-09-02**
- **Do:** list cloud sessions with live status, ordered by recent activity; resume into F-12; delete with confirmation; pin.
- **Show both statuses.** A session carries `status` (`idle`/`in_progress`) and `instanceStatus` (the sandbox VM's own lifecycle, e.g. `suspended`). "Idle but suspended" and "idle and warm" mean different wait times when you tap in, and only one of them is instant.
- **The roster pushes itself.** `_kiro/sessions/changed` delivers upserts and deletes with status transitions, so the list can be live without polling.
- **Note:** renaming is not supported in the cloud-session preview — don't build the affordance.
- **Done when:** the list reflects real status, survives rotation and process death, and handles the empty state as an invitation to create a session rather than a blank screen. **Shipped 2026-09-02**: delete (confirmation dialog, wired to the already-working `gateway.deleteSession`) and pin (client-local, DataStore-persisted, sorted to top) via a new `SessionListViewModel` that also fixes the rotation/process-death survival gap by replacing the old `remember{}` state in `AppNavigation`.
- **Depends on:** F-05. Can be built against `FakeGateway` before F-03 lands.

### F-11 · New Cloud Session flow · `L` · 🟡 **PARTIAL** — repo multi-select, manual entry, mode, first prompt and the documented failure messages done; all three ADR-004 §5 picker layers now present (catalog, MRU-derived recents, and manual entry with removable pills — the previously-missing recents layer and the previously-invisible manual pill are both shipped 2026-09-02); not exercised against a real create (spends credits)
**The headline feature.** The whole reason the app exists.

- **Do:** a create flow with — repository multi-select from the user's connected GitHub/GitLab account (removable pills, matching how other Kiro surfaces present bound repos); model selection; autonomy level (**Autopilot** or **Autonomous** only — Supervised does not exist for cloud sessions); first-prompt composer; submit, provision, and land in the live transcript.
- **Constraints to honour rather than paper over:** repositories are fixed at creation time; **branches cannot be selected** — set the expectation in the UI and mention that the agent can be asked to create a branch once running; the preview caps concurrent sessions at 10, so handle that failure specifically.
- **Done when:** a session can be created from the phone with 1..n repos and a first prompt, the composer is locked during provisioning with visible progress, and every documented failure mode has its own message.
- **Easier than scoped:** F-01 found a first-class repository catalog already live — `_kiro/sourceProviders/list` gives providers with a `connectionStatus` (so "connect GitLab first" is renderable instead of an empty list), and `/listResources` gives repos with `visibility` and `defaultBranch`. Show the default branch; don't offer to change it.
- **The picker is specified in [ADR-004 §5](adr/ADR-004-work-repo-selection.md#5-decision):** layered `RepoCatalog` implementations, falling through from richest to always-available — `_kiro/sourceProviders/listResources` (the catalog above, superseding ADR-004's `commands/options` proposal — use this layer first), most-recently-used repos derived from existing sessions, and manual `owner/repo` entry, which is a **permanent affordance, never a failure state**. Validation is shape-only; a server rejection most likely means the Kiro Agent App is not installed for that repository, and the error should say so and offer a Custom Tab into Kiro's settings.
- **Depends on:** F-05, F-09. Whether repos can be bound at creation with no forced interactive step is still open (ADR-004 A8) — see F-01.

### F-12 · Transcript rendering + streaming · `L` · 🟡 **PARTIAL** — reducer, coalescing tick, streaming node and generic unknown entry done; the 500-entry scroll on a mid-range device is unmeasured
- **Do:** the live conversation view — user messages, agent messages, collapsible tool-call entries with status, and the update kinds from [ACP-INTEGRATION §4](ACP-INTEGRATION.md#4-streaming-updates). There are **seven**, not four, and turn boundaries are not their own kind — see [PROTOCOL-FINDINGS §5](PROTOCOL-FINDINGS.md#5-corrections-to-the-documented-protocol).
- **Performance design is a requirement, not an optimisation** ([ADR-003 §3](adr/ADR-003-tech-stack.md#3-two-conventions-that-are-load-bearing)): coalesce chunks on a ~60–100 ms tick; render the in-flight message **outside** the lazy list and append it only at `TurnEnd`; never recompute highlighting per token on a growing string.
- **Done when:** a 500+ entry transcript scrolls smoothly on a mid-range device; an unknown update kind renders as a generic entry; `compaction/status` shows an indicator instead of an unexplained stall. Note the real replay volume: F-01's cloud session replayed **991 updates** on a single `session/load`, so replay performance is on the critical path, not a tail case.
- **Depends on:** F-05. Build against fixtures from F-01.

### F-13 · Prompt composer · `M` · ✅ **DONE 2026-09-02**
- **Do:** send prompts into a live session; attach images (the harness advertises `promptCapabilities.image` — on a phone this is a real differentiator: photograph a whiteboard, attach a failure screenshot); cancel an in-flight turn; queue a message while the agent is working to steer without cancelling.
- **Done when:** `session/prompt` content is sent as a typed block **array**; cancel takes effect promptly; images round-trip. **Shipped 2026-09-02**: attach via the Android Photo Picker (`ActivityResultContracts.PickVisualMedia`, no new dependency, no runtime permission needed), local preview with remove, gated on `ConnectionState.supportsImages` threaded down from `MainActivity`.
- **Depends on:** F-12.

### F-14 · Permission / approval UI · `M` · 🟡 **PARTIAL** — approval card, agent-supplied options, cross-client resolution, and `_kiro/userInput` UI done 2026-09-02; the "check for a pending approval before replay finishes" durable-state requirement is still unbuilt
- **Do:** surface agent-initiated permission requests and answer them. On attach, **check for a pending approval before the transcript finishes replaying** — Kiro holds a request raised while no client was attached and presents it to the next client, so this is durable state, not a transient event.
- **Render `options[]` as sent.** F-01 observed four (`allow_once`, `allow_always`, `reject_once`, `reject_always`) but the list is agent-supplied — key off `kind`, don't hard-code. `_meta.kiro.consent` gives the capability, the concrete resource, and whether the ask was implicit — enough to make a notification readable without opening the session.
- **`pendingInteraction` / `interactionResolved` arrive in the stream.** Use them to render the waiting state, and to clear it when *another* client answers first.
- **Also build `_kiro/userInput`.** It is a second, distinct channel: the agent asking a free-text question mid-turn. Undocumented, and the plan did not know about it. **Shipped 2026-09-02**: `UserInputCard` (free text, distinct from the fixed-option `ApprovalCard`), stacked with a pending approval rather than either silently dropping the other, plus a `FakeGateway.simulateUserInput` hook for testing without a real bridge.
- **Done when:** an approval can be granted or denied from the phone and the agent proceeds; a pending approval is never silently buried below the scroll; a free-text agent question can be answered or dismissed. **Still open:** the pre-replay pending-approval check was not addressed by this round and applies to both channels, not just userInput.
- **Depends on:** F-12. Payload shape is pinned by [`prompt-turn-with-permission.jsonl`](../core/src/test/resources/fixtures/prompt-turn-with-permission.jsonl).

### F-15 · Connection lifecycle: foreground service, reconnect, replay · `L` · 🟡 **PARTIAL** — foreground service (manifest-declared 2026-09-02, was silently missing before), jittered backoff wired to the live reconnect loop, connectivity-regained eager retry, and the bridge-side replay log done; `_bridge/resume` incremental replay is not
The item that decides whether the app is trustworthy. A session that dies when the phone locks is a broken client regardless of how good the UI looks.

- **Do:** a `dataSync` foreground service for active turns; exponential backoff with jitter; eager reconnect on connectivity-regained; a replay protocol — **check F-03's decision first**, since `_meta.kiro.messageId` may make the `lastSeq` scheme in [ACP-INTEGRATION §7](ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros) unnecessary; explicit handling of Android 15's 6h/24h `dataSync` cap including `onTimeout()`; Doze-aware behaviour.
- **Answering an approval does not require the original connection.** KAS correlates permissions by `toolCallId` (`_kiro/permission/respond`), which is exactly the mobile case: notification arrives, socket has since dropped, user taps Allow on a fresh one.
- **Also do — the degradation contract** from [ADR-005 §5.3](adr/ADR-005-bridge-hosting-and-availability.md#53-the-degradation-contract), which is acceptance criteria, not polish: render the session list from cache with a visible "last synced"; name the state ("bridge unreachable — last seen 3h ago", and say the machine is probably asleep when the only bridge is a workstation); disable create **with a reason**; select between paired bridges and refetch the transcript when the chosen bridge has no replay log for a session. **Never queue prompts for later delivery** — a prompt composed hours ago against unseen state, delivered unattended to an autonomous agent with repository write access, is the one failure mode worth designing out. Hold the draft; send it when the user is present.
- **Done when:** backgrounding the app, locking the phone, flipping wifi→cellular, and killing the socket mid-turn all resume with **no gap and no duplicate entries**; log truncation past `lastSeq` triggers an honest full refetch rather than a silent hole; and every bridge-unreachable path shows a named state rather than a spinner. **Shipped 2026-09-02:** fixed a real bug where `SessionConnectionService` was never declared in `AndroidManifest.xml` (would have thrown at runtime on first use); wired the previously-unused `Backoff` class into the actual reconnect loop in `MainActivity`; `ConnectionState.Reconnecting` is now emitted (was defined with UI text but never emitted); added a `ConnectivityObserver` so a network-regained event interrupts the backoff wait instead of sitting it out — caught and fixed a real bug during live device testing where a freshly-registered `NetworkCallback` fires immediately on an already-up network, defeating backoff entirely. **Still open:** the app always does a full `session/load` on reconnect rather than calling the already-implemented `_bridge/resume {sessionId, afterMessageId}` for incremental replay — bigger, separately-scoped item.
- **Depends on:** F-03, F-12.

### F-16 · Push notifications · `M`
**Delivery depends on a reachable bridge** — pushes are sent by it. A sleeping workstation bridge is a silent app; that is a documented limitation ([ADR-005 §3](adr/ADR-005-bridge-hosting-and-availability.md#3-the-availability-question)), not a bug to chase, and it is the main reason an always-on host is the recommended setup.
- **Do:** FCM for *turn finished* and *approval needed*, with inline allow/deny actions on the approval notification. Bridge sends; app receives, wakes, and answers.
- **Done when:** an approval can be answered from the notification shade with the app backgrounded; notification channels are separated so users can silence turn-completion without silencing approvals.
- **Depends on:** F-03, F-14, F-15.

---

## Phase 4 — Make it good

### F-17 · Code block and diff rendering · `L`
[ADR-002](adr/ADR-002-react-native-vs-native.md) identifies this as the **one area where the native choice is genuinely weaker** than React Native, and explicitly asks that it be budgeted as a named work item rather than absorbed as an afterthought. Treat that as the brief.

- **Do:** evaluate a Compose syntax highlighter vs. a contained WebView for diffs only; implement code blocks with language detection, horizontal scroll, and copy; implement unified/split diff viewing.
- **Done when:** the languages in the project's own test repos render correctly, and the approach + rejected alternatives are recorded in a short **ADR-006** (004 and 005 are taken).
- **Depends on:** F-12.

### F-18 · Result and PR surfacing · `S`
Cloud sessions deliver work through the source provider, usually as a pull request. Surface the PR link prominently on turn completion and in the session list; deep-link out to GitHub/GitLab.
**Depends on:** F-12.

### F-19 · Slash commands and MCP OAuth relay · `M`
Expose available slash commands from the `commands/available` notification with autocomplete via `commands/options`. Relay `mcp/oauth_request` URLs into a Custom Tab — the same browser-based pattern as F-08. Degrade silently if the extensions are absent.
**Depends on:** F-04, F-12.

### F-19b · Per-turn cost display · `S` ∥
*Added by F-01.* Every turn ends with a `promptTurnSummaries` payload carrying credit spend and the tools used. Surface it — a running per-session cost is genuinely useful on a metered plan, and it is close to free given the data already arrives.
**Depends on:** F-12.

### F-20 · Settings, logout, diagnostics · `S`
Bridge management (re-pair, forget), Kiro sign-out, cached-transcript clearing, a log viewer for support, version info. Logout wipes tokens (F-06) and cached transcripts. **Kiro sign-out is destructive** — it runs `kiro-cli logout` on the bridge host, ending cloud access for every client, so confirm it explicitly.
**Depends on:** F-06, F-09.

### F-21 · Accessibility, theming, large screens · `M`
Dark theme, dynamic colour, TalkBack labels on tool-call entries and approval actions, font scaling to 200% without clipping, tablet/foldable layouts. **Approval dialogs must be fully operable with TalkBack** — it is the highest-stakes interaction in the app.
**Depends on:** F-11, F-12, F-14.

### F-22 · Observability and protocol-drift metrics · `S`
Track unknown-method and parse-failure rates. [ADR-002 §5](adr/ADR-002-react-native-vs-native.md#5-recommendation) makes this number the explicit trigger for revisiting the runtime decision — so it must actually be measured, not assumed. Local/opt-in only; never log prompt or repository content.
**Depends on:** F-04.

### F-23 · Release engineering and distribution · `M`
Signing, versioning, reproducible builds, and a distribution decision. **Note:** an unofficial client using Kiro's name and marks needs a real answer on naming, branding, and Play Store policy before publishing. Resolve alongside F-00.
**Depends on:** a shippable Phase 3.

---

## How to pick up an item

1. **Read [ADR-001](adr/ADR-001-cloud-session-access.md) first.** It rules out an entire (tempting) approach. A PR that adds a reverse-engineered `app.kiro.dev` endpoint is rejected on principle.
2. **Read [PROTOCOL-FINDINGS.md](PROTOCOL-FINDINGS.md).** F-01 has reported. It corrects the documented protocol in several places and shrinks at least one item's scope; starting from the docs alone will send you down a path F-01 already closed.
3. **Respect the seams.** UI items talk to `CloudSessionGateway` (F-05), never to a transport. `core/` takes no Android dependency — CI enforces this.
4. **Use the paths in [ADR-003 §2](adr/ADR-003-tech-stack.md#2-module-layout).** If you need a new package, say so in the PR.
5. **Definition of done:** acceptance criteria met · unit tests for `core/` logic · no new lint/detekt warnings · docs updated if a decision changed · tolerant parsing preserved.
6. **If an assumption proves false, stop and report it** rather than working around it locally. ADR-001 §5 exists so that a refuted assumption updates the plan instead of quietly becoming one feature's private hack.

### Parallelisation

```
F-00 ─┐                                    (independent, non-code)
F-24 ─┤                                    (independent spike, ADR-005 A17)
F-01 ─┴─► F-03 ─┬────────────────► F-07 ─► F-08 ─► F-09 ─┐
                │                                         │
F-02 ─► F-04 ─► F-05 ─┬─► F-10 ────────────────────────► F-11
        │             ├─► F-12 ─┬─► F-13                  │
        │             │         ├─► F-14 ─┐               │
        │             │         ├─► F-17  │               │
        │             │         └─► F-18  │               │
        │             └─► F-15 ───────────┴─► F-16        │
        └─► F-22                                          │
                                            F-19/20/21/23 ┘
```

- **Status 2026-09-02:** F-02, F-03, F-04, F-05, F-06, F-10 and F-13 are done; F-07, F-11, F-12, F-14 and F-15 are partial (see each item). `./gradlew build` is green and the bridge has been driven end-to-end against a real `kiro-cli`. F-07/F-10/F-14's userInput slice/F-13/F-15's reconnect slice were all closed in one round against `FakeGateway` on a local emulator, each verified with real screenshots and logcat rather than asserted.
- **The largest untested seam** is everything that needs a real cloud *turn*: F-11's create, F-14's approval round trip, and F-15's reconnect-mid-turn replay. All three cost credits to exercise and none of them has been.
- **Start now, in parallel:** F-00, F-24, and F-01's A7–A17 follow-up. (A1–A6 and A18 are done — their fixtures and findings are ready.)
- **Widest parallel band:** after F-05, the UI items (F-10, F-12, F-17, F-18) can all proceed against `FakeGateway` while F-03 and F-15 handle the hard infrastructure.
- **Single-threaded chain:** F-03 → F-07 → F-08 → F-09. Auth is sequential by nature; don't split it across agents.
- **Highest-risk items:** F-15 (hardest to get right) and F-03 (most security-sensitive). F-01 was the highest-risk item and has now reported without invalidating the plan.
