package dev.helmcode.helm

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.helmcode.helm.analytics.Analytics
import dev.helmcode.helm.attribution.Attribution
import dev.helmcode.helm.feedback.FeedbackApi
import dev.helmcode.helm.feedback.FeedbackDeviceInfo
import dev.helmcode.helm.feedback.JpegShrinker
import dev.helmcode.helm.feedback.UriImageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Main entry point for the Helm Android SDK.
 *
 * Usage:
 * ```
 * Helm.configure(context, publishableKey = "pk_...", baseURL = "https://helmcode.dev")
 * Helm.attribution.match(context)
 * ```
 *
 * To send feedback from a user, call [submitFeedback] from a coroutine.
 */
object Helm {

    /**
     * Configure the SDK with your publishable key and API base URL.
     * Must be called before using any other SDK features.
     *
     * Prefer the [configure] overload that takes a `Context`: the influencer
     * attribution methods ([Attribution.submitPromoCode],
     * [Attribution.fetchAttributionStatus],
     * [Attribution.submitOriginalTransactionId]) need persistence for their
     * offline queue and status cache, and report `"not_configured"` until a
     * Context is bound. `Helm.attribution.match(context)` binds one too.
     *
     * @param publishableKey Your Helm publishable key (starts with "pk_")
     * @param baseURL The base URL of your Helm API (e.g. "https://helmcode.dev")
     * @param debug `true` registers every attribution submission (promo-code
     *   link, status, transaction) as sandbox test data in Helm; sandbox data is
     *   always excluded from payouts. Feedback sent with [submitFeedback] is
     *   marked as test data too. Set it from your build configuration;
     *   default `false` keeps production builds safe.
     * @param environment a free-form label for the build's deployment
     *   environment, e.g. `"production"`, `"staging"`, `"development"`. It is
     *   recorded on every analytics event so activity can be filtered by
     *   environment. It is independent of [debug]: [debug] controls whether the
     *   activity counts as real usage at all, while `environment` only says
     *   which deployment it came from. Default `"production"`.
     */
    fun configure(
        publishableKey: String,
        baseURL: String,
        debug: Boolean = false,
        environment: String = "production",
    ) {
        Configuration.instance = Configuration(publishableKey, baseURL, debug, environment)
    }

    /**
     * Configure the SDK and bind the attribution module's persistence.
     *
     * This is the form to call: binding a Context enables the influencer
     * attribution methods, and configuring is itself one of the three triggers
     * that replay the offline submission queue (the others are app foregrounding
     * and the next attribution call).
     *
     * Any `userId` later handed to the attribution methods is passed to the
     * backend verbatim and must equal the app's RevenueCat app user ID -- that
     * identifier is what joins a Helm influencer link to the customer's
     * subscription.
     *
     * @param context Android context (Activity or Application); only the
     *   application context is retained.
     * @param publishableKey Your Helm publishable key (starts with "pk_")
     * @param baseURL The base URL of your Helm API (e.g. "https://helmcode.dev")
     * @param debug `true` registers every attribution submission (promo-code
     *   link, status, transaction) as sandbox test data in Helm; sandbox data is
     *   always excluded from payouts. Feedback sent with [submitFeedback] is
     *   marked as test data too. Set it from your build configuration;
     *   default `false` keeps production builds safe. A submission that is
     *   queued offline keeps the value in force when it was made, so a replay
     *   after a build change still reports the environment it came from.
     * @param environment a free-form label for the build's deployment
     *   environment, e.g. `"production"`, `"staging"`, `"development"`. It is
     *   recorded on every analytics event so activity can be filtered by
     *   environment. It is independent of [debug]: [debug] controls whether the
     *   activity counts as real usage at all, while `environment` only says
     *   which deployment it came from. Default `"production"`.
     */
    fun configure(
        context: Context,
        publishableKey: String,
        baseURL: String,
        debug: Boolean = false,
        environment: String = "production",
    ) {
        Configuration.instance = Configuration(publishableKey, baseURL, debug, environment)
        Attribution.instance.bind(context)
    }

    /**
     * Access the attribution tracking module.
     */
    val attribution: Attribution
        get() = Attribution.instance

    /**
     * Access the product analytics module. Call Helm.analytics.start(context)
     * after configure to begin installation/session/event tracking.
     */
    val analytics: Analytics
        get() = Analytics.instance

    /**
     * Send feedback from a user of your app to Helm, with an optional image.
     *
     * Project members read the feedback on the User Feedback page in the Helm
     * dashboard. The SDK adds the platform, app version (`versionName`), Android
     * version and device model itself. When [configure] was called with
     * `debug = true`, the feedback is marked as sandbox test data.
     *
     * The request is sent once. There is no offline queue: if it cannot be
     * delivered, the result says so and the app decides whether to try again.
     *
     * Returns [HelmResult.Success] with the stored [SubmittedFeedback], or
     * [HelmResult.Failure] with one of these codes:
     * - `"missing_field"`: [message] is empty after trimming.
     * - `"invalid_field"`: [message] is longer than 5000 characters, [image]
     *   was given without [imageMimeType], or the server rejected another field
     *   (for example an invalid [email] or a [userId] over 100 characters).
     * - `"invalid_image"`: the image is empty, or the server could not read it
     *   as a JPEG, PNG, HEIC or WebP image.
     * - `"image_too_large"`: the image is over 10 MB.
     * - `"auth_required"` or `"invalid_token"`: the publishable key is missing
     *   or not accepted.
     * - `"not_configured"`: [configure] has not been called.
     * - `"network_error"`: no response was received.
     * - `"invalid_response"`: the server answered with a success status but a
     *   body the SDK could not read.
     * - `"http_<status>"`: any other error response, for example `"http_500"`.
     *
     * When the SDK finds the problem before sending, [HelmResult.Failure.httpStatus]
     * is null and nothing is sent.
     *
     * Never throws. It never returns [HelmResult.Queued].
     *
     * @param context any Context; used to read the app's version name.
     * @param type the kind of feedback.
     * @param message the user's text. Leading and trailing whitespace is removed
     *   before it is checked and sent.
     * @param image optional image bytes, for example a screenshot. JPEG, PNG,
     *   HEIC or WebP, at most 10 MB. A larger image is not sent.
     * @param imageMimeType the MIME type of [image], for example `"image/png"`.
     *   Required when [image] is set.
     * @param userId optional ID of the user in your app, at most 100 characters.
     *   Sent as given.
     * @param email optional email address, so you can reply to the user.
     */
    suspend fun submitFeedback(
        context: Context,
        type: FeedbackType,
        message: String,
        image: ByteArray? = null,
        imageMimeType: String? = null,
        userId: String? = null,
        email: String? = null,
    ): HelmResult<SubmittedFeedback> {
        val upload = image?.let { FeedbackApi.Image(it, imageMimeType ?: "") }
        return FeedbackApi.submit(type, message, upload, userId, email, FeedbackDeviceInfo.collect(context))
    }

    /**
     * Send feedback with a [Bitmap], for example a screenshot, encoded as JPEG.
     *
     * The bitmap is encoded at JPEG quality 90. If the result is over 10 MB, the
     * SDK lowers the quality, and then reduces the width and height, until it
     * fits. JPEG has no transparency, so transparent areas become black. The
     * bitmap you pass is not changed or recycled.
     *
     * Behaves like the [submitFeedback] overload that takes image bytes in
     * every other way, including the error codes. A bitmap that cannot be
     * encoded fails with `"invalid_image"`.
     */
    suspend fun submitFeedback(
        context: Context,
        type: FeedbackType,
        message: String,
        image: Bitmap,
        userId: String? = null,
        email: String? = null,
    ): HelmResult<SubmittedFeedback> {
        // Check the message first so a bad message does not cost an encode.
        FeedbackApi.validate(message.trim(), null)?.let { return it }
        val fitted = try {
            withContext(Dispatchers.Default) {
                JpegShrinker.fit(JpegShrinker.encoderFor(image), FeedbackApi.MAX_IMAGE_BYTES)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // For example, the bitmap was already recycled.
            return HelmResult.Failure("invalid_image", null, "The bitmap could not be encoded: ${e.message}")
        }
        return when (fitted) {
            is JpegShrinker.Result.Fits -> FeedbackApi.submit(
                type,
                message,
                FeedbackApi.Image(fitted.bytes, "image/jpeg"),
                userId,
                email,
                FeedbackDeviceInfo.collect(context),
            )
            JpegShrinker.Result.TooLarge -> FeedbackApi.imageTooLarge()
            JpegShrinker.Result.EncodingFailed ->
                HelmResult.Failure("invalid_image", null, "The bitmap could not be encoded as JPEG.")
        }
    }

    /**
     * Send feedback with an image read from a content [Uri], for example one
     * the user picked with the photo picker.
     *
     * The image is read through `context.contentResolver`, and its MIME type is
     * the one the content resolver reports. An image over 10 MB is not resized:
     * the call fails with `"image_too_large"` without sending anything. An
     * image that cannot be opened or read, or whose MIME type the resolver
     * does not report, fails with `"invalid_image"`.
     *
     * Behaves like the [submitFeedback] overload that takes image bytes in
     * every other way, including the error codes.
     */
    suspend fun submitFeedback(
        context: Context,
        type: FeedbackType,
        message: String,
        image: Uri,
        userId: String? = null,
        email: String? = null,
    ): HelmResult<SubmittedFeedback> {
        FeedbackApi.validate(message.trim(), null)?.let { return it }
        val read = withContext(Dispatchers.IO) {
            UriImageReader.read(context.contentResolver, image, FeedbackApi.MAX_IMAGE_BYTES)
        }
        return when (read) {
            is UriImageReader.Result.Read -> FeedbackApi.submit(
                type,
                message,
                FeedbackApi.Image(read.bytes, read.mimeType),
                userId,
                email,
                FeedbackDeviceInfo.collect(context),
            )
            UriImageReader.Result.TooLarge -> FeedbackApi.imageTooLarge()
            is UriImageReader.Result.Unreadable -> HelmResult.Failure("invalid_image", null, read.reason)
        }
    }
}
