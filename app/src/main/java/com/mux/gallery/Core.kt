package com.mux.gallery

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class Media(
    val id: Long, val name: String, val isVideo: Boolean, val dateMs: Long, val size: Long,
    val w: Int, val h: Int, val durMs: Long, val path: String, val mime: String
) {
    val key: String get() = (if (isVideo) "v" else "i") + id
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
        )
    val folder: String get() = path.substringBeforeLast('/', "")
}

fun scanMedia(ctx: Context, videos: Boolean): List<Media> {
    val out = ArrayList<Media>()
    val ip = arrayOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATA, MediaStore.Images.Media.MIME_TYPE
    )
    ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ip, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val taken = c.getLong(2)
            out += Media(
                c.getLong(0), c.getString(1) ?: "Image", false, if (taken > 0) taken else c.getLong(3) * 1000,
                c.getLong(4), c.getInt(5), c.getInt(6), 0L, c.getString(7) ?: "", c.getString(8) ?: "image/*"
            )
        }
    }
    if (videos) {
        val vp = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATA, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        )
        ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vp, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val taken = c.getLong(2)
                out += Media(
                    c.getLong(0), c.getString(1) ?: "Video", true, if (taken > 0) taken else c.getLong(3) * 1000,
                    c.getLong(4), c.getInt(5), c.getInt(6), c.getLong(9), c.getString(7) ?: "", c.getString(8) ?: "video/*"
                )
            }
        }
    }
    return out
}

private val MEDIA_EXT = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
    "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v"
)

/** Asks Android to index media files in already-known folders that are not in the media library yet. */
suspend fun indexNewFiles(ctx: Context, known: List<Media>): Unit = withContext(Dispatchers.IO) {
    val knownPaths = known.mapTo(HashSet()) { it.path }
    val files = known.map { it.folder }.toSet().flatMap { dir ->
        File(dir).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXT && it.absolutePath !in knownPaths }
            ?.map { it.absolutePath }
            ?: emptyList()
    }
    if (files.isEmpty()) return@withContext
    val latch = CountDownLatch(files.size)
    MediaScannerConnection.scanFile(ctx, files.toTypedArray(), null) { _, _ -> latch.countDown() }
    latch.await(8, TimeUnit.SECONDS)
    Unit
}

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> "%.2f GB".format(b / 1073741824.0)
    b >= (1L shl 20) -> "%.1f MB".format(b / 1048576.0)
    else -> "${b / 1024} KB"
}

fun fmtDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(ms))

fun dayIndex(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L

fun dayLabel(ms: Long): String {
    val diff = dayIndex(System.currentTimeMillis()) - dayIndex(ms)
    return when (diff) {
        0L -> "TODAY"
        1L -> "YESTERDAY"
        else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms)).uppercase()
    }
}

object Store {
    private lateinit var sp: SharedPreferences
    var accent by mutableIntStateOf(0)
    var columns by mutableIntStateOf(4)
    var sortDesc by mutableStateOf(true)
    var showVideos by mutableStateOf(true)
    var dateHeaders by mutableStateOf(true)
    var slideSec by mutableIntStateOf(3)
    var favs by mutableStateOf<Set<String>>(emptySet())
    var media by mutableStateOf<List<Media>>(emptyList())

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("mux_gallery", Context.MODE_PRIVATE)
        accent = sp.getInt("accent", 0)
        columns = sp.getInt("columns", 4)
        sortDesc = sp.getBoolean("sortDesc", true)
        showVideos = sp.getBoolean("showVideos", true)
        dateHeaders = sp.getBoolean("dateHeaders", true)
        slideSec = sp.getInt("slideSec", 3)
        favs = HashSet(sp.getStringSet("favs", emptySet()) ?: emptySet())
    }

    fun updateAccent(i: Int) { accent = i; sp.edit().putInt("accent", i).apply() }
    fun updateColumns(n: Int) { columns = n; sp.edit().putInt("columns", n).apply() }
    fun updateSortDesc(b: Boolean) { sortDesc = b; sp.edit().putBoolean("sortDesc", b).apply() }
    fun updateShowVideos(b: Boolean) { showVideos = b; sp.edit().putBoolean("showVideos", b).apply() }
    fun updateDateHeaders(b: Boolean) { dateHeaders = b; sp.edit().putBoolean("dateHeaders", b).apply() }
    fun updateSlideSec(n: Int) { slideSec = n; sp.edit().putInt("slideSec", n).apply() }

    /** If every key is already a favorite they are removed, otherwise all are added. */
    fun toggleFav(keys: Collection<String>) {
        val next = HashSet(favs)
        if (keys.all { it in favs }) next.removeAll(keys.toSet()) else next.addAll(keys)
        favs = next
        sp.edit().putStringSet("favs", HashSet(next)).apply()
    }
}
