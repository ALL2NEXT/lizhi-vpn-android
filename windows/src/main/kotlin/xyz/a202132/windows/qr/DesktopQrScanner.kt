package xyz.a202132.windows.qr

import com.github.sarxos.webcam.Webcam
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import java.util.EnumMap
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

/**
 * A small desktop QR scanner shared by the Compose UI and image-picker flow.
 * Camera access stays on a daemon thread so a missing/blocked camera cannot
 * freeze the Swing event thread or the Compose frame loop.
 */
class DesktopQrScanner {
    @Volatile
    private var running = false

    @Volatile
    private var webcam: Webcam? = null

    @Volatile
    private var worker: Thread? = null

    @Synchronized
    fun start(
        onFrame: (BufferedImage) -> Unit,
        onCode: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        running = true
        worker = thread(
            start = true,
            isDaemon = true,
            name = "firefly-qr-camera"
        ) {
            var camera: Webcam? = null
            try {
                camera = Webcam.getDefault()
                    ?: error("未找到可用摄像头")
                webcam = camera
                runCatching { camera.setViewSize(Dimension(640, 480)) }
                check(camera.open()) { "摄像头打开失败" }

                var frameNumber = 0
                while (running && !Thread.currentThread().isInterrupted) {
                    val captured = camera.image ?: continue
                    val frame = captured.copy()
                    onFrame(frame)

                    // Decoding every third frame keeps the preview responsive while
                    // still making the QR scan feel immediate.
                    if (frameNumber++ % 3 == 0) {
                        DesktopQrCode.decode(frame)?.text?.trim()?.takeIf(String::isNotEmpty)?.let {
                            onCode(it)
                            break
                        }
                    }
                    try {
                        Thread.sleep(45)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (error: Throwable) {
                if (running) {
                    onError(error.message?.takeIf(String::isNotBlank) ?: "摄像头不可用")
                }
            } finally {
                runCatching { camera?.close() }
                webcam = null
                running = false
                worker = null
            }
        }
    }

    @Synchronized
    fun close() {
        running = false
        worker?.interrupt()
        runCatching { webcam?.close() }
        webcam = null
        worker = null
    }
}

data class QrImageSelection(
    val selected: Boolean,
    val text: String?
)

object DesktopQrCode {
    private val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.TRY_HARDER, true)
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(com.google.zxing.BarcodeFormat.QR_CODE))
        put(DecodeHintType.CHARACTER_SET, "UTF-8")
    }

    fun decode(image: BufferedImage): Result? {
        val source = BufferedImageLuminanceSource(image)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return runCatching {
            MultiFormatReader().decode(bitmap, hints)
        }.recoverCatching {
            // A second reader pass handles images where the multi-format reader
            // rejects a QR-specific alignment pattern on the first attempt.
            com.google.zxing.qrcode.QRCodeReader().decode(bitmap, hints)
        }.getOrNull()
    }

    fun decodeFile(file: File): String? = runCatching {
        ImageIO.read(file)?.let(::decode)?.text?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()

    /** Opens a native Windows file chooser from the AWT event thread. */
    fun chooseImageAndDecode(): QrImageSelection {
        var selectedFile: File? = null
        val choose = {
            val chooser = JFileChooser().apply {
                dialogTitle = "选择二维码图片"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter(
                    "图片文件 (*.png, *.jpg, *.jpeg, *.bmp, *.gif)",
                    "png",
                    "jpg",
                    "jpeg",
                    "bmp",
                    "gif"
                )
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selectedFile = chooser.selectedFile
            }
        }

        runCatching {
            if (SwingUtilities.isEventDispatchThread()) choose()
            else SwingUtilities.invokeAndWait(choose)
        }.onFailure {
            return QrImageSelection(selected = false, text = null)
        }
        val file = selectedFile ?: return QrImageSelection(selected = false, text = null)
        return QrImageSelection(selected = true, text = decodeFile(file))
    }
}

private fun BufferedImage.copy(): BufferedImage {
    val colorModel = colorModel
    return BufferedImage(
        colorModel,
        copyData(null),
        colorModel.isAlphaPremultiplied,
        null
    )
}
