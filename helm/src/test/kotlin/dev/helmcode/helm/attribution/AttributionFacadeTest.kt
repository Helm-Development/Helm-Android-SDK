package dev.helmcode.helm.attribution

import dev.helmcode.helm.Configuration
import dev.helmcode.helm.HelmResult
import dev.helmcode.helm.Helm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unbound facade must degrade, not throw or crash.
 *
 * There is no Context here (no Robolectric in this module), so
 * `Attribution.instance` is never bound -- which is exactly the state an app on
 * the legacy two-argument `Helm.configure` is in, and the one most likely to be
 * reported as a bug.
 */
class AttributionFacadeTest {

    @After
    fun tearDown() {
        Configuration.instance = null
    }

    @Test
    fun unboundAttributionMethodsReportNotConfiguredRatherThanThrowing() = runBlocking {
        Helm.configure(publishableKey = "pk_test", baseURL = "http://127.0.0.1:1")
        val attribution = Helm.attribution

        val promo = attribution.submitPromoCode("user-1", "anna")
        val status = attribution.fetchAttributionStatus("user-1")
        val transaction = attribution.submitOriginalTransactionId("user-1", "tx-9")

        for (result in listOf(promo, status, transaction)) {
            assertEquals("not_configured", (result as HelmResult.Failure).code)
            assertTrue(
                "the message must point at the fix",
                result.message?.contains("Helm.configure(context") == true,
            )
        }
    }

    /**
     * Compile-and-run check for the fire-and-forget pair. Kotlin rejects a
     * suspend/non-suspend overload with identical value parameters as
     * conflicting (KT-23610), so the fire-and-forget variant carries the
     * `...Async` suffix; this test pins both call shapes so a future rename back
     * to an overload fails here rather than in a consumer app.
     */
    @Test
    fun bothTransactionSubmissionShapesAreCallable() = runBlocking {
        val attribution = Helm.attribution

        val awaited: HelmResult<Unit> = attribution.submitOriginalTransactionId("user-1", "tx-9")
        assertTrue(awaited is HelmResult.Failure)

        // Callable from a suspend context and from plain code alike; discards its result.
        attribution.submitOriginalTransactionIdAsync("user-1", "tx-9")
        fireAndForgetFromNonSuspendContext(attribution)
    }

    private fun fireAndForgetFromNonSuspendContext(attribution: Attribution) {
        attribution.submitOriginalTransactionIdAsync("user-1", "tx-9")
    }

    @Test
    fun resetOnAnUnboundInstanceIsANoOp() {
        Helm.attribution.reset()
    }

    // ---- debug / sandbox marker (TAS-801) -------------------------------

    /**
     * `debug` is defaulted on both overloads, so every pre-0.6.0 call site --
     * two positional arguments, named arguments, with or without a Context --
     * still compiles and still means live. This test is the source-compatibility
     * proof: it exercises the old shapes verbatim.
     */
    @Test
    fun legacyConfigureCallShapesStillCompileAndMeanLive() {
        Helm.configure("pk_test", "https://helmcode.dev")
        assertEquals(false, Configuration.instance?.debug)

        Helm.configure(publishableKey = "pk_test", baseURL = "https://helmcode.dev")
        assertEquals(false, Configuration.instance?.debug)
    }

    @Test
    fun configureCarriesTheDebugFlagOntoTheConfiguration() {
        Helm.configure("pk_test", "https://helmcode.dev", debug = true)
        assertEquals(true, Configuration.instance?.debug)
        assertEquals("pk_test", Configuration.instance?.publishableKey)

        Helm.configure(publishableKey = "pk_test", baseURL = "https://helmcode.dev", debug = false)
        assertEquals(false, Configuration.instance?.debug)
    }

    /**
     * The sandbox marker reaches the request builders through
     * `AttributionApi.currentDebug()`, which is the single read of the
     * configuration on the fresh-submission path.
     */
    @Test
    fun theConfiguredFlagIsWhatTheRequestBuildersRead() {
        Helm.configure("pk_test", "https://helmcode.dev", debug = true)
        assertEquals(true, AttributionApi.currentDebug())
        assertEquals(true, AttributionApi.promoCodeBody("u", "c", "d", AttributionApi.currentDebug())["debug"])

        Helm.configure("pk_test", "https://helmcode.dev", debug = false)
        assertEquals(false, AttributionApi.currentDebug())
    }
}
