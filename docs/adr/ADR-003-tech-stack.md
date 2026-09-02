# ADR-003: Tech stack and module layout

- **Status:** Accepted, and implemented — verified against the real `gradle/libs.versions.toml`, module structure, and CI config as of 2026-09-02 (see §1/§2 notes below for the two version numbers that have since moved).
- **Date:** 2026-09
- **Depends on:** [ADR-001](ADR-001-cloud-session-access.md) (topology), [ADR-002](ADR-002-react-native-vs-native.md) (runtime = native Kotlin/Compose)

This ADR pins the concrete stack so that feature work items can name packages and dependencies instead of re-litigating them. It is deliberately prescriptive — the point is that ten parallel agents produce one coherent codebase.

---

## 1. Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | **Kotlin 2.4.10** | Was "2.0.x". Kotlin 2.0 cannot drive AGP 9, and AGP 9 is what the current SDK requires. Corrected by F-02. |
| UI | Jetpack Compose + Material 3 | Compose BOM, so individual artifact versions stay aligned |
| Min / target / compile SDK | **26 / 37 / 37** | minSdk 26 unchanged — it keeps adaptive icons and modern crypto available and covers effectively the whole active device base. Target/compile have moved twice since this ADR was written (35 → 36 → 37, current as of `gradle/libs.versions.toml`); this row is corrected in place rather than tracked with a changelog, since the number itself carries no design decision. |
| Android Gradle Plugin | **9.4.0** | **AGP 9 applies the Kotlin plugin itself.** Adding `org.jetbrains.kotlin.android` alongside it is now a hard error, not a redundancy — the first thing F-02 hit. |
| Gradle | **9.7.1**, via the committed wrapper | |
| JDK for the build | **17 or 21** | AGP does not support JDK 22+. The sandbox default is JDK 25, so CI and local builds must pin `JAVA_HOME`. This bites immediately; see §4. `core/` and `bridge/` emit **Java 17 bytecode** whichever of the two runs the build, rather than demanding a JDK 17 toolchain that may not be installed. |
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

There is also a `tools/` directory at the repo root (`tools/acp-probe/`, plus device/emulator run scripts). It is not a Gradle module — it doesn't appear in `settings.gradle.kts` — but it is the harness that produced the fixtures in [PROTOCOL-FINDINGS.md](../PROTOCOL-FINDINGS.md) and is worth knowing about even though this diagram, being about module layout, doesn't include it.

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

### Bridge language — **decided: Kotlin/JVM** (F-03, 2026-09-02)

The weak preference held, and two things that arrived after this ADR was written strengthened it:

- **[ADR-005](ADR-005-bridge-hosting-and-availability.md) made the artifact a container image**, which flattens the "requires a JVM on the host" objection almost to nothing. Nobody installing a container cares what is inside it.
- **[PROTOCOL-FINDINGS §4](../PROTOCOL-FINDINGS.md#4-the-larger-finding-kas-already-solves-most-of-f-03) shrank the bridge's job** to authentication, transport security, process supervision and a replay log. That is a small program, and the smaller it is the more the type sharing dominates: `bridge/` depends on `:core` and relays `RpcMessage` values the app parses with the same code that produced them. There is no second protocol definition to drift.

The honest argument against, recorded because it is real: **KAS is a JS bundle and ships `.d.ts` declarations**, so a Node bridge could in principle track Kiro's own types rather than our transcription of them. That was not chosen — depending on a proprietary binary's declaration files in a shipped artifact is a licensing question, not a free win — but it is the strongest case for the road not taken, and it is the thing to re-examine if protocol drift ever becomes the bridge's main maintenance cost.

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

CI must pin the JDK explicitly rather than inheriting the runner default. Required SDK packages track the compile SDK in §1 above (currently `platform-tools`, `platforms;android-37`, `build-tools;37.0.0` — install whatever `compileSdk` in `gradle/libs.versions.toml` currently says, not the literal number here).

---

## 5. Consequences

- Feature agents get concrete paths and a fixed dependency set, so parallel work merges cleanly.
- `core/` purity gives fast JVM tests now and a KMP option later.
- Ktor over OkHttp is a small ergonomic cost today for optionality later — accepted knowingly.
- Hand-rolled Keystore token storage is more work than the deprecated AndroidX wrapper, and is the correct amount of work for a credential that can reach the user's repositories.
