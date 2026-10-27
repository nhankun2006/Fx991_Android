package io.paimon.fx991.ui

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.paimon.fx991.engine.OcrText

// ---------------------------------------------------------------------------
// Batch F: Local offline OCR · ML Kit wrapper (Android side, relies on ML Kit bundled models)
//
// - Uses bundled version (model distributed with APK, independent of Google Play services, fully offline).
// - Chinese model itself recognizes Latin letters and digits; falls back to Latin model only if recognition is blank.
// - This file runs only on Android runtime (Bitmap / ML Kit native), not on JVM;
//   testable text merging / cleaning logic is all in engine/OcrText.kt (pure Kotlin, regression-tested).
// - Callers must invoke from worker thread / coroutine IO dispatcher; never on main thread (Tasks.await blocks).
// ---------------------------------------------------------------------------

object MlKitOcr {

    class MlKitOcrError(message: String) : Exception(message)

    data class Result(
        val rawText: String,     // ML Kit raw full text (including newlines)
        val lines: List<String>, // Lines split by block/line
        val cleaned: String,     // Expression cleaned by OcrText
        val model: String,       // Actual model that produced the result (for UI display)
    )

    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }

    /** Recognizes a bitmap; throws [MlKitOcrError] if nothing can be recognized */
    fun recognize(bitmap: Bitmap): Result {
        val image = InputImage.fromBitmap(bitmap, 0)
        // 1. Chinese bundled model (includes Latin letters / numbers / common symbols)
        val zh = run(chinese, image)
        if (zh.first.isNotBlank()) return build(zh.first, zh.second, "Chinese model (offline)")
        // 2. Fall back to Latin model only when entire image is blank (e.g. pure English print missed by Chinese model)
        val la = run(latin, image)
        if (la.first.isBlank()) {
            throw MlKitOcrError(
                "No text recognized in this image. Please ensure the expression is clear, well-lit, and aligned; handwriting recognition is limited, printed text is recommended.",
            )
        }
        return build(la.first, la.second, "Latin model (offline)")
    }

    private fun run(client: TextRecognizer, image: InputImage): Pair<String, List<String>> {
        val text: Text = try {
            Tasks.await(client.process(image))
        } catch (e: Exception) {
            throw MlKitOcrError(
                "Local recognition failed: ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}",
            )
        }
        val lines = ArrayList<String>()
        for (block in text.textBlocks) {
            for (line in block.lines) lines.add(line.text)
        }
        return Pair(text.text ?: "", lines)
    }

    private fun build(raw: String, lines: List<String>, model: String): Result {
        val cleaned = if (lines.isNotEmpty()) OcrText.cleanLines(lines) else OcrText.clean(raw)
        if (cleaned.isBlank()) {
            throw MlKitOcrError("No calculable expression found in recognized text (original: ${raw.take(60)}).")
        }
        return Result(raw, lines, cleaned, model)
    }
}
