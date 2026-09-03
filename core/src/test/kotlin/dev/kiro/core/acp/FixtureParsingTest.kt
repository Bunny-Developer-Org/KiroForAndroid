package dev.kiro.core.acp

import dev.kiro.core.Fixtures
import dev.kiro.core.model.ExecutionTarget
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parsing asserted against frames Kiro actually sent, not frames we imagined.
 */
class FixtureParsingTest {

    private fun firstAgentResult(fixture: String, skip: Int = 0) =
        Fixtures.load(fixture)
            .filter { it.isFromAgent }
            .mapNotNull { it.decoded as? RpcResponse }
            .drop(skip)
            .first()
            .result

    @Test
    fun `handshake yields the runtime configuration the client needs`() {
        val result = assertNotNull(InitializeResult.parse(firstAgentResult("initialize-v3.jsonl")))

        assertEquals(1, result.protocolVersion)
        assertTrue(result.loadSession, "resume is impossible without loadSession")
        assertTrue(result.supportsImages)
        assertTrue(result.supportsSessionList)

        // The runtime signal that this is not a local-only agent.
        assertTrue(result.supportsCloudSessions)
        assertEquals(listOf("local", "cloud-sandbox"), result.executionTargets)
        assertEquals(listOf("local", "remote"), result.sessionSources)
        assertEquals(listOf("workspace", "user"), result.sessionListScopes)

        assertEquals("_kiro/", result.namespace.prefix)
        assertTrue(result.extensionMethods.contains("_kiro/sourceProviders/listResources"))
    }

    /**
     * The trap ACP-INTEGRATION §2 documents, with a real casualty: JetBrains read a
     * non-empty `authMethods` as "not signed in", showed a login button, and called
     * a method `kiro-cli` does not implement. This fixture was captured from a CLI
     * that *was* fully authenticated and still lists two methods.
     */
    @Test
    fun `authMethods is present on an authenticated CLI and means nothing about auth state`() {
        val result = assertNotNull(InitializeResult.parse(firstAgentResult("initialize-v3.jsonl")))
        assertEquals(2, result.authMethods.size)
        assertEquals("aws-builder-id", result.authMethods.first().id)
        // Nothing in InitializeResult exposes an "isAuthenticated" derived from it,
        // and this test exists to keep it that way.
    }

    @Test
    fun `cloud roster keeps agent status and VM status apart`() {
        val sessions = SessionParser.parseList(firstAgentResult("session-list-remote.jsonl", skip = 1))
        assertTrue(sessions.isNotEmpty())

        val first = sessions.first()
        assertEquals(SessionSource.REMOTE, first.source)
        assertEquals(ExecutionTarget.CLOUD_SANDBOX, first.executionTarget)

        // Both are "idle" in casual speech and they mean different wait times.
        assertEquals(SessionStatus.IDLE, first.status)
        assertEquals(InstanceStatus.SUSPENDED, first.instanceStatus)
        assertTrue(first.needsWarmUp)

        assertEquals("example-org/repo-1", first.repositories.single().name)
        assertEquals("GITHUB", first.repositories.single().providerType)
    }

    @Test
    fun `a session with no bound repositories parses rather than being skipped`() {
        val sessions = SessionParser.parseList(firstAgentResult("session-list-remote.jsonl", skip = 1))
        val bare = sessions.first { it.repositories.isEmpty() }
        assertEquals(SessionSource.REMOTE, bare.source)
    }

    @Test
    fun `the all scope mixes local and cloud sessions and both keep their target`() {
        val sessions = SessionParser.parseList(firstAgentResult("session-list-remote.jsonl", skip = 2))
        assertTrue(sessions.any { it.executionTarget == ExecutionTarget.CLOUD_SANDBOX })
        assertTrue(sessions.any { it.executionTarget == ExecutionTarget.LOCAL })
        // F-03 pins a working directory so stray local sessions never enter our
        // list; the parser must still be able to tell them apart.
        assertTrue(sessions.filter { it.source == SessionSource.LOCAL }.all { !it.isCloud })
    }

    @Test
    fun `repository catalog carries visibility and default branch`() {
        val repos = RepoCatalogParser.parseResources(firstAgentResult("source-providers.jsonl", skip = 1))
        assertTrue(repos.size > 5)

        val private = repos.first { it.isPrivate }
        assertEquals("GITHUB", private.providerType)
        assertNotNull(private.defaultBranch)

        // Default branches genuinely differ per repo, which is why the UI shows it
        // rather than assuming "main" -- and why it must not offer to change it.
        assertTrue(repos.map { it.defaultBranch }.toSet().size > 1)
    }

    /**
     * The model list exists, and this is where it lives.
     *
     * Nothing in the protocol lists models on their own — the handshake's 24
     * extension methods contain no catalog — so the only source is a session's
     * `configOptions`, which this `session/new` result carries (PROTOCOL-FINDINGS
     * §4d). The ids are asserted verbatim because they are also what
     * `session/set_config_option` has to be given back.
     */
    @Test
    fun `session new carries the model catalog and the current model`() {
        val result = firstAgentResult("prompt-turn-with-permission.jsonl", skip = 1)
        val selection = ConfigOptionParser.parse(result).modelSelection()

        assertTrue(selection.isKnown)
        assertTrue(selection.hasCatalog)
        assertEquals("auto", selection.currentId)
        assertEquals("Auto", assertNotNull(selection.current).name)

        val ids = selection.available.map { it.id }
        assertTrue(ids.contains("claude-opus-5"), "expected the real ids, got $ids")
        assertTrue(ids.contains("gpt-5.6-luna"))

        // The credit multiplier is the only pricing signal the protocol gives, and
        // it lives in the choice's _meta.kiro rather than alongside name.
        val opus = selection.available.first { it.id == "claude-opus-5" }
        assertEquals(2.2, opus.rateMultiplier)
        assertEquals("Credit", opus.rateUnit)
    }

    /** The same set arrives live, which is a cloud session's *only* route to it. */
    @Test
    fun `a config option update carries the same model set as session new`() {
        val update = Fixtures.load("prompt-turn-with-permission.jsonl")
            .filter { it.isFromAgent }
            .mapNotNull { it.decoded as? RpcNotification }
            .mapNotNull { SessionUpdateParser.parse(it.params) }
            .filterIsInstance<SessionUpdate.ConfigOptionsChanged>()
            .first()

        val selection = update.options.modelSelection()
        assertEquals("auto", selection.currentId)
        assertTrue(selection.available.size > 10)

        // Modes ride the same notification and must not be confused with models.
        assertTrue(update.options.agentModes().map { it.id }.contains("vibe"))
    }

    @Test
    fun `every frame in every fixture decodes without a malformed result`() {
        val fixtures = listOf(
            "initialize-v3.jsonl",
            "session-list-remote.jsonl",
            "source-providers.jsonl",
            "session-load-remote-head.jsonl",
            "prompt-turn-with-permission.jsonl",
        )
        fixtures.forEach { name ->
            Fixtures.load(name).forEach { frame ->
                assertFalse(
                    frame.decoded is RpcMalformed,
                    "$name has a frame the codec cannot classify: ${frame.raw.take(120)}",
                )
            }
        }
    }
}
