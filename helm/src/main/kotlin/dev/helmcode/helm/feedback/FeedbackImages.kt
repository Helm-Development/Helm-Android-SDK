package dev.helmcode.helm.feedback

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The app and device details sent with feedback. A null value is left out of
 * the request.
 */
internal data class FeedbackDeviceInfo(
    val appVersion: String?,
    val osVersion: String?,
    val deviceModel: String?,
) {
    companion object {
        fun collect(context: Context): FeedbackDeviceInfo {
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) {
                null
            }
            return FeedbackDeviceInfo(
                appVersion = appVersion,
                osVersion = Build.VERSION.RELEASE,
                deviceModel = Build.MODEL,
            )
        }
    }
}

/**
 * Encodes a Bitmap as JPEG, small enough to upload.
 *
 * It first lowers the JPEG quality step by step. If the image is still too
 * large at the lowest quality, it shrinks the width and height by a quarter
 * at a time until the result fits.
 */
internal object JpegShrinker {

    /** JPEG qualities tried at full size, best first. */
    internal val QUALITIES = listOf(90, 80, 70, 60, 50)

    /** Each downscale step multiplies the width and height by this. */
    internal const val DOWNSCALE_FACTOR = 0.75f

    /** After this many downscale steps (about a tenth of the original width), give up. */
    internal const val MAX_DOWNSCALE_STEPS = 8

    /**
     * Encodes an image at a JPEG quality and a scale (1.0 is the original size).
     * Returns null when encoding fails.
     */
    internal fun interface Encoder {
        fun encode(quality: Int, scale: Float): ByteArray?
    }

    /** Outcome of [fit]. */
    internal sealed class Result {
        class Fits(val bytes: ByteArray) : Result()
        object TooLarge : Result()
        object EncodingFailed : Result()
    }

    /** Finds the first encoding, from best to smallest, that is at most [maxBytes]. */
    fun fit(encoder: Encoder, maxBytes: Int): Result {
        for (quality in QUALITIES) {
            val bytes = encoder.encode(quality, 1f) ?: return Result.EncodingFailed
            if (bytes.size <= maxBytes) return Result.Fits(bytes)
        }
        val lowestQuality = QUALITIES.last()
        var scale = 1f
        repeat(MAX_DOWNSCALE_STEPS) {
            scale *= DOWNSCALE_FACTOR
            val bytes = encoder.encode(lowestQuality, scale) ?: return Result.EncodingFailed
            if (bytes.size <= maxBytes) return Result.Fits(bytes)
        }
        return Result.TooLarge
    }

    /**
     * An [Encoder] for [bitmap]. Scaled copies are recycled after encoding; the
     * caller's bitmap is never modified or recycled.
     */
    fun encoderFor(bitmap: Bitmap): Encoder = Encoder { quality, scale ->
        val source = if (scale >= 1f) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true,
            )
        }
        try {
            val out = ByteArrayOutputStream()
            if (source.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }
}

/** Reads an image from a content Uri, without reading more than the size limit allows. */
internal object UriImageReader {

    /** Outcome of [read]. */
    internal sealed class Result {
        class Read(val bytes: ByteArray, val mimeType: String) : Result()
        object TooLarge : Result()
        class Unreadable(val reason: String) : Result()
    }

    /**
     * Reads [uri] through [resolver], taking the MIME type from the resolver.
     *
     * Stops reading as soon as the data passes [maxBytes], so a very large file
     * is never fully loaded into memory.
     */
    fun read(resolver: ContentResolver, uri: Uri, maxBytes: Int): Result {
        val mimeType = try {
            resolver.getType(uri)
        } catch (e: Exception) {
            return Result.Unreadable("Could not read the image's type: ${e.message}")
        }
        if (mimeType.isNullOrBlank()) {
            return Result.Unreadable("The content resolver did not report a MIME type for the image.")
        }
        return try {
            val input = resolver.openInputStream(uri)
                ?: return Result.Unreadable("The content resolver could not open the image.")
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return Result.TooLarge
                    out.write(buffer, 0, count)
                }
                Result.Read(out.toByteArray(), mimeType)
            }
        } catch (e: Exception) {
            Result.Unreadable("Could not read the image: ${e.message}")
        }
    }
}
