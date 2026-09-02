package dev.kiro.core.util

/**
 * `core/` cannot use `android.util.Log`, so logging is an interface implemented in
 * `app/` and in `bridge/` (ADR-003 §2).
 *
 * Never log prompt text, repository contents, tokens, or the API key. F-22 tracks
 * drift *rates*; it does not need payloads to do it.
 */
public interface Logger {
    public fun debug(message: String)
    public fun info(message: String)
    public fun warn(message: String, error: Throwable? = null)
    public fun error(message: String, error: Throwable? = null)

    public companion object {
        public val None: Logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String, error: Throwable?) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}

/** Wall-clock, injected so tests are not at the mercy of one. */
public fun interface Clock {
    public fun nowMillis(): Long

    public companion object {
        public val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Counts of things that should be rare.
 *
 * ADR-002 §5 makes the unknown-method and parse-failure rate the explicit trigger
 * for revisiting the runtime decision, which only works if someone actually
 * measures it. This is that measurement, and F-22 surfaces it.
 */
public interface DriftMetrics {
    public fun parseFailure(reason: String)
    public fun unknownMethod(method: String)
    public fun unknownUpdateKind(kind: String)

    public companion object {
        public val None: DriftMetrics = object : DriftMetrics {
            override fun parseFailure(reason: String) = Unit
            override fun unknownMethod(method: String) = Unit
            override fun unknownUpdateKind(kind: String) = Unit
        }
    }
}
