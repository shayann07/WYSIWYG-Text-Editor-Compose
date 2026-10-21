package dev.shayxo.composedoc.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.shayxo.composedoc.model.ImageResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AndroidLoadedImages(
    val images: Map<String, ImageBitmap> = emptyMap(),
    val errors: List<String> = emptyList(),
)

/** UI retains only encoded metadata and completed images; decoding and validation run on a worker. */
@Composable
internal fun rememberAndroidImages(resources: Map<String, ImageResource>): AndroidLoadedImages {
    var images by remember { mutableStateOf(AndroidLoadedImages()) }
    LaunchedEffect(resources) {
        images = withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            val loaded = resources.mapNotNull { (id, resource) ->
                val decoded = runCatching {
                    val bytes = resource.bytes()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    require(options.outWidth == resource.width && options.outHeight == resource.height)
                    require(options.outWidth.toLong() * options.outHeight <= MAX_PIXELS)
                    id to checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)).asImageBitmap()
                }
                decoded.onFailure {
                    errors += "Image ${id.take(ID_LABEL_UNITS)} unavailable: ${it.message}; owned bytes retained"
                }.getOrNull()
            }.toMap()
            AndroidLoadedImages(loaded, errors)
        }
    }
    return images
}

private const val MAX_PIXELS = 16_000_000L
private const val ID_LABEL_UNITS = 8
