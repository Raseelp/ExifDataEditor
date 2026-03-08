@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.exifdataeditor

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Bg         = Color(0xFF111318)
private val Surface1   = Color(0xFF1C1F27)
private val Surface2   = Color(0xFF252830)
private val OnSurface  = Color(0xFFE2E5F0)
private val OnSurface2 = Color(0xFF8B90A4)
private val Divider    = Color(0xFF2C303D)
private val Primary    = Color(0xFF5B8DEF)
private val PrimaryDim = Color(0xFF1E2D4D)
private val Danger     = Color(0xFFE05263)
private val DangerDim  = Color(0xFF2D1820)
private val Success    = Color(0xFF34C77B)
private val SuccessDim = Color(0xFF152B1F)
private val Warn       = Color(0xFFE8A030)
private val SelectBg   = Color(0xFF1A2540)

private enum class Filter(val label: String) { ALL("All"), MISMATCH("Issues"), OK("Clean") }
private enum class GridMode(val columns: Int) { ONE(1), TWO(2), THREE(3) }
private data class PendingFix(val items: List<MediaItem>, val exifDate: Long?)

@Composable
fun ImageListScreen(context: Context = LocalContext.current) {

    val scope = rememberCoroutineScope()

    var threshold          by remember { mutableStateOf<MismatchThreshold>(MismatchThreshold.default) }
    var images             by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selectedFilter     by remember { mutableStateOf(Filter.ALL) }
    var expandedUri        by remember { mutableStateOf<String?>(null) }
    var gridMode           by remember { mutableStateOf(GridMode.ONE) }
    var showThresholdSheet by remember { mutableStateOf(false) }
    var selectedUris       by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isSelectMode       = selectedUris.isNotEmpty()

    var pendingFix         by remember { mutableStateOf<PendingFix?>(null) }
    var showDatePicker     by remember { mutableStateOf(false) }
    var pickerItems        by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var pickerPresetMillis by remember { mutableStateOf<Long?>(null) }

    var isScanning        by remember { mutableStateOf(false) }
    var isFixing          by remember { mutableStateOf(false) }
    var fixResultMsg      by remember { mutableStateOf<String?>(null) }
    var progressText      by remember { mutableStateOf<String?>(null) }
    var progressValue     by remember { mutableStateOf<Float?>(null) }
    var progressCurrent   by remember { mutableStateOf(0) }
    var progressTotal     by remember { mutableStateOf(0) }
    var pendingGrantItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var pendingGrantDate  by remember { mutableStateOf<Long?>(null) }
    val snackbarState      = remember { SnackbarHostState() }

    val writeGrantLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingGrantItems.isNotEmpty()) {
            val grantedItems  = pendingGrantItems
            val grantedDate   = pendingGrantDate
            pendingGrantItems = emptyList()
            pendingGrantDate  = null
            scope.launch {
                isFixing        = true
                progressText    = "Applying changes…"
                progressValue   = null
                progressCurrent = 0
                progressTotal   = grantedItems.size
                val fixed = withContext(Dispatchers.IO) {
                    val count = DateFixer.fixAllWithGrant(context, grantedItems, grantedDate) { progress ->
                        scope.launch(Dispatchers.Main) {
                            when (progress) {
                                is FixProgress.Processing -> {
                                    progressCurrent = progress.current
                                    progressTotal   = progress.total
                                    progressText    = "Updating ${progress.current} of ${progress.total}"
                                    progressValue   = progress.current.toFloat() / progress.total
                                }
                                FixProgress.Rescanning -> {
                                    progressText  = "Refreshing library…"
                                    progressValue = null
                                }
                                else -> {}
                            }
                        }
                    }
                    grantedItems.forEach { item ->
                        try { context.contentResolver.notifyChange(Uri.parse(item.uri), null) }
                        catch (e: Exception) {}
                    }
                    Thread.sleep(800)
                    count
                }
                isScanning      = true
                images          = withContext(Dispatchers.IO) { MediaScanner(context).scanImages(threshold) }
                isScanning      = false
                selectedUris    = emptySet()
                progressText    = null
                progressValue   = null
                progressCurrent = 0
                progressTotal   = 0
                isFixing        = false
                fixResultMsg    = if (fixed > 0)
                    "Updated $fixed photo${if (fixed != 1) "s" else ""}"
                else
                    "Could not update — check permissions and try again"
            }
        } else {
            pendingGrantItems = emptyList()
            pendingGrantDate  = null
            fixResultMsg      = "Permission denied"
        }
    }

    LaunchedEffect(threshold) {
        isScanning     = true
        images         = withContext(Dispatchers.IO) { MediaScanner(context).scanImages(threshold) }
        isScanning     = false
        expandedUri    = null
        selectedFilter = Filter.ALL
        selectedUris   = emptySet()
    }

    LaunchedEffect(fixResultMsg) {
        fixResultMsg?.let { snackbarState.showSnackbar(it); fixResultMsg = null }
    }

    fun runFix(items: List<MediaItem>, newDate: Long?) {
        if (items.isEmpty()) return
        scope.launch {
            isFixing        = true
            progressText    = "Preparing…"
            progressValue   = null
            progressCurrent = 0
            progressTotal   = items.size

            val batchResult = withContext(Dispatchers.IO) {
                DateFixer.fixAll(context, items, newDate) { progress ->
                    scope.launch(Dispatchers.Main) {
                        when (progress) {
                            FixProgress.Preparing -> {
                                progressText    = "Preparing…"
                                progressValue   = null
                                progressCurrent = 0
                            }
                            FixProgress.RequestingPermission -> {
                                progressText  = "Waiting for permission…"
                                progressValue = null
                            }
                            is FixProgress.Processing -> {
                                progressCurrent = progress.current
                                progressTotal   = progress.total
                                progressText    = "Updating ${progress.current} of ${progress.total}"
                                progressValue   = progress.current.toFloat() / progress.total
                            }
                            FixProgress.Rescanning -> {
                                progressText  = "Refreshing library…"
                                progressValue = null
                            }
                            FixProgress.Completed -> {
                                progressText  = "Done"
                                progressValue = 1f
                            }
                            is FixProgress.Error -> {
                                progressText  = "Error: ${progress.message}"
                                progressValue = null
                            }
                        }
                    }
                }
            }

            if (batchResult.needsGrant.isNotEmpty()) {
                val needsGrantUriStrings = batchResult.needsGrant.map { it.toString() }.toSet()
                pendingGrantItems = items.filter { it.uri in needsGrantUriStrings }
                pendingGrantDate  = newDate
                isFixing          = false
                DateFixer.requestWriteGrant(context, batchResult.needsGrant, writeGrantLauncher)
                if (batchResult.fixed > 0) {
                    isScanning = true
                    images = withContext(Dispatchers.IO) {
                        Thread.sleep(500)
                        MediaScanner(context).scanImages(threshold)
                    }
                    isScanning = false
                }
            } else {
                withContext(Dispatchers.IO) {
                    items.forEach { item ->
                        try { context.contentResolver.notifyChange(Uri.parse(item.uri), null) }
                        catch (e: Exception) {}
                    }
                    Thread.sleep(500)
                }
                isScanning      = true
                images          = withContext(Dispatchers.IO) { MediaScanner(context).scanImages(threshold) }
                isScanning      = false
                selectedUris    = emptySet()
                progressText    = null
                progressValue   = null
                progressCurrent = 0
                progressTotal   = 0
                isFixing        = false
                fixResultMsg    = buildResultMessage(batchResult)
            }
        }
    }

    fun requestFix(items: List<MediaItem>) {
        if (items.isEmpty()) return
        pendingFix = PendingFix(items, if (items.size == 1) items.first().dateTaken else null)
    }

    val filtered = remember(images, selectedFilter) {
        when (selectedFilter) {
            Filter.ALL      -> images
            Filter.MISMATCH -> images.filter { it.hasMismatch }
            Filter.OK       -> images.filter { !it.hasMismatch }
        }
    }
    val mismatchImages = remember(images) { images.filter { it.hasMismatch } }

    pendingFix?.let { fix ->
        DateActionDialog(
            isSingleItem = fix.items.size == 1,
            exifDate     = fix.exifDate,
            onUseExif    = { pendingFix = null; runFix(fix.items, null) },
            onPickCustom = {
                pendingFix         = null
                pickerItems        = fix.items
                pickerPresetMillis = fix.exifDate
                showDatePicker     = true
            },
            onDismiss = { pendingFix = null }
        )
    }

    if (showDatePicker) {
        DateTimePickerDialog(
            context       = context,
            initialMillis = pickerPresetMillis,
            onConfirm     = { chosenMillis ->
                showDatePicker = false
                val items = pickerItems
                pickerItems        = emptyList()
                pickerPresetMillis = null
                runFix(items, chosenMillis)
            },
            onDismiss = {
                showDatePicker     = false
                pickerItems        = emptyList()
                pickerPresetMillis = null
            }
        )
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost   = { SnackbarHost(hostState = snackbarState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = mismatchImages.isNotEmpty() && !isSelectMode && !isFixing,
                enter   = fadeIn() + slideInVertically { it / 2 },
                exit    = fadeOut()
            ) {
                FloatingActionButton(
                    onClick        = { requestFix(mismatchImages) },
                    containerColor = Danger,
                    contentColor   = Color.White,
                    shape          = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text       = "Fix ${mismatchImages.size} issue${if (mismatchImages.size != 1) "s" else ""}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 14.sp
                        )
                    }
                }
            }
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(Bg)
        ) {
            AppBar(
                imageCount      = images.size,
                mismatchCount   = mismatchImages.size,
                gridMode        = gridMode,
                onGridMode      = { gridMode = it },
                threshold       = threshold,
                onOpenThreshold = { showThresholdSheet = true }
            )

            AnimatedVisibility(visible = isSelectMode, enter = expandVertically(), exit = shrinkVertically()) {
                SelectionBar(
                    selectedCount = selectedUris.size,
                    totalCount    = filtered.size,
                    onClear       = { selectedUris = emptySet() },
                    onSelectAll   = {
                        selectedUris = if (selectedUris.size == filtered.size)
                            emptySet()
                        else
                            filtered.map { it.uri }.toSet()
                    },
                    onAction = { requestFix(images.filter { it.uri in selectedUris }) }
                )
            }

            FilterTabs(
                selected = selectedFilter,
                onSelect = { selectedFilter = it; selectedUris = emptySet() },
                counts   = mapOf(
                    Filter.ALL      to images.size,
                    Filter.MISMATCH to mismatchImages.size,
                    Filter.OK       to images.count { !it.hasMismatch }
                )
            )

            AnimatedVisibility(visible = isScanning && !isFixing) {
                LinearProgressIndicator(
                    modifier   = Modifier.fillMaxWidth().height(2.dp),
                    color      = Primary,
                    trackColor = Divider
                )
            }

            AnimatedVisibility(visible = isFixing) {
                ProgressBanner(
                    text    = progressText,
                    value   = progressValue,
                    current = progressCurrent,
                    total   = progressTotal
                )
            }

            if (filtered.isEmpty() && !isScanning) {
                EmptyState(modifier = Modifier.weight(1f), filter = selectedFilter)
            } else {
                LazyVerticalGrid(
                    columns               = GridCells.Fixed(gridMode.columns),
                    modifier              = Modifier.weight(1f),
                    contentPadding        = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement   = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(filtered, key = { _, it -> it.uri }) { index, image ->
                        AnimatedVisibility(
                            visible = true,
                            enter   = fadeIn(tween(250, delayMillis = (index * 30).coerceAtMost(300))) +
                                    slideInVertically(tween(250, delayMillis = (index * 30).coerceAtMost(300))) { it / 6 }
                        ) {
                            PhotoCard(
                                image       = image,
                                expanded    = expandedUri == image.uri,
                                compact     = gridMode.columns > 1,
                                threshold   = threshold,
                                selected    = image.uri in selectedUris,
                                selectMode  = isSelectMode,
                                onToggle    = {
                                    if (isSelectMode) {
                                        selectedUris = if (image.uri in selectedUris)
                                            selectedUris - image.uri
                                        else
                                            selectedUris + image.uri
                                    } else {
                                        expandedUri = if (expandedUri == image.uri) null else image.uri
                                    }
                                },
                                onLongPress = {
                                    selectedUris = if (image.uri in selectedUris)
                                        selectedUris - image.uri
                                    else
                                        selectedUris + image.uri
                                },
                                onFix = { requestFix(listOf(image)) }
                            )
                        }
                    }
                }
            }
        }

        if (showThresholdSheet) {
            ThresholdSheet(
                current   = threshold,
                onSelect  = { threshold = it; showThresholdSheet = false },
                onDismiss = { showThresholdSheet = false }
            )
        }
    }
}

@Composable
private fun AppBar(
    imageCount:      Int,
    mismatchCount:   Int,
    gridMode:        GridMode,
    onGridMode:      (GridMode) -> Unit,
    threshold:       MismatchThreshold,
    onOpenThreshold: () -> Unit
) {
    Surface(color = Surface1, tonalElevation = 0.dp) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text("EXIF Inspector", color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (imageCount > 0) {
                        Spacer(Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text("$imageCount photos", color = OnSurface2, fontSize = 13.sp)
                            if (mismatchCount > 0) {
                                Text("·", color = OnSurface2, fontSize = 13.sp)
                                Text(
                                    "$mismatchCount need attention",
                                    color      = Danger,
                                    fontSize   = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    GridToggle(current = gridMode, onSelect = onGridMode)
                    IconButton(
                        onClick  = onOpenThreshold,
                        modifier = Modifier.size(20.dp).background(Surface1, RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OnSurface2, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Divider, thickness = 1.dp)
}

@Composable
private fun GridToggle(current: GridMode, onSelect: (GridMode) -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = Surface2) {
        Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(GridMode.ONE to "▬", GridMode.TWO to "⊞", GridMode.THREE to "⊟").forEach { (mode, icon) ->
                val active = mode == current
                Box(
                    modifier = Modifier
                        .background(if (active) Primary.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(7.dp))
                        .clickable { onSelect(mode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = icon,
                        fontSize   = 14.sp,
                        color      = if (active) Primary else OnSurface2,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    totalCount:    Int,
    onClear:       () -> Unit,
    onSelectAll:   () -> Unit,
    onAction:      () -> Unit
) {
    val allSelected = selectedCount == totalCount
    Surface(color = PrimaryDim) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = OnSurface, modifier = Modifier.size(18.dp))
                }
                Text("$selectedCount selected", color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        text       = if (allSelected) "Deselect all" else "Select all",
                        color      = Primary,
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Button(
                onClick        = onAction,
                colors         = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                shape          = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Change Date", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FilterTabs(selected: Filter, onSelect: (Filter) -> Unit, counts: Map<Filter, Int>) {
    Surface(color = Surface1) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Filter.entries.forEach { filter ->
                val active = filter == selected
                val count  = counts[filter] ?: 0
                val bgColor = when {
                    active && filter == Filter.MISMATCH -> Danger.copy(alpha = 0.15f)
                    active                              -> Primary.copy(alpha = 0.12f)
                    else                                -> Surface2
                }
                val textColor = when {
                    active && filter == Filter.MISMATCH -> Danger
                    active                              -> Primary
                    else                                -> OnSurface2
                }
                Surface(shape = RoundedCornerShape(20.dp), color = bgColor, modifier = Modifier.clickable { onSelect(filter) }) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(filter.label, color = textColor, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        if (count > 0) {
                            Surface(shape = CircleShape, color = textColor.copy(alpha = 0.15f)) {
                                Text(
                                    text       = count.toString(),
                                    color      = textColor,
                                    fontSize   = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Divider, thickness = 1.dp)
}

@Composable
private fun ProgressBanner(text: String?, value: Float?, current: Int, total: Int) {
    Surface(color = SuccessDim) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text ?: "Working…", color = Success, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (total > 0 && current > 0) {
                    Text("$current / $total", color = Success.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (value != null) {
                LinearProgressIndicator(
                    progress   = value,
                    modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color      = Success,
                    trackColor = Success.copy(alpha = 0.2f)
                )
            } else {
                LinearProgressIndicator(
                    modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color      = Success,
                    trackColor = Success.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoCard(
    image:      MediaItem,
    expanded:   Boolean,
    compact:    Boolean,
    threshold:  MismatchThreshold,
    selected:   Boolean,
    selectMode: Boolean,
    onToggle:   () -> Unit,
    onLongPress: () -> Unit,
    onFix:      () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue   = when {
            selected          -> Primary
            image.hasMismatch -> Danger.copy(alpha = 0.5f)
            else              -> Divider
        },
        animationSpec = tween(150),
        label         = "border"
    )
    val borderWidth: Dp by animateDpAsState(
        targetValue   = if (selected) 2.dp else 1.dp,
        animationSpec = tween(150),
        label         = "borderWidth"
    )
    val thumbHeight: Dp = if (compact) 120.dp else 200.dp

    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = if (image.hasMismatch && !selected) DangerDim else Surface1,
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(thumbHeight)) {
                AsyncImage(
                    model              = Uri.parse(image.uri),
                    contentDescription = image.name,
                    modifier           = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale       = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)),
                            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        )
                )

                if (selected) {
                    Box(modifier = Modifier.fillMaxSize().background(Primary.copy(alpha = 0.3f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)))
                    Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), shape = CircleShape, color = Primary) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.padding(4.dp).size(16.dp))
                    }
                }

                if (image.hasMismatch && !selected) {
                    Surface(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(6.dp), color = Danger) {
                        Text(
                            text       = if (compact) "!" else "Date mismatch",
                            color      = Color.White,
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text       = image.name,
                    color      = Color.White,
                    fontSize   = if (compact) 11.sp else 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 10.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)
            ) {
                if (!compact || expanded) {
                    MetaRow(icon = Icons.Outlined.DateRange, label = "Taken", value = formatDate(image.dateTaken), tint = Primary, small = compact)
                    MetaRow(icon = Icons.Outlined.Edit, label = "Modified", value = formatDate(image.dateModified), tint = if (image.hasMismatch) Danger else OnSurface2, small = compact)
                } else {
                    Text(formatDateShort(image.dateTaken), color = OnSurface2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (image.hasMismatch && expanded) {
                    HorizontalDivider(color = Divider)
                    MismatchBanner(compact = compact, threshold = threshold)
                }

                if (!selectMode) {
                    Spacer(Modifier.height(2.dp))
                    OutlinedButton(
                        onClick        = onFix,
                        modifier       = Modifier.fillMaxWidth(),
                        colors         = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                        border         = BorderStroke(1.dp, Primary.copy(alpha = 0.5f)),
                        shape          = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compact) 5.dp else 7.dp)
                    ) {
                        Text("Change Date", fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (image.hasMismatch && !selectMode) {
                    val arrowRotation by animateFloatAsState(
                        targetValue   = if (expanded) 180f else 0f,
                        animationSpec = tween(200),
                        label         = "arrow"
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (expanded) "Hide details" else "Show details", color = OnSurface2, fontSize = 11.sp)
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = OnSurface2, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = arrowRotation })
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(
    icon:  androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    tint:  Color,
    small: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (small) 13.dp else 15.dp))
        Text(label, color = OnSurface2, fontSize = if (small) 11.sp else 12.sp, modifier = Modifier.width(if (small) 46.dp else 58.dp))
        Text(value, color = OnSurface, fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MismatchBanner(compact: Boolean, threshold: MismatchThreshold) {
    Surface(shape = RoundedCornerShape(8.dp), color = Danger.copy(alpha = 0.08f)) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.Top
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Danger, modifier = Modifier.size(14.dp).padding(top = 1.dp))
            Text(
                text       = if (compact) "Date mismatch detected"
                else "Timestamps differ by more than ${threshold.label}. The file may have been copied or edited.",
                color      = Danger,
                fontSize   = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, filter: Filter) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (filter == Filter.MISMATCH) "✓" else "📷", fontSize = 40.sp)
            Text(
                text       = when (filter) {
                    Filter.MISMATCH -> "No date issues found"
                    Filter.OK       -> "No clean photos in this view"
                    Filter.ALL      -> "No photos found"
                },
                color      = OnSurface,
                fontSize   = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text    = when (filter) {
                    Filter.MISMATCH -> "All your photos have consistent dates"
                    else            -> "Try a different filter"
                },
                color   = OnSurface2,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun DateActionDialog(
    isSingleItem: Boolean,
    exifDate:     Long?,
    onUseExif:    () -> Unit,
    onPickCustom: () -> Unit,
    onDismiss:    () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.9f).wrapContentHeight(), shape = RoundedCornerShape(16.dp), color = Surface2, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Set date", color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text       = if (isSingleItem) "How would you like to update this photo's modified date?"
                    else "How would you like to update the modified dates for these photos?",
                    color      = OnSurface2,
                    fontSize   = 13.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(20.dp))
                ActionOption(
                    title    = "Use original EXIF date",
                    subtitle = if (exifDate != null) formatDate(exifDate) else "Restore each photo's capture date",
                    color    = Primary,
                    onClick  = onUseExif
                )
                Spacer(Modifier.height(10.dp))
                ActionOption(title = "Pick a custom date", subtitle = "Choose any date and time", color = Warn, onClick = onPickCustom)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = OnSurface2)
                }
            }
        }
    }
}

@Composable
private fun ActionOption(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Surface(
        shape    = RoundedCornerShape(10.dp),
        color    = color.copy(alpha = 0.07f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier              = Modifier
                .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = OnSurface2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DateTimePickerDialog(
    context:       Context,
    initialMillis: Long?,
    onConfirm:     (Long) -> Unit,
    onDismiss:     () -> Unit
) {
    val initCal = remember {
        Calendar.getInstance().apply {
            if (initialMillis != null && initialMillis > 0) timeInMillis = initialMillis
        }
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initCal.timeInMillis)
    val timePickerState = rememberTimePickerState(
        initialHour   = initCal.get(Calendar.HOUR_OF_DAY),
        initialMinute = initCal.get(Calendar.MINUTE),
        is24Hour      = true
    )
    var showTimePicker by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = if (showTimePicker) ({ showTimePicker = false }) else onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxWidth(0.95f).wrapContentHeight(), shape = RoundedCornerShape(16.dp), color = Surface2, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                if (!showTimePicker) {
                    Text("Select date", color = OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
                    DatePicker(
                        state  = datePickerState,
                        colors = DatePickerDefaults.colors(
                            containerColor                    = Surface2,
                            titleContentColor                 = OnSurface2,
                            headlineContentColor              = OnSurface,
                            weekdayContentColor               = OnSurface2,
                            subheadContentColor               = OnSurface2,
                            navigationContentColor            = OnSurface,
                            yearContentColor                  = OnSurface,
                            currentYearContentColor           = Primary,
                            selectedYearContentColor          = Color.White,
                            selectedYearContainerColor        = Primary,
                            dayContentColor                   = OnSurface,
                            selectedDayContentColor           = Color.White,
                            selectedDayContainerColor         = Primary,
                            todayContentColor                 = Primary,
                            todayDateBorderColor              = Primary,
                            dayInSelectionRangeContentColor   = OnSurface,
                            dayInSelectionRangeContainerColor = Primary.copy(alpha = 0.2f)
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurface2) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { if (datePickerState.selectedDateMillis != null) showTimePicker = true },
                            enabled = datePickerState.selectedDateMillis != null,
                            colors  = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape   = RoundedCornerShape(8.dp)
                        ) {
                            Text("Next", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    val selectedDateMillis = datePickerState.selectedDateMillis ?: 0L
                    val previewCal = Calendar.getInstance().apply {
                        timeInMillis = selectedDateMillis
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    Text("Select time", color = OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 2.dp))
                    Text(formatDate(previewCal.timeInMillis), color = Primary, fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp, bottom = 16.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TimePicker(
                            state  = timePickerState,
                            colors = TimePickerDefaults.colors(
                                clockDialColor                       = Surface1,
                                clockDialSelectedContentColor        = Color.White,
                                clockDialUnselectedContentColor      = OnSurface2,
                                selectorColor                        = Primary,
                                containerColor                       = Surface2,
                                periodSelectorBorderColor            = Divider,
                                timeSelectorSelectedContainerColor   = Primary.copy(alpha = 0.2f),
                                timeSelectorUnselectedContainerColor = Surface1,
                                timeSelectorSelectedContentColor     = Primary,
                                timeSelectorUnselectedContentColor   = OnSurface2
                            )
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showTimePicker = false }) { Text("Back", color = OnSurface2) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onConfirm(previewCal.timeInMillis) },
                            colors  = ButtonDefaults.buttonColors(containerColor = Success),
                            shape   = RoundedCornerShape(8.dp)
                        ) {
                            Text("Apply", color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThresholdSheet(current: MismatchThreshold, onSelect: (MismatchThreshold) -> Unit, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onDismiss))
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = false) {}, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = Surface2) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                Box(modifier = Modifier.width(32.dp).height(3.dp).background(Divider, RoundedCornerShape(2.dp)).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(20.dp))
                Text("Sensitivity", color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Flag photos where EXIF date and file date differ by more than:", color = OnSurface2, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(16.dp))
                MismatchThreshold.all.forEach { option ->
                    ThresholdRow(option = option, selected = option::class == current::class, onSelect = { onSelect(option) })
                    if (option != MismatchThreshold.all.last()) Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ThresholdRow(option: MismatchThreshold, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape    = RoundedCornerShape(10.dp),
        color    = if (selected) Primary.copy(alpha = 0.08f) else Surface1,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) Primary.copy(alpha = 0.4f) else Divider, RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.label, color = if (selected) Primary else OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(option.description, color = OnSurface2, fontSize = 12.sp)
            }
            RadioButton(
                selected = selected,
                onClick  = onSelect,
                colors   = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = Divider)
            )
        }
    }
}

private fun buildResultMessage(result: DateFixer.BatchResult): String = when {
    result.fixed == 0 && result.needsGrant.isEmpty() -> "Dates are already up to date"
    result.needsGrant.isNotEmpty()                   -> "Updated ${result.fixed}, permission needed for ${result.needsGrant.size} more"
    result.fixed == result.total                     -> "Updated ${result.fixed} photo${if (result.fixed != 1) "s" else ""}"
    else                                             -> "Updated ${result.fixed} of ${result.total} photos"
}

fun formatDate(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "Unknown"
    return SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(timestamp))
}

fun formatDateShort(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "Unknown"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun animateColorAsState(
    targetValue:   Color,
    animationSpec: AnimationSpec<Color> = tween(150),
    label:         String               = ""
): State<Color> = animateValueAsState(
    targetValue   = targetValue,
    typeConverter = TwoWayConverter(
        convertToVector   = { AnimationVector4D(it.red, it.green, it.blue, it.alpha) },
        convertFromVector = { Color(it.v1, it.v2, it.v3, it.v4) }
    ),
    animationSpec = animationSpec,
    label         = label
)