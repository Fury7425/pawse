package app.pawse.feature.food.camera

import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A camera preview that hands each frame to an analyser, and does nothing else.
 *
 * Deliberately small and deliberately dumb. It knows how to open a camera, show
 * it, and pass frames along; it knows nothing about barcodes, labels or food. The
 * two things that read frames are supplied by the caller, which is what lets both
 * be swapped or tested without this file.
 *
 * Frames are analysed under `STRATEGY_KEEP_ONLY_LATEST`: a phone that falls behind
 * drops frames rather than queueing them, because a scanner lagging a second
 * behind the tin in your hand is worse than one that skips.
 *
 * There is no `ImageCapture` use case here at all, and that absence is the
 * structural reason no photograph of anyone's kitchen can end up on disk. Frames
 * are read and released; nothing is stored and nothing is uploaded.
 */
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(lifecycleOwner, analyzer) {
        // awaitInstance rather than getInstance: the ListenableFuture overload drags
        // Guava's type onto the compile classpath, where it resolves to the empty
        // "avoid-conflict-with-guava" artifact and does not exist.
        val cameraProvider = runCatching { ProcessCameraProvider.awaitInstance(context) }
            .getOrNull() ?: return@LaunchedEffect
        provider = cameraProvider
        cameraProvider.unbindAll()

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, analyzer)

        // A camera another app grabbed first, or a device with none: the preview
        // stays blank and the rest of the screen still works. Nothing here is worth
        // crashing over.
        runCatching {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Whether this device has a camera at all. A phone without one still logs by hand. */
fun hasCamera(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
