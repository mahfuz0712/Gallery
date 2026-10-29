package com.mux.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

sealed interface GridRow
data class RowHead(val day: Long, val label: String) : GridRow
data class RowCell(val m: Media, val idx: Int) : GridRow

private fun buildRows(list: List<Media>, headers: Boolean): List<GridRow> {
    val out = ArrayList<GridRow>(list.size + 64)
    var lastDay = Long.MIN_VALUE
    list.forEachIndexed { i, m ->
        if (headers) {
            val d = dayIndex(m.dateMs)
            if (d != lastDay) { out += RowHead(d, dayLabel(m.dateMs)); lastDay = d }
        }
        out += RowCell(m, i)
    }
    return out
}

@Composable
fun ConfirmDialog(title: String, text: String, okLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF050D12)).border(1.dp, cMain).padding(20.dp)) {
            T("// CONFIRM", 12f, cSub)
            T(title, 17f, cMain, true, Modifier.padding(top = 6.dp), 2)
            T(text, 12f, cSub, mod = Modifier.padding(top = 10.dp), lines = 4)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("CANCEL", false, Modifier.weight(1f), onCancel)
                Chip(okLabel, true, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
private fun MediaCell(
    m: Media, idx: Int, selected: Boolean, selecting: Boolean, fav: Boolean,
    onClick: (Int) -> Unit, onLong: (Int) -> Unit
) {
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLong)
    Box(
        Modifier.aspectRatio(1f).padding(1.dp).pointerInput(m.key, idx) {
            detectTapGestures(onTap = { click(idx) }, onLongPress = { long(idx) })
        }
    ) {
        Thumb(m, 320, Modifier.fillMaxSize())
        if (m.isVideo) {
            T(
                "VID " + fmtDur(m.durMs), 10f, Color.White,
                mod = Modifier.align(Alignment.BottomStart).padding(3.dp).background(Color(0xAA000000)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        if (fav) T("*", 18f, cMain, true, Modifier.align(Alignment.TopEnd).padding(horizontal = 6.dp))
        if (selected) {
            Box(Modifier.fillMaxSize().background(cMain.copy(alpha = 0.28f)).border(2.dp, cMain))
            T("[X]", 12f, cMain, true, Modifier.align(Alignment.TopStart).padding(4.dp))
        } else if (selecting) {
            T("[ ]", 12f, cSub, mod = Modifier.align(Alignment.TopStart).padding(4.dp))
        }
    }
}

/** Timeline grid used by the PHOTOS and FAVS tabs. */
@Composable
fun PhotosScreen(
    folder: String?, favsOnly: Boolean, scanning: Boolean, clearSel: Int,
    onRefresh: () -> Unit, onClearFolder: () -> Unit,
    onOpen: (List<Media>, Int) -> Unit, onDelete: (List<Media>) -> Unit
) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var type by remember { mutableIntStateOf(0) } // 0 all, 1 photos, 2 videos
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(clearSel) { selected = emptySet() }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    val shown = remember(Store.media, Store.favs, folder, favsOnly, q, type, Store.sortDesc) {
        val f = Store.media.filter {
            (folder == null || it.folder == folder) &&
                (!favsOnly || it.key in Store.favs) &&
                (type == 0 || (type == 1 && !it.isVideo) || (type == 2 && it.isVideo)) &&
                (q.isBlank() || it.name.contains(q, true) || it.folder.contains(q, true))
        }
        if (Store.sortDesc) f.sortedByDescending { it.dateMs } else f.sortedBy { it.dateMs }
    }
    val rows = remember(shown, Store.dateHeaders) { buildRows(shown, Store.dateHeaders) }
    val selecting = selected.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF06141A)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                T("[X]", 14f, cMain, true, Modifier.clickable { selected = emptySet() }.padding(8.dp))
                T("${selected.size} SELECTED", 13f, cMain, true, Modifier.weight(1f))
                T("[ALL]", 11f, cMain, mod = Modifier.clickable { selected = shown.map { it.key }.toSet() }.padding(6.dp))
                T("[SHARE]", 11f, cMain, mod = Modifier.clickable { shareMedia(ctx, shown.filter { it.key in selected }) }.padding(6.dp))
                T("[FAV]", 11f, cMain, mod = Modifier.clickable { Store.toggleFav(selected) }.padding(6.dp))
                T("[DEL]", 11f, Color(0xFFFF6B6B), mod = Modifier.clickable { onDelete(shown.filter { it.key in selected }) }.padding(6.dp))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 6.dp).border(1.dp, cDim).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                T("> ", 15f, cMain, true)
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) T("SEARCH_MEDIA...", 14f, cSub)
                    BasicTextField(
                        q, { q = it }, singleLine = true,
                        textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 15.sp),
                        cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                    )
                }
                if (q.isNotEmpty()) T("[X]", 13f, cMain, mod = Modifier.clickable { q = "" })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "PHOTOS", "VIDEOS").forEachIndexed { i, n -> Chip(n, type == i, Modifier.weight(1f)) { type = i } }
                Chip(if (Store.sortDesc) "NEWEST" else "OLDEST", false, Modifier.width(96.dp)) { Store.updateSortDesc(!Store.sortDesc) }
            }
            if (folder != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("ALBUM: ${folder.substringAfterLast('/')}", 12f, cMain, mod = Modifier.weight(1f))
                    T("[CLEAR]", 12f, cMain, mod = Modifier.clickable(onClick = onClearFolder).padding(6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                T(if (scanning) "// SCANNING..." else "// ${shown.size} ITEMS", 11f, cSub, mod = Modifier.weight(1f))
                T("[REFRESH]", 12f, cMain, mod = Modifier.clickable(onClick = onRefresh).padding(6.dp))
            }
        }
        if (shown.isEmpty() && !scanning) {
            T(if (favsOnly) "NO FAVORITES YET. LONG-PRESS ITEMS AND TAP [FAV]." else "NO MEDIA FOUND", 12f, cSub, mod = Modifier.padding(16.dp), lines = 3)
        }
        LazyVerticalGrid(GridCells.Fixed(Store.columns), Modifier.fillMaxSize()) {
            items(
                rows,
                key = { if (it is RowHead) "h${it.day}" else (it as RowCell).m.key },
                span = { if (it is RowHead) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
            ) { r ->
                when (r) {
                    is RowHead -> T(r.label, 12f, cSub, true, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                    is RowCell -> MediaCell(
                        r.m, r.idx, r.m.key in selected, selecting, r.m.key in Store.favs,
                        onClick = { i ->
                            if (selecting) {
                                val k = shown[i].key
                                selected = if (k in selected) selected - k else selected + k
                            } else onOpen(shown, i)
                        },
                        onLong = { i -> selected = selected + shown[i].key }
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumsScreen(onPick: (String) -> Unit) {
    val albums = remember(Store.media) {
        Store.media.groupBy { it.folder }
            .map { (k, v) -> Triple(k, v.size, v.maxByOrNull { it.dateMs }!!) }
            .sortedBy { it.first.substringAfterLast('/').lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        T("// ${albums.size} ALBUMS", 11f, cSub, mod = Modifier.padding(16.dp, 12.dp))
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(albums, key = { it.first }) { (folder, count, cover) ->
                Column(Modifier.padding(6.dp).clickable { onPick(folder) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).brackets(cMain.copy(alpha = 0.7f), 14.dp)) {
                        Thumb(cover, 400, Modifier.fillMaxSize().padding(3.dp))
                    }
                    T(folder.substringAfterLast('/').ifEmpty { "/" }, 13f, Color(0xFFD8F3F7), true, Modifier.padding(top = 6.dp))
                    T("$count items", 11f, cSub)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(scanning: Boolean, onRescan: () -> Unit, onUpdateFound: (UpdateInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    @Composable fun section(t: String) = T("// $t", 12f, cSub, true, Modifier.padding(top = 22.dp, bottom = 8.dp))
    @Composable fun onOff(value: Boolean, set: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("ON", value, Modifier.weight(1f)) { set(true) }
        Chip("OFF", !value, Modifier.weight(1f)) { set(false) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        T("SYSTEM CONFIG", 16f, cMain, true)

        section("INTERFACE COLOR")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ACCENTS.forEachIndexed { i, a -> Chip(a.name, Store.accent == i, Modifier.weight(1f)) { Store.updateAccent(i) } }
        }

        section("GRID COLUMNS")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in listOf(3, 4, 5, 6)) Chip("$n", Store.columns == n, Modifier.weight(1f)) { Store.updateColumns(n) }
        }

        section("SHOW VIDEOS")
        onOff(Store.showVideos) { Store.updateShowVideos(it) }

        section("DATE HEADERS")
        onOff(Store.dateHeaders) { Store.updateDateHeaders(it) }

        section("SLIDESHOW SPEED")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in listOf(2, 3, 5, 10)) Chip("${n}s", Store.slideSec == n, Modifier.weight(1f)) { Store.updateSlideSec(n) }
        }

        section("LIBRARY")
        Chip(if (scanning) "SCANNING..." else "RESCAN LIBRARY", false, Modifier.fillMaxWidth()) { onRescan() }
        T("${Store.media.size} items indexed", 12f, cSub, mod = Modifier.padding(top = 8.dp))

        section("UPDATES")
        Chip("CHECK FOR UPDATES", false, Modifier.fillMaxWidth()) {
            scope.launch {
                status = "CHECKING..."
                if (!isOnline(ctx)) { status = "NO INTERNET CONNECTION"; return@launch }
                val (ok, info) = checkUpdate(ctx)
                status = when {
                    !ok -> "CHECK FAILED"
                    info == null -> "UP TO DATE"
                    else -> "NEW VERSION AVAILABLE: v${info.version}"
                }
                if (info != null) onUpdateFound(info)
            }
        }
        if (status.isNotEmpty()) T(status, 12f, cSub, mod = Modifier.padding(top = 8.dp), lines = 2)

        section("ABOUT")
        T("GALLERY v${appVersion(ctx)}", 13f, cMain, true)
        T("GALLERY CORE: MUX", 11f, cSub, mod = Modifier.padding(top = 4.dp))
        T("SOURCE: LOCAL FILES ONLY", 11f, cSub, mod = Modifier.padding(top = 2.dp, bottom = 10.dp))
        T("DEV: ${AppInfo.DEV_NAME}", 12f, Color(0xFFD8F3F7))
        T("MAIL: ${AppInfo.DEV_EMAIL}", 12f, cMain, mod = Modifier.clickable { openMail(ctx, AppInfo.DEV_EMAIL) }.padding(vertical = 6.dp))
        T("GITHUB: github.com/${AppInfo.GITHUB_USER}", 12f, cMain, mod = Modifier.clickable { openUrl(ctx, "https://github.com/${AppInfo.GITHUB_USER}") }.padding(vertical = 6.dp), lines = 2)

        section("MORE APPS")
        T("zApps Store >>", 13f, cMain, true, Modifier.clickable { openUrl(ctx, AppInfo.MORE_APPS) }.padding(vertical = 6.dp))
        T("zappsstore-frontend.onrender.com", 11f, cSub, lines = 2)
    }
}
