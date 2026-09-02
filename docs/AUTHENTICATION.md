# Authentication plan

**Requirement:** the user signs in with their **Kiro account** using **OAuth, via a web link**.

This document explains how that is achieved given the constraint established in [ADR-001](adr/ADR-001-cloud-session-access.md): Kiro publishes no OAuth client registration or API for third-party clients.

---

## 1. There are two separate authentications

Conflating these is the main source of confusion, so name them once and keep them apart:

| # | Boundary | Credential | Whose auth is it? |
|---|---|---|---|
| **Auth-1** | Android app ↔ bridge | Pairing token issued by the bridge | **Ours.** Not OAuth. Protects the WebSocket. |
| **Auth-2** | Bridge host ↔ Kiro | The user's real Kiro account | **Kiro's.** OAuth, via web link. Delegated to `kiro-cli`. |

**Auth-2 is the requirement.** Auth-1 is plumbing that exists only because of the bridge topology.

The key insight: **Auth-2 can be driven from the phone without the app ever holding Kiro's OAuth client secrets**, because the OAuth 2.0 Device Authorization Grant ([RFC 8628](https://datatracker.ietf.org/doc/html/rfc8628)) is designed for exactly this shape — a device that can display a URL but shouldn't own the credential exchange. Kiro CLI already supports it: `kiro-cli login --use-device-flow` exists for SSH and remote sessions, and shows a device code plus a URL to complete on another device ([CLI commands](https://kiro.dev/docs/reference/cli-commands/)).

We are that "another device," in reverse.

---

## 2. What is known about Kiro sign-in

| Fact | Implication for us |
|---|---|
| Kiro supports GitHub, Google, AWS Builder ID, IAM Identity Center, and org IdPs (Okta, Microsoft Entra ID) ([Authentication](https://kiro.dev/docs/getting-started/authentication/)) | The account model is federated. We must not assume a single provider. |
| `https://app.kiro.dev/signin/oauth` is the redirect URI orgs must allowlist ([Cloud sessions](https://kiro.dev/docs/cloud-sessions/)) | This belongs to **Kiro Web**. It is not ours and we must not reuse it. |
| Cloud sessions need a Pro plan or higher, and admin enablement for Identity Center orgs | Sign-in succeeding does not imply cloud sessions are available. Handle "authenticated but not entitled" as a distinct state. |
| `kiro-cli login --use-device-flow` performs a device-code flow | The mechanism we drive for Auth-2. |
| Builder ID / Identity Center sign-in is AWS-backed | The underlying device flow is likely AWS's public `sso-oidc` API (`RegisterClient` → `StartDeviceAuthorization` → `CreateToken`), which supports dynamic client registration and so needs no pre-assigned `client_id`. **Unverified** — noted because it is the thing that would make a future direct implementation feasible without Kiro issuing us credentials. |

No `client_id`, authorization endpoint, or token endpoint is published for third-party clients. That is why Auth-2 goes through the CLI rather than through our own OAuth client.

---

## 3. Primary flow — device authorization, relayed through the app

The user never types a password into our app, and our app never sees a Kiro token.

> **Corrected by [F-01](PROTOCOL-FINDINGS.md#a6--login---use-device-flow-is-scriptable--partially-refuted).** The flow below survives, but one assumption in it was wrong: `--use-device-flow` is **not** non-interactive. Before printing anything parseable, the CLI opens a provider-picker TUI on its pty. So **provider selection moves into the app**, and the bridge must drive a pty rather than read a pipe. The diagram and requirements are updated accordingly.

```
 Android app                Bridge                    kiro-cli              Browser (phone)
     │                        │                          │                        │
     │  1. POST /auth/kiro/start { provider }            │                        │
     ├───────────────────────►│                          │                        │
     │                        │  2. spawn on a PTY       │                        │
     │                        │  kiro-cli login          │                        │
     │                        │  --use-device-flow       │                        │
     │                        ├─────────────────────────►│                        │
     │                        │                          │                        │
     │                        │  2b. answer the provider │                        │
     │                        │      picker TUI          │                        │
     │                        ├─────────────────────────►│                        │
     │                        │                          │                        │
     │                        │  3. parse verification   │                        │
     │                        │     URI + user code      │                        │
     │                        │◄─────────────────────────┤                        │
     │  4. { verificationUri, userCode, expiresIn }      │                        │
     │◄───────────────────────┤                          │                        │
     │                        │                          │                        │
     │  5. open Custom Tab ─────────────────────────────────────────────────────► │
     │     show userCode, offer copy                     │                        │
     │                        │                          │   6. user signs in to  │
     │                        │                          │      their Kiro account│
     │                        │                          │◄───────────────────────┤
     │                        │  7. CLI polls, gets token│                        │
     │                        │◄─────────────────────────┤                        │
     │  8. status: authenticated (via WS event)          │                        │
     │◄───────────────────────┤                          │                        │
```

Properties worth noting:

- **The Kiro token lives only on the bridge host**, managed by `kiro-cli` in its own credential store. The app stores nothing belonging to Kiro. This materially shrinks the blast radius of a compromised phone.
- **It is genuinely OAuth via web link** — the user completes a real OAuth flow at a Kiro-controlled URL in a real browser.
- Works with **every** provider Kiro supports — but the app must *ask which*, because the CLI does. F-01 observed the picker offering **Use with Builder ID · Use with Google · Use with GitHub · Use with Your Organization**, with no flag to preselect. Mirror those four; there is no way to defer the choice to the browser.

### What the CLI actually prints

```
To sign in with Google, visit:
  https://app.kiro.dev/account/device?user_code=XXXX-XXXX&login_provider=Google
And confirm the code: XXXX-XXXX
```

Both values are extractable, and **the verification URI already carries the user code as a query parameter** — so the Custom Tab can open pre-filled and the code becomes a fallback rather than a transcription chore. Progress then renders as a spinner until the exchange completes.

### Requirements

- Open the verification URI in a **Custom Tab**, never a WebView ([RFC 8252 §8.12](https://datatracker.ietf.org/doc/html/rfc8252#section-8.12)). A WebView lets the host app observe credentials and breaks the user's ability to verify the origin.
- Display the user code prominently, tappable to copy, and keep it visible when the app returns to the foreground.
- Respect `interval` and `expires_in`; surface expiry with a one-tap restart.
- Handle the full RFC 8628 error set: `authorization_pending` (keep waiting), `slow_down` (back off), `access_denied`, `expired_token`.
- **Present the provider picker in the app** and pass the choice to the bridge; the bridge answers the CLI's TUI on its pty. There is no non-interactive path.
- Sign-in may already be satisfied — always probe `kiro-cli whoami --format json` first and skip the flow if the host is signed in. Signed in it returns `{"accountType": "…", "email": "…"}`; signed out, `{"account": null}`.
- **`login` refuses while already signed in**, exiting non-zero with `error: Already logged in, please logout with kiro-cli logout first`. Re-authentication therefore requires an explicit `kiro-cli logout` — which is destructive and ends cloud access for every connected client. Make it a deliberate, confirmed user action; **never an automatic retry** on an auth error.
- **`whoami` exposes no entitlement.** There is no plan field, so the app cannot pre-check Pro eligibility. `NotEntitled` (§6) has to be derived from the error a cloud session create actually returns.

---

## 3b. Alternative for Auth-2 — API-key provisioning (verified 2026-09-02)

[A18 is closed](PROTOCOL-FINDINGS.md#4b-a18--kiro_api_key-authenticates-the-acp-surface--verified): `kiro-cli acp --agent-engine v3` authenticates from a `KIRO_API_KEY` environment variable, that mode reaches cloud sessions, and it **overrides the credential store even when `--auth-method cli` is passed explicitly**. There is no flag to select it and none to suppress it — presence of the variable decides.

This gives Auth-2 a second, much simpler shape:

| | Device-flow relay (§3) | API key |
|---|---|---|
| Who signs in | The user, from the phone, in a browser | Whoever provisions the bridge, once, in Kiro's console |
| Bridge needs a pty | **Yes** (provider-picker TUI, [A6](PROTOCOL-FINDINGS.md#a6--login---use-device-flow-is-scriptable--partially-refuted)) | No |
| Re-auth over time | Token refresh, delegated to the CLI | None — the key is long-lived |
| Credential lifetime | Bounded, refreshable, revocable per session | **Long-lived, no documented TTL, scoping, or rotation** |
| Availability | Any Kiro account | Pro/Pro+/Pro Max/Power only; admin-managed accounts need generation enabled |

**This does not replace §3, and saying so precisely matters.** The stated product requirement is that *the user* signs in with their Kiro account via a web link. An API key authenticates the **bridge host**; it says nothing about who is holding the phone. A bridge provisioned with a key and then paired to a phone has performed Auth-1 and skipped Auth-2's user-facing half entirely. That is acceptable when the person provisioning the bridge *is* the person holding the phone — which is the common case for a self-hosted bridge — and it is not acceptable as the only path, because it cannot express "sign in as me" to a bridge someone else set up.

**So: both.** F-03 implements API-key provisioning first because it is a few lines and unblocks every downstream item without a pty. F-08 still owes the device-flow relay, and it remains the flow the onboarding presents to a user who is signing in rather than provisioning.

### Requirements this adds

- **The bridge must build its child environment explicitly.** Inheriting `process.env` into the `kiro-cli` child means a stray `KIRO_API_KEY` on the host silently changes which account sessions run as, with nothing in the app reflecting it. Construct the environment from a known set; pass the key only when the bridge was configured with one.
- **The key is a secret of the same class as a device token.** It never leaves the bridge host, is never sent to the app, never appears in a pairing payload, and is redacted from every log and diagnostic bundle (F-20).
- **The app must be able to say which mode its bridge is in.** "Signed in as you" and "running under a host API key" are different trust statements, and the settings screen should not blur them. The bridge reports its mode; the app displays it.
- **`whoami` is not a reliable identity probe under a key.** With `KIRO_API_KEY` set, `whoami --format json` returned `{"accountType":"SocialGoogle","email":null}` on a host with a valid stored Google login — the email resolution takes a different branch. Do not use `whoami` to decide which mode is active; use the bridge's own configuration.

---

## 4. Auth-1: pairing the app to the bridge

The bridge is a WebSocket that can drive an agent with write access to the user's repositories. It must not be open.

Design:

1. Bridge generates a **pairing code** on first run and prints it to its own console (plus a QR encoding of `wss://host:port` + code).
2. App scans the QR or accepts a manual entry, and exchanges the pairing code **once** for a long-lived device token.
3. Device token is stored using AndroidKeyStore + DataStore (**not** `androidx.security:security-crypto`, which is deprecated — see [ADR-003](adr/ADR-003-tech-stack.md#1-stack)).
4. Every WebSocket connection authenticates with the device token; the bridge maintains a revocable device list.

Non-negotiables:

- **TLS required.** Plain `ws://` allowed only for `localhost`, and gated behind an explicit debug build flag. A LAN-exposed plaintext bridge is a credential leak waiting to happen.
- Pairing codes are single-use and short-lived (~5 min).
- Rate-limit pairing attempts; the code is short enough to brute force otherwise.
- Bridge binds to `127.0.0.1` by default and requires explicit opt-in to bind `0.0.0.0`, with a warning.
- Device tokens are revocable from the bridge without re-pairing other devices.
- On logout, wipe the device token and clear cached transcripts.

---

## 5. Reserve flow — authorization code + PKCE

If Kiro ever publishes third-party API access (ADR-001 Option C), the app talks to Kiro directly and Auth-2 becomes a standard native-app OAuth flow. Documented now so the abstraction is built with it in mind, **not to be implemented speculatively.**

- Authorization code + **PKCE with S256** ([RFC 8252](https://datatracker.ietf.org/doc/html/rfc8252)). Never the implicit flow. Never a client secret in the APK.
- Redirect via **App Links** (`https://`, verified via `assetlinks.json`) in preference to a custom scheme — a custom scheme can be claimed by another installed app.
- `state` for CSRF, verified on return; reject mismatches.
- Refresh tokens stored Keystore-backed; refresh serialised so concurrent 401s cause one refresh, not a stampede.

The `AuthGrant` interface in `core/auth/` should be shaped so `DeviceCodeFlow` and `PkceFlow` are peers from the start. That is cheap to do now and expensive to retrofit.

---

## 6. Session and error states the UI must handle

Auth is not a boolean. The state machine:

| State | Meaning | UI |
|---|---|---|
| `Unpaired` | No bridge configured | Pairing screen |
| `BridgeUnreachable` | Paired, host down | Retry + host troubleshooting |
| `KiroSignedOut` | Bridge up, `kiro-cli` not signed in | Start device flow |
| `KiroSignInPending` | Device flow in progress | Show code + URL, allow reopen |
| `NotEntitled` | Signed in, no cloud-session entitlement | Explain Pro requirement / admin enablement; link docs |
| `Ready` | Cloud sessions usable | Normal app |
| `TokenExpired` | Kiro credential lapsed | Silent re-auth if possible, else device flow |

`NotEntitled` is easy to forget and will otherwise present as a confusing generic failure: cloud sessions require Pro or higher, and Identity Center orgs additionally require an admin to enable the preview.

---

## 7. Open questions

**Answered by the F-01 spike (2026-09-02)** — full detail in [PROTOCOL-FINDINGS §3 A6](PROTOCOL-FINDINGS.md#a6--login---use-device-flow-is-scriptable--partially-refuted):

1. ~~Can `login --use-device-flow` be driven non-interactively?~~ **Partly.** The verification URI and user code are printed parseably, and the URI carries the code as a query parameter. But the CLI shows an interactive provider picker first, with no flag to preselect — so the bridge needs a **pty**, and the app needs its own provider list. Not brittle, just more work than assumed; the manual-sign-in fallback is not needed.
2. ~~Does `whoami --format json` expose entitlement?~~ **Identity only** — `accountType` and `email`, or `{"account": null}` when signed out. `NotEntitled` must be inferred from a failed session creation, as feared.

**Still open:**

3. What is the actual lifetime of CLI credentials, and does the CLI refresh silently? Determines whether `TokenExpired` is rare or routine. *Not answered by F-01 — needs observation over days, not one session.* One lead: KAS logs `Auth: --auth=acp-callback (host-mediated refresh via _kiro/auth/getAccessToken)`, which suggests refresh is delegated to whoever owns the token — the CLI, under `--auth-method cli`.
4. Is there any supported way to detect sign-in state changes without polling `whoami`? *Still open.* Note that `_kiro/auth/getAccessToken` is a **client-implemented** method — an ACP client can be asked to supply a token, which is a different trust model worth understanding before F-03 settles its design.

---

## 8. Explicit non-goals

- **No credential proxying.** The app never sends Kiro credentials anywhere, and never asks for a Kiro password. If a design discussion trends that way, it has gone wrong.
- **No embedded WebView sign-in**, under any circumstance.
- **No reverse-engineered OAuth client.** Using a `client_id` extracted from Kiro's own clients is out of scope for this repo (ADR-001 §3).
