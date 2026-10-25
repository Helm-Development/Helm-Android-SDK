package dev.helmcode.helm.feedback

import android.content.ContentResolver
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

class FeedbackImagesTest {

    // ---- JpegShrinker --------------------------------------------------------

    /** Records each (quality, scale) it is asked for and returns [size] bytes for it. */
    private class FakeEncoder(private val size: (quality: Int, scale: Float) -> Int?) : JpegShrinker.Encoder {
        val calls = mutableListOf<Pair<Int, Float>>()
        override fun encode(quality: Int, scale: Float): ByteArray? {
            calls += quality to scale
            return size(quality, scale)?.let { ByteArray(it) }
        }
    }

    @Test
    fun bitmapThatFitsIsEncodedOnceAtQuality90() {
        val encoder = FakeEncoder { _, _ -> 500 }

        val result = JpegShrinker.fit(encoder, 1000)

        assertEquals(500, (result as JpegShrinker.Result.Fits).bytes.size)
        assertEquals(listOf(90 to 1f), encoder.calls)
    }

    @Test
    fun lowersTheQualityBeforeDownscaling() {
        // Fits once the quality reaches 70.
        val encoder = FakeEncoder { quality, _ -> quality * 20 }

        val result = JpegShrinker.fit(encoder, 1400)

        assertEquals(1400, (result as JpegShrinker.Result.Fits).bytes.size)
        assertEquals(listOf(90 to 1f, 80 to 1f, 70 to 1f), encoder.calls)
    }

    @Test
    fun downscalesAtTheLowestQualityWhenLoweringTheQualityIsNotEnough() {
        // The size follows the pixel count: scale squared.
        val encoder = FakeEncoder { _, scale -> (10_000 * scale * scale).toInt() }

        val result = JpegShrinker.fit(encoder, 4_000)

        assertTrue(result is JpegShrinker.Result.Fits)
        assertEquals(JpegShrinker.QUALITIES.map { it to 1f }, encoder.calls.take(5))
        // Scale 0.75 gives 5625 bytes (too large); scale 0.5625 gives 3164 bytes (fits).
        val downscaled = encoder.calls.drop(5)
        assertEquals(2, downscaled.size)
        assertTrue(downscaled.all { it.first == 50 })
        assertEquals(0.5625f, downscaled.last().second, 0.0001f)
        assertEquals(3164, (result as JpegShrinker.Result.Fits).bytes.size)
    }

    @Test
    fun givesUpAfterTheLastDownscaleStep() {
        val encoder = FakeEncoder { _, _ -> 2_000 }

        val result = JpegShrinker.fit(encoder, 1_000)

        assertSame(JpegShrinker.Result.TooLarge, result)
        assertEquals(JpegShrinker.QUALITIES.size + JpegShrinker.MAX_DOWNSCALE_STEPS, encoder.calls.size)
    }

    @Test
    fun reportsAnEncodingFailure() {
        val encoder = FakeEncoder { _, _ -> null }

        assertSame(JpegShrinker.Result.EncodingFailed, JpegShrinker.fit(encoder, 1_000))
    }

    // ---- UriImageReader ------------------------------------------------------

    // Plain Mockito: the mockito-kotlin helpers are compiled for JVM 11 and
    // cannot be inlined into this module's JVM 1.8 bytecode.
    private val uri: Uri = mock(Uri::class.java)

    private fun resolver(type: String?, bytes: ByteArray): ContentResolver {
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.getType(uri)).thenReturn(type)
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bytes))
        return resolver
    }

    @Test
    fun readsTheBytesAndTheResolverMimeType() {
        val bytes = ByteArray(200_000) { (it % 7).toByte() }

        val result = UriImageReader.read(resolver("image/webp", bytes), uri, 1_000_000)

        result as UriImageReader.Result.Read
        assertEquals("image/webp", result.mimeType)
        assertArrayEquals(bytes, result.bytes)
    }

    @Test
    fun imageAtTheLimitIsRead() {
        val result = UriImageReader.read(resolver("image/png", ByteArray(1_000)), uri, 1_000)

        assertEquals(1_000, (result as UriImageReader.Result.Read).bytes.size)
    }

    @Test
    fun imageOverTheLimitIsTooLarge() {
        val result = UriImageReader.read(resolver("image/png", ByteArray(1_001)), uri, 1_000)

        assertSame(UriImageReader.Result.TooLarge, result)
    }

    @Test
    fun missingMimeTypeIsUnreadable() {
        val result = UriImageReader.read(resolver(null, ByteArray(10)), uri, 1_000)

        assertTrue(result is UriImageReader.Result.Unreadable)
    }

    @Test
    fun streamThatCannotBeOpenedIsUnreadable() {
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.getType(uri)).thenReturn("image/jpeg")
        `when`(resolver.openInputStream(uri)).thenThrow(FileNotFoundException("gone"))

        val result = UriImageReader.read(resolver, uri, 1_000)

        assertTrue((result as UriImageReader.Result.Unreadable).reason.contains("gone"))
    }
}
