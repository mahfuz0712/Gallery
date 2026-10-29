package com.mux.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Viewer {
    var active by mutableStateOf(false)
    var list by mutableStateOf<List<Media>>(emptyList())
    var start = 0

    fun open(l: List<Media>, index: Int) { list = l; start = index; active = true }
    fun close() { active = false }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = cMain, activeTrackColor = cMain, inactiveTrackColor = cDim)

/** Photo with pinch-to-zoom, pan and double-tap zoom. */
@Composable
private fun ZoomImage(m: Media, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, m.key) { value = Thumbs.full(ctx, m, 2400) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        onZoom(scale > 1f)
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val multi = ev.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val z = ev.calculateZoom()
                            val p = ev.calculatePan()
                            scale = (scale * z).coerceIn(1f, 6f)
                            offset = if (scale > 1f) clamp(offset + p, scale) else Offset.Zero
                            onZoom(scale > 1f)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b, null,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit
            )
        } else T("// LOADING...", 12f, cSub)
    }
}

/** Video inside the viewer: thumbnail + play button, then a basic player. */
@Composable
private fun VideoPage(m: Media, active: Boolean, chrome: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) started = false }
    val exo = remember(started) {
        if (started) ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(m.uri)); prepare(); playWhenReady = true
        } else null
    }
    DisposableEffect(exo) { onDispose { exo?.release() } }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        exo?.addListener(l)
        onDispose { exo?.removeListener(l) }
    }
    LaunchedEffect(exo) {
        while (exo != null) {
            pos = exo.currentPosition
            dur = exo.duration.coerceAtLeast(0L)
            delay(250)
        }
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
        if (exo == null) {
            Thumb(m, 1024, Modifier.fillMaxSize())
        } else {
            AndroidView(
                factory = { c -> PlayerView(c).apply { useController = false; player = exo; keepScreenOn = true } },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (exo == null || chrome) {
            Box(Modifier.size(72.dp).background(Color(0x88000000), CircleShape).border(2.dp, cMain, CircleShape), Alignment.Center) {
                Glyph(if (exo != null && playing) G.PAUSE else G.PLAY, 44.dp, cMain) {
                    if (exo == null) started = true else if (exo.isPlaying) exo.pause() else exo.play()
                }
            }
        }
        if (exo != null && chrome) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 84.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    T(fmtDur(if (drag != null) (drag!! * dur).toLong() else pos), 11f, Color.White)
                    Spacer(Modifier.weight(1f))
                    T(fmtDur(dur), 11f, Color.White)
                }
                val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                Slider(
                    value = drag ?: f, onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { exo.seekTo((it * dur).toLong()) }; drag = null },
                    colors = sliderColors()
                )
            }
        }
    }
}

@Composable
private fun Act(label: String, color: Color = cMain, onClick: () -> Unit) =
    T("[$label]", 11f, color, mod = Modifier.clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 12.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(onDelete: (Media) -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val list = Viewer.list
    val pager = rememberPagerState(initialPage = Viewer.start.coerceIn(0, (list.size - 1).coerceAtLeast(0)), pageCount = { list.size })
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    val cur = list.getOrNull(pager.currentPage)

    // rotate with the phone's auto-rotate setting while viewing
    LaunchedEffect(Unit) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER }
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { a ->
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                WindowCompat.getInsetsController(a.window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // immersive when the controls are hidden or in landscape
    LaunchedEffect(chrome, landscape) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (!chrome || landscape) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    LaunchedEffect(slideshow, pager.currentPage) {
        if (slideshow) {
            val isVid = cur?.isVideo == true
            delay(if (isVid) 400L else Store.slideSec * 1000L)
            if (pager.currentPage < list.size - 1) pager.animateScrollToPage(pager.currentPage + 1) else slideshow = false
        }
    }
    BackHandler(true) { if (showInfo) showInfo = false else Viewer.close() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { list[it].key }) { page ->
            val m = list[page]
            if (m.isVideo) VideoPage(m, pager.currentPage == page, chrome) { chrome = !chrome }
            else ZoomImage(m, onTap = { chrome = !chrome }, onZoom = { zoomed = it })
        }

        if (chrome && cur != null) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x88000000)).statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                T("<", 22f, cMain, true, Modifier.clickable { Viewer.close() }.padding(horizontal = 12.dp, vertical = 4.dp))
                Column(Modifier.weight(1f)) {
                    T(cur.name, 13f, Color.White)
                    T("${pager.currentPage + 1} / ${list.size}   ${fmtDate(cur.dateMs)}", 10f, cSub)
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x88000000)).navigationBarsPadding()
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Act("SHARE") { shareMedia(ctx, listOf(cur)) }
                Act(if (cur.key in Store.favs) "UNFAV" else "FAV") { Store.toggleFav(listOf(cur.key)) }
                Act("INFO") { showInfo = true }
                Act(if (slideshow) "STOP" else "SLIDESHOW") { slideshow = !slideshow; chrome = false }
                if (!cur.isVideo) Act("SET AS") { setAs(ctx, cur) }
                Act("DELETE", Color(0xFFFF6B6B)) { onDelete(cur) }
            }
        }

        if (showInfo && cur != null) {
            val info by produceState<List<Pair<String, String>>>(emptyList(), cur.key) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readInfo(ctx, cur) }
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xEE050D12)).border(1.dp, cMain)
                    .navigationBarsPadding().padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("// FILE INFO", 12f, cSub, true, Modifier.weight(1f))
                    T("[CLOSE]", 11f, cMain, mod = Modifier.clickable { showInfo = false }.padding(6.dp))
                }
                info.forEach { (k, v) ->
                    Row(Modifier.padding(top = 6.dp)) {
                        T(k, 10f, cSub, mod = Modifier.width(96.dp))
                        T(v, 12f, Color(0xFFD8F3F7), lines = 3, mod = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
