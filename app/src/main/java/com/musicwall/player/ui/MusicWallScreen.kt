package com.musicwall.player.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicwall.player.NowPlaying
import com.musicwall.player.PlayerUiState
import com.musicwall.player.PlayerViewModel
import com.musicwall.player.Screen
import com.musicwall.player.Source
import com.musicwall.player.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val LandscapeSidePadding = 20.dp
private const val WallControlsTimeoutMs = 3_500L

@Composable
fun MusicWallScreen(
    model: PlayerViewModel,
    audioOutput: StateFlow<String>,
    openOutput: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val output by audioOutput.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var serverSettings by rememberSaveable { mutableStateOf(false) }
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    val appearance = state.theme.appearance
    val spectrum = remember(state.playing.id) { SpectrumMotion() }

    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::importFiles)
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) model.importFolder(it)
    }
    LaunchedEffect(state.notice) {
        val message = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        model.consumeNotice()
    }
    BackHandler(state.wall || state.screen == Screen.Library) {
        when {
            state.wall -> model.setWall(false)
            state.source == Source.Navidrome && state.selectedAlbum != null -> model.openAlbum(null)
            state.source == Source.Navidrome && state.selectedPlaylist != null -> model.openPlaylist(null)
            state.source == Source.Navidrome && state.searchQuery.isNotEmpty() -> model.search("")
            else -> model.show(Screen.Player)
        }
    }
    MaterialTheme(colorScheme = appearance.colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = appearance.colors.background,
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (state.wall) {
                    WallDisplay(state, model, appearance, spectrum, choosingTheme) { choosingTheme = true }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(state.screen, output, model::show, openOutput) { choosingTheme = true }
                        when (state.screen) {
                            Screen.Player -> PlayerDisplay(state, model, appearance, spectrum, Modifier.weight(1f))
                            Screen.Library -> LibraryScreen(
                                state = state,
                                model = model,
                                importFiles = { filesPicker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream")) },
                                importFolder = { folderPicker.launch(null) },
                                openServer = {
                                    model.clearServerError()
                                    serverSettings = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
        }
        if (serverSettings) ServerSettings(state, model) { serverSettings = false }
        if (choosingTheme) {
            AppThemePicker(
                selected = state.theme,
                select = {
                    model.selectAppTheme(it)
                    choosingTheme = false
                },
                dismiss = { choosingTheme = false },
            )
        }
    }
}

@Composable
private fun TopBar(
    screen: Screen,
    output: String,
    changeScreen: (Screen) -> Unit,
    openOutput: () -> Unit,
    openTheme: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((tab, label) in listOf(Screen.Player to "播放中", Screen.Library to "音乐库")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { changeScreen(tab) }) {
                    Text(label, color = if (screen == tab) colors.onSurface else colors.onSurfaceVariant)
                }
                Box(Modifier.width(27.dp).height(2.dp).background(if (screen == tab) colors.primary else Color.Transparent))
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = openTheme) { Text("主题") }
        TextButton(onClick = openOutput, modifier = Modifier.widthIn(max = 128.dp)) {
            Dot()
            Spacer(Modifier.width(6.dp))
            Text(output, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant, fontSize = 12.sp)
        }
    }
    HorizontalDivider(color = colors.outlineVariant)
}

@Composable
private fun PlayerDisplay(
    state: PlayerUiState,
    model: PlayerViewModel,
    appearance: AppAppearance,
    spectrum: SpectrumMotion,
    modifier: Modifier = Modifier,
) {
    val song = state.playing
    var showingDetails by rememberSaveable(song.id) { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth > 530.dp || maxWidth > maxHeight * 1.75f
        if (wide) {
            val compactControls = maxWidth >= 600.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            Column(
                Modifier.fillMaxSize().background(appearance.background)
                    .padding(horizontal = LandscapeSidePadding, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                LandscapePlayer(
                    song, state.artwork, appearance, Modifier.weight(1f).fillMaxWidth(),
                    onDetails = if (song.id.isEmpty()) null else ({ showingDetails = true }),
                )
                if (song.id.isNotEmpty()) {
                    HorizontalDivider(color = appearance.colors.outlineVariant)
                    Transport(song, model, state.playerReady, state.shuffle, compact = compactControls)
                } else {
                    OpenLibraryButton(model)
                }
            }
        } else {
            PortraitPlayer(state, model, appearance, spectrum) { showingDetails = true }
        }
    }
    if (showingDetails) {
        TrackDetailsDialog(song, appearance.titleFont) { showingDetails = false }
    }
}

@Composable
internal fun OpenLibraryButton(model: PlayerViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Button(onClick = { model.show(Screen.Library) }) { Text("打开音乐库") }
    }
}

@Composable
private fun LandscapePlayer(
    song: NowPlaying,
    artwork: Bitmap?,
    appearance: AppAppearance,
    modifier: Modifier,
    onDetails: (() -> Unit)?,
) {
    BoxWithConstraints(modifier) {
        val contentHeight = maxHeight
        val coverSize = minOf(58.dp, contentHeight * .32f)
        val meterMaxWidth = minOf(560.dp, contentHeight * (2f * appearance.compactMeters.aspectRatio) + 10.dp)
        val trackAction = if (onDetails == null) Modifier else Modifier.clickable(
            onClickLabel = "查看完整曲目信息", role = Role.Button, onClick = onDetails,
        )
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(.85f).heightIn(max = contentHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Artwork(artwork, appearance, Modifier.width(coverSize))
                TrackText(song, Modifier.fillMaxWidth().then(trackAction), titleFont = appearance.titleFont)
            }
            Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                Dials(song.playing, Modifier.widthIn(max = meterMaxWidth).fillMaxWidth(), appearance.compactMeters)
            }
        }
    }
}

@Composable
private fun TrackDetailsDialog(song: NowPlaying, titleFont: FontFamily, close: () -> Unit) {
    val format = listOf(
        song.format, song.details, if (song.transcoded) "Navidrome 转码 · MP3" else "",
    ).filter(String::isNotBlank).joinToString(" · ")
    AlertDialog(
        onDismissRequest = close,
        title = { Text("曲目信息") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(song.title, fontSize = 20.sp, fontFamily = titleFont, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                for ((label, value) in listOf("歌手" to song.artist, "专辑" to song.album, "音源" to song.source, "格式" to format)) {
                    if (value.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Text(value, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } },
    )
}

@Composable
internal fun TrackText(
    song: NowPlaying,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    titleFont: FontFamily = FontFamily.Default,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp * scale)) {
        Text(
            song.title.ifBlank { "选一首歌" },
            fontWeight = FontWeight.Medium,
            fontFamily = titleFont,
            color = colors.onSurface,
            fontSize = 24.sp * scale,
            lineHeight = 28.sp * scale,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = listOf(song.artist, song.album).filter(String::isNotBlank).joinToString(" · ")
        if (subtitle.isNotBlank()) Text(subtitle, color = colors.onSurfaceVariant, fontSize = 13.sp * scale,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sourceInfo = listOf(song.source, song.format, song.details).filter(String::isNotBlank).joinToString(" · ")
        if (sourceInfo.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp * scale)) {
                Dot(if (song.playing) colors.tertiary else colors.onSurfaceVariant, scale)
                Text(sourceInfo, color = colors.onSurfaceVariant, fontSize = 11.sp * scale, lineHeight = 15.sp * scale,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (song.buffering) Text("正在缓冲…", color = colors.onSurfaceVariant, fontSize = 12.sp * scale)
        if (song.transcoded) Text("Navidrome 转码 · MP3", color = colors.primary, fontSize = 11.sp * scale)
        song.error?.let { Text(it, color = colors.error, fontSize = 12.sp * scale, lineHeight = 16.sp * scale) }
    }
}

@Composable
internal fun Artwork(
    artwork: Bitmap?,
    appearance: AppAppearance,
    modifier: Modifier,
    minCorner: Dp = 0.dp,
) {
    val colors = appearance.colors
    val shape = RoundedCornerShape(maxOf(appearance.coverCorner, minCorner))
    val frame = modifier.aspectRatio(1f).shadow(appearance.elevation, shape).clip(shape)
        .border(1.dp, colors.outlineVariant, shape)
    if (artwork != null) {
        Image(
            artwork.asImageBitmap(), contentDescription = "专辑封面",
            modifier = frame, contentScale = ContentScale.Crop,
        )
    } else {
        val placeholder = remember(appearance) {
            Brush.linearGradient(listOf(colors.secondaryContainer, colors.surface))
        }
        Box(
            frame.background(placeholder).semantics { contentDescription = "专辑封面占位" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).border(1.dp, colors.primary.copy(alpha = .65f), CircleShape))
            Box(Modifier.size(5.dp).background(colors.primary, CircleShape))
        }
    }
}

@Composable
private fun Transport(song: NowPlaying, model: PlayerViewModel, ready: Boolean, shuffle: Boolean, compact: Boolean = false) {
    if (compact) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaybackProgress(song, ready, model::seek, Modifier.weight(1f))
            ShuffleButton(shuffle, model, ready)
            PlaybackButtons(song, model, ready)
            WallButton(model)
        }
        return
    }
    PlaybackProgress(song, ready, model::seek)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ShuffleButton(shuffle, model, ready)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { PlaybackButtons(song, model, ready) }
        WallButton(model)
    }
}

@Composable
internal fun ShuffleButton(shuffle: Boolean, model: PlayerViewModel, ready: Boolean) {
    val color = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(
        onClick = model::toggleShuffle,
        enabled = ready,
        modifier = Modifier.semantics { contentDescription = if (shuffle) "播放模式：随机播放" else "播放模式：顺序播放" },
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(if (shuffle) "随机" else "顺序", color = color, maxLines = 1)
    }
}

@Composable
internal fun PlaybackButtons(song: NowPlaying, model: PlayerViewModel, ready: Boolean, round: Boolean = false) {
    val playing = song.playing || song.buffering
    val action = if (playing) "暂停" else "播放"
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = model::previous, modifier = Modifier.semantics { contentDescription = "上一首" }) { Text("⏮", fontSize = 21.sp) }
        Button(
            onClick = model::togglePlayback,
            enabled = ready,
            modifier = if (round) Modifier.size(56.dp).semantics { contentDescription = action } else Modifier,
            shape = if (round) CircleShape else ButtonDefaults.shape,
            contentPadding = if (round) PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
        ) {
            if (round) PlaybackGlyph(playing) else Text(action)
        }
        TextButton(onClick = model::next, modifier = Modifier.semantics { contentDescription = "下一首" }) { Text("⏭", fontSize = 21.sp) }
    }
}

@Composable
private fun PlaybackGlyph(playing: Boolean) {
    val color = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(20.dp)) {
        val width = size.width
        val height = size.height
        if (playing) {
            for (x in listOf(.23f, .58f)) {
                drawRoundRect(
                    color,
                    topLeft = Offset(width * x, height * .12f),
                    size = Size(width * .2f, height * .76f),
                    cornerRadius = CornerRadius(width * .07f),
                )
            }
        } else {
            drawPath(Path().apply {
                moveTo(width * .27f, height * .08f)
                lineTo(width * .88f, height * .5f)
                lineTo(width * .27f, height * .92f)
                close()
            }, color)
        }
    }
}

@Composable
internal fun WallButton(model: PlayerViewModel, modifier: Modifier = Modifier) {
    TextButton(onClick = { model.setWall(true) }, modifier = modifier) {
        Text("上墙", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaybackProgress(
    song: NowPlaying,
    ready: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    var seeking by remember(song.id) { mutableStateOf<Float?>(null) }
    val maxPosition = song.duration.coerceAtLeast(1).toFloat()
    val position = (seeking ?: song.position.toFloat()).coerceIn(0f, maxPosition)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Clock(position.toLong())
        Slider(
            value = position,
            onValueChange = { seeking = it },
            onValueChangeFinished = {
                seeking?.let { onSeek(it.toLong()) }
                seeking = null
            },
            enabled = ready && song.duration > 0,
            valueRange = 0f..maxPosition,
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f).semantics { contentDescription = "播放进度" },
        )
        Clock(song.duration)
    }
}

@Composable
private fun WallDisplay(
    state: PlayerUiState,
    model: PlayerViewModel,
    appearance: AppAppearance,
    spectrum: SpectrumMotion,
    choosingTheme: Boolean,
    openTheme: () -> Unit,
) {
    val song = state.playing
    val colors = appearance.colors
    val progressInteraction = remember { MutableInteractionSource() }
    val dragging by progressInteraction.collectIsDraggedAsState()
    var revealRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealRequest, dragging, choosingTheme, song.id, song.playing, state.shuffle) {
        if (revealRequest != 0 && !dragging && !choosingTheme) {
            delay(WallControlsTimeoutMs)
            revealRequest = 0
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(appearance.background)
            .pointerInput(Unit) { detectTapGestures { revealRequest++ } }
            .semantics {
                contentDescription = "上墙显示"
                onClick(label = "显示播放进度和上墙选项") {
                    revealRequest++
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val availableWidth = (maxWidth - 24.dp).coerceAtLeast(1.dp)
        val portrait = maxHeight > maxWidth
        if (portrait) {
            PortraitWallDisplay(state, appearance, spectrum)
        } else {
            val width = minOf(availableWidth, maxHeight * 2.65f)
            val scale = (width / 680.dp).coerceIn(.72f, 1.55f)
            Row(
                Modifier.width(width),
                horizontalArrangement = Arrangement.spacedBy(18.dp * scale),
                verticalAlignment = Alignment.Top,
            ) {
                WallTrackDetails(state, appearance, width * .19f, scale, Modifier.weight(.85f))
                Dials(song.playing, Modifier.weight(2f), appearance.meters, subdued = !song.playing)
            }
        }
        AnimatedVisibility(
            visible = revealRequest != 0,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val panel = Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(colors.background.copy(alpha = .9f), colors.background)),
            ).padding(start = 16.dp, end = 8.dp, top = 2.dp)
            val progress: @Composable (Modifier) -> Unit = { modifier ->
                PlaybackProgress(
                    song, state.playerReady,
                    onSeek = {
                        model.seek(it)
                        revealRequest++
                    },
                    modifier = modifier,
                    interactionSource = progressInteraction,
                )
            }
            if (portrait) {
                Column(panel.padding(bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        progress(Modifier.weight(1f))
                        ShuffleButton(state.shuffle, model, state.playerReady)
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = openTheme) { Text("主题") }
                        PlaybackButtons(song, model, state.playerReady, round = true)
                        WallExitButton(model)
                    }
                }
            } else {
                Row(
                    panel,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    progress(Modifier.weight(1f))
                    ShuffleButton(state.shuffle, model, state.playerReady)
                    TextButton(onClick = openTheme) { Text("主题") }
                    WallExitButton(model)
                }
            }
        }
    }
}

@Composable
private fun WallExitButton(model: PlayerViewModel) {
    TextButton(onClick = { model.setWall(false) }) {
        Text("退出上墙", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WallTrackDetails(
    state: PlayerUiState,
    appearance: AppAppearance,
    coverSize: Dp,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp * scale)) {
        Artwork(
            state.artwork,
            appearance,
            Modifier.width(coverSize),
        )
        TrackText(state.playing, scale = scale, titleFont = appearance.titleFont)
    }
}

@Composable
private fun AppThemePicker(selected: AppTheme, select: (AppTheme) -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("界面主题") },
        text = {
            Column {
                for (theme in AppTheme.entries) {
                    val swatch = theme.appearance.colors
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = selected == theme, onClick = { select(theme) }, role = Role.RadioButton)
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(24.dp).background(swatch.surface, CircleShape)
                                .border(1.dp, swatch.outline, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Dot(swatch.primary) }
                        Text(theme.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        RadioButton(selected = selected == theme, onClick = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } },
    )
}

internal fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun Clock(ms: Long, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(playbackTime(ms), color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
}

@Composable
internal fun Dot(color: Color = MaterialTheme.colorScheme.tertiary, scale: Float = 1f) {
    Box(Modifier.size(6.dp * scale).background(color, CircleShape))
}

@Composable
internal fun Busy() {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
}
