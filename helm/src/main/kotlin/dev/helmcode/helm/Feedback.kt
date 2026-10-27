package dev.helmcode.helm

import java.util.Date

/**
 * The kind of feedback a user is sending with [Helm.submitFeedback].
 *
 * @property wireValue the value sent to Helm in the `feedback_type` field.
 */
enum class FeedbackType(val wireValue: String) {
    /** Something in the app is broken or behaves unexpectedly. */
    BUG_REPORT("bug_report"),

    /** The user is asking for something the app does not do yet. */
    FEATURE_REQUEST("feature_request"),

    /** Any other comment about the app. */
    FEEDBACK("feedback");

    internal companion object {
        fun fromWireValue(value: String?): FeedbackType? = values().firstOrNull { it.wireValue == value }
    }
}

/**
 * Feedback that Helm has accepted and stored.
 *
 * @param id the feedback's ID in Helm (a UUID).
 * @param feedbackType the type Helm recorded.
 * @param status the feedback's status in Helm. New feedback is always `"new"`;
 *   project members change it later in the Helm dashboard.
 * @param dateCreated when Helm stored the feedback. Null only if the server sent
 *   a timestamp the SDK could not read; the feedback was still stored.
 */
data class SubmittedFeedback(
    val id: String,
    val feedbackType: FeedbackType,
    val status: String,
    val dateCreated: Date?,
)
