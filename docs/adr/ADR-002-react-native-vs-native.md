# ADR-002: React Native vs. native Kotlin + Compose for KiroForAndroid

- **Status:** Accepted, and implemented — the app, core, and bridge modules are all native Kotlin/Compose as of 2026-09-02. Nothing since has surfaced a reason to revisit; see §5's trigger table.
- **Date:** 2026-09
- **Decision owner:** KiroForAndroid tech lead
- **Scope:** Client platform/runtime choice for this repo. Does not decide UI design, DI, or module layout.

---

## 1. Context

KiroForAndroid is an Android client for Kiro [cloud sessions](https://kiro.dev/docs/cloud-sessions/) — agent runs that live in a managed cloud sandbox and survive the client disconnecting. Kiro ships a native iOS app (closed source, Apple-built, TestFlight) and no Android app. Every Kiro surface — IDE, CLI, Web, Mobile — talks to the same agent harness over the [Agent Client Protocol](https://kiro.dev/docs/cli/acp/) (JSON-RPC 2.0), extended with Kiro-specific `_kiro/` methods; Web and Mobile use a WebSocket transport.

At the time this ADR was written, the repo contained **planning documents only — no implementation**, which is why §6's migration-cost table below starts from a "switching is free" baseline. That baseline is now historical: the stack it proposed ([ADR-003](ADR-003-tech-stack.md), Kotlin + Compose, Ktor, kotlinx.serialization) has since been built — `app/`, `core/`, and `bridge/` are real, tested, CI-green Kotlin code, with session list, multi-bridge pairing, transcript rendering, and reconnect logic shipped (see [FEATURES.md](../FEATURES.md)). The decision recorded here has not been revisited; §6 is kept as a historical estimate of what switching would have cost at each milestone, with a note added on where the project actually sits today.

The requirements that actually discriminate between runtimes:

| # | Requirement | Why it discriminates |
|---|---|---|
| R1 | OAuth via system browser — auth code + PKCE ([RFC 8252](https://datatracker.ietf.org/doc/html/rfc8252)) and/or device authorization grant ([RFC 8628](https://datatracker.ietf.org/doc/html/rfc8628), already supported by `kiro-cli login --use-device-flow`), against AWS Builder ID / IAM Identity Center OIDC plus Google/GitHub social | Custom Tabs + intent-filter/App Links are platform APIs; library coverage differs per grant |
| R2 | Tokens encrypted at rest | Keystore-backed; both runtimes wrap the same primitive, but the wrappers differ in maturity |
| R3 | Long-lived WebSocket + JSON-RPC with reconnect/replay across backgrounding and network loss | The single hardest requirement, and it is ~100% platform code |
| R4 | Token-by-token streaming into transcripts of thousands of messages/tool calls, with code blocks + highlighting | Render/layout cost model differs sharply |
| R5 | Permission prompts surfaced promptly (incl. push) and answered | Needs background wake + notification actions |
| R6 | Background behaviour + FCM push for turn-end / approval-needed | Foreground services, Doze, notification channels |
| R7 | Diff viewing for agent-produced PRs | Rich text rendering; ecosystem-dependent |

Two constraints frame everything below:

- **This is a third-party Android client.** Kiro's iOS app and Web client are both closed source. We cannot consume their code in any runtime.
- **Android-only scope today.** No committed iOS roadmap for this repo.

---

## 2. Options

### (a) Native Kotlin + Jetpack Compose — status quo

Compose + Material 3, OkHttp `WebSocket` (or Ktor) for ACP, kotlinx.serialization for JSON-RPC framing, a `dataSync` foreground service for the live turn, WorkManager for reconnect/backoff, AppAuth-Android or a hand-rolled device-flow poller for R1, Keystore for R2, Firebase Messaging for R6. One Gradle build, one language.

Open homework independent of this ADR: `androidx.security:security-crypto` [was deprecated at `1.1.0-alpha07`](https://developer.android.com/reference/androidx/security/crypto/package-summary), with the guidance pointing at `AndroidKeyStore` directly. Token storage needs a Keystore + `DataStore` implementation, not `EncryptedSharedPreferences`. This is now reflected in [ADR-003 §1](ADR-003-tech-stack.md#1-stack) and scoped as work item F-06.

### (b) React Native

The New Architecture (Fabric renderer, TurboModules, JSI, bridgeless) has been the default since 0.76 and is [the only architecture as of 0.82](https://reactnative.dev/blog/2025/10/08/react-native-0.82?) — the legacy bridge is gone, not merely discouraged. [0.84 made Hermes V1 the default engine](https://reactnative.dev/blog/2026/02/11/react-native-0.84); the current stable is **0.87** (Aug 2026), which defaults the Strict TypeScript API and adds AGP 9 support. Notably, [0.83 and 0.86 shipped with no user-facing breaking changes](https://reactnative.dev/blog) — the upgrade treadmill is measurably calmer than its reputation.

**Expo vs bare:** choose **Expo with a development build** (CNG / prebuild), not bare RN and not Expo Go. This app needs custom native code (foreground service, FCM data handling), which rules out Expo Go — and on Android, [remote push notifications don't work in Expo Go at all since SDK 53](https://docs.expo.dev/versions/latest/sdk/notifications/). Development builds explicitly support arbitrary native modules and are [the recommended path for store releases](https://docs.expo.dev/develop/development-builds/introduction/). Bare RN throws away config plugins, EAS Build, and EAS Update for no benefit here. Current SDK is [56 (RN 0.85, React 19.2)](https://expo.dev/changelog/sdk-56).

Concrete stack: `expo-auth-session` (R1) + `expo-secure-store` (R2) + RN's WebSocket wrapped in a JSON-RPC layer (R3) + FlashList v2 (R4) + a custom TurboModule foreground service (R3/R6) + `expo-notifications` (R5/R6) + Shiki-based highlighting (R4/R7).

### (c) Kotlin Multiplatform

`:core` in `commonMain` — ACP client, JSON-RPC framing, session/turn state machine, token refresh, transcript reducer — with Ktor's WebSocket client (OkHttp engine on Android, Darwin on iOS). UI stays native: Compose on Android, SwiftUI on iOS. [KMP is stable, production-ready, and Google-supported](https://developer.android.com/kotlin/multiplatform); [Compose Multiplatform for iOS reached stable in May 2025](https://volpis.com/blog/is-kotlin-multiplatform-production-ready/) if UI sharing is ever wanted.

The important property: **this is not a competing option today, it is a shape.** Everything in `commonMain` would be code we write anyway. The only cost of keeping the door open is discipline — no `android.*` imports in `:core`, Ktor instead of raw OkHttp.

### (d) Flutter — reference point only

Dart + its own Skia/Impeller renderer, so text layout never crosses a language boundary; the best streaming-render ceiling of the cross-platform options and a single toolchain. But: background work still goes through platform channels (same native code as RN), no reuse with any TS/web asset, and the smallest contributor overlap with "people who want an Android Kiro client." Included for calibration, not seriously proposed.

---

## 3. Evaluation against the requirements

Scores 1–5 (5 = best). Weight reflects how much each criterion can sink this specific app.

| Criterion | W | Native | RN | KMP | Flutter | Reasoning |
|---|---|---|---|---|---|---|
| R1 OAuth / Custom Tabs / deep links | M | **5** | 4 | 5 | 4 | Near-tie. `expo-auth-session` and `react-native-app-auth` both drive Custom Tabs properly and refuse WebViews per RFC 8252 §8.12; `react-native-app-auth` is AppAuth-Android underneath. One real gap: it [supports only the authorization code flow](https://github.com/FormidableLabs/react-native-app-auth), so RFC 8628 device flow is hand-rolled — though device flow is just `POST` + poll + open a URL, so that's ~100 lines in any language. RN loses a half point for debugging AWS OIDC edge cases through a native module you don't own. |
| R2 Secure token storage | M | 4 | 4 | 4 | 4 | Genuine tie. `expo-secure-store` [encrypts values with the Android Keystore](https://commerce.nearform.com/open-source/react-native-app-auth/docs/token-storage), `react-native-keychain` adds biometric gating. Native is *not* ahead here: the obvious AndroidX wrapper is deprecated (above), so both runtimes end up writing deliberate Keystore code. Nobody gets a 5. |
| R3 WebSocket + JSON-RPC + background survival | **XL** | **5** | 2 | 5 | 2 | The decisive row. RN's WebSocket API is fine while the app is foregrounded; the problem is that the JS runtime is not a reliable place for a connection that must survive backgrounding. [Headless JS pauses once the task resolves](https://reactnative.dev/docs/headless-js-android.html), and periodic background APIs can't hold a socket. The accepted RN answer is to run the socket inside a native foreground service and bridge events out — i.e. you write the Kotlin anyway, then pay for a JSI boundary and dual state ownership on top. Android 15's [6-hour-per-24h `dataSync` cap](https://developer.android.com/develop/background-work/services/fgs/timeout) and `onTimeout()` handling apply to both, but native handles them in the same language as the rest of the app. |
| R4 Streaming-text render performance | **L** | 4 | 3 | 4 | 4 | Both runtimes must batch chunks (~60–100 ms coalescing); nobody should re-render per token. Post-Fabric RN is far better than its reputation and [FlashList v2 is a JS-only rewrite for the New Architecture with no size estimates](https://shopify.engineering/flashlist-v2) — but recycling means [item components must be cheap and props memoized](https://shopify.github.io/flash-list/docs/usage/), and field reports are consistent that [FlashList isn't a free pass when rows do real work](https://www.reactnativepro.com/tutorials/flashlist-performance-in-react-native-a-case-study/) — which a highlighted markdown row does. Compose can mutate one `AnnotatedString` in a `SnapshotStateList` and confine recomposition to a single text node with no serialization boundary. Both are shippable; native's floor on cheap hardware is higher. Recommended pattern either way: render the in-flight message as a separate view *outside* the virtualized list, and only append it to the list at `TurnEnd`. |
| R5/R6 Background + push | **L** | **5** | 2 | 5 | 2 | Same story as R3 plus notification-action round-trips. Approval prompts arriving via FCM data messages must wake the app, hit the ACP endpoint, and post an actionable notification — all before JS is guaranteed alive. `expo-notifications` is [push-service agnostic and works with FCM directly](https://docs.expo.dev/push-notifications/sending-notifications-custom/), so wiring exists, but the background-execution semantics are the constraint, not the API. |
| R7 Code blocks / diffs / markdown | M | 3 | **5** | 3 | 4 | **RN's strongest row, and it is not close.** Syntax highlighting and diff rendering want a mature grammar+theme ecosystem, and that lives in JS: [Shiki uses the same TextMate grammars and themes as VS Code](https://shiki.style/guide/), with [a JSI-backed Oniguruma engine for RN](https://github.com/skiniks/react-native-shiki-engine). Compose has no first-class markdown renderer and Kotlin highlighters cover far fewer languages, so native means porting grammars, adopting a View-based library, or embedding a WebView. For an app whose primary content is agent-authored code and diffs, this is a real, recurring cost — arguably 30–40% of the UI surface. |
| Binary size | S | **5** | 3 | 5 | 3 | RN ships Hermes + JSI + RN core `.so`s. With an App Bundle and per-ABI splits this is single-digit MB, not catastrophic, and [Hermes is the smaller engine option](https://docs.expo.dev/guides/using-hermes/) — but it's still overhead a Compose app simply doesn't have. Low weight: nobody abandons a client over 6 MB. |
| Build / CI complexity | M | **5** | 3 | 4 | 4 | Native: one Gradle invocation, one JDK. RN: Gradle *and* Node/Metro, two dependency resolvers, two lockfiles, two caches, npm audit noise in CI. EAS Build removes local setup pain but adds a hosted dependency. For an Android-only project this is pure overhead. |
| Contributor availability | M | 3 | **4** | 3 | 2 | The honest count favours JS/TS. But the *self-selected* pool for "unofficial Android client for a coding agent" skews Android-native, and RN's hard parts here (foreground service, FCM, Keystore) require Android knowledge regardless — so an RN codebase risks contributors who can edit screens but not the parts that break. Net: a modest RN win, smaller than the raw developer-population gap suggests. |
| Dependency / supply-chain risk | **L** | **5** | 2 | 5 | 4 | Weighted heavily *for this app specifically*. This client holds OAuth tokens for an account whose agent can clone repos and open PRs. Token exfiltration is a supply-chain compromise of the user's source control. Native: ~10 first-party AndroidX/Square/JetBrains deps. RN: hundreds of transitive npm packages, historically the most actively attacked registry, in the same process as the token store. That asymmetry is the strongest non-performance argument against RN here. |
| Long-term maintenance | M | 4 | 3 | 4 | 4 | RN's cadence is ~6 releases/year with only the newest few supported, though the [recent no-breaking-change releases](https://reactnative.dev/blog) and [staged release levels](https://reactnative.dev/docs/release-levels) are real improvements, and Expo's yearly SDK majors are predictable. Still more moving parts than an AndroidX BOM bump. |
| **Weighted outcome** | | **Strong** | **Weak–moderate** | **Strong** | **Moderate** | |

---

## 4. Where React Native genuinely wins, and where it genuinely hurts

### Testing the "no iOS codebase to share with" claim

The premise is that RN's core advantage is unavailable because Kiro's iOS app is closed and Apple-built. **That premise is directionally right but the reasoning is wrong, and the correction matters.**

The wrong part: the interesting reuse target for a Kiro client was never iOS — it was **Kiro Web**. Web and Mobile speak the same ACP transport, and a browser client already solves the two hardest *content* problems this app has: streaming markdown/code rendering and diff viewing. A shared TypeScript core — protocol types, JSON-RPC client, transcript reducer, markdown/diff renderers — spanning web and mobile is a coherent architecture, and it is the architecture that would make RN obviously correct here.

The reason it still doesn't apply: **Kiro Web is closed source too.** This is a third-party client, so there is no code to share in either direction, in any runtime. RN's reuse advantage isn't unavailable because iOS is native — it's unavailable because *every* Kiro surface is proprietary. The honest version of the premise: **for this repo, cross-platform code reuse scores zero for every option, and any option's value must come from something other than sharing.**

So RN has to win on its own merits. It has two real ones.

### Where RN genuinely wins

**1. Rendering agent output (the strongest argument, and it survives scrutiny).** See R7 above. This app's content *is* markdown, code blocks, and diffs. Shiki brings VS Code's grammars and themes; Compose brings a smaller, thinner set of options. This isn't a one-time cost — every new language a user's repo contains is a grammar question. If someone argues for RN, this is the argument, and it deserves a straight answer rather than a dismissal.

**2. Over-the-air updates against a moving protocol.** Kiro extends ACP under `_kiro/`, on a cadence set by Kiro, not by us. A client that mis-parses a new notification shape is broken until a Play review completes and a staged rollout finishes — days. [EAS Update ships a JS bundle](https://docs.expo.dev/eas/workflows/get-started/) in minutes. For a client chasing a proprietary, evolving protocol, this is a genuine operational advantage and the one I find hardest to counter. Native mitigations exist and should be adopted regardless: tolerant deserialization (`ignoreUnknownKeys`, unknown notifications logged and dropped rather than fatal), server-driven rendering for permission prompts, remote feature flags. They reduce the gap; they don't close it.

Two weaker wins, for completeness: Fast Refresh on a chat-heavy UI, and a larger nominal contributor pool.

### Where RN genuinely hurts

**1. The reliability core is native either way — so RN adds a boundary and buys nothing.** R3/R5/R6 are the product. A session that silently dies when the user locks their phone, or an approval prompt that arrives twenty minutes late, is a broken client no matter how nicely the transcript renders. All of that logic — foreground service with `dataSync` type and `onTimeout()` handling, Doze-aware reconnect with backoff, FCM data-message wake, replay-from-cursor after a gap, notification actions — is Kotlin in *every* option. Choosing RN means writing that Kotlin and *then* designing a TurboModule contract for it, and reasoning about connection state that lives in two runtimes with two lifecycles. This isn't RN being bad; it's the app's centre of gravity sitting exactly where RN adds cost and no leverage.

**2. Streaming text is RN's worst-case shape.** A single string growing 30–60×/second inside a long virtualized list, with syntax highlighting recomputed on the growing tail, is the canonical RN performance trap. Fabric, Hermes V1, and FlashList v2 make it tractable — I don't think it's disqualifying — but it takes deliberate work (chunk coalescing, memoized rows, incremental highlighting, the streaming message hoisted out of the list) and the low-end-device floor stays lower. Compose needs the same batching and gets a better floor for less effort.

**3. Supply chain, given what the tokens unlock.** Covered above; the point is the *consequence*, not the dependency count. A compromised transitive dep in a client whose credentials let an agent write to the user's repos is a source-control breach.

**4. Build complexity with no offsetting benefit.** Two toolchains to serve one platform.

---

## 5. Recommendation

**Keep native Kotlin + Jetpack Compose. Structure the ACP/session core as a platform-agnostic Kotlin module so a KMP shift later is a refactor, not a rewrite.**

**Confidence: high (~85%)** for the current Android-only, third-party scope. The residual 15% is almost entirely the R7 rendering cost and the OTA-velocity argument, which are real and which I expect to be felt.

Concretely:
1. `:core` module: pure Kotlin/JVM, **no `android.*` imports**. ACP client, JSON-RPC framing, turn state machine, transcript reducer, token refresh.
2. Use **Ktor** (OkHttp engine) rather than raw `OkHttp.WebSocket` in `:core` — the only meaningful cost of keeping KMP viable.
3. Budget the highlighter/diff renderer as a **named work item with a real estimate**, not an afterthought. Evaluate a Compose highlighter vs. a contained WebView for diffs only. This is where native's weakness lands; plan for it.
4. Ship tolerant protocol parsing + remote feature flags from day one to blunt the OTA gap.
5. Replace `androidx.security:security-crypto` with a Keystore + `DataStore` implementation before any token is written to disk.

### Conditions that flip this

| Trigger | Flips to | Why |
|---|---|---|
| Project decides to serve iOS too, with no appetite for two UI codebases | **KMP** (RN second) | Shared Kotlin core + native UI beats RN on R3/R5/R6, which don't get easier on iOS. RN only wins if the team is JS-first. |
| Kiro publishes an official TS ACP client, or open-sources Web's transcript/diff renderers | **Re-evaluate RN seriously** | This is the missing premise. With real code to share, RN's case stops being hypothetical. |
| Protocol churn empirically breaks the client faster than Play review can ship fixes | **RN + EAS Update**, or native + server-driven UI | If measured, not feared. Track it: log unknown-method/parse-failure rates for the first two months and let data decide. |
| Contributor reality is "5 React devs, 0 Android devs" | **RN** | A shipped RN app beats an unfinished native one. Accept the R3/R4 cost and budget for one Android-literate maintainer on the service/FCM module. |
| Transcript rendering must match Kiro Web pixel-for-pixel | **RN** | Matching a React UI in Compose is a permanent tax. |

Flutter does not win under any of these branches: its background story is identical to RN's, and it forfeits the one asset RN might someday have — TS reuse.

---

## 6. Migration cost estimate

**Historical estimate, written when the repo was plan-only.** The table below models the cost of switching *at each milestone*; it was accurate at the "Now" row when this ADR was written, and is kept as-is because the milestones and reasoning still hold. It has not been kept in sync with which row the project is actually on — do not read the "Now" row as current.

**Where the project actually sits as of 2026-09-02:** session list, multi-bridge pairing, transcript streaming/rendering, prompt composer, permission and free-text-input UI, and reconnect hardening have all shipped in some form (several still `PARTIAL` per [FEATURES.md](../FEATURES.md)); the foreground service is declared and wired but push notifications (F-16) have not started. That puts the real switch cost somewhere between the "After streaming transcript ships" and "After FGS + reconnect/replay + push ship" rows — call it **4–8 weeks**, not the "~0" implied by reading only the first data row below.

| Switch point | Effort | Notes |
|---|---|---|
| **Now** (plan only, no code) | **~0** | Revise ADR-003 and the stack references in the backlog. Nothing to port. |
| After auth + session creation | 1.5–2.5 weeks | Re-do R1/R2 with `expo-auth-session` + `expo-secure-store` (well-trodden). Repo/model/autonomy pickers are plain UI — fast to rewrite. |
| After streaming transcript ships | 4–6 weeks | Transcript is the largest UI surface; rewriting it in RN means re-solving R4 from scratch with a different cost model, including perf work on low-end devices. |
| After FGS + reconnect/replay + push ship | **6–10 weeks** | Worst case, and asymmetric: the Kotlin service/FCM/reconnect code (~30–40% of the codebase) largely *survives*, wrapped as a TurboModule — but you gain a new bridge contract, dual state ownership, and a fresh class of lifecycle bugs. Rewriting the transcript dominates the estimate. |

Ongoing delta if RN is chosen: **+1 toolchain in CI**, npm audit triage, an RN/Expo upgrade every 1–2 quarters, and one maintainer who must still know Android well enough to own the foreground service.

**Practical consequence:** if RN is going to be chosen at all, choose it in the next two weeks. The decision gets an order of magnitude more expensive once the transcript exists — which makes "decide now, on merits" the actual recommendation of this ADR, and "keep native" the substance of it.

---

*Sources are linked inline. All external content is paraphrased.*
