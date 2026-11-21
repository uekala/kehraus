package de.uekala.kehraus.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/** Texterkennung auf dem Gerät. Nur aus einem Hintergrund-Thread aufrufen. */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Erkannter Text des Fotos; null, wenn es sich nicht lesen ließ. */
    fun text(file: File): String? {
        val bitmap = decode(file, 1600) ?: return null
        return try {
            val image = InputImage.fromBitmap(bitmap, rotationOf(file))
            Tasks.await(recognizer.process(image), 30, TimeUnit.SECONDS).text
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Verkleinert beim Laden auf höchstens [maxSide] Pixel – genug für Text, schont den Speicher. */
    private fun decode(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Throwable) {
        null
    }

    private fun rotationOf(file: File): Int = try {
        when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}
