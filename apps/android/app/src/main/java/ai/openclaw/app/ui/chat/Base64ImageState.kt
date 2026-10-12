package ai.openclaw.app.ui.chat

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dispatcher for chat image decoding; tests provide their own to drain decodes explicitly. */
internal val LocalChatImageDecodeDispatcher = staticCompositionLocalOf<CoroutineDispatcher> { Dispatchers.Default }

/** Compose state for async base64 image decoding. */
internal data class Base64ImageState(
  val image: ImageBitmap?,
  val failed: Boolean,
  /** The payload exceeds [Base64ImageSource.maxBase64Chars], so decoding was not attempted. */
  val tooLarge: Boolean = false,
)

/** Decodes a base64 image off the UI thread and reports failure state. */
@Composable
internal fun rememberBase64ImageState(
  base64: String,
  source: Base64ImageSource = Base64ImageSource.Inline,
): Base64ImageState {
  val decodeDispatcher = LocalChatImageDecodeDispatcher.current
  var image by remember(base64, source) { mutableStateOf<ImageBitmap?>(null) }
  var failed by remember(base64, source) { mutableStateOf(false) }

  LaunchedEffect(base64, source, decodeDispatcher) {
    failed = false
    image =
      withContext(decodeDispatcher) {
        try {
          val bitmap = decodeBase64Bitmap(base64, source = source) ?: return@withContext null
          bitmap.asImageBitmap()
        } catch (err: Throwable) {
          // Surface the real cause (OOM, corrupt data) instead of a bare "could not decode".
          Log.w("OpenClawImage", "base64 image decode failed (${base64.length} chars)", err)
          null
        }
      }
    if (image == null) failed = true
  }

  return Base64ImageState(image = image, failed = failed, tooLarge = base64.length > source.maxBase64Chars)
}
