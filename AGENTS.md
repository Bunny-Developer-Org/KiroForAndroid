# AGENTS.md — orientation for AI coding agents

Written for an agent that has just been dropped into this repo and needs to be useful within one context window. Every claim below was checked against the files or commands named next to it; anything unverified is marked as such, in the house style this project's docs already use (see [`docs/HOSTING.md`](docs/HOSTING.md) §"What is and isn't verified").

---

## 1. What this project is

An unofficial **Android client for [Kiro](https://kiro.dev) cloud sessions** — agent runs that live in a managed cloud sandbox and keep running after the client disconnects. Kiro publishes no third-party API, so the app cannot reach Kiro directly. Instead it talks to a **bridge**: a small Kotlin/JVM process you run yourself on a host where `kiro-cli` is installed and signed in; the bridge supervises `kiro-cli acp` and relays it to the phone over an authenticated WebSocket. That constraint is the origin of nearly every structural decision here and is argued in [ADR-001](docs/adr/ADR-001-cloud-session-access.md).

Status, per [`README.md`](README.md) and [`docs/FEATURES.md`](docs/FEATURES.md): 8 of 28 backlog items done, 6 partial. **The caveat the README puts in bold and you should carry with you: nothing has been exercised against a real, paid cloud-session creation yet.** Work verified so far ran against `FakeGateway` or a local `kiro-cli`.

---

## 2. Where things are

Three Gradle modules, confirmed by [`settings.gradle.kts`](settings.gradle.kts) and by running `./gradlew projects --offline`:

| Path | What it is | When you touch it |
|---|---|---|
| [`core/`](core/) | **Pure Kotlin/JVM.** No Android dependency, enforced mechanically (§5). The protocol layer and all business logic. | Protocol, models, session state, anything you want unit-testable without an emulator. |
| [`app/`](app/) | The Android app. Compose + Material 3, `applicationId` `dev.kiro.android`. | Screens, ViewModels, Android platform implementations. |
| [`bridge/`](bridge/) | The host-side relay. Kotlin/JVM `application`, main class `dev.kiro.bridge.MainKt`. Not shipped in the APK. | Pairing, WebSocket serving, `kiro-cli` supervision, replay log. |
| [`tools/`](tools/) | **Not a Gradle module** — absent from `settings.gradle.kts`. Node probe + shell scripts + deploy scripts. | Capturing protocol fixtures, running on a device/emulator, hosting the bridge. |
| [`docs/`](docs/) | The ADRs and findings. This project's real accumulated knowledge. | Read before code; update when a decision changes. |
| [`config/detekt/detekt.yml`](config/detekt/detekt.yml) | Detekt config, applied to every subproject from the root `build.gradle.kts`. | Adding a rule (it is deliberately small — read its header comment first). |
| [`.github/workflows/ci.yml`](.github/workflows/ci.yml) | The whole CI definition. | Changing what gates a PR. |

### `core/` — `dev.kiro.core`

- [`acp/`](core/src/main/kotlin/dev/kiro/core/acp/) — `JsonRpc.kt` (framing), `AcpMethods.kt` (method constants **plus** `ExtensionNamespace`, which derives Kiro's `_kiro/` prefix from the handshake instead of hardcoding it), `AcpModels.kt`, `AcpTransport.kt` (the transport seam), `AcpClient.kt` (request/response correlation, and handling of agent→client requests).
- [`session/`](core/src/main/kotlin/dev/kiro/core/session/) — `CloudSessionGateway.kt` is **the seam every feature codes against**; `BridgeGateway.kt` is the live implementation over ACP; `FakeGateway.kt` is the offline stand-in; `TranscriptReducer.kt`; `RepoCatalog.kt`.
- [`model/`](core/src/main/kotlin/dev/kiro/core/model/) — `CloudSession`, `SourceRepo`, `RepoSlug`, `PermissionRequest`, `TranscriptEntry`, `AgentMode`.
- [`auth/TokenStore.kt`](core/src/main/kotlin/dev/kiro/core/auth/TokenStore.kt), [`util/Logger.kt`](core/src/main/kotlin/dev/kiro/core/util/Logger.kt) (also declares `Clock` and `DriftMetrics`) — platform interfaces implemented in `app/`.
- [`src/test/resources/fixtures/`](core/src/test/resources/fixtures/) — **golden JSONL frames captured from a real `kiro-cli 2.19.2`.** Tests parse these, not invented frames. Its [README](core/src/test/resources/fixtures/README.md) explains the format.

Note: [ADR-003 §2](docs/adr/ADR-003-tech-stack.md#2-module-layout) lists a few files that do not exist yet (`TurnStateMachine.kt`, `auth/DeviceCodeFlow.kt`, `PkceFlow.kt`, `model/AutonomyLevel.kt`, `KiroModel.kt`, `ToolCall.kt`, an `app/ui/settings/` package). The ADR describes the intended layout; the tree above is what is actually on disk today.

### `app/` — `dev.kiro.android`

- Entry points: [`KiroApp.kt`](app/src/main/kotlin/dev/kiro/android/KiroApp.kt), [`MainActivity.kt`](app/src/main/kotlin/dev/kiro/android/MainActivity.kt), and [`service/SessionConnectionService.kt`](app/src/main/kotlin/dev/kiro/android/service/SessionConnectionService.kt) (a `dataSync` foreground service, declared in [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)).
- [`ServiceLocator.kt`](app/src/main/kotlin/dev/kiro/android/ServiceLocator.kt) — **the project's DI pattern is manual constructor injection behind a small service locator, not Hilt.** It also falls back to `FakeGateway()` when nothing is paired, which is why every screen renders with no bridge running.
- [`platform/`](app/src/main/kotlin/dev/kiro/android/platform/) — `WebSocketAcpTransport`, `KeystoreTokenStore`, `PairingClient`, `AndroidAdapters` (DataStore-backed registries/stores).
- [`service/`](app/src/main/kotlin/dev/kiro/android/service/) — `Backoff`, `ConnectivityObserver`, the connection service.
- [`ui/`](app/src/main/kotlin/dev/kiro/android/ui/) — `onboarding/` (pairing), `bridges/` (multi-bridge list), `sessions/`, `create/`, `transcript/` (incl. `ApprovalCard`, `UserInputCard`, `Composer`), `common/Components.kt`, `theme/` (`Palette.kt`, `KiroColors.kt`, `Theme.kt`), plus `AppNavigation.kt` / `Screen.kt`.
- Debug variant only: [`network_security_config.xml`](app/src/debug/res/xml/network_security_config.xml) via [`app/src/debug/AndroidManifest.xml`](app/src/debug/AndroidManifest.xml) — cleartext for local bridges.

### `bridge/` — `dev.kiro.bridge`

[`Main.kt`](bridge/src/main/kotlin/dev/kiro/bridge/Main.kt) (CLI parsing, pairing banner, `USAGE` text), [`BridgeConfig.kt`](bridge/src/main/kotlin/dev/kiro/bridge/BridgeConfig.kt) (defaults `127.0.0.1:8765`; `validate()` refuses a non-loopback bind without TLS), [`BridgeServer.kt`](bridge/src/main/kotlin/dev/kiro/bridge/BridgeServer.kt) (Ktor CIO + WebSockets), [`CliSupervisor.kt`](bridge/src/main/kotlin/dev/kiro/bridge/CliSupervisor.kt), [`PairingService.kt`](bridge/src/main/kotlin/dev/kiro/bridge/PairingService.kt), [`SessionLog.kt`](bridge/src/main/kotlin/dev/kiro/bridge/SessionLog.kt). Plus [`bridge/Dockerfile`](bridge/Dockerfile).

### `tools/`

- [`acp-probe/`](tools/acp-probe/) — dependency-free Node harness (`probe.mjs`, `mkfixtures.mjs`, `scripts/*.json`) that produced `docs/PROTOCOL-FINDINGS.md` and the golden fixtures. Re-run it after a `kiro-cli` upgrade; the fixture diff is the protocol changelog Kiro doesn't publish.
- [`run-emulator.sh`](tools/run-emulator.sh) — installs (if needed) and boots a headless AVD matching `compileSdk`.
- [`run-on-device.sh`](tools/run-on-device.sh) — builds, installs, sets up `adb reverse tcp:8765`, starts the bridge. The `adb reverse` trick is what lets a loopback-only bridge be reached from the phone without TLS.
- [`deploy/gcp/`](tools/deploy/gcp/) and [`deploy/cloudflare/`](tools/deploy/cloudflare/) — always-on hosting. **Read §7 before touching these.**

---

## 3. Build, run, test

Environment (from [`README.md`](README.md), [ADR-003 §4](docs/adr/ADR-003-tech-stack.md#4-build-environment-gotcha) and [`.github/workflows/ci.yml`](.github/workflows/ci.yml)):

```bash
export JAVA_HOME=/path/to/jdk-21   # AGP rejects JDK 22+; CI pins Temurin 21
export ANDROID_HOME=/path/to/android-sdk
```

Gradle wrapper is **9.7.1** ([`gradle/wrapper/gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties)). [`gradle.properties`](gradle.properties) enables parallel builds, the build cache **and the configuration cache** — a build script change you make will be re-configured, but keep configuration-cache compatibility in mind when adding tasks.

Commands, in the order CI runs them:

```bash
./gradlew :core:corePurityCheck      # the one hard rule, checked first (§5)
./gradlew detekt                     # static analysis + ktlint formatting rules, maxIssues: 0
./gradlew :core:test :bridge:test :app:testDebugUnitTest   # JVM + Robolectric-free unit tests
./gradlew :app:assembleDebug :bridge:installDist
```

**Verified here:** `./gradlew projects --offline` and `./gradlew :core:corePurityCheck :core:test :bridge:test --offline` both succeed on this machine (Temurin 21.0.6, ~2 s warm, all tasks `UP-TO-DATE`). Gradle prints a Gradle-10 deprecation warning; harmless today.

`app/` has unit tests too ([under `app/src/test/`](app/src/test/kotlin/dev/kiro/android/)) and, as of the `unit-tests-github-actions` change, **CI runs them**: `ci.yml`'s "Unit tests" step runs `:core:test :bridge:test :app:testDebugUnitTest`. Run `./gradlew :app:testDebugUnitTest` locally when you change `app/`.

**Android SDK:** `:app:assembleDebug` needs one. `:bridge:installDist` does **not** — [`bridge/Dockerfile`](bridge/Dockerfile) states this was verified locally with `ANDROID_HOME` unset, and [`docs/HOSTING.md`](docs/HOSTING.md) repeats it. *I did not re-verify this myself* (this machine has a valid `local.properties` pointing at an SDK, so the test would have been meaningless without perturbing the working tree). Note the accompanying caveat, which is real: configure-on-demand is off, so Gradle still *configures* `:app`; only configuration, not the SDK, is required.

Running the bridge locally:

```bash
./gradlew :bridge:installDist && ./bridge/build/install/bridge/bin/bridge
```

It prints a pairing code. `--help` documents the flags (`--bind`, `--port`, `--api-key`/`KIRO_API_KEY`, `--tls-cert`, `--state-dir`, `--pair`). For a phone over USB use [`tools/run-on-device.sh`](tools/run-on-device.sh); for an emulator, [`tools/run-emulator.sh`](tools/run-emulator.sh) first.

---

## 4. Key documents

Read [ADR-001](docs/adr/ADR-001-cloud-session-access.md) and [PROTOCOL-FINDINGS](docs/PROTOCOL-FINDINGS.md) before writing code. The README's reading order is the right one.

| Document | What it is for |
|---|---|
| [`docs/adr/ADR-001-cloud-session-access.md`](docs/adr/ADR-001-cloud-session-access.md) | Why there is a bridge at all. Accepted; constrains everything else. Rejects reverse-engineering Kiro's private API **on principle**. |
| [`docs/adr/ADR-002-react-native-vs-native.md`](docs/adr/ADR-002-react-native-vs-native.md) | Native Kotlin/Compose over React Native. Accepted and implemented. |
| [`docs/adr/ADR-003-tech-stack.md`](docs/adr/ADR-003-tech-stack.md) | Module layout, package structure, the `core/` purity rule, tolerant parsing, streaming-render rule, JDK/AGP ceiling. Accepted and implemented. |
| [`docs/adr/ADR-004-work-repo-selection.md`](docs/adr/ADR-004-work-repo-selection.md) | How a repository gets bound to a cloud session. **Status: Proposed.** |
| [`docs/adr/ADR-005-bridge-hosting-and-availability.md`](docs/adr/ADR-005-bridge-hosting-and-availability.md) | Where the bridge runs and what the app does when it is unreachable. **Status: Proposed.** |
| [`docs/PROTOCOL-FINDINGS.md`](docs/PROTOCOL-FINDINGS.md) | The F-01 spike report: what a real `kiro-cli 2.19.2` / KAS 0.52.1 actually does, versus the published docs. Where they disagree, this wins. |
| [`docs/ACP-INTEGRATION.md`](docs/ACP-INTEGRATION.md) | The ACP contract the client implements, rewritten on top of the captured frames. |
| [`docs/AUTHENTICATION.md`](docs/AUTHENTICATION.md) | The two separate authentications (phone↔bridge, bridge↔Kiro) and the sign-in contract. |
| [`docs/HOSTING.md`](docs/HOSTING.md) | The concrete "how" for ADR-005 Option B: a fully cloud-hosted bridge on GCE + Cloudflare Tunnel, with a cost table (~$3/month, not $0) and an explicit verified/unverified section. |
| [`docs/VISUAL-LANGUAGE.md`](docs/VISUAL-LANGUAGE.md) | Not an ADR. Constrains look, so parallel screen work produces one app. Gives numbers; use them. |
| [`docs/PRIOR-ART.md`](docs/PRIOR-ART.md) | Other unofficial Kiro clients, surveyed 2026-09-02. None reaches cloud sessions. |
| [`docs/FEATURES.md`](docs/FEATURES.md) | The backlog (`F-00`…`F-26`, 28 headings — `F-19` appears twice, and `F-24`–`F-26` sit out of numeric order) with per-item status — the freshest source of truth for what is done. **Its "How to pick up an item" section is effectively this repo's contribution guide.** |

---

## 5. Conventions that are actually enforced

- **`core/` may not import `android.*` or `androidx.*`.** Not a guideline — [`core/build.gradle.kts`](core/build.gradle.kts) defines a `CorePurityCheck` Gradle task wired into `check`, and CI runs it first and alone so a violation reports as itself. Express the platform dependency as an interface in `core/` and implement it in `app/` (`TokenStore`, `BrowserLauncher`, `Clock`, `Logger`).
- **`allWarningsAsErrors` is on in `core/` and `bridge/`** (their `build.gradle.kts` `compilerOptions`). Not in `app/`.
- **Detekt with `maxIssues: 0`**, applied to all subprojects from the root build file. The config is intentionally minimal, and two rules are disabled *on purpose* (`TooGenericExceptionCaught`, `SwallowedException`) because tolerant parsing requires broad catches at the protocol boundary.
- **Version catalog only, no `buildSrc`.** [`gradle/libs.versions.toml`](gradle/libs.versions.toml) is the single source of truth: AGP 9.4.0, Kotlin 2.4.10, Ktor 3.5.2, coroutines 1.11.0, serialization 1.11.0, detekt 1.23.8, Compose BOM 2026.08.00; `compileSdk`/`targetSdk` 37, `minSdk` 26. Java 17 bytecode everywhere, built on JDK 21. Add a dependency there first.
- **AGP 9 applies the Kotlin plugin itself** — `app/build.gradle.kts` says so in a comment; adding `org.jetbrains.kotlin.android` on top is now an error, not a redundancy.
- **Tolerant deserialization is a project-wide requirement** ([ADR-003 §3](docs/adr/ADR-003-tech-stack.md#3-two-conventions-that-are-load-bearing)): `ignoreUnknownKeys = true`, unknown methods logged and dropped, unknown update kinds rendered generically. A protocol addition must degrade to a cosmetic gap, never a broken session.
- **Streaming rendering is designed, not discovered** (same section): coalesce chunks on a ~60–100 ms tick; render the in-flight message outside the lazy list.
- **Comment style.** Code comments here explain *why*, cite the ADR section that decided it, and record the road not taken. See the `CorePurityCheck` block, `BridgeConfig`'s KDoc, or the `testImplementation(libs.ktor.server.*)` comment in `app/build.gradle.kts`. Match this — terse comments that only restate the code are off-style.
- **Docs state their verification status and the date, inline.** ADR headers carry `Status:` / `Date:`; findings say what was captured, from which `kiro-cli` version, on which date; scripts that were never run say so in their own file header. Keep doing this. If you did not check something, say you did not.
- **Commit messages:** Conventional-Commits-style prefixes, lowercase, module-scoped — `feat(app):`, `fix(core):`, `fix(bridge):`, `docs:`, `spike(F-01):`, `chore:` — with a full explanatory body: what changed per module, what was verified and how. Feature commits reference their `F-NN` item. 22 of the commits so far carry a `Co-Authored-By:` trailer. Merges of agent worktrees read `Merge <description> (worktree-agent-<id>)`.

---

## 6. Definition of done

Taken verbatim in substance from [`docs/FEATURES.md` § How to pick up an item](docs/FEATURES.md#how-to-pick-up-an-item):

1. Read ADR-001 first. A PR adding a reverse-engineered `app.kiro.dev` endpoint is **rejected on principle**.
2. Read PROTOCOL-FINDINGS; the published Kiro docs are wrong in several places.
3. **Respect the seams.** UI talks to `CloudSessionGateway`, never to a transport. `core/` takes no Android dependency.
4. Use the paths in ADR-003 §2. If you need a new package, say so.
5. Done = acceptance criteria met · unit tests for `core/` logic · no new lint/detekt warnings · docs updated if a decision changed · tolerant parsing preserved.
6. **If an assumption proves false, stop and report it** — update the ADR, don't work around it locally.

---

<!-- skill-issue:sentinel:start -->
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
quality, security, and deployment contracts. Sentinel reports are local
evidence under `.sentinel/reviews/` and are excluded from Git.
<!-- skill-issue:sentinel:end -->

---

## 7. Pitfalls, each with its evidence

- **`kiro-cli acp` must be started with `--agent-engine v3 --auth-method cli`.** The default engine is local-only and cannot see cloud sessions *at all* — a client using default flags would wrongly conclude the whole approach is dead. ([ACP-INTEGRATION](docs/ACP-INTEGRATION.md) opening note, [PROTOCOL-FINDINGS §2](docs/PROTOCOL-FINDINGS.md).)
- **Don't hardcode the extension prefix.** `kiro-cli 2.19.2` sends `_kiro/`, the docs page says `_kiro.dev/`, and KAS lets an operator change it via `KIRO_EXTENSION_NAMESPACE`. `ExtensionNamespace` in [`AcpMethods.kt`](core/src/main/kotlin/dev/kiro/core/acp/AcpMethods.kt) derives it from the handshake; use that.
- **The agent sends requests too.** Permission prompts arrive as agent→client requests and must be answered or the agent blocks forever. A request/response-only client hangs on the first tool approval. (`AcpClient` KDoc; ACP-INTEGRATION §1.)
- **`KIRO_API_KEY` overrides whatever account the CLI is signed in as, with no flag to suppress it.** Verified as A18 on 2026-09-02; documented in `BridgeConfig`'s KDoc and the bridge `--help`.
- **The bridge refuses a non-loopback bind without TLS** (`BridgeConfig.validate()`). This is deliberate, not a bug to route around. For device testing use `adb reverse`, which [`tools/run-on-device.sh`](tools/run-on-device.sh) sets up.
- **A sleeping bridge is a silent app.** Notifications originate at the bridge; a laptop that closes at night delays approvals until it wakes. ADR-005 treats this as a documented limitation with a designed degradation path.
- **Nothing under [`tools/deploy/`](tools/deploy/) has ever been run against a real cloud account or domain**, and both scripts say so in their own headers: they were checked line-by-line against real `gcloud` / `cloudflared --help` output, but nothing that provisions or spends was executed. `bridge/Dockerfile` has likewise never been built (no Docker daemon in the authoring environment). Treat all three as reviewed-but-unexercised. Also: the GCP setup is **not** $0 — the external IPv4 is ~$3/month; see [`docs/HOSTING.md`](docs/HOSTING.md) §3.
- **`docs/FEATURES.md` anchors are load-bearing.** F-01's heading carries an HTML comment asking you to keep status markers *out* of headings, because three other documents link to its anchor.
- **`local.properties` is gitignored** and holds `sdk.dir`. It exists on this machine; a fresh clone needs it or `ANDROID_HOME`.
- **The emulator system image lags `compileSdk`.** `tools/run-emulator.sh` pins API `37.0` `google_apis` `x86_64` and explains that `compileSdk` moves ahead of stable images.

---

## 8. Things I could not verify

Listed explicitly so nobody mistakes them for checked facts:

- That `:bridge:installDist` builds with no Android SDK present. Documented in `bridge/Dockerfile` and `docs/HOSTING.md`; **not re-tested here** (see §3 for why).
- The contents of `docs/*.md` beyond their opening sections and the passages quoted above. §4's one-line descriptions are summaries of what each document *says it is for*, not independent verification of its claims.
- Anything about a real Kiro cloud session. Per the README, no paid session has been created yet; `FakeGateway` and a local `kiro-cli` are the extent of what has been exercised.
- Whether `./gradlew :app:assembleDebug` currently succeeds — not run (it needs an SDK download path and takes considerably longer than the JVM targets). Only `projects`, `corePurityCheck`, `:core:test` and `:bridge:test` were executed, offline.
- `.kiro/agents/` is empty and `.kiro/settings/cli.json` holds only three `toolSearch.*` keys; I found no repo-local agent role definitions.
