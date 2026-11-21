package app.frict.android

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

sealed interface ScanResult {
    data object NothingFound : ScanResult
    data object NotAnActivationLink : ScanResult
    data class Found(val link: String) : ScanResult
}

/** Decodes a QR code, and no other format, in [frame]; its text counts only if [parseActivation] accepts it. */
fun scanActivation(frame: LuminanceSource): ScanResult {
    val text = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(frame))).text
    } catch (_: ReaderException) {
        return ScanResult.NothingFound
    }
    return if (parseActivation(text) == null) ScanResult.NotAnActivationLink else ScanResult.Found(text)
}

/**
 * Scans camera frames, on one thread, until one holds an activation link: then calls [stop],
 * hands the link to [onLink] and ignores every later frame.
 */
class ActivationScanner(
    private val stop: () -> Unit,
    private val onLink: (String) -> Unit,
    private val onNotAnActivationLink: () -> Unit,
) {
    private var done = false

    fun onFrame(frame: LuminanceSource) {
        if (done) return
        when (val result = scanActivation(frame)) {
            is ScanResult.Found -> {
                done = true
                stop()
                onLink(result.link)
            }
            ScanResult.NotAnActivationLink -> onNotAnActivationLink()
            ScanResult.NothingFound -> Unit
        }
    }
}
