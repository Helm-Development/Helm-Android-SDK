package dev.helmcode.helm.feedback

import android.util.Log
import dev.helmcode.helm.Configuration
import dev.helmcode.helm.FeedbackType
import dev.helmcode.helm.HelmResult
import dev.helmcode.helm.SubmittedFeedback
import dev.helmcode.helm.attribution.AttributionApi
import dev.helmcode.helm.networking.HelmError
import dev.helmcode.helm.networking.HelmHttpClient
import dev.helmcode.helm.networking.MultipartFormData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends user feedback to `POST /api/client/v1/feedback/` (HELM-307).
 *
 * Takes plain values (no Context, no Bitmap) so it can be tested against a
 * MockWebServer; [dev.helmcode.helm.Helm.submitFeedback] collects the device
 * details and turns a Bitmap or Uri into bytes before calling [submit].
 *
 * There is no offline queue: every outcome is returned to the caller.
 */
internal object FeedbackApi {

    private const val TAG = "HelmSDK"

    internal const val PATH_FEEDBACK = "/api/client/v1/feedback/"

    /** Wire value for this SDK in the `platform` field. */
    internal const val PLATFORM = "android"

    /** The server's limit on the trimmed message, counted in Unicode code points. */
    internal const val MAX_MESSAGE_LENGTH = 5000

    /** The server's limit on the image: 10 MB. */
    internal const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

    // The server's limits on the fields the SDK fills in itself. The SDK
    // shortens its own values to fit rather than have the server reject the
    // user's feedback over a long device model name.
    private const val MAX_APP_VERSION_LENGTH = 50
    private const val MAX_OS_VERSION_LENGTH = 50
    private const val MAX_DEVICE_MODEL_LENGTH = 100

    /** An image ready to upload. */
    internal class Image(val bytes: ByteArray, val mimeType: String)

    /**
     * Validates the input, sends it, and maps the response.
     *
     * Never throws, except to pass on coroutine cancellation.
     */
    suspend fun submit(
        type: FeedbackType,
        message: String,
        image: Image?,
        userId: String?,
        email: String?,
        device: FeedbackDeviceInfo,
    ): HelmResult<SubmittedFeedback> {
        val trimmed = message.trim()
        validate(trimmed, image)?.let { return it }

        val debug = Configuration.instance?.debug ?: false
        val form = MultipartFormData(
            fields = formFields(type, trimmed, userId, email, device, debug),
            file = image?.let {
                MultipartFormData.FilePart(
                    name = "image",
                    fileName = fileNameFor(it.mimeType),
                    mimeType = it.mimeType,
                    bytes = it.bytes,
                )
            },
        )

        return try {
            parseSuccess(HelmHttpClient.postMultipart(PATH_FEEDBACK, form), type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HelmError.NotConfigured) {
            HelmResult.Failure("not_configured", null, e.message)
        } catch (e: HelmError.ServerError) {
            // The server answered, so its error code is the result. An error
            // body that is not the standard envelope becomes "http_<status>".
            AttributionApi.parseErrorEnvelope(e.code, e.message)
        } catch (e: HelmError.NetworkError) {
            HelmResult.Failure("network_error", null, e.message)
        } catch (e: HelmError.InvalidResponse) {
            HelmResult.Failure("invalid_response", null, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "feedback request failed: ${e.message}", e)
            HelmResult.Failure("network_error", null, e.message)
        }
    }

    /**
     * Checks the request against the server's rules before anything is sent.
     * Uses the server's own error codes so the caller handles one set of codes
     * whether the SDK or the server caught the problem; a local failure has a
     * null `httpStatus`.
     *
     * @param trimmedMessage the message after trimming.
     * @return the failure to return, or null when the input is valid.
     */
    internal fun validate(trimmedMessage: String, image: Image?): HelmResult.Failure? {
        if (trimmedMessage.isEmpty()) {
            return HelmResult.Failure("missing_field", null, "message must not be empty.")
        }
        if (trimmedMessage.codePointCount(0, trimmedMessage.length) > MAX_MESSAGE_LENGTH) {
            return HelmResult.Failure(
                "invalid_field",
                null,
                "message must be at most $MAX_MESSAGE_LENGTH characters.",
            )
        }
        if (image != null) {
            if (image.mimeType.isBlank()) {
                return HelmResult.Failure("invalid_field", null, "imageMimeType is required when an image is sent.")
            }
            if (image.bytes.isEmpty()) {
                return HelmResult.Failure("invalid_image", null, "The image is empty.")
            }
            if (image.bytes.size > MAX_IMAGE_BYTES) {
                return imageTooLarge()
            }
        }
        return null
    }

    /** The failure for an image that does not fit in [MAX_IMAGE_BYTES]. */
    internal fun imageTooLarge(): HelmResult.Failure =
        HelmResult.Failure("image_too_large", null, "The image must be at most 10 MB.")

    /**
     * The text fields of the form, in the order they are sent.
     *
     * Optional values that are null or blank are left out, because the server
     * rejects an empty `email`. `debug` is always sent, as `"true"` or
     * `"false"`, the same way every other request from this SDK always carries
     * `debug`; the server treats anything other than `"true"` as live data.
     */
    internal fun formFields(
        type: FeedbackType,
        trimmedMessage: String,
        userId: String?,
        email: String?,
        device: FeedbackDeviceInfo,
        debug: Boolean,
    ): List<Pair<String, String>> = buildList {
        add("feedback_type" to type.wireValue)
        add("message" to trimmedMessage)
        add("platform" to PLATFORM)
        userId?.takeIf { it.isNotBlank() }?.let { add("user_id" to it) }
        email?.trim()?.takeIf { it.isNotEmpty() }?.let { add("email" to it) }
        device.appVersion.limit(MAX_APP_VERSION_LENGTH)?.let { add("app_version" to it) }
        device.osVersion.limit(MAX_OS_VERSION_LENGTH)?.let { add("os_version" to it) }
        device.deviceModel.limit(MAX_DEVICE_MODEL_LENGTH)?.let { add("device_model" to it) }
        add("debug" to debug.toString())
    }

    /** A file name whose extension matches [mimeType]. The server checks the content too. */
    internal fun fileNameFor(mimeType: String): String =
        when (mimeType.substringBefore(';').trim().lowercase()) {
            "image/jpeg", "image/jpg" -> "feedback.jpg"
            "image/png" -> "feedback.png"
            "image/heic" -> "feedback.heic"
            "image/heif" -> "feedback.heif"
            "image/webp" -> "feedback.webp"
            else -> "feedback"
        }

    /** Reads the 201 body. A body without an `id` is treated as an invalid response. */
    internal fun parseSuccess(body: Map<String, Any?>, sentType: FeedbackType): HelmResult<SubmittedFeedback> {
        val id = body["id"] as? String
        if (id.isNullOrEmpty()) {
            return HelmResult.Failure("invalid_response", null, "The response did not include a feedback id.")
        }
        return HelmResult.Success(
            SubmittedFeedback(
                id = id,
                feedbackType = FeedbackType.fromWireValue(body["feedback_type"] as? String) ?: sentType,
                status = body["status"] as? String ?: "new",
                dateCreated = parseTimestamp(body["date_created"] as? String),
            ),
        )
    }

    /** Date, time to the second, optional fraction, and `Z` or a numeric UTC offset. */
    private val TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})$""")

    /**
     * Parses the ISO 8601 timestamp the server sends, for example
     * `2026-10-25T09:01:52+00:00`. Also accepts `Z` for UTC and fractional
     * seconds (kept to the millisecond). Returns null for anything else.
     *
     * Uses SimpleDateFormat because java.time needs API 26 and this SDK
     * supports API 24. Its `XXX` offset pattern is available from API 24.
     */
    internal fun parseTimestamp(value: String?): Date? {
        val match = TIMESTAMP.matchEntire(value?.trim() ?: return null) ?: return null
        val (dateTime, fraction, offset) = match.destructured
        // SimpleDateFormat reads "SSS" as a count of milliseconds, so a
        // microsecond fraction such as ".123456" must be cut to three digits.
        val millis = fraction.take(3).padEnd(3, '0')
        val normalizedOffset = when {
            offset == "Z" -> "+00:00"
            offset.length == 5 -> offset.substring(0, 3) + ":" + offset.substring(3)
            else -> offset
        }
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                .apply { isLenient = false }
                .parse("$dateTime.$millis$normalizedOffset")
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.limit(maxLength: Int): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLength)
}
