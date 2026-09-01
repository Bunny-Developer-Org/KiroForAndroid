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

```
 Android app                Bridge                    kiro-cli              Browser (phone)
     │                        │                          │                        │
     │  1. POST /auth/kiro/start                         │                        │
     ├───────────────────────►│                          │                        │
     │                        │  2. spawn                │                        │
     │                        │  kiro-cli login          │                        │
     │                        │  --use-device-flow       │                        │
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
- Works with **every** provider Kiro supports, because provider selection happens in the browser, not in our UI. We don't have to know or care whether the user is on Builder ID or Okta.

### Requirements

- Open the verification URI in a **Custom Tab**, never a WebView ([RFC 8252 §8.12](https://datatracker.ietf.org/doc/html/rfc8252#section-8.12)). A WebView lets the host app observe credentials and breaks the user's ability to verify the origin.
- Display the user code prominently, tappable to copy, and keep it visible when the app returns to the foreground.
- Respect `interval` and `expires_in`; surface expiry with a one-tap restart.
- Handle the full RFC 8628 error set: `authorization_pending` (keep waiting), `slow_down` (back off), `access_denied`, `expired_token`.
- Sign-in may already be satisfied — always probe `kiro-cli whoami` first and skip the flow if the host is signed in.

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

Answered by the F-01 spike:

1. Can `kiro-cli login --use-device-flow` be driven non-interactively enough to reliably scrape the verification URI and user code? If output parsing proves brittle, fall back to a one-time manual sign-in on the host — worse UX, still shippable.
2. Does `kiro-cli whoami --format json` expose entitlement, or only identity? If only identity, `NotEntitled` has to be inferred from a failed session creation.
3. What is the actual lifetime of CLI credentials, and does the CLI refresh silently? Determines whether `TokenExpired` is rare or routine.
4. Is there any supported way to detect sign-in state changes without polling `whoami`?

---

## 8. Explicit non-goals

- **No credential proxying.** The app never sends Kiro credentials anywhere, and never asks for a Kiro password. If a design discussion trends that way, it has gone wrong.
- **No embedded WebView sign-in**, under any circumstance.
- **No reverse-engineered OAuth client.** Using a `client_id` extracted from Kiro's own clients is out of scope for this repo (ADR-001 §3).
