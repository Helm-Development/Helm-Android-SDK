package dev.helmcode.helm.networking

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * A `multipart/form-data` request body, written by hand because the SDK sends
 * requests with HttpURLConnection and has no runtime HTTP library dependency.
 *
 * @param fields text fields, sent in order. Values are encoded as UTF-8.
 * @param file an optional file part, sent after the text fields.
 * @param boundary the separator between parts. Random by default; tests can
 *   pass a fixed one.
 */
internal class MultipartFormData(
    private val fields: List<Pair<String, String>>,
    private val file: FilePart? = null,
    val boundary: String = "HelmBoundary" + UUID.randomUUID().toString().replace("-", ""),
) {

    /** A file part: the form field name, the file name sent to the server, its MIME type and bytes. */
    internal class FilePart(
        val name: String,
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray,
    )

    /** The value for the request's `Content-Type` header. */
    val contentType: String
        get() = "multipart/form-data; boundary=$boundary"

    /** The complete encoded body. */
    fun toByteArray(): ByteArray {
        val out = ByteArrayOutputStream((file?.bytes?.size ?: 0) + 1024)
        for ((name, value) in fields) {
            out.writeAscii("--$boundary\r\n")
            out.writeAscii("Content-Disposition: form-data; name=\"${quoted(name)}\"\r\n\r\n")
            out.write(value.toByteArray(Charsets.UTF_8))
            out.writeAscii("\r\n")
        }
        if (file != null) {
            out.writeAscii("--$boundary\r\n")
            out.writeAscii(
                "Content-Disposition: form-data; name=\"${quoted(file.name)}\"; " +
                    "filename=\"${quoted(file.fileName)}\"\r\n",
            )
            out.writeAscii("Content-Type: ${file.mimeType}\r\n\r\n")
            out.write(file.bytes)
            out.writeAscii("\r\n")
        }
        out.writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /** Makes a name safe to put inside double quotes in a part header. */
    private fun quoted(value: String): String =
        value.replace("\"", "%22").replace("\r", "").replace("\n", "")
}
