package app.pawse.feature.food.camera

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/**
 * Reads product barcodes out of camera frames.
 *
 * The formats are restricted to the ones that appear on food packaging. That is
 * not tidiness: a scanner willing to read QR codes and PDF417 will happily lock
 * onto a delivery label or a wine app's promo code on the same box, and hand back
 * a string that is not a product at all.
 *
 * The frame is closed only once ML Kit has finished with it. Closing it earlier —
 * the obvious-looking thing, since detection is asynchronous — invalidates the
 * buffer mid-read; closing it never stalls the analyser after two frames and looks
 * exactly like a camera that froze.
 */
class BarcodeFrameReader(
    private val onBarcode: (String) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            )
            .build(),
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onBarcode)
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun close() {
        scanner.close()
    }
}

/**
 * Reads the nutrition panel itself, on-device.
 *
 * This is the path that keeps the whole feature working with the network switched
 * off: no database in the world has every Korean corner-shop product, but every
 * one of them has the panel printed on the back. The Korean recogniser bundles
 * Latin script too, so one model handles a domestic snack and an imported tin.
 *
 * Text is handed on as lines, in reading order, because that is what the parser
 * wants and because a blob of concatenated text loses the association between a
 * nutrient and the number beside it.
 */
class LabelFrameReader(
    private val onLines: (List<String>) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { block -> block.lines.map { it.text } }
                if (lines.isNotEmpty()) onLines(lines)
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun close() {
        recognizer.close()
    }
}
