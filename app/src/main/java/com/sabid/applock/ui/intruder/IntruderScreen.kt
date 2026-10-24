package com.sabid.applock.ui.intruder

import com.sabid.applock.ui.theme.glass
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.IntruderLog
import com.sabid.applock.core.model.IntruderViewMode
import com.sabid.applock.core.util.AppInfoLoader
import com.sabid.applock.core.util.IntruderPhotos
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Everyone who got the PIN wrong, with their photo.
 *
 * Three things here are less obvious than they look. Thumbnails come from
 * [IntruderPhotos] rather than being decoded inline, because decoding a full camera frame per
 * row is what made this screen crawl. Tapping opens [PhotoViewer], which is a composable in
 * this tree rather than a dialog or an activity, so the photo can never end up in a window
 * without AppLock's FLAG_SECURE. And the layout is pinchable as well as switchable, because a
 * gallery that only offers a toggle is a gallery people pinch at anyway.
 */
@Composable
fun IntruderScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = AppLockApp.get()
    val logs by app.repo.intruderLogs.collectAsState(initial = emptyList())
    val settings by app.settings.flow.collectAsState(initial = app.snapshot)

    val mode = settings.intruderViewMode
    var openId by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun setMode(next: IntruderViewMode) {
        if (next != mode) scope.launch { app.settings.setIntruderViewMode(next) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(
                count = logs.size,
                retention = if (settings.intruderAutoDelete) settings.intruderRetentionDays else null,
                mode = mode,
                onMode = ::setMode,
                onClear = { confirmClear = true }
            )

            if (logs.isEmpty()) {
                Empty()
            } else {
                val pinch = Modifier.pinchToSwitch(
                    onSpread = { setMode(mode.sparser()) },
                    onSqueeze = { setMode(mode.denser()) }
                )
                when (mode) {
                    IntruderViewMode.LIST -> LazyColumn(
                        modifier = pinch,
                        contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(logs, key = { it.id }) { log ->
                            IntruderRow(log) { openId = log.id }
                        }
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(mode.columns),
                        modifier = pinch,
                        contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(logs, key = { it.id }) { log ->
                            IntruderTile(log) { openId = log.id }
                        }
                    }
                }
            }
        }

        // Resolved from the live list every recomposition rather than held as state, so
        // deleting the open photo closes the viewer instead of leaving a stale copy of a
        // deleted row on screen.
        val startIndex = openId?.let { id -> logs.indexOfFirst { it.id == id } } ?: -1
        if (openId != null && startIndex >= 0) {
            PhotoViewer(
                logs = logs,
                startIndex = startIndex,
                onClose = { openId = null },
                onToggleKeep = { l ->
                    scope.launch { app.repo.setIntruderKeepForever(l.id, !l.keepForever) }
                },
                onDelete = { l -> scope.launch { IntruderPhotos.delete(context, l) } }
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all photos?") },
            text = {
                Text(
                    "Every intruder photo is deleted from this device, including any you " +
                        "have pinned. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { IntruderPhotos.clearAll(context) }
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Header(
    count: Int,
    retention: Int?,
    mode: IntruderViewMode,
    onMode: (IntruderViewMode) -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(20.dp, 12.dp, 12.dp, 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Intruders",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    when {
                        count == 0 -> "Photos taken after repeated wrong attempts"
                        retention == null -> "$count photo${if (count == 1) "" else "s"} · kept until you delete them"
                        else -> "$count photo${if (count == 1) "" else "s"} · deleted after $retention days"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (count > 0) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete all photos",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (count > 0) {
            Spacer(Modifier.height(12.dp))
            ViewModePicker(mode, onMode)
        }
    }
}

/** Segmented control over the three layouts. Pinching does the same thing. */
@Composable
private fun ViewModePicker(mode: IntruderViewMode, onMode: (IntruderViewMode) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeChip(Icons.Default.ViewAgenda, "List view", mode == IntruderViewMode.LIST) {
            onMode(IntruderViewMode.LIST)
        }
        ModeChip(Icons.Default.GridView, "Large grid", mode == IntruderViewMode.GRID_LARGE) {
            onMode(IntruderViewMode.GRID_LARGE)
        }
        ModeChip(Icons.Outlined.ViewModule, "Small grid", mode == IntruderViewMode.GRID_SMALL) {
            onMode(IntruderViewMode.GRID_SMALL)
        }
    }
}

@Composable
private fun ModeChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun Empty() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "No one has tried to get in.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "If someone gets your PIN wrong too many times, a front-camera photo is " +
                    "saved here — private to AppLock, and never in your gallery.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun IntruderRow(log: IntruderLog, onOpen: () -> Unit) {
    val context = LocalContext.current
    val label by produceState(log.targetPackage, log.targetPackage) {
        value = AppInfoLoader.label(context, log.targetPackage)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .glass(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(log, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Tried to open $label",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${stamp(log.capturedAt)} · ${log.failedAttempts} failed attempts",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (log.keepForever) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = "Kept forever",
                modifier = Modifier.padding(end = 6.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun IntruderTile(log: IntruderLog, onOpen: () -> Unit) {
    val context = LocalContext.current
    val label by produceState(log.targetPackage, log.targetPackage) {
        value = AppInfoLoader.label(context, log.targetPackage)
    }

    Box(
        Modifier
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(18.dp))
            .glass(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
    ) {
        Thumb(log, Modifier.fillMaxSize())

        // Scrim only behind the caption. A full-tile overlay would dim the face, which is
        // the one thing the owner opened this screen to look at.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))
                )
                .padding(10.dp, 18.dp, 10.dp, 8.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stamp(log.capturedAt),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f)
            )
        }

        if (log.keepForever) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "Kept forever",
                    modifier = Modifier.size(15.dp),
                    tint = Color.White
                )
            }
        }
    }
}

/** The cached thumbnail, or a neutral placeholder while it loads or if the file is gone. */
@Composable
private fun Thumb(log: IntruderLog, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, log.photoFile) {
        value = IntruderPhotos.thumbnail(context, log)?.asImageBitmap()
    }

    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                it,
                contentDescription = "Intruder photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * Full-screen gallery: swipe left/right between photos, pinch-zoom, pan and double-tap.
 *
 * Paging is switched off while a photo is zoomed so panning it cannot be mistaken for a swipe
 * to the next one. The bitmap is loaded at [VIEWER_PX] rather than full camera resolution — a
 * phone screen cannot show 12 megapixels, and decoding them to prove it is how a viewer runs
 * out of memory on exactly the low-heap devices this app is most likely to be running on.
 */
@Composable
private fun PhotoViewer(
    logs: List<IntruderLog>,
    startIndex: Int,
    onClose: () -> Unit,
    onToggleKeep: (IntruderLog) -> Unit,
    onDelete: (IntruderLog) -> Unit
) {
    val context = LocalContext.current
    if (logs.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, logs.size - 1)) { logs.size }
    val log = logs[pager.currentPage.coerceIn(0, logs.size - 1)]
    val label by produceState(log.targetPackage, log.targetPackage) {
        value = AppInfoLoader.label(context, log.targetPackage)
    }
    val hideChrome = com.sabid.applock.ui.theme.LocalHideChrome.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        hideChrome.value = true
        onDispose { hideChrome.value = false }
    }
    var zoomed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
            key = { logs[it].id }
        ) { page ->
            ZoomablePhoto(
                log = logs[page],
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                onTapClose = onClose
            )
        }

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .statusBarsPadding()
                .padding(6.dp, 4.dp, 6.dp, 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${stamp(log.capturedAt)} · ${log.failedAttempts} failed attempts",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            IconButton(onClick = { onToggleKeep(log) }) {
                Icon(
                    if (log.keepForever) Icons.Default.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (log.keepForever) "Stop keeping forever" else "Keep forever",
                    tint = if (log.keepForever) MaterialTheme.colorScheme.primary else Color.White
                )
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete photo", tint = Color.White)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (log.keepForever) {
                Text(
                    "Pinned — never deleted automatically",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(Modifier.height(8.dp))
            }
            if (logs.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${logs.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this photo?") },
            text = { Text("The photo is removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    zoomed = false
                    onDelete(log)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

/** One page of the gallery. Only claims touches for zooming when two fingers are down or already zoomed. */
@Composable
private fun ZoomablePhoto(log: IntruderLog, onZoomChanged: (Boolean) -> Unit, onTapClose: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, log.photoFile) {
        value = IntruderPhotos.full(context, log, VIEWER_PX)?.asImageBitmap()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1.01f) { onZoomChanged(scale > 1.01f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.size > 1
                        if (multi || scale > 1.01f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale <= 1.01f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (scale <= 1.01f) onTapClose() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when (val image = bitmap) {
            null -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
            else -> Image(
                image,
                contentDescription = "Intruder photo",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * Reports two-finger pinches without breaking one-finger scrolling.
 *
 * Watching the [PointerEventPass.Initial] pass is what makes this work: the lazy list is a
 * child, so on the main pass it would already have claimed the drag for scrolling and there
 * would be nothing left to interpret. Events are only consumed once a second finger is down,
 * which is why an ordinary scroll still reaches the list untouched — `detectTransformGestures`
 * would have swallowed those too.
 *
 * One step per gesture. Without that latch, a single slow pinch walks through every layout in
 * turn and settles on whichever the fingers happened to stop at.
 */
private fun Modifier.pinchToSwitch(onSpread: () -> Unit, onSqueeze: () -> Unit): Modifier =
    pointerInput(onSpread, onSqueeze) {
        awaitEachGesture {
            var base = 0f
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.filter { it.pressed }
                if (down.isEmpty()) break
                if (down.size < 2) continue

                val spread = (down[0].position - down[1].position).getDistance()
                if (base == 0f) {
                    // Degenerate on the frame two fingers land on the same spot.
                    if (spread > 1f) base = spread
                    continue
                }

                if (!fired && abs(spread - base) > base * PINCH_FRACTION) {
                    if (spread > base) onSpread() else onSqueeze()
                    fired = true
                }
                down.forEach { it.consume() }
            }
        }
    }

/** How far two fingers must move, relative to their starting gap, to count as a pinch. */
private const val PINCH_FRACTION = 0.28f

/**
 * Longest edge the viewer decodes to. Generous enough that zooming to 6x on a tall phone
 * still shows detail, small enough that the bitmap stays a few megabytes.
 */
private const val VIEWER_PX = 1600

private fun stamp(millis: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(millis))
