package com.rerunguide.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** SwiftUI's `AsyncImage(url:) { $0.resizable().scaledToFill() } placeholder: { Color.clear }`: fills the modifier's
 *  size, clear while loading or when the load fails. Decoded no larger than drawn, kept in memory by URL. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop,
                overlay: (DrawScope.() -> Unit)? = null) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var image by remember(url) { mutableStateOf(url?.let { RemoteImages.cache.get(it) }) }
    LaunchedEffect(url, size) {
        if (url == null || size.width == 0 || size.height == 0) return@LaunchedEffect
        if (image?.covers(size) == true) return@LaunchedEffect
        RemoteImages.load(url, size)?.let { image = it }
    }
    Box(modifier.onSizeChanged { size = it }) {
        // `overlay` is drawn over the picture only once it has loaded (AsyncImage content's own overlay).
        image?.let { Image(it.bitmap, null, if (overlay == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().drawWithContent { drawContent(); overlay() },
            contentScale = contentScale) }
    }
}

/** A decoded image, and whether it is the source's full size (so a bigger box can't get a sharper one). */
internal class Decoded(bitmap: Bitmap, val full: Boolean) {
    val bitmap = bitmap.asImageBitmap()
    val bytes = bitmap.byteCount
    fun covers(size: IntSize) = full || (bitmap.width >= size.width && bitmap.height >= size.height)
}

internal object RemoteImages {
    /** About an eighth of the heap, by decoded bytes. */
    val cache = object : LruCache<String, Decoded>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Decoded) = value.bytes
    }

    /** Downloads `url` and decodes it at the smallest power-of-two reduction that still covers `size`. Null on failure. */
    suspend fun load(url: String, size: IntSize): Decoded? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val bytes = try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
                ?: return@runCatching null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= size.width && o.outHeight / (sample * 2) >= size.height) sample *= 2
            // Decoded straight into GPU memory here, off the main thread: a plain bitmap is uploaded by the RenderThread
            // on the frame it first shows (a full-screen backdrop stalled that frame on a TV). Plain if a decoder can't.
            fun decode(hw: Boolean) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample; if (hw) inPreferredConfig = Bitmap.Config.HARDWARE
            })
            (runCatching { decode(true) }.getOrNull() ?: decode(false))?.let { Decoded(it, sample == 1) }
        }.getOrNull()?.also { cache.put(url, it) }
    }
}
