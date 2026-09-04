package dev.kiro.android.platform

import com.google.mlkit.common.MlKitException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QrScannerTest {

    /**
     * These two lead to different UI and getting them the wrong way round is
     * expensive in both directions: treating a transient failure as permanent hides
     * the scan button on a device that works, and treating a missing scanner as
     * transient leaves a button that can never succeed.
     */
    @Test
    fun `a device with no scanner is distinguished from a scan that went wrong`() {
        val unavailable = scanFailureOutcome(MlKitException.CODE_SCANNER_UNAVAILABLE, "not installed")
        val failed = scanFailureOutcome(MlKitException.UNKNOWN, "camera busy")

        assertIs<QrScanner.Outcome.Unavailable>(unavailable)
        assertIs<QrScanner.Outcome.Failed>(failed)
    }

    /** Play Services can fail without an MlKitException, so the code may be absent. */
    @Test
    fun `a failure with no error code is treated as a failure, not a missing scanner`() {
        assertIs<QrScanner.Outcome.Failed>(scanFailureOutcome(null, null))
    }

    /** Whatever the cause, the way forward is the form below the button. */
    @Test
    fun `both messages point at manual entry`() {
        val unavailable = scanFailureOutcome(MlKitException.CODE_SCANNER_UNAVAILABLE, null)
        val failed = scanFailureOutcome(null, null)

        assertTrue("by hand" in (unavailable as QrScanner.Outcome.Unavailable).message, unavailable.message)
        assertTrue("by hand" in (failed as QrScanner.Outcome.Failed).message, failed.message)
    }
}
