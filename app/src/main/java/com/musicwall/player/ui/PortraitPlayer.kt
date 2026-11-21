package com.musicwall.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musicwall.player.PlayerUiState
import com.musicwall.player.PlayerViewModel
import kotlin.math.roundToLong

@Composable
internal fun PortraitPlayer(
    state: PlayerUiState,
    model: PlayerViewModel,
    appearance: AppAppearance,
    spectrum: SpectrumMotion,
    onDetails: () -> Unit,
) {
    val song = state.playing
    val hasTrack = song.id.isNotEmpty()
    val canSeek = state.playerReady && song.seekable && song.duration > 0
    var preview by remember(song.id) { mutableStateOf<Float?>(null) }
    val fraction = preview ?: if (song.duration > 0) {
        (song.position.toDouble() / song.duration.toDouble()).toFloat().coerceIn(0f, 1f)
    } else 0f
    val position = preview?.let { (it.toDouble() * song.duration).roundToLong() } ?: song.position
    Column(Modifier.fillMaxSize().background(appearance.background)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp),
        ) {
            Column(
                Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 20.dp)
                    .widthIn(max = 412.dp).fillMaxWidth(.98f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CoverProgress(
                    fraction = fraction,
                    elapsed = "${playbackTime(position)} / ${playbackTime(song.duration)}",
                    enabled = canSeek,
                    trackId = song.id,
                    appearance = appearance,
                    onPreview = { preview = it },
                    onSeek = { model.seek((it.toDouble() * song.duration).roundToLong()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Artwork(state.artwork, appearance, Modifier.fillMaxWidth(), minCorner = 16.dp)
                }
                if (hasTrack) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Clock(position, appearance.colors.primary)
                        Clock(song.duration)
                    }
                }
            }
            val details = if (hasTrack) {
                Modifier.clickable(onClickLabel = "查看完整曲目信息", role = Role.Button, onClick = onDetails)
            } else Modifier
            TrackText(
                song, Modifier.fillMaxWidth().padding(horizontal = 20.dp).then(details),
                scale = 1.18f, titleFont = appearance.titleFont,
            )
            if (hasTrack) {
                SpectrumDisplay(
                    motion = spectrum,
                    running = song.playing && preview == null,
                    appearance = appearance.spectrum,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(90.dp),
                )
            }
        }

        if (hasTrack) {
            val colors = appearance.colors
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(23.dp),
                color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(74.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShuffleButton(state.shuffle, model, state.playerReady)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        PlaybackButtons(song, model, state.playerReady, round = true)
                    }
                    WallButton(model)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) { OpenLibraryButton(model) }
        }
    }
}
@Composable
internal fun PortraitWallDisplay(state: PlayerUiState, appearance: AppAppearance, spectrum: SpectrumMotion) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverLimit = minOf((maxWidth - 28.dp).coerceAtLeast(1.dp), maxHeight * .55f, 540.dp)
        val spectrumHeight = minOf(220.dp, maxHeight * .285f)
        val gap = minOf(22.dp, maxHeight * .035f)
        val verticalPadding = 16.dp
        val spectrumOffset = minOf(verticalPadding, maxHeight * .025f)
        Column(
            Modifier.fillMaxSize().padding(vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap, BiasAlignment.Vertical(-.64f)),
        ) {
            BoxWithConstraints(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
                Artwork(state.artwork, appearance, Modifier.width(minOf(coverLimit, maxHeight)), minCorner = 18.dp)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TrackText(
                    state.playing, Modifier.width(coverLimit), scale = 1.2f, titleFont = appearance.titleFont,
                )
                SpectrumDisplay(
                    motion = spectrum, running = state.playing.playing,
                    appearance = appearance.spectrum,
                    modifier = Modifier.offset(y = spectrumOffset).fillMaxWidth()
                        .padding(horizontal = 10.dp).height(spectrumHeight),
                )
            }
        }
    }
}
