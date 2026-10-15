package com.example.numberscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object ScanConfig {
    const val CROP_W = 0.9f
    const val CROP_H = 0.22f
}

class CodeAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val hits = HashMap<String, Pair<Int, Long>>()
    private val needHits = 4
    private val windowMs = 3000L

    private val angles = floatArrayOf(0f, -25f, 25f)
    private var frameIndex = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val c = 1.8f
        val t = (-0.5f * c + 0.5f) * 255f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        colorFilter = ColorMatrixColorFilter(cm)
    }

    private val toDigit = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0',
        'I' to '1', 'L' to '1', 'T' to '1', 'J' to '1',
        'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6'
    )
    private val toLetter = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z')

    override fun analyze(proxy: ImageProxy) {
        val bmp = try {
            prepare(proxy)
        } catch (e: Exception) {
            null
        }
        if (bmp == null) {
            proxy.close()
            return
        }
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                val found = HashSet<String>()
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line -> found.addAll(extract(line.text)) }
                }
                found.forEach { confirm(it) }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun prepare(proxy: ImageProxy): Bitmap {
        val src = proxy.toBitmap()
        val rot = proxy.imageInfo.rotationDegrees
        val extra = angles[frameIndex]
        frameIndex = (frameIndex + 1) % angles.size

        val sideways = rot == 90 || rot == 270
        val rw = if (sideways) src.height else src.width
        val rh = if (sideways) src.width else src.height
        val cw = (rw * ScanConfig.CROP_W).toInt()
        val ch = (rh * ScanConfig.CROP_H).toInt()

        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val m = Matrix()
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postRotate(rot + extra)
        m.postTranslate(cw / 2f, ch / 2f)
        canvas.drawBitmap(src, m, paint)
        return out
    }

    private fun extract(raw: String): List<String> {
        val clean = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        val out = mutableListOf<String>()
        var i = 0
        while (i < clean.length) {
            var used = 0
            for (len in intArrayOf(12, 11, 10, 9, 8)) {
                if (i + len > clean.length) continue
                val code = convert(clean.substring(i, i + len))
                if (code != null) {
                    out.add(code)
                    used = len
                    break
                }
            }
            i += if (used > 0) used else 1
        }
        return out
    }

    private fun convert(w: String): String? {
        var letterCount = 0
        for (i in 0 until minOf(3, w.length)) {
            val c = w[i]
            if (c in 'A'..'Z' || toLetter.containsKey(c)) {
                letterCount++
            } else {
                break
            }
        }
        
        // Разрешаем от 1 до 3 букв в начале
        if (letterCount !in 1..3) return null

        val sb = StringBuilder()
        for (j in 0 until letterCount) {
            val c = w[j]
            sb.append(if (c in 'A'..'Z') c else (toLetter[c] ?: return null))
        }
        for (j in letterCount until w.length) {
            val c = w[j]
            sb.append(if (c in '0'..'9') c else (toDigit[c] ?: return null))
        }
        return sb.toString()
    }

    private fun confirm(code: String) {
        val now = System.currentTimeMillis()
        val prev = hits[code]
        val updated = if (prev == null || now - prev.second > windowMs) 1 to now
        else (prev.first + 1) to prev.second
        if (updated.first >= needHits) {
            hits.remove(code)
            onFound(code)
        } else {
            hits[code] = updated
        }
    }
}
