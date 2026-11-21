package app.frict.android

import androidx.activity.ComponentActivity
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.Executors

/**
 * The back camera's preview, scanned for an activation link. The camera runs while this is
 * shown and [activity] is started, and stops at the first activation link, which goes to
 * [onLink] on the main thread. A camera that can't start goes to [onCameraError].
 */
@Composable
fun QrScanner(
    activity: ComponentActivity,
    onLink: (String) -> Unit,
    onNotAnActivationLink: () -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier,
) {
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    DisposableEffect(activity) {
        val main = activity.mainExecutor
        val analysisThread = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(activity)
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val provider = try {
                future.get()
            } catch (e: Exception) {
                onCameraError(e.cause?.message ?: e.javaClass.simpleName)
                return@addListener
            }
            val scanner = ActivationScanner(
                stop = { main.execute(provider::unbindAll) },
                onLink = { main.execute { onLink(it) } },
                onNotAnActivationLink = { main.execute { onNotAnActivationLink() } },
            )
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(main) { surfaceRequest = it }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(analysisThread) { image -> image.use { scanner.onFrame(it.luminance()) } }
            try {
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: IllegalArgumentException) {
                onCameraError(e.message ?: e.javaClass.simpleName)
            }
        }, main)
        onDispose {
            disposed = true
            if (future.isDone && !future.isCancelled) runCatching { future.get().unbindAll() }
            analysisThread.shutdown()
        }
    }
    Box(modifier) { surfaceRequest?.let { CameraXViewfinder(it) } }
}

/** The frame's Y plane: its brightness, all a QR decoder needs. */
private fun ImageProxy.luminance(): PlanarYUVLuminanceSource {
    val y = planes[0]
    val data = ByteArray(y.rowStride * height)
    y.buffer.get(data, 0, y.buffer.remaining())
    return PlanarYUVLuminanceSource(data, y.rowStride, height, 0, 0, width, height, false)
}
