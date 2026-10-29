package com.mux.gallery

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.exifinterface.media.ExifInterface

fun shareMedia(ctx: Context, items: List<Media>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    intent.type = when {
        items.none { it.isVideo } -> "image/*"
        items.all { it.isVideo } -> "video/*"
        else -> "*/*"
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(intent, "Share")) } catch (e: Exception) { }
}

fun setAs(ctx: Context, m: Media) {
    val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
        setDataAndType(m.uri, m.mime)
        putExtra("mimeType", m.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try { ctx.startActivity(Intent.createChooser(intent, "Set as")) } catch (e: Exception) { }
}

/**
 * Deletes media. Android 11+ shows the system confirmation (result comes back through [launcher]).
 * Returns true when everything was already deleted (older Android versions).
 */
fun requestDelete(ctx: Context, items: List<Media>, launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
    if (items.isEmpty()) return true
    if (Build.VERSION.SDK_INT >= 30) {
        val pi = MediaStore.createDeleteRequest(ctx.contentResolver, items.map { it.uri })
        launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        return false
    }
    return try {
        items.forEach { ctx.contentResolver.delete(it.uri, null, null) }
        true
    } catch (e: SecurityException) {
        if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
            launcher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
        }
        false
    }
}

/** Details shown in the INFO panel. */
fun readInfo(ctx: Context, m: Media): List<Pair<String, String>> {
    val l = ArrayList<Pair<String, String>>()
    l += "NAME" to m.name
    l += "DATE" to fmtDate(m.dateMs)
    l += "SIZE" to fmtSize(m.size)
    if (m.w > 0 && m.h > 0) l += "RESOLUTION" to "${m.w} x ${m.h}  (%.1f MP)".format(m.w * m.h / 1_000_000.0)
    if (m.isVideo) l += "LENGTH" to fmtDur(m.durMs)
    l += "TYPE" to m.mime
    l += "PATH" to m.path
    if (!m.isVideo) {
        try {
            ctx.contentResolver.openInputStream(m.uri)?.use { s ->
                val e = ExifInterface(s)
                val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ")
                if (cam.isNotBlank()) l += "CAMERA" to cam
                e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { l += "APERTURE" to "f/$it" }
                e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { l += "EXPOSURE" to "${it}s" }
                e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { l += "ISO" to it }
                e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { l += "FOCAL LENGTH" to it }
            }
        } catch (e: Exception) { }
    }
    return l
}
