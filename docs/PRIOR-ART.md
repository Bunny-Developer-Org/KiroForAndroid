# Prior art: other people reaching Kiro from a phone

Surveyed **2026-09-02**. Three published projects put a Kiro session on a phone, plus one adjacent CLI client worth knowing about. **All of them took a different path than [ADR-001](adr/ADR-001-cloud-session-access.md)'s, none reaches cloud sessions, none implements approvals, and none implements reconnect/replay.**

That is the headline. This document exists so nobody re-derives it, and so the two genuinely reusable ideas don't get lost with it.

| Project | Path to Kiro | Reaches cloud sessions? | Approvals? | Reconnect/replay? |
|---|---|---|---|---|
| [ajitnk-lab/kiro-acp-telegram-bot](https://github.com/ajitnk-lab/kiro-acp-telegram-bot) | stdio ACP, **v2 engine** | ✗ (didn't know it was possible) | ✗ (`--trust-all-tools`) | ✗ |
| [4regab/kiro-mobile-bridge](https://github.com/4regab/kiro-mobile-bridge) | Chrome DevTools Protocol against the Electron IDE | ✗ (local IDE only) | ✗ | ✗ |
| [Lock128/kiro-mobile](https://github.com/Lock128/kiro-mobile) | WebView credential capture from Kiro Web sign-in | via Kiro Web's own UI | n/a | n/a |

---

## 1. `ajitnk-lab/kiro-acp-telegram-bot` — stdio ACP, local engine

~450 lines of JavaScript, MIT-0, last pushed 2026-03-24. A Telegram bot that spawns `kiro-cli acp` and relays prompts. Architecturally our nearest cousin: same protocol, same "bridge process fronting the CLI" shape, different client surface.

### Worth reading

**The message discriminator.** The one non-obvious part of an ACP client, and they got it right — three-way dispatch on the presence of `id` and `method`:

```js
_handleMessage(msg) {
  if (msg.id !== undefined && this.pending.has(msg.id)) { /* response to a request we sent */ }
  if (msg.id !== undefined && msg.method)               { /* server-initiated REQUEST — must answer */ }
  if (msg.method)                                        { /* notification — no reply */ }
}
```

Plus newline-delimited framing with buffer carry-over across chunks, and error replies shaped as `{code: -32000, message}`. That is the skeleton [F-04](FEATURES.md) needs. We'd write it in Kotlin with a sealed type rather than three `if`s, but the shape is correct and the fixture-free version of it is a day of work they've already done.

### The more valuable half: what it warns us about

**It is local-only and doesn't know it.** It spawns `kiro-cli acp --trust-all-tools` — no `--agent-engine v3 --auth-method cli`. A whole shipped product built on the v2 surface without discovering cloud sessions exist over ACP. This is the external evidence cited in [PROTOCOL-FINDINGS §2](PROTOCOL-FINDINGS.md#2-the-one-thing-the-plan-missed-entirely---agent-engine-v3).

**It hardcodes the wrong extension prefix.** Its README documents `_kiro.dev/mcp/server_initialized` and `_kiro.dev/commands/execute`, copied from Kiro's ACP docs page — which [A3](PROTOCOL-FINDINGS.md#a3--the-extension-prefix--verified--it-is-_kiro) refuted (live prefix is `_kiro/`). A second independent data point that the published page misleads implementers, and concrete justification for F-04's rule: derive the prefix from `initialize`, never match a constant.

**`--trust-all-tools` deletes approvals entirely.** There is no `session/request_permission` handler anywhere in the repo. Our most differentiated feature has **zero prior art here** — expect to borrow nothing. It is also a warning about gravity: the easy path is to switch approvals off, which is precisely the thing this app exists to surface.

**Advertising `fs`/`terminal` as `true` costs you a workspace.** They send `clientCapabilities: {fs:{readTextFile:true,writeTextFile:true}, terminal:true}` and must therefore implement `fs/readTextFile`, `fs/writeTextFile` and `terminal/execute` against a real host directory. [ACP-INTEGRATION §2](ACP-INTEGRATION.md#2-handshake) advertises **false** deliberately; their code is the concrete demonstration of what that buys — it is *why* [ADR-005 §5.1](adr/ADR-005-bridge-hosting-and-availability.md#51-the-bridge-stays-thin)'s thin relay needs no checkout, no file I/O and no shell.

**A security shape we must not copy** — the direct cost of the above:

```js
case "terminal/execute": {
  const out = execSync(params.command, { cwd: WORKSPACE, timeout: 30_000, ... });
```

Unsandboxed shell on the host, driven by the agent, reachable from a Telegram message, with `ALLOWED_USERS` defaulting to *everyone*. Not a fair criticism of a prototype, but it is a live example of why [AUTHENTICATION §4](AUTHENTICATION.md#4-auth-1-pairing-the-app-to-the-bridge)'s requirements are written as mandatory rather than recommended.

**Three details we'd inherit as bugs:**

- **No live streaming.** `prompt()` accumulates chunks and resolves only at turn end. Our transcript streams; nothing to borrow.
- **Notifications aren't filtered by `sessionId`.** A per-prompt global listener — fine for one session, cross-talks at two. We are multi-session by definition; key the update `Flow` on session ID from the start.
- **A flat 120 s timeout on every request, `session/prompt` included.** A cloud agent turn can far exceed that. Our timeout policy must distinguish "transport is dead" from "agent is still thinking" — almost certainly no deadline on a prompt, with liveness carried by transport-level ping and turn-end.

**Nothing of [F-03](FEATURES.md#f-03--bridge-service-mvp--l)'s hard part exists.** No reconnect, no replay, no `session/load` persistence, one ACP session shared across all users. Their own "Improvement Ideas" list — auto-restart with exponential backoff, session persistence via `session/load`, multi-user session pool — is a subset of F-03's acceptance criteria. Independent confirmation that the `L` sizing is honest and that nobody has solved this in public.

---

## 2. `4regab/kiro-mobile-bridge` — CDP against the Electron IDE

npm `kiro-mobile-bridge`, v1.0.23, last published 2026-02-15. Requires launching the IDE as `kiro --remote-debugging-port=9000`, then drives it over **Chrome DevTools Protocol**: scrapes chat, editor and task panes by adaptive polling, and injects text into the chat input. Local IDE sessions only, and structurally more fragile than either ACP or credential capture — it breaks on any IDE DOM change.

**Worth stealing: the pairing UX.** A 6-digit OTP printed to the terminal on server startup, single-use, one device per server session, with an explicit `--no-auth` escape hatch for trusted networks. That is a close cousin of [AUTHENTICATION §4](AUTHENTICATION.md#4-auth-1-pairing-the-app-to-the-bridge)'s pairing design, already field-tested by users, and the "one device per server session, restart to re-issue" simplification is worth considering against our revocable-device-list model.

**Worth reading for a different reason:** its README's LAN and Windows-firewall troubleshooting section is a sober preview of the support burden [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md) signs us up for by putting a server on the user's network. Whatever onboarding we write, that section is the floor.

Surfaced via a comment on [kirodotdev/Kiro#6099](https://github.com/kirodotdev/Kiro/issues/6099), where the commenter explicitly notes they'd *prefer an official solution* — which is also data for [F-00](FEATURES.md#f-00--pursue-official-third-party-api-access--s-).

---

## 3. `Lock128/kiro-mobile` — WebView credential capture

A Flutter app (Android/iOS/Web) that renders Kiro Web's sign-in page in a WebView, captures the resulting credentials, persists them in platform-native secure storage (Android Keystore), and displays authenticated Kiro Web content in-app. Self-described as alpha and explicitly unaudited: *"has not undergone a formal security audit or validation."*

This is the closest thing to a real Android Kiro client that exists — and it is essentially [ADR-001 Option A](adr/ADR-001-cloud-session-access.md#option-a--direct-to-kiros-private-cloud-api) in practice: it holds a Kiro credential on the phone, obtained from a surface nobody has promised to keep stable. It is useful to us mainly as the counterfactual. It ships something we can't, and it carries exactly the risk ADR-001 §4 declined to take on the user's behalf.

---

## 4. Adjacent: `shell.online`

Mentioned by a vendor employee (disclosed) on [#7993](https://github.com/kirodotdev/Kiro/issues/7993): running `kiro-cli chat` under their tool prints a shareable URL that reaches the same live session from a phone browser, read-only optional. MIT, macOS/Linux. Not Kiro-specific — it is generic terminal sharing — so it inherits none of Kiro's session model and offers no approval surface. Listed for completeness; not prior art for our architecture.

---

## What this survey changes

Nothing structural. It **confirms** rather than challenges [ADR-001](adr/ADR-001-cloud-session-access.md):

1. Ours is still the only design aimed at the durable, documented surface, and the only one that reaches cloud sessions at all.
2. `--agent-engine v3 --auth-method cli` now looks like the single most valuable thing this repo knows that nobody else does — a shipped competitor missed it.
3. The hard parts we scoped (approvals, reconnect, replay, multi-session) are unsolved in public. There is no shortcut to borrow, and the `L` on F-03 is not pessimism.

Two concrete borrowings, both recorded where they belong: the OTP pairing simplification (§2, against AUTHENTICATION §4) and the approval envelope from #9460 ([ADR-005 §7](adr/ADR-005-bridge-hosting-and-availability.md#a14-has-a-shape-not-just-a-yesno)).

---

*All external facts are cited inline and paraphrased. Code snippets are short excerpts quoted for identification under the projects' own licences (MIT-0 for §1).*
