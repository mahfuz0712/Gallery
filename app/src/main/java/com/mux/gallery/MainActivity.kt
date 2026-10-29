package com.mux.gallery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        Store.init(this)
        setContent { App() }
    }
}

enum class Screen(val label: String) { PHOTOS("PHOTOS"), ALBUMS("ALBUMS"), FAVS("FAVS"), SETTINGS("SYS") }

private fun perms(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

private fun hasPerm(c: Context) = ContextCompat.checkSelfPermission(c, perms()[0]) == PackageManager.PERMISSION_GRANTED

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.PHOTOS) }
    var folder by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    var scanning by remember { mutableStateOf(false) }
    var rescan by remember { mutableIntStateOf(0) }
    var deepScan by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var clearSel by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<List<Media>?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerm(ctx) }
    val delLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { Viewer.close(); clearSel++; rescan++ }
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perms()) }

    fun askDelete(items: List<Media>) {
        // Android 11+ shows its own system confirmation, older versions get ours first
        if (Build.VERSION.SDK_INT >= 30) requestDelete(ctx, items, delLauncher) else pendingDelete = items
    }

    LaunchedEffect(granted, Store.showVideos, rescan) {
        if (granted) {
            scanning = true
            if (deepScan) {
                deepScan = false
                indexNewFiles(ctx, Store.media)
            }
            val fresh = withContext(Dispatchers.IO) { scanMedia(ctx, Store.showVideos) }
            if (fresh != Store.media) Store.media = fresh
            scanning = false
        }
    }
    LaunchedEffect(Unit) { if (isOnline(ctx)) update = checkUpdate(ctx).second }

    // Auto refresh when the Android media library changes (new photo, delete, ...)
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val handler = Handler(Looper.getMainLooper())
        val run = Runnable { rescan++ }
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, 1500)
            }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
        onDispose {
            ctx.contentResolver.unregisterContentObserver(obs)
            handler.removeCallbacks(run)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rescan++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        update?.let { u ->
            UpdateDialog(u, appVersion(ctx), onUpdate = { openUrl(ctx, u.apkUrl); update = null }, onCancel = { update = null })
        }
        pendingDelete?.let { items ->
            ConfirmDialog(
                "DELETE ${items.size} ITEM(S)?", "The file(s) will be removed from this device.", "DELETE",
                onConfirm = {
                    pendingDelete = null
                    if (requestDelete(ctx, items, delLauncher)) { Viewer.close(); clearSel++; rescan++ }
                },
                onCancel = { pendingDelete = null }
            )
        }
        HudBackground {
            if (Viewer.active) {
                ViewerScreen(onDelete = { askDelete(listOf(it)) })
            } else {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("GALLERY", 20f, cMain, true)
                            T("// MUX GALLERY CORE", 11f, cSub)
                        }
                        T("[ ${Store.media.size} ]", 11f, cSub)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(cDim))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val needsPerm = screen != Screen.SETTINGS
                        when {
                            !granted && needsPerm -> Column(
                                Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally
                            ) {
                                T("ACCESS DENIED", 18f, cMain, true)
                                T("Photo and video permission is required to show your gallery.", 12f, cSub, mod = Modifier.padding(vertical = 12.dp), lines = 3, align = TextAlign.Center)
                                Chip("GRANT ACCESS", true, Modifier.fillMaxWidth()) { permLauncher.launch(perms()) }
                                Spacer(Modifier.height(8.dp))
                                Chip("OPEN APP SETTINGS", false, Modifier.fillMaxWidth()) {
                                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                                }
                            }
                            screen == Screen.PHOTOS || screen == Screen.FAVS -> PhotosScreen(
                                folder = if (screen == Screen.PHOTOS) folder else null,
                                favsOnly = screen == Screen.FAVS,
                                scanning = scanning, clearSel = clearSel,
                                onRefresh = { deepScan = true; rescan++ },
                                onClearFolder = { folder = null },
                                onOpen = { list, i -> Viewer.open(list, i) },
                                onDelete = { askDelete(it) }
                            )
                            screen == Screen.ALBUMS -> AlbumsScreen { folder = it; screen = Screen.PHOTOS }
                            else -> SettingsScreen(scanning, { deepScan = true; rescan++ }, { update = it })
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Color(0xFF050D12))) {
                        for (s in Screen.entries) {
                            Column(
                                Modifier.weight(1f).clickable { screen = s }.padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                T(s.label, 12f, if (screen == s) cMain else cSub, screen == s)
                                Box(Modifier.padding(top = 4.dp).width(24.dp).height(2.dp).background(if (screen == s) cMain else Color.Transparent))
                            }
                        }
                    }
                }
            }
        }
    }
}
