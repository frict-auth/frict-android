package app.frict.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Test

private const val LINK = "frict://enroll?code=abc-123&host=frict.example.com&user_id=DUABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** [text] encoded as [format], drawn as a camera would see it: black on white, with a margin. */
private fun frame(text: String, format: BarcodeFormat = BarcodeFormat.QR_CODE): LuminanceSource {
    val height = if (format == BarcodeFormat.CODE_128) 120 else 400
    return luminance(MultiFormatWriter().encode(text, format, 400, height))
}

private fun luminance(m: BitMatrix): LuminanceSource {
    val pixels = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else -1 }
    return RGBLuminanceSource(m.width, m.height, pixels)
}

private fun blank(): LuminanceSource = RGBLuminanceSource(400, 400, IntArray(400 * 400) { -1 })

class QrScanTest {
    @Test
    fun activationLinkQrCodeIsFound() {
        assertEquals(ScanResult.Found(LINK), scanActivation(frame(LINK)))
    }

    @Test
    fun qrCodeOfAnythingElseIsNotAnActivationLink() {
        listOf(
            "https://enroll?code=abc-123&host=frict.example.com&user_id=DUABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "frict://login?code=abc-123&host=frict.example.com&user_id=DUABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "frict://enroll?code=abc-123&host=evil.example.com/x&user_id=DUABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "https://frict.example.com/",
            "WIFI:S:guest;T:WPA;P:hunter2;;",
            "garbage \u0000ÿ",
        ).forEach { assertEquals(it, ScanResult.NotAnActivationLink, scanActivation(frame(it))) }
    }

    @Test
    fun noCodeFindsNothing() {
        assertEquals(ScanResult.NothingFound, scanActivation(blank()))
    }

    @Test
    fun otherBarcodeFormatsAreNotRead() {
        listOf(BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417, BarcodeFormat.CODE_128).forEach {
            assertEquals(it.name, ScanResult.NothingFound, scanActivation(frame(LINK, it)))
        }
    }

    @Test
    fun scannerStopsAtTheFirstLinkAndIgnoresLaterFrames() {
        val events = mutableListOf<String>()
        val scanner = ActivationScanner(
            stop = { events += "stop" },
            onLink = { events += "link $it" },
            onNotAnActivationLink = { events += "rejected" },
        )
        scanner.onFrame(blank())
        scanner.onFrame(frame("https://frict.example.com/"))
        scanner.onFrame(frame(LINK))
        scanner.onFrame(frame(LINK))
        scanner.onFrame(frame("https://frict.example.com/"))
        assertEquals(listOf("rejected", "stop", "link $LINK"), events)
    }
}
