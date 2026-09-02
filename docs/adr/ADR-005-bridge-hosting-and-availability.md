# ADR-005: Where the bridge runs, and what the app does when it doesn't

- **Status:** Proposed
- **Date:** 2026-09
- **Depends on:** [ADR-001](ADR-001-cloud-session-access.md) (decides *that* there is a bridge), [ADR-004](ADR-004-work-repo-selection.md) (establishes what the bridge host actually needs)
- **Scope:** The bridge host's requirements, its hosting model, and the app's behaviour when it is unreachable. Does not re-open ADR-001's choice of topology.

---

## 1. The question, stated precisely

The blocker is real and worth naming without softening it:

> **`kiro-cli` cannot run on the phone, so the app cannot reach a cloud session on its own.**

[ADR-001](ADR-001-cloud-session-access.md) already answered *that* question — a user-hosted bridge fronting `kiro-cli`, chosen because it is the only fully documented route to cloud sessions, and accepted with its cost stated in §4 of that ADR. This ADR does not relitigate it.

What ADR-001 left open, and what turns out to matter more than the topology choice itself:

1. **What does the bridge host actually need?** ADR-001 said "a machine where `kiro-cli` is installed and signed in" and implied it sits next to the user's code.
2. **Where should it run?** "Home server, workstation, or cheap VPS" is a list, not a decision, and the three have very different consequences.
3. **What happens when it is off?** A phone client whose backend is someone's sleeping laptop is a product with an availability model, and nobody has written it down.

The third is the one that decides whether this app is usable, and it is the one ADR-001 never addresses.

---

## 2. What ADR-004 changes about the bridge

[ADR-004 §2](ADR-004-work-repo-selection.md#2-what-we-found-how-kiro-cli-actually-selects-the-work-repo) establishes that for a **cloud** session, `kiro-cli` does not use the working directory, a local checkout, or local git credentials. Repositories are named as `owner/repo`, resolved against the Kiro account's connected provider, and cloned inside Kiro's sandbox, which also pushes branches and opens the PR. Cloud sessions are stored in the account's cloud session store rather than the CLI's per-directory local database ([Session management](https://kiro.dev/docs/cli/chat/session-management/)).

The bridge host requirement collapses accordingly:

| ADR-001 implied | Actually required |
|---|---|
| A machine sitting next to the user's source tree | Any machine. There is no source tree involved. |
| Clones of the repositories being worked on | None. Cloning happens in Kiro's sandbox. |
| Git credentials with write access | None. The Kiro Agent GitHub App / GitLab PAT do the pushing, server-side. |
| A meaningful working directory | None for cloud sessions. Pin one anyway so stray *local* sessions never appear in our list. |
| Developer-grade hardware | `kiro-cli` supports Linux `x86_64` **and `aarch64`**, and macOS ([CLI installation](https://kiro.dev/docs/cli/installation/)). A Raspberry Pi qualifies. |

What is genuinely required is short: the `kiro-cli` binary, a Kiro account signed in on that host with a Pro-or-higher plan, outbound HTTPS, and enough uptime to be reachable when the user picks up their phone.

**The bridge is a relay, not a workspace.** That is a much easier thing to ask someone to run, and it is the single most useful consequence of the ADR-004 research.

### The property that makes this tractable

Because session state lives in the *account*, not on the bridge host, **bridges are fungible**. Two bridges signed in as the same Kiro account see the same sessions. A user can run one on a Pi and one on their laptop; either can attach to the same session; losing one loses no work.

The one exception is our own replay log ([ACP-INTEGRATION §7](../ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros)), which is bridge-local by design. Switching bridges mid-session means the new bridge has no log for it. That is a defined case, not a corruption: the app refetches the transcript from scratch, exactly as it already must when a log has been truncated past `lastSeq`.

---

## 3. The availability question

The instinct is that a sleeping bridge means a dead app. It doesn't, and the reason is the whole point of cloud sessions: **disconnecting a client does not stop the agent, and an in-flight task keeps running** ([Cloud sessions](https://kiro.dev/docs/cloud-sessions/)). The durable thing is Kiro's sandbox. The bridge is a window onto it.

So bridge downtime costs **observation and steering**, not work:

| While the bridge is down | Consequence |
|---|---|
| A running session keeps working, pushes its branch, opens its PR | No loss. This is the documented behaviour. |
| The user cannot watch the transcript or send a prompt | Degraded, recoverable — everything replays on reattach. |
| The user cannot create a new session | Blocked until the bridge is back. |
| A permission request blocks the agent | Stalls until a client attaches. Kiro presents a waiting request to the next client that attaches, so it is durable, not lost ([ACP-INTEGRATION §6](../ACP-INTEGRATION.md#6-permission-requests)). |
| **Nobody is told anything happened** | **The real cost.** F-16's push notifications are sent *by the bridge*. A sleeping bridge is a silent app: the PR landed two hours ago and the phone never buzzed. |

That last row is the one that decides the hosting recommendation. The app's value proposition is "the agent reaches you where you are" — and it is precisely the notification path, not the streaming path, that requires something to be awake while the user is not looking.

Two things soften it, and both are worth knowing before over-engineering a solution:

- **Cloud sessions block less often than F-14 assumes.** They run in Autopilot or Autonomous only; per-change approval is not available ([Cloud sessions](https://kiro.dev/docs/cloud-sessions/), [Autonomous mode](https://kiro.dev/docs/web/autonomous-mode/)). Approvals still arrive from the capability-permissions layer ([Permissions](https://kiro.dev/docs/permissions/)), but "the agent is blocked waiting for you" is a rarer state in a cloud session than in a supervised local one.
- **Nothing is lost by being late.** A stalled session resumes where it stopped. Latency is the damage, not data.

---

## 4. Options for hosting

### Option A — The user's workstation, on demand

The bridge runs on the laptop the user already codes on; it is up when the laptop is.

- **Pro:** zero marginal cost, zero new infrastructure, `kiro-cli` is already installed and signed in. Fine for "start it before I leave the desk."
- **Con:** asleep exactly when the phone matters. No notifications overnight. This is the configuration that makes the app feel broken while being configured correctly.
- **Verdict:** **Supported, and honestly labelled.** The app must show this state as "your bridge is asleep," never as a failure.

### Option B — A small always-on host

A VPS, a NAS, a home server, a Raspberry Pi. `aarch64` Linux is supported, so the cheapest tier of nearly anything qualifies.

- **Pro:** the only configuration where the product works as advertised. Notifications arrive. Sessions can be created from anywhere. Costs a few dollars a month or a device already in the house.
- **Con:** the user runs infrastructure, and it holds a signed-in Kiro session — a credential that lets an agent write to their repositories. Security requirements are not optional here (see §5).
- **Verdict:** **Recommended configuration.** Documentation, onboarding, and defaults should assume it.

### Option C — A published container image

Ship the bridge as an OCI image with a documented `docker run`, a persistent volume for the CLI's credential store and replay logs, and a first-run flow that surfaces the device-code URL for `kiro-cli login` ([AUTHENTICATION](../AUTHENTICATION.md)).

- **Pro:** turns Option B from a project into a command. Same artifact serves Option A. Multi-arch build covers Pi and VPS from one recipe.
- **Con:** a container image containing a signed-in agent CLI needs its threat model written down, not assumed.
- **Verdict:** **Adopt as the primary distribution form** for the bridge. This *is* how B and A get delivered.

### Option D — A multi-tenant bridge hosted by this project

We run bridges; users point the app at ours.

- **Pro:** phone-only. The adoption barrier disappears entirely.
- **Con:** we would hold other people's Kiro credentials and operate agents with write access to other people's repositories, as an unaffiliated third party with no agreement with Kiro. The security, liability, cost, and terms-of-service exposure are all bad, and they are bad in the same direction as ADR-001's rejected Option A.
- **Verdict:** **Rejected, and not a close call.** ADR-001 declined to hold Kiro's private protocol; holding users' Kiro sessions is strictly worse.

### Option E — The bridge on the phone

`kiro-cli` supports Linux `aarch64`. Termux with a `proot-distro` Debian userland is an `aarch64` Linux environment. In principle the CLI could run on the device, and the app would have no external dependency at all.

- **Pro:** it is the *only* path to a phone-only app that does not require official API access. If it works, ADR-001's headline cost disappears.
- **Con:** unverified and probably load-bearing on details — glibc under proot, the AppImage/`.deb` install path, Android's Doze and background-process limits killing a long-lived agent, battery, and a credential store on a device that is easier to lose than a server. It also puts a signed-in Kiro account on the least-controlled device in the chain.
- **Verdict:** **Rejected for v1, worth a timeboxed spike.** The upside is large enough that "we never checked" would be a poor answer. Fail it fast on the first hard blocker.

---

## 5. Decision

**Ship the bridge as a container image (Option C), document an always-on small host as the supported configuration (Option B), fully support the workstation case (Option A) with explicit degradation, reject hosting bridges ourselves (Option D), and spike the on-device case once (Option E).**

Four commitments follow.

### 5.1 The bridge stays thin

No checkout, no git credentials, no repository-specific configuration, no meaningful working directory. It supervises `kiro-cli`, relays ACP, holds a replay log, and authenticates devices. Anything that tempts the bridge to become a workspace is a design smell and belongs in the sandbox instead.

### 5.2 Multiple bridges are a supported configuration, not an accident

Because sessions live account-side, the app pairs with **a set** of bridges and may attach a session through any of them. The app stores bridges as a list, tracks last-seen per bridge, and prefers the most recently reachable. Switching bridges for a session is legal and costs a transcript refetch. `SessionConnectionService` (F-15) owns the selection; the UI shows which bridge is in use only when there is more than one.

### 5.3 The degradation contract

When no paired bridge is reachable, the app is explicit rather than spinning:

- **Session list renders from cache**, timestamped, with a visible "last synced" marker. A stale list is more useful than a spinner and far more useful than an empty state.
- **The state is named.** "Bridge unreachable — last seen 3h ago" with a retry, not a generic error. If the user has only a workstation bridge, the copy says the machine is probably asleep, because that is almost always what happened.
- **Create is disabled with a reason**, not hidden.
- **Prompts are never queued for later delivery.** This is deliberate and worth defending: a prompt composed six hours ago, against a transcript the user has not seen since, delivered unattended to an autonomous agent with write access to their repositories, is a bad instruction sent at the worst possible moment. Queuing looks like a courtesy and is actually a way to lose control of an agent. The app holds the draft locally and sends it when the user is present and looking at current state.
- **Reconnection is eager but not a storm** — exponential backoff with jitter, plus an immediate attempt on a connectivity-regained callback, exactly as [ACP-INTEGRATION §7](../ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros) already specifies.

### 5.4 Onboarding tells the truth in this order

ADR-001 §4 says the bridge requirement belongs on the first screen of onboarding. Concretely, the order that avoids a dead end:

1. **You need a machine running the bridge.** Say it before sign-in, with the container command visible. A user who cannot meet this should find out in ten seconds, not after an OAuth round trip.
2. **Recommend an always-on host, explain what a workstation-only bridge costs** — specifically, that notifications stop when it sleeps.
3. **Pair the device** (F-07).
4. **Sign in to Kiro** via the device flow relayed from the bridge (F-08).
5. **Connect a source provider** in Kiro's own settings via Custom Tab, per [ADR-004 §5](ADR-004-work-repo-selection.md#5-decision) — otherwise the first repository picker is empty and nothing explains why.

### 5.5 Security requirements are unchanged and non-negotiable

[F-03](../FEATURES.md)'s security list — loopback bind by default, TLS for non-loopback, single-use short-TTL pairing codes, rate limiting, revocable device tokens — was written for a bridge on a laptop. Recommending an always-on, internet-reachable host makes every item load-bearing rather than prudent. The container image must ship with the safe defaults, and exposing it to a network must be an explicit act by the user, not the image's default posture.

---

## 6. What this costs us

- **The honest recommendation is "run a small server,"** and that is a real adoption filter. This ADR does not make the app phone-only; it makes the requirement precise, cheap, and explainable instead of vague.
- **We now maintain a container image**, with a multi-arch build and a supply chain, as a first-class deliverable rather than a script in a README.
- **Refusing to queue prompts will read as a missing feature.** It will be requested. The reasoning in §5.3 is the answer, and it should live in the docs, not only in this ADR.
- **Multi-bridge support costs complexity in F-15** — bridge selection, per-bridge last-seen, transcript refetch on switch. It is bought with a genuine resilience win, and the property that enables it is free.

---

## 7. Assumptions to verify — extends ADR-001 §5 and ADR-004 §7

| # | Assumption | Risk if wrong |
|---|---|---|
| A13 | A cloud session created through one bridge is visible and attachable from a **second** bridge signed in as the same account | Medium — kills §5.2's multi-bridge model and the failover story. Fungibility is inferred from the account-side session store, not directly documented. |
| A14 | A permission request raised while no client is attached is genuinely durable and is re-presented on reattach | **High** — §3's "nothing is lost by being late" depends on it. Stated in ADR-001/ACP-INTEGRATION but never observed. |
| A15 | `kiro-cli` can be kept resident and signed in on a headless host across restarts without an interactive re-auth, and token refresh survives long idle periods | **High** — if the CLI needs periodic interactive re-auth, an always-on bridge silently stops working and Option B's whole premise is weaker than claimed. |
| A16 | A single bridge host can supervise several concurrent cloud sessions (the preview cap is 10) within reasonable memory on small hardware | Medium — decides whether a Pi is a real recommendation or an aspirational one. |
| A17 | `kiro-cli` installs and runs under Termux + `proot-distro` on `aarch64` | Low stakes, high upside — Option E's spike. A single "no" closes it. |
| A18 | `kiro-cli acp --agent-engine v3` authenticates from `KIRO_API_KEY` alone, with no prior interactive `kiro-cli login`, **and** the resulting session reaches cloud sessions | Medium — a yes removes the pty-driven login from F-03/F-08 and largely dissolves A15. Added 2026-09-02; see [F-01's brief](../FEATURES.md#f-01--protocol-spike-verify-assumptions-capture-golden-fixtures) for how to run it and what it costs. |

Add A13–A16 and A18 to F-01's brief; A17 is its own small spike and should not block anything.

### A14 has a shape, not just a yes/no

A14 currently asks only whether a pending permission survives being un-attended. That is the necessary half. The sufficient half is *what the app shows when it re-presents one*, and there is a good externally-authored answer to steal: a commenter on [kirodotdev/Kiro#9460](https://github.com/kirodotdev/Kiro/issues/9460) (`rpelevin`, 2026-06-22) argues that a remote approval surface can be small but must be **exact**, and lists the minimum envelope before you let an agent continue from a phone —

> session identity · tool or command identity · sanitized command/argument digest · workspace or target scope · risk reason · expiry · terminal outcome after the decision

— with the decision **consumed once**, and **failing closed if the underlying action changed** while the user was away.

That last clause is a design constraint this ADR had not written down, and it is the one with teeth: an approval card rendered from a snapshot, answered twenty minutes later, must not authorise a *different* action than the one displayed. Whether ACP gives us enough to enforce that — whether a permission request carries anything stable to bind the decision to, or whether we must bind on `_meta.kiro.messageId` ourselves — is part of what F-03 confirms on its first cloud turn. Treat the seven fields as acceptance criteria for the approval UI, and treat "the phone must not become a standing permission for whatever the agent asks next" as the principle behind them.

---

## 8. Consequences

- **F-03** gains a defined deliverable shape (container image, multi-arch, safe-by-default network posture) and loses the assumption that it lives beside a checkout.
- **F-07** pairs with a *list* of bridges rather than one, and F-15 owns selection between them.
- **F-15** gains the degradation contract in §5.3 as acceptance criteria, and transcript refetch on bridge switch as a defined case.
- **F-16** should state plainly that push delivery depends on a reachable bridge; this is a documented limitation, not a bug to chase.
- **ADR-003 §2** — the bridge-language decision (Kotlin/JVM vs. Node/TS) now carries a new input: the artifact is a container image, which flattens the "requires a JVM on the host" objection to Kotlin considerably.
- **README** should be corrected: the bridge does not need to sit near the user's code, and the requirement is better described as "a small always-on machine" than as "a machine where you code."

---

*External facts are cited inline and paraphrased. As in [ADR-004 §2.1](ADR-004-work-repo-selection.md#21-evidence), `kiro.dev` was unreachable and nothing was read verbatim; the docs also appear to lag the CLI by several minor versions. `kiro-cli --help` is authoritative over both.*
