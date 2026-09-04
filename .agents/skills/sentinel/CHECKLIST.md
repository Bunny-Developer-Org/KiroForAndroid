# Sentinel — KiroForAndroid Checklist

Apply only sections relevant to the task. Root [`AGENTS.md`](../../../AGENTS.md)
and the ADRs under [`docs/adr/`](../../../docs/adr/) remain authoritative.

## Repository and delivery policy

- [ADR-001](../../../docs/adr/ADR-001-cloud-session-access.md) is accepted and
  constrains everything: a change that reaches Kiro by reverse-engineering
  `app.kiro.dev` or any other private endpoint is rejected on principle, not
  reworked to be sneakier about it. If a change adds a new outbound call, ask
  where it goes.
- The change set matches the requested outcome and excludes unrelated work,
  credentials, `local.properties`, build output, and captured protocol
  fixtures that were not actually recaptured.
- Docs and scripts in this repo state their own verification status inline
  (`Status:`/`Date:` in ADRs, "verified"/"not re-verified" language in
  findings and script headers). A new or edited doc/script must carry the same
  discipline — never assert a check that wasn't actually run this session.
- Commit messages are Conventional-Commits-style, lowercase, module-scoped
  (`feat(app):`, `fix(core):`, `fix(bridge):`, `docs:`, `spike(F-NN):`,
  `chore:`), with a body describing what changed per module and what was
  verified and how. Feature commits reference their `F-NN` item from
  [`docs/FEATURES.md`](../../../docs/FEATURES.md).

## Module and quality scope

- [`core/`](../../../core/) — pure Kotlin/JVM, protocol layer and business
  logic. **Must not import `android.*` or `androidx.*`**; run
  `./gradlew :core:corePurityCheck` for any `core/` change — it is not
  optional and CI runs it first, alone. `allWarningsAsErrors` is on here.
- [`app/`](../../../app/) — the Android app (Compose/Material 3). UI talks to
  `CloudSessionGateway`, never to a transport directly. Run
  `./gradlew :app:testDebugUnitTest` for logic changes and
  `./gradlew :app:assembleDebug` when SDK-dependent code changed.
- [`bridge/`](../../../bridge/) — host-side relay (Ktor CIO + WebSockets),
  `allWarningsAsErrors` is on here too. Run `./gradlew :bridge:test`; if
  `CliSupervisor`, `PairingService`, or `BridgeConfig` changed, also sanity
  check `--help` output and `BridgeConfig.validate()` behavior described
  below.
- [`tools/`](../../../tools/) — not a Gradle module. `tools/acp-probe/`
  changes should be checked against the real fixture-capture flow it exists
  for, not just "it runs". `tools/deploy/gcp/` and `tools/deploy/cloudflare/`
  have never been run against a real account (see AGENTS.md §7/§8) — a change
  here needs the same explicit "reviewed but not exercised" caveat unless the
  agent actually ran it against a real account, in which case say so and
  update the caveat.
- Cross-module change (e.g. a new `CloudSessionGateway` method, a new ACP
  model): run `./gradlew :core:corePurityCheck detekt :core:test :bridge:test
  :app:testDebugUnitTest`, not just the one module's tests.

## Kotlin/Gradle conventions

- Detekt (`./gradlew detekt`) is `maxIssues: 0` across all subprojects via
  [`config/detekt/detekt.yml`](../../../config/detekt/detekt.yml).
  `TooGenericExceptionCaught` and `SwallowedException` are disabled on
  purpose for tolerant protocol parsing — don't "fix" call sites that rely on
  that, and don't add narrower catches that would defeat tolerant parsing
  (see below).
- Dependencies and versions come from
  [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) only; no
  `buildSrc`, no inline version strings in module `build.gradle.kts` files.
- AGP 9 applies the Kotlin plugin itself; adding
  `org.jetbrains.kotlin.android` explicitly is an error, not a redundancy —
  don't reintroduce it.
- JDK 21 is the ceiling (AGP rejects 22+); a build/CI change must not silently
  bump this.

## ACP protocol and Kiro-specific contracts

- **Tolerant deserialization is load-bearing**
  ([ADR-003 §3](../../../docs/adr/ADR-003-tech-stack.md#3-two-conventions-that-are-load-bearing)):
  `ignoreUnknownKeys = true`, unknown methods logged and dropped, unknown
  update kinds rendered generically. A protocol-facing change must degrade to
  a cosmetic gap on an unexpected frame, never a crash or a hung session.
- Never hardcode the `_kiro/` extension prefix; it must be derived from the
  handshake via `ExtensionNamespace`
  ([`AcpMethods.kt`](../../../core/src/main/kotlin/dev/kiro/core/acp/AcpMethods.kt)) —
  the docs say `_kiro.dev/`, the real CLI sends `_kiro/`, and an operator can
  override it with `KIRO_EXTENSION_NAMESPACE`.
- Agent→client requests (permission prompts) must always resolve to a
  response. A protocol/client change that adds a new request path without an
  answer path will hang the agent on the first approval — check
  `AcpClient`'s request handling covers it.
- Streaming rendering must stay coalesced on a ~60–100ms tick with the
  in-flight message rendered outside the lazy list
  ([ADR-003 §3](../../../docs/adr/ADR-003-tech-stack.md#3-two-conventions-that-are-load-bearing)).
  A change that renders every chunk immediately, or moves the in-flight
  message into the lazy list, reintroduces the jank this was designed against.
- `core/src/test/resources/fixtures/` holds golden JSONL frames captured from
  a real `kiro-cli`. Tests should parse these, not invented frames; a new
  fixture needs the same provenance note the existing
  [README](../../../core/src/test/resources/fixtures/README.md) uses (CLI
  version, date, how it was captured).

## Bridge and runtime safety

- `BridgeConfig.validate()` refuses a non-loopback bind without TLS — this is
  deliberate defense, not a bug to relax. A change touching bind/TLS handling
  must not weaken this without an explicit, documented reason.
- `KIRO_API_KEY`, if set, silently overrides whichever account the CLI is
  signed into, with no suppression flag. Any change to auth/env handling in
  `bridge/` must preserve this being documented in `BridgeConfig`'s KDoc and
  `--help`, not just in AGENTS.md.
- `kiro-cli acp` must keep being started with `--agent-engine v3
  --auth-method cli` in `CliSupervisor`; the default engine can't see cloud
  sessions at all. Don't let a refactor drop or default away these flags.

## Security and secrets

- No plaintext secrets, tokens, or pairing codes in logs, comments, commit
  messages, or example config — including in `bridge/` startup banners and
  `Logger` call sites.
- `local.properties` and anything under a debug-only cleartext network config
  (e.g. `app/src/debug/res/xml/network_security_config.xml`) must stay
  scoped to the debug variant; a change must not let a debug-only relaxation
  leak into `release`.
- `TokenStore` (`core/` interface, `KeystoreTokenStore` in `app/`) is the only
  sanctioned place secrets touch storage — a new code path storing a token or
  key elsewhere (SharedPreferences, DataStore not routed through the
  interface, plain files) is a regression.

## Test expectations

- `core/` logic changes need JVM unit tests exercising the new behavior
  against real or fixture-derived frames, not mocks of `core/`'s own types.
- A protocol-tolerance claim ("unknown field is ignored", "unknown method is
  dropped") needs a test that actually sends the unexpected shape through
  deserialization, not just a code-reading justification.
- `app/` and `bridge/` unit tests run in CI
  (`:core:test :bridge:test :app:testDebugUnitTest`); a change that needs
  `:app:assembleDebug` or an emulator to prove itself should say so explicitly
  rather than claiming CI-level proof it doesn't have.

## Sentinel stopping check

- Re-read every P1/P2 against the current working tree and current line
  anchors.
- Remove findings not introduced or worsened by the task and merge duplicates
  by root cause.
- Downgrade claims that depend on unavailable environment evidence (no SDK,
  no real cloud session, no Docker — per AGENTS.md §8).
- Confirm the verdict follows from remaining findings and check status.
- Confirm every correction is included in a later Sentinel pass.
- Confirm the final response states Sentinel use, pass count, rerun checks,
  and accepted tracked deferrals.
