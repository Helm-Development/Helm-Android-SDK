package dev.helmcode.helm.feedback

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import dev.helmcode.helm.Configuration
import dev.helmcode.helm.FeedbackType
import dev.helmcode.helm.Helm
import dev.helmcode.helm.HelmResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * The three public [Helm.submitFeedback] overloads: that each one turns its
 * input into the right request, and that defaulted parameters resolve.
 *
 * Uses plain Mockito: the mockito-kotlin helpers are compiled for JVM 11 and
 * cannot be inlined into this module's JVM 1.8 bytecode.
 */
class HelmSubmitFeedbackTest {

    private lateinit var server: MockWebServer

    private val created =
        """{"id": "f-1", "feedback_type": "feedback", "status": "new", "date_created": "2026-10-25T09:01:52+00:00"}"""

    private val uri: Uri = mock(Uri::class.java)
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        Configuration.instance = Configuration("pk_test", server.url("/").toString().trimEnd('/'))

        val packageInfo = PackageInfo().apply { versionName = "3.0.0" }
        val packageManager = mock(PackageManager::class.java)
        `when`(packageManager.getPackageInfo(anyString(), anyInt())).thenReturn(packageInfo)
        resolver = mock(ContentResolver::class.java)
        context = mock(Context::class.java)
        `when`(context.packageManager).thenReturn(packageManager)
        `when`(context.packageName).thenReturn("com.example.app")
        `when`(context.contentResolver).thenReturn(resolver)
    }

    @After
    fun tearDown() {
        Configuration.instance = null
        server.shutdown()
    }

    private fun created() = MockResponse().setResponseCode(201).setBody(created)

    private fun bodyText(): String = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()

    @Test
    fun messageOnlyCallSendsTheAppVersionNameAndNoImage() = runBlocking {
        server.enqueue(created())

        val result = Helm.submitFeedback(context, FeedbackType.FEEDBACK, "Love the new layout.")

        assertEquals("f-1", (result as HelmResult.Success).value.id)
        val body = bodyText()
        assertTrue(body, body.contains("name=\"app_version\"\r\n\r\n3.0.0\r\n"))
        assertTrue(body, body.contains("name=\"platform\"\r\n\r\nandroid\r\n"))
        assertTrue(body, !body.contains("name=\"image\""))
    }

    @Test
    fun byteArrayImageIsSentWithItsMimeType() = runBlocking {
        server.enqueue(created())

        Helm.submitFeedback(
            context,
            FeedbackType.BUG_REPORT,
            "Crash on save",
            image = byteArrayOf(1, 2, 3),
            imageMimeType = "image/heic",
            userId = "u-1",
        )

        val body = bodyText()
        assertTrue(body, body.contains("filename=\"feedback.heic\"\r\nContent-Type: image/heic\r\n"))
        assertTrue(body, body.contains("name=\"user_id\"\r\n\r\nu-1\r\n"))
    }

    @Test
    fun bitmapIsEncodedAsJpeg() = runBlocking {
        server.enqueue(created())
        val bitmap = mock(Bitmap::class.java)
        `when`(bitmap.compress(any(), anyInt(), any())).thenAnswer { call ->
            (call.arguments[2] as OutputStream).write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            true
        }

        val result = Helm.submitFeedback(context, FeedbackType.BUG_REPORT, "Screenshot attached", bitmap)

        assertTrue(result is HelmResult.Success)
        val body = bodyText()
        assertTrue(body, body.contains("filename=\"feedback.jpg\"\r\nContent-Type: image/jpeg\r\n"))
        verify(bitmap, never()).recycle()
    }

    @Test
    fun bitmapThatCannotBeEncodedFailsWithoutSending() = runBlocking {
        val bitmap = mock(Bitmap::class.java)
        `when`(bitmap.compress(any(), anyInt(), any())).thenReturn(false)

        val result = Helm.submitFeedback(context, FeedbackType.BUG_REPORT, "Screenshot attached", bitmap)

        assertEquals("invalid_image", (result as HelmResult.Failure).code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun bitmapCallChecksTheMessageBeforeEncoding(): Unit = runBlocking {
        val bitmap = mock(Bitmap::class.java)

        val result = Helm.submitFeedback(context, FeedbackType.BUG_REPORT, "  ", bitmap)

        assertEquals("missing_field", (result as HelmResult.Failure).code)
        verify(bitmap, never()).compress(any(), anyInt(), any())
    }

    @Test
    fun uriImageIsReadThroughTheContentResolver() = runBlocking {
        server.enqueue(created())
        `when`(resolver.getType(uri)).thenReturn("image/png")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(byteArrayOf(9, 8, 7)))

        val result = Helm.submitFeedback(context, FeedbackType.FEATURE_REQUEST, "Like this", uri, email = "a@b.co")

        assertTrue(result is HelmResult.Success)
        val body = bodyText()
        assertTrue(body, body.contains("filename=\"feedback.png\"\r\nContent-Type: image/png\r\n"))
        assertTrue(body, body.contains("name=\"email\"\r\n\r\na@b.co\r\n"))
    }

    @Test
    fun uriImageOverTenMegabytesFailsWithoutSending() = runBlocking {
        `when`(resolver.getType(uri)).thenReturn("image/jpeg")
        `when`(resolver.openInputStream(uri))
            .thenReturn(ByteArrayInputStream(ByteArray(10 * 1024 * 1024 + 1)))

        val result = Helm.submitFeedback(context, FeedbackType.BUG_REPORT, "Big photo", uri) as HelmResult.Failure

        assertEquals("image_too_large", result.code)
        assertNull(result.httpStatus)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun uriWithoutAMimeTypeFailsWithoutSending() = runBlocking {
        `when`(resolver.getType(uri)).thenReturn(null)

        val result = Helm.submitFeedback(context, FeedbackType.BUG_REPORT, "Photo", uri) as HelmResult.Failure

        assertEquals("invalid_image", result.code)
        assertEquals(0, server.requestCount)
    }
}
