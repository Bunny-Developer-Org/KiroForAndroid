package dev.kiro.android.platform

import android.app.Activity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reading a pairing QR off the bridge's console.
 *
 * An interface rather than a direct call because the implementation is the one
 * genuinely untestable thing in this feature -- it needs Play Services and a
 * camera -- so everything that *can* be decided in code is decided outside it:
 * the payload format lives in `core/`, the failure wording in `ScanMessages`, and
 * the mapping below in [scanFailureOutcome].
 */
interface QrScanner {

    /**
     * False on a device with no Play Services -- GrapheneOS, LineageOS, microG.
     *
     * The caller hides the scan affordance entirely rather than disabling it,
     * because manual entry is a complete path rather than a degraded one, and a
     * dead control with no way forward is worse than no control.
     */
    suspend fun isAvailable(): Boolean

    suspend fun scan(): Outcome

    sealed interface Outcome {
        data class Scanned(val raw: String) : Outcome

        /** Backing out of the scanner is not an error and must not read as one. */
        data object Cancelled : Outcome

        /** Play Services cannot provide a scanner. Distinct because it is permanent. */
        data class Unavailable(val message: String) : Outcome

        data class Failed(val message: String) : Outcome
    }
}

/** Used in previews, and wherever a scanner would be constructed without an Activity. */
object NoQrScanner : QrScanner {
    override suspend fun isAvailable(): Boolean = false
    override suspend fun scan(): QrScanner.Outcome =
        QrScanner.Outcome.Unavailable("This build has no QR scanner.")
}

/**
 * The Play Services code scanner: no camera permission, no preview to build.
 *
 * Takes an **Activity**, not the application context, and is therefore constructed
 * in `MainActivity` rather than in `ServiceLocator` like everything else here.
 * `GmsBarcodeScanning.getClient` needs an Activity to present its own UI over, so
 * handing it `ServiceLocator.appContext` -- the obvious move, given every other
 * platform class in this package -- is a latent crash rather than a style choice.
 */
class GmsQrScanner(private val activity: Activity) : QrScanner {

    override suspend fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS

    override suspend fun scan(): QrScanner.Outcome = suspendCancellableCoroutine { continuation ->
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            // A pairing QR is read off a laptop screen an arm's length away, which
            // is exactly the case auto-zoom exists for.
            .enableAutoZoom()
            .build()

        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                continuation.resume(
                    if (raw.isNullOrBlank()) {
                        QrScanner.Outcome.Failed("That code came back empty. Try again, or enter the details by hand.")
                    } else {
                        QrScanner.Outcome.Scanned(raw)
                    },
                )
            }
            .addOnCanceledListener { continuation.resume(QrScanner.Outcome.Cancelled) }
            .addOnFailureListener { error ->
                continuation.resume(scanFailureOutcome((error as? MlKitException)?.errorCode, error.message))
            }
    }
}

/**
 * Splits "there is no scanner on this device" from "this scan went wrong".
 *
 * Extracted and internal so it can be unit tested -- the two lead to different UI,
 * one hiding the scan affordance for the rest of the session and the other leaving
 * it alone, and getting them the wrong way round either strands a working device
 * or offers a button that can never succeed.
 */
internal fun scanFailureOutcome(errorCode: Int?, message: String?): QrScanner.Outcome =
    if (errorCode == MlKitException.CODE_SCANNER_UNAVAILABLE) {
        QrScanner.Outcome.Unavailable(
            "This device cannot open a QR scanner. Enter the bridge address and code below by hand.",
        )
    } else {
        QrScanner.Outcome.Failed(
            message?.let { "Scanning failed: $it" } ?: "Scanning failed. Try again, or enter the details by hand.",
        )
    }
