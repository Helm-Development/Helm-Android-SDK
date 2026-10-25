package dev.helmcode.helm.feedback

import dev.helmcode.helm.Configuration
import dev.helmcode.helm.FeedbackType
import dev.helmcode.helm.HelmResult
import dev.helmcode.helm.SubmittedFeedback
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The feedback request against a real HTTP server: what goes on the wire, what
 * the SDK refuses to send, and how each response becomes a [HelmResult].
 */
class FeedbackApiTest {

    private lateinit var server: MockWebServer

    private val device = FeedbackDeviceInfo(appVersion = "2.3.1", osVersion = "14", deviceModel = "Pixel 8")

    private val created = """
        {"id": "6f1c2b9e-0d7a-4c1e-9b8a-2f4d5e6a7b8c", "feedback_type": "bug_report",
         "status": "new", "date_created": "2026-10-25T09:01:52+00:00"}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        configure(debug = false)
    }

    @After
    fun tearDown() {
        Configuration.instance = null
        server.shutdown()
    }

    private fun configure(debug: Boolean) {
        Configuration.instance = Configuration("pk_test", server.url("/").toString().trimEnd('/'), debug)
    }

    private fun jsonResponse(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    /** One part of a recorded multipart request. */
    private class Part(val headers: Headers, val bytes: ByteArray) {
        val text: String get() = String(bytes, Charsets.UTF_8)
        val disposition: String get() = headers["Content-Disposition"]!!
    }

    /** Parses a recorded multipart body into its parts, keyed by form field name. */
    private fun parts(request: RecordedRequest): Map<String, Part> {
        val contentType = request.getHeader("Content-Type")!!
        assertTrue(contentType, contentType.startsWith("multipart/form-data; boundary="))
        val boundary = contentType.toMediaType().parameter("boundary")!!
        val reader = MultipartReader(request.body, boundary)
        val result = linkedMapOf<String, Part>()
        while (true) {
            val part = reader.nextPart() ?: break
            val name = Regex("name=\"([^\"]*)\"").find(part.headers["Content-Disposition"]!!)!!.groupValues[1]
            result[name] = Part(part.headers, part.body.readByteArray())
        }
        return result
    }

    private suspend fun submit(
        type: FeedbackType = FeedbackType.BUG_REPORT,
        message: String = "The app crashes when I tap Save.",
        image: FeedbackApi.Image? = null,
        userId: String? = null,
        email: String? = null,
        deviceInfo: FeedbackDeviceInfo = device,
    ) = FeedbackApi.submit(type, message, image, userId, email, deviceInfo)

    // ---- the request ------------------------------------------------------

    @Test
    fun sendsAnAuthenticatedMultipartPostToTheFeedbackPath() = runBlocking {
        server.enqueue(jsonResponse(201, created))

        submit()

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/client/v1/feedback/", request.path)
        assertEquals("Bearer pk_test", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
    }

    @Test
    fun sendsEveryFieldTheSdkFillsIn() = runBlocking {
        server.enqueue(jsonResponse(201, created))

        submit(
            type = FeedbackType.FEATURE_REQUEST,
            message = "  Please add dark mode.  ",
            userId = "user-42",
            email = "ana@example.com",
        )

        val parts = parts(server.takeRequest(2, TimeUnit.SECONDS)!!)
        assertEquals(
            listOf(
                "feedback_type", "message", "platform", "user_id", "email",
                "app_version", "os_version", "device_model", "debug",
            ),
            parts.keys.toList(),
        )
        assertEquals("feature_request", parts.getValue("feedback_type").text)
        assertEquals("the message is sent trimmed", "Please add dark mode.", parts.getValue("message").text)
        assertEquals("android", parts.getValue("platform").text)
        assertEquals("user-42", parts.getValue("user_id").text)
        assertEquals("ana@example.com", parts.getValue("email").text)
        assertEquals("2.3.1", parts.getValue("app_version").text)
        assertEquals("14", parts.getValue("os_version").text)
        assertEquals("Pixel 8", parts.getValue("device_model").text)
        assertEquals("false", parts.getValue("debug").text)
        assertFalse("no image part without an image", parts.containsKey("image"))
        assertFalse(
            "text fields are not file parts",
            parts.getValue("message").disposition.contains("filename"),
        )
    }

    @Test
    fun eachFeedbackTypeUsesItsWireValue() = runBlocking {
        val expected = mapOf(
            FeedbackType.BUG_REPORT to "bug_report",
            FeedbackType.FEATURE_REQUEST to "feature_request",
            FeedbackType.FEEDBACK to "feedback",
        )
        for ((type, wire) in expected) {
            assertEquals(wire, type.wireValue)
            server.enqueue(jsonResponse(201, created))
            submit(type = type)
            assertEquals(wire, parts(server.takeRequest(2, TimeUnit.SECONDS)!!).getValue("feedback_type").text)
        }
    }

    @Test
    fun sendsUnicodeMessagesAsUtf8() = runBlocking {
        server.enqueue(jsonResponse(201, created))

        submit(message = "Ça plante quand j’enregistre 😕")

        assertEquals(
            "Ça plante quand j’enregistre 😕",
            parts(server.takeRequest(2, TimeUnit.SECONDS)!!).getValue("message").text,
        )
    }

    @Test
    fun leavesOutOptionalFieldsThatAreMissingOrBlank() = runBlocking {
        server.enqueue(jsonResponse(201, created))

        submit(userId = " ", email = "", deviceInfo = FeedbackDeviceInfo(null, " ", null))

        val parts = parts(server.takeRequest(2, TimeUnit.SECONDS)!!)
        assertEquals(listOf("feedback_type", "message", "platform", "debug"), parts.keys.toList())
    }

    @Test
    fun shortensDeviceDetailsToTheServerLimits() = runBlocking {
        server.enqueue(jsonResponse(201, created))

        submit(deviceInfo = FeedbackDeviceInfo("v".repeat(80), "o".repeat(80), "m".repeat(150)))

        val parts = parts(server.takeRequest(2, TimeUnit.SECONDS)!!)
        assertEquals(50, parts.getValue("app_version").text.length)
        assertEquals(50, parts.getValue("os_version").text.length)
        assertEquals(100, parts.getValue("device_model").text.length)
    }

    @Test
    fun sendsTheImageAsAFilePart() = runBlocking {
        server.enqueue(jsonResponse(201, created))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x7F, 0xFF.toByte())

        submit(image = FeedbackApi.Image(png, "image/png"))

        val parts = parts(server.takeRequest(2, TimeUnit.SECONDS)!!)
        assertEquals("the image is the last part", "image", parts.keys.last())
        val image = parts.getValue("image")
        assertEquals("form-data; name=\"image\"; filename=\"feedback.png\"", image.disposition)
        assertEquals("image/png", image.headers["Content-Type"])
        assertArrayEquals(png, image.bytes)
    }

    @Test
    fun fileNameExtensionFollowsTheMimeType() {
        assertEquals("feedback.jpg", FeedbackApi.fileNameFor("image/jpeg"))
        assertEquals("feedback.png", FeedbackApi.fileNameFor("image/png"))
        assertEquals("feedback.heic", FeedbackApi.fileNameFor("image/heic"))
        assertEquals("feedback.heif", FeedbackApi.fileNameFor("image/heif"))
        assertEquals("feedback.webp", FeedbackApi.fileNameFor("IMAGE/WEBP"))
        assertEquals("feedback", FeedbackApi.fileNameFor("image/gif"))
    }

    // ---- debug flag --------------------------------------------------------

    @Test
    fun sendsDebugTrueWhenConfiguredForDebug() = runBlocking {
        configure(debug = true)
        server.enqueue(jsonResponse(201, created))

        submit()

        assertEquals("true", parts(server.takeRequest(2, TimeUnit.SECONDS)!!).getValue("debug").text)
    }

    @Test
    fun sendsDebugFalseWhenNotConfiguredForDebug() = runBlocking {
        configure(debug = false)
        server.enqueue(jsonResponse(201, created))

        submit()

        assertEquals("false", parts(server.takeRequest(2, TimeUnit.SECONDS)!!).getValue("debug").text)
    }

    // ---- validation before sending -----------------------------------------

    @Test
    fun emptyOrWhitespaceMessageFailsWithoutSending() = runBlocking {
        for (message in listOf("", "   ", "\n\t ")) {
            val result = submit(message = message) as HelmResult.Failure
            assertEquals("missing_field", result.code)
            assertNull("a local failure has no HTTP status", result.httpStatus)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun messageOverTheLimitFailsWithoutSending() = runBlocking {
        val result = submit(message = "a".repeat(5001)) as HelmResult.Failure

        assertEquals("invalid_field", result.code)
        assertNull(result.httpStatus)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun messageAtTheLimitIsSentAndTheLimitAppliesAfterTrimming() = runBlocking {
        server.enqueue(jsonResponse(201, created))

        val result = submit(message = "  " + "a".repeat(5000) + "  ")

        assertTrue(result is HelmResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun messageLengthCountsCharactersNotUtf16Units() = runBlocking {
        server.enqueue(jsonResponse(201, created))

        // Each emoji is two UTF-16 units but one character, as the server counts it.
        val result = submit(message = "😕".repeat(5000))

        assertTrue(result is HelmResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun imageOverTenMegabytesFailsWithoutSending() = runBlocking {
        val result = submit(image = FeedbackApi.Image(ByteArray(10 * 1024 * 1024 + 1), "image/jpeg"))
            as HelmResult.Failure

        assertEquals("image_too_large", result.code)
        assertNull(result.httpStatus)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun imageOfExactlyTenMegabytesIsSent() = runBlocking {
        server.enqueue(jsonResponse(201, created))
        val bytes = ByteArray(10 * 1024 * 1024) { (it % 251).toByte() }

        val result = submit(image = FeedbackApi.Image(bytes, "image/jpeg"))

        assertTrue(result is HelmResult.Success)
        assertArrayEquals(bytes, parts(server.takeRequest(5, TimeUnit.SECONDS)!!).getValue("image").bytes)
    }

    @Test
    fun imageWithoutMimeTypeFailsWithoutSending() = runBlocking {
        val result = submit(image = FeedbackApi.Image(byteArrayOf(1, 2, 3), "")) as HelmResult.Failure

        assertEquals("invalid_field", result.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun emptyImageFailsWithoutSending() = runBlocking {
        val result = submit(image = FeedbackApi.Image(ByteArray(0), "image/png")) as HelmResult.Failure

        assertEquals("invalid_image", result.code)
        assertEquals(0, server.requestCount)
    }

    // ---- responses ---------------------------------------------------------

    @Test
    fun parsesTheCreatedResponse() = runBlocking {
        server.enqueue(jsonResponse(201, created))

        val result = submit()

        assertEquals(
            HelmResult.Success(
                SubmittedFeedback(
                    id = "6f1c2b9e-0d7a-4c1e-9b8a-2f4d5e6a7b8c",
                    feedbackType = FeedbackType.BUG_REPORT,
                    status = "new",
                    dateCreated = Date(1_792_918_912_000L),
                ),
            ),
            result,
        )
    }

    @Test
    fun parsesTheServerTimestampFormatAndCommonVariants() {
        // The form the server sends: whole seconds and a numeric UTC offset.
        assertEquals(Date(1_792_918_912_000L), FeedbackApi.parseTimestamp("2026-10-25T09:01:52+00:00"))
        assertEquals(Date(1_792_918_912_000L), FeedbackApi.parseTimestamp("2026-10-25T04:01:52-05:00"))
        assertEquals(Date(1_792_918_912_000L), FeedbackApi.parseTimestamp("2026-10-25T09:01:52Z"))
        assertEquals(Date(1_792_918_912_000L), FeedbackApi.parseTimestamp("2026-10-25T09:01:52+0000"))
        // Fractional seconds are kept to the millisecond.
        assertEquals(Date(1_792_918_912_123L), FeedbackApi.parseTimestamp("2026-10-25T09:01:52.123456+00:00"))
        assertEquals(Date(1_792_918_912_500L), FeedbackApi.parseTimestamp("2026-10-25T09:01:52.5Z"))
    }

    @Test
    fun unreadableTimestampIsNull() {
        assertNull(FeedbackApi.parseTimestamp(null))
        assertNull(FeedbackApi.parseTimestamp(""))
        assertNull(FeedbackApi.parseTimestamp("yesterday"))
        assertNull("no offset", FeedbackApi.parseTimestamp("2026-10-25T09:01:52"))
        assertNull("not a real date", FeedbackApi.parseTimestamp("2026-13-45T09:01:52+00:00"))
    }

    @Test
    fun unreadableTimestampStillReturnsTheStoredFeedback() = runBlocking {
        server.enqueue(jsonResponse(201, """{"id": "f-1", "feedback_type": "feedback", "status": "new", "date_created": "soon"}"""))

        val result = submit() as HelmResult.Success

        assertEquals("f-1", result.value.id)
        assertNull(result.value.dateCreated)
    }

    @Test
    fun responseWithoutAnIdIsAnInvalidResponse() = runBlocking {
        server.enqueue(jsonResponse(201, """{"status": "new"}"""))

        assertEquals("invalid_response", (submit() as HelmResult.Failure).code)
    }

    @Test
    fun unreadableSuccessBodyIsAnInvalidResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("not json"))

        assertEquals("invalid_response", (submit() as HelmResult.Failure).code)
    }

    @Test
    fun eachServerErrorCodeIsReturnedWithItsStatusAndMessage() = runBlocking {
        val cases = listOf(
            400 to "missing_field",
            400 to "invalid_field",
            400 to "invalid_image",
            413 to "image_too_large",
            401 to "auth_required",
            401 to "invalid_token",
        )
        for ((status, code) in cases) {
            server.enqueue(jsonResponse(status, """{"error": {"message": "Server says $code.", "code": "$code"}}"""))

            assertEquals(HelmResult.Failure(code, status, "Server says $code."), submit())
        }
        assertEquals("nothing is retried", cases.size, server.requestCount)
    }

    @Test
    fun errorBodyWithoutTheStandardShapeBecomesHttpStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))

        val result = submit() as HelmResult.Failure

        assertEquals("http_502", result.code)
        assertEquals(502, result.httpStatus)
        assertEquals("<html>Bad Gateway</html>", result.message)
        assertEquals("there is no offline queue or retry", 1, server.requestCount)
    }

    @Test
    fun droppedConnectionIsANetworkError() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = submit() as HelmResult.Failure

        assertEquals("network_error", result.code)
        assertNull(result.httpStatus)
    }

    @Test
    fun unreachableServerIsANetworkError() = runBlocking {
        // Nothing listens on port 1, so the connection fails at once.
        Configuration.instance = Configuration("pk_test", "http://127.0.0.1:1")

        assertEquals("network_error", (submit() as HelmResult.Failure).code)
    }

    @Test
    fun notConfiguredFailsWithoutSending() = runBlocking {
        Configuration.instance = null

        assertEquals("not_configured", (submit() as HelmResult.Failure).code)
        assertEquals(0, server.requestCount)
    }
}
