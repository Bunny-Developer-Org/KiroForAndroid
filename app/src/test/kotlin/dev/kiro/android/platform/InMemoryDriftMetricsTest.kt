package dev.kiro.android.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-002 §5 leans on these counts to decide whether the protocol is drifting
 * enough to revisit the runtime -- untested until now, despite backing a
 * decision trigger.
 */
class InMemoryDriftMetricsTest {

    @Test
    fun `a fresh snapshot is all zero`() {
        val metrics = InMemoryDriftMetrics()

        val snapshot = metrics.snapshot()

        assertEquals(0L, snapshot.parseFailures)
        assertEquals(0L, snapshot.unknownMethods)
        assertEquals(0L, snapshot.unknownUpdateKinds)
        assertEquals(emptyMap<String, Long>(), snapshot.byName)
    }

    @Test
    fun `each kind of event increments its own counter independently`() {
        val metrics = InMemoryDriftMetrics()

        metrics.parseFailure("bad json")
        metrics.parseFailure("bad json")
        metrics.unknownMethod("_kiro/mystery")
        metrics.unknownUpdateKind("weird_kind")

        val snapshot = metrics.snapshot()
        assertEquals(2L, snapshot.parseFailures)
        assertEquals(1L, snapshot.unknownMethods)
        assertEquals(1L, snapshot.unknownUpdateKinds)
    }

    @Test
    fun `repeated names accumulate under one key, update kinds are namespaced`() {
        val metrics = InMemoryDriftMetrics()

        metrics.unknownMethod("_kiro/mystery")
        metrics.unknownMethod("_kiro/mystery")
        metrics.unknownUpdateKind("weird_kind")

        val byName = metrics.snapshot().byName
        assertEquals(2L, byName["_kiro/mystery"])
        assertEquals(1L, byName["update:weird_kind"])
    }

    @Test
    fun `a parse failure's reason is never retained, only counted`() {
        val metrics = InMemoryDriftMetrics()

        metrics.parseFailure("contains something sensitive")

        assertEquals(emptyMap<String, Long>(), metrics.snapshot().byName)
    }
}
