package dev.helmcode.helm.attribution

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the attribution endpoint contract: these paths MUST equal the Helm
 * backend routes (apps/client_api/urls.py, mounted at /api/) and the iOS SDK.
 * A prefix-less path (e.g. "/attribution/match/") 404s and is silently
 * swallowed, recording no attribution.
 */
class AttributionPathsTest {
    @Test
    fun attributionPathsMatchBackendRoutes() {
        assertEquals("/api/client/v1/attribution/referrer/", Attribution.PATH_REFERRER)
        assertEquals("/api/client/v1/attribution/match/", Attribution.PATH_MATCH)
        assertEquals("/api/client/v1/attribution/event/", Attribution.PATH_EVENT)
    }

    /**
     * HELM-221 endpoints. Same contract, same failure mode: these three POST
     * routes (trailing slash included) are the frozen agreement with the Helm
     * service ticket and the iOS SDK's APIPath additions.
     */
    @Test
    fun promoCodeStatusAndTransactionPathsMatchBackendRoutes() {
        assertEquals("/api/client/v1/attribution/promo-code/", Attribution.PATH_PROMO_CODE)
        assertEquals("/api/client/v1/attribution/status/", Attribution.PATH_STATUS)
        assertEquals("/api/client/v1/attribution/transaction/", Attribution.PATH_TRANSACTION)
    }
}
