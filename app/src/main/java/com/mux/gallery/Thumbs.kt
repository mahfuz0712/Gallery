package com.mux.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Suppress("DEPRECATION")
    suspend fun thumb(ctx: Context, m: Media, px: Int): ImageBitmap? {
        val key = m.key + "_" + px
        cache.get(key)?.let { return it.asImageBitmap() }
        val b: Bitmap? = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(m.uri, Size(px, px), null)
                else if (m.isVideo) ThumbnailUtils.createVideoThumbnail(m.path, MediaStore.Images.Thumbnails.MINI_KIND)
                else decode(ctx, m.uri, px)
            } catch (e: Exception) { null }
        }
        if (b != null) cache.put(key, b)
        return b?.asImageBitmap()
    }

    /** Large version for the full-screen viewer (max side = [maxSide] px, rotated by EXIF). */
    suspend fun full(ctx: Context, m: Media, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        try { decode(ctx, m.uri, maxSide)?.asImageBitmap() } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
    }

    private fun decode(ctx: Context, uri: Uri, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var s = 1
        while (max(bounds.outWidth, bounds.outHeight) / s > target) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val deg = try {
            ctx.contentResolver.openInputStream(uri)?.use { degrees(ExifInterface(it)) } ?: 0
        } catch (e: Exception) { 0 }
        if (deg != 0) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        }
        return bmp
    }

    private fun degrees(e: ExifInterface): Int = when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}

/** Square-cropped thumbnail. */
@Composable
fun Thumb(m: Media, px: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, m.key, px) { value = Thumbs.thumb(ctx, m, px) }
    Box(modifier.background(Color(0xFF06141A))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
