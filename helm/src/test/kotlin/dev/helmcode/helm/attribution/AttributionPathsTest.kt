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
}
