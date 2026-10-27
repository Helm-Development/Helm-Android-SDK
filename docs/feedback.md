# User feedback

Available from version 1.1.0.

`Helm.submitFeedback(...)` sends feedback from a user of your app to Helm. Project
members read it on the **User Feedback** page, under **Monitoring**, in the Helm
dashboard.

Each piece of feedback has a type, a message, and optionally an image, the user's
ID in your app and an email address you can reply to. The SDK adds the platform,
the app's `versionName`, the Android version and the device model itself.

## Example

Call `Helm.configure(...)` first, then call `submitFeedback` from a coroutine:

```kotlin
lifecycleScope.launch {
    val result = Helm.submitFeedback(
        context = this@FeedbackActivity,
        type = FeedbackType.BUG_REPORT,
        message = messageField.text.toString(),
        image = screenshotBitmap,      // optional: a Bitmap, a content Uri, or ByteArray + imageMimeType
        userId = currentUser?.id,      // optional
        email = emailField.text.toString(), // optional
    )
    when (result) {
        is HelmResult.Success -> showThanks()            // result.value.id is the feedback's ID in Helm
        is HelmResult.Failure -> showError(result.code)  // for example "missing_field" or "network_error"
        HelmResult.Queued -> Unit                        // never returned by submitFeedback
    }
}
```

The three ways to attach an image:

| Parameter | What the SDK does |
|---|---|
| `image: ByteArray` with `imageMimeType` | Sends the bytes as they are. JPEG, PNG, HEIC or WebP. |
| `image: Bitmap` | Encodes it as JPEG. If the result is over 10 MB, lowers the quality and then the size until it fits. Transparent areas become black. |
| `image: Uri` | Reads a `content://` Uri through the `ContentResolver`, which also gives the MIME type. |

## Limits

The SDK checks these before sending, and sends nothing if one fails:

- The message must not be empty after leading and trailing whitespace is removed,
  and must be at most 5000 characters.
- The image must be at most 10 MB. Bytes or a Uri over the limit are not resized;
  the call fails with `image_too_large`.

## Results

`submitFeedback` returns `HelmResult<SubmittedFeedback>`. On success,
`SubmittedFeedback` holds the feedback's `id`, `feedbackType`, `status` (`"new"`)
and `dateCreated` (a `java.util.Date`).

There is no offline queue. If the request cannot be delivered, the call returns a
failure and your app decides whether to try again. `HelmResult.Failure.code` is
one of:

| Code | Meaning |
|---|---|
| `missing_field` | The message is empty. |
| `invalid_field` | The message is over 5000 characters, an image was given without a MIME type, or the server rejected another field, such as an invalid email. |
| `invalid_image` | The image is empty or could not be read, or the server did not accept it as a JPEG, PNG, HEIC or WebP image. |
| `image_too_large` | The image is over 10 MB. |
| `auth_required`, `invalid_token` | The publishable key is missing or was not accepted. |
| `not_configured` | `Helm.configure(...)` has not been called. |
| `network_error` | No response was received. |
| `invalid_response` | The server answered with a success status but a body the SDK could not read. |
| `http_<status>` | Any other error response, for example `http_500`. |

When the SDK catches the problem before sending, `httpStatus` is `null`.

## Test data

When `Helm.configure(...)` is called with `debug = true`, every piece of feedback
from that build is marked as test data in Helm, the same way attribution and
analytics requests are.
