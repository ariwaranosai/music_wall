package com.musicwall.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicwall.player.PlayerUiState
import com.musicwall.player.PlayerViewModel
import com.musicwall.player.NavidromeSection
import com.musicwall.player.Source
import com.musicwall.player.data.Track

@Composable
internal fun LibraryScreen(
    state: PlayerUiState,
    model: PlayerViewModel,
    importFiles: () -> Unit,
    importFolder: () -> Unit,
    openServer: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.source == Source.Local, onClick = { model.selectSource(Source.Local) },
                label = { Text("本地文件") })
            FilterChip(selected = state.source == Source.Navidrome, onClick = { model.selectSource(Source.Navidrome) },
                label = { Text("极空间") })
        }

        if (state.source == Source.Local) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = importFiles, enabled = !state.importing) { Text("导入文件") }
                TextButton(onClick = importFolder, enabled = !state.importing) { Text("导入文件夹") }
                if (state.importing) Busy()
            }
            if (state.localTracks.isEmpty()) {
                EmptyLibrary(if (state.importing) "正在扫描文件…" else "从手机添加音乐文件或文件夹。")
            } else {
                TrackList(state.localTracks, state, model, Modifier.weight(1f))
            }
            return
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.server == null) "Navidrome 未连接" else "Navidrome · ${state.server.address.host}",
                color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = openServer) { Text(if (state.server == null) "添加服务器" else "连接设置") }
        }

        if (state.server == null) {
            EmptyLibrary("填写极空间上 Navidrome 的地址和账号。")
            return
        }

        val album = state.selectedAlbum
        val playlist = state.selectedPlaylist
        val showingPlaylists = state.navidromeSection == NavidromeSection.Playlists
        if (playlist != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.openPlaylist(null) }) { Text("‹ 播放列表") }
                Text(playlist.name, Modifier.weight(1f), fontWeight = FontWeight.Medium,
                    fontFamily = state.theme.appearance.titleFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(
                    onClick = { model.playAll(state.serverTracks) },
                    enabled = state.playerReady && state.serverTracks.isNotEmpty(),
                ) { Text(if (state.shuffle) "随机播放" else "播放全部") }
            }
        } else if (album != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.openAlbum(null) }) { Text("‹ 专辑") }
                Text(album.title, fontWeight = FontWeight.Medium, fontFamily = state.theme.appearance.titleFont,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showingPlaylists, onClick = { model.selectNavidromeSection(NavidromeSection.Albums) },
                    label = { Text("专辑") })
                FilterChip(selected = showingPlaylists, onClick = { model.selectNavidromeSection(NavidromeSection.Playlists) },
                    label = { Text("播放列表") })
            }
            if (!showingPlaylists) SearchBar(state.searchQuery, model::search)
        }

        val error = state.catalogError
        if (error != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.loadCatalog() }) { Text("重试") }
            }
        }

        val isTracks = playlist != null || album != null || state.searchQuery.isNotBlank()
        if (isTracks) {
            TrackList(
                state.serverTracks, state, model, Modifier.weight(1f),
                loading = state.catalogBusy,
                emptyText = if (error != null) null else if (playlist != null) "这个播放列表还没有曲目。" else "没有找到曲目。",
                more = if (state.hasMore) ({ model.loadCatalog(append = true) }) else null,
            )
        } else if (showingPlaylists) {
            LazyColumn(Modifier.weight(1f)) {
                items(state.playlists, key = { it.id }) { item ->
                    CatalogRow(item.name, item.owner, item.songs, state) { model.openPlaylist(item) }
                }
                when {
                    state.catalogBusy -> item { Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { Busy() } }
                    state.playlists.isEmpty() && error == null -> item { EmptyLibrary("Navidrome 里还没有播放列表。") }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(state.albums, key = { it.id }) { item ->
                    CatalogRow(item.title, item.artist, item.songs, state) { model.openAlbum(item) }
                }
                when {
                    state.catalogBusy -> item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { Busy() } }
                    state.hasMore -> item { TextButton(onClick = { model.loadCatalog(append = true) }) { Text("加载更多") } }
                    state.albums.isEmpty() && error == null -> item { EmptyLibrary("这个曲库还没有专辑。") }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(title: String, byline: String, songs: Int, state: PlayerUiState, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open)
        .sizeIn(minHeight = 56.dp).padding(horizontal = 6.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 15.sp, fontFamily = state.theme.appearance.titleFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOf(byline, "$songs 首").filter(String::isNotBlank).joinToString(" · "),
            color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = colors.outlineVariant)
}

@Composable
private fun SearchBar(query: String, search: (String) -> Unit) {
    var input by rememberSaveable(query) { mutableStateOf(query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        search(input)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("曲名、歌手或专辑") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = submit) { Text("搜索") }
        if (query.isNotEmpty()) TextButton(onClick = { search("") }) { Text("清除") }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    state: PlayerUiState,
    model: PlayerViewModel,
    modifier: Modifier,
    loading: Boolean = false,
    emptyText: String? = null,
    more: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val titleFont = state.theme.appearance.titleFont
    var removing by remember { mutableStateOf<Track?>(null) }
    LazyColumn(modifier.fillMaxWidth()) {
        itemsIndexed(tracks, key = { index, song -> "$index:${song.id}" }) { index, song ->
            val id = if (song.localUri == null) "navidrome:${song.id}" else song.id
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(enabled = state.playerReady, role = Role.Button) { model.play(tracks, index) }
                        .sizeIn(minHeight = 58.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = if (state.playing.id == id) colors.primary else colors.onSurface,
                            fontSize = 15.sp, fontFamily = titleFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOf(song.artist, song.album).filter(String::isNotBlank).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (song.format.isNotBlank()) Text(song.format, color = colors.onSurfaceVariant, fontSize = 11.sp)
                    if (song.durationMs > 0) Clock(song.durationMs)
                }
                if (song.localUri != null) {
                    TextButton(onClick = { removing = song }) { Text("移除", color = colors.onSurfaceVariant, fontSize = 11.sp) }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
        when {
            loading -> item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { Busy() } }
            more != null -> item { TextButton(onClick = more) { Text("加载更多") } }
            tracks.isEmpty() && emptyText != null -> item { EmptyLibrary(emptyText) }
        }
    }
    removing?.let { song ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除曲目") },
            text = { Text("从音乐库移除《${song.title}》，手机上的文件会保留。") },
            confirmButton = {
                TextButton(onClick = { song.localUri?.let(model::removeLocal); removing = null }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyLibrary(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 17.dp, horizontal = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
internal fun ServerSettings(state: PlayerUiState, model: PlayerViewModel, close: () -> Unit) {
    val profile = state.server
    var address by rememberSaveable(profile?.address) { mutableStateOf(profile?.address?.toString().orEmpty()) }
    var username by rememberSaveable(profile?.username) { mutableStateOf(profile?.username.orEmpty()) }
    var password by remember(profile) { mutableStateOf("") }
    var canReusePassword by remember(profile) { mutableStateOf(profile != null) }
    val enabled = !state.connecting
    AlertDialog(
        onDismissRequest = { if (enabled) close() },
        title = { Text("连接 Navidrome") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; canReusePassword = false },
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.20:4533") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; canReusePassword = false },
                    label = { Text("用户名") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; canReusePassword = false },
                    label = { Text("密码") },
                    placeholder = if (canReusePassword) ({ Text("使用已保存的密码") }) else null,
                    singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (profile != null) TextButton(onClick = { model.disconnect(close) }, enabled = enabled) { Text("断开连接") }
            }
        },
        confirmButton = {
            Button(
                onClick = { model.connect(address, username, if (canReusePassword) profile?.password.orEmpty() else password, close) },
                enabled = enabled,
            ) { if (state.connecting) Busy() else Text("测试并保存") }
        },
        dismissButton = { TextButton(onClick = close, enabled = enabled) { Text("取消") } },
    )
}
