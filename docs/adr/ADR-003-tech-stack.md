# ADR-003: Tech stack and module layout

- **Status:** Proposed
- **Date:** 2026-09
- **Depends on:** [ADR-001](ADR-001-cloud-session-access.md) (topology), [ADR-002](ADR-002-react-native-vs-native.md) (runtime = native Kotlin/Compose)

This ADR pins the concrete stack so that feature work items can name packages and dependencies instead of re-litigating them. It is deliberately prescriptive — the point is that ten parallel agents produce one coherent codebase.

---

## 1. Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.0.x | |
| UI | Jetpack Compose + Material 3 | Compose BOM, so individual artifact versions stay aligned |
| Min / target / compile SDK | 26 / 35 / 35 | minSdk 26 keeps adaptive icons and modern crypto available and covers effectively the whole active device base |
| JDK for the build | **17 or 21** | AGP does not support JDK 22+. The sandbox default is JDK 25, so CI and local builds must pin `JAVA_HOME`. This bites immediately; see §4. |
| Build | Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`) | No buildSrc; catalog only |
| Async | Coroutines + `Flow` | Session updates are a `Flow`, not callbacks |
| JSON | `kotlinx.serialization` | Configured `ignoreUnknownKeys = true` — non-negotiable, see §3 |
| HTTP / WebSocket | **Ktor client** (OkHttp engine on Android) | Chosen over raw `OkHttp.WebSocket` per ADR-002 §5 so `:core` stays KMP-portable |
| Navigation | `navigation-compose` | Type-safe routes |
| Preferences | `androidx.datastore` (Preferences) | |
| Secret storage | **AndroidKeyStore + DataStore, written by hand** | Do **not** use `androidx.security:security-crypto`. It is deprecated and its guidance points at AndroidKeyStore directly. Flagged in ADR-002 §3. |
| OAuth | Custom Tabs via `androidx.browser`, plus a hand-rolled device-flow poller | See [AUTHENTICATION.md](../AUTHENTICATION.md). AppAuth-Android is a reasonable alternative for the auth-code leg but does not cover RFC 8628. |
| Push | Firebase Cloud Messaging | Only needed once the bridge can send; see F-16 |
| DI | Manual constructor injection behind a small `ServiceLocator` | Hilt is permitted but not required. Whoever lands F-02 sets the pattern and it is then fixed. |
| Testing | JUnit4 + `kotlinx-coroutines-test` + Turbine for `Flow` | Protocol layer must be unit-testable with a fake transport |

Deliberately **excluded** for now: Room (no local corpus worth a schema yet — transcripts are cached as JSONL, mirroring how the CLI stores sessions), Paging, WorkManager (until F-15 proves it's needed), any WebView except possibly for diff rendering (F-17).

---

## 2. Module layout

```
KiroForAndroid/
├── app/          Android application. Compose UI, navigation, ViewModels,
│                 Android entry points (Activity, Service, FCM receiver), DI wiring.
├── core/         Pure Kotlin/JVM library. THE IMPORTANT ONE.
│                 ACP client, JSON-RPC framing, session/turn state machine,
│                 transcript reducer, CloudSessionGateway + BridgeGateway,
│                 auth flow logic (grant orchestration, not Android plumbing).
└── bridge/       Host-side bridge process (ADR-001 Option B).
                 Runs where kiro-cli lives. Not shipped in the APK.
```

### The one hard rule

> **`core/` must not contain a single `android.*` or `androidx.*` import.**

Enforce it in CI with a grep, not with good intentions. This is what keeps a future iOS/KMP target a refactor rather than a rewrite (ADR-002 §5), and it has an immediate payoff regardless: the entire protocol layer becomes testable on the JVM with no emulator.

Anything platform-specific that `core/` needs is expressed as an interface in `core/` and implemented in `app/`:

| Interface in `core/` | Implementation in `app/` |
|---|---|
| `TokenStore` | Keystore + DataStore |
| `BrowserLauncher` | Custom Tabs |
| `Clock`, `Logger` | Android equivalents |

### Package structure

```
core/  dev.kiro.core
  acp/            JsonRpc.kt, AcpMethods.kt, AcpModels.kt, AcpClient.kt, AcpTransport.kt
  session/        CloudSessionGateway.kt, BridgeGateway.kt, TurnStateMachine.kt,
                  TranscriptReducer.kt
  auth/           AuthGrant.kt, DeviceCodeFlow.kt, PkceFlow.kt, TokenStore.kt
  model/          CloudSession.kt, SourceRepo.kt, AutonomyLevel.kt, KiroModel.kt,
                  TranscriptEntry.kt, ToolCall.kt, PermissionRequest.kt

app/   dev.kiro.android
  ui/onboarding/  bridge pairing + sign-in
  ui/sessions/    session list
  ui/create/      new cloud session
  ui/transcript/  live conversation
  ui/settings/
  ui/theme/
  service/        SessionConnectionService (foreground), FCM receiver
  platform/       TokenStore/BrowserLauncher implementations
```

Feature work items in [FEATURES.md](../FEATURES.md) reference these paths directly. If a work item needs a new package, it says so.

### Bridge language — open decision

Two candidates, to be settled by whoever takes F-03:

- **Kotlin/JVM** — shares `core/`'s protocol types verbatim, one language across the project, ships as a fat jar. Cost: requires a JVM on the host.
- **Node/TypeScript** — lower install friction for most developers, trivial process spawning. Cost: protocol types are duplicated and can drift, which is exactly the bug class we least want.

Weak preference for **Kotlin/JVM**, because a drifting protocol definition between app and bridge would be a genuinely nasty class of bug and type sharing eliminates it outright. Not yet decided — F-03 owns it and must record the outcome here.

---

## 3. Two conventions that are load-bearing

**Tolerant deserialization, everywhere, from day one.** Kiro's ACP extensions are documented as experimental and subject to change, and [the docs disagree with themselves about the extension prefix](ADR-001-cloud-session-access.md#1-the-problem). Therefore: `ignoreUnknownKeys = true`; an unrecognised notification method is logged and dropped, never fatal; an unrecognised session-update kind renders as a generic entry rather than crashing the transcript. A protocol addition on Kiro's side must degrade to a cosmetic gap, never to a broken session. ADR-002 §4 makes the same point from the OTA-update angle.

**Streaming rendering is designed, not discovered.** Chunks arrive many times per second. Coalesce them on a ~60–100 ms tick; render the in-flight message as a node *outside* the lazy list and append it to the list only on `TurnEnd`. Recomputing highlighting on a growing string per token is the specific mistake to avoid. This is a design constraint on F-12, not a later optimisation.

---

## 4. Build environment gotcha

The AGP/JDK ceiling is a real trap and worth stating once, loudly:

```bash
# AGP does not support JDK 22+. Pin an LTS.
export JAVA_HOME="$(mise where java@21)"
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleDebug
```

CI must pin the JDK explicitly rather than inheriting the runner default. Required SDK packages: `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`.

---

## 5. Consequences

- Feature agents get concrete paths and a fixed dependency set, so parallel work merges cleanly.
- `core/` purity gives fast JVM tests now and a KMP option later.
- Ktor over OkHttp is a small ergonomic cost today for optionality later — accepted knowingly.
- Hand-rolled Keystore token storage is more work than the deprecated AndroidX wrapper, and is the correct amount of work for a credential that can reach the user's repositories.
