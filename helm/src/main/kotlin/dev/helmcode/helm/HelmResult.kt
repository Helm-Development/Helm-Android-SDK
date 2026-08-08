package dev.helmcode.helm

/**
 * Outcome of a Helm attribution call.
 *
 * The SDK never throws out of its public attribution surface: every network,
 * parsing, and configuration failure is mapped onto one of these cases.
 */
sealed class HelmResult<out T> {

    /** The call succeeded. */
    data class Success<T>(val value: T) : HelmResult<T>()

    /**
     * The backend rejected the call (terminal) or the SDK could not perform it.
     *
     * Terminal outcomes are never retried: the server answered, and replaying
     * would produce the same answer.
     *
     * @param code the backend error envelope code -- `"invalid_code"`,
     *   `"code_inactive"`, `"already_linked"`, `"invalid_token"`,
     *   `"missing_field"`, `"not_found"` -- or an SDK-local code:
     *   `"not_configured"` (no `Helm.configure(context, ...)` yet) or
     *   `"network_error"` (status fetch failed with no cached status to fall
     *   back on). An unrecognised error body yields `"http_<status>"`.
     * @param httpStatus the HTTP status when a response was received, else null.
     * @param message the server-supplied human-readable message, when present.
     */
    data class Failure(
        val code: String,
        val httpStatus: Int? = null,
        val message: String? = null,
    ) : HelmResult<Nothing>()

    /**
     * Transport failure: the submission was persisted locally and will be
     * replayed automatically -- on the next `Helm.configure(context, ...)`, the
     * next app foregrounding, or before the next attribution call -- for up to
     * 30 days.
     *
     * Returned only by the submit calls. A queued submission that later fails
     * server-side validation is resolved silently (the entry is dropped); the
     * app discovers the real state through [dev.helmcode.helm.attribution.Attribution.fetchAttributionStatus].
     */
    object Queued : HelmResult<Nothing>()
}

/**
 * A successful promo-code link.
 *
 * @param influencerCode the influencer code the customer is now linked to.
 * @param offeringId the offering the linked code entitles the customer to, when
 *   the code carries one.
 */
data class PromoCodeLink(
    val influencerCode: String,
    val offeringId: String?,
)

/**
 * A customer's minimal attribution status.
 *
 * @param isLinked whether the customer is linked to an influencer code.
 * @param influencerCode the linked code, null when not linked.
 * @param offeringId the linked code's offering, when it carries one.
 * @param fromCache true when the network was unavailable and this status was
 *   served from the local per-user cache. Treat it as possibly stale.
 */
data class AttributionStatus(
    val isLinked: Boolean,
    val influencerCode: String?,
    val offeringId: String?,
    val fromCache: Boolean,
)
