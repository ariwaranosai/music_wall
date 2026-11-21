package com.musicwall.player.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val format: String,
    val durationMs: Long,
    val coverId: String? = null,
    val localUri: String? = null,
    val details: String = "",
)

@Entity(tableName = "local_tracks")
data class LocalTrack(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val format: String,
    val durationMs: Long,
) {
    fun asTrack() = Track(
        id = "local:$uri",
        title = title,
        artist = artist,
        album = album,
        format = format,
        durationMs = durationMs,
        localUri = uri,
    )
}

@Dao
interface LocalTrackDao {
    @Query("SELECT * FROM local_tracks ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, title COLLATE NOCASE")
    fun observe(): Flow<List<LocalTrack>>

    @Upsert
    suspend fun save(tracks: List<LocalTrack>)

    @Query("DELETE FROM local_tracks WHERE uri = :uri")
    suspend fun remove(uri: String)
}

@Database(entities = [LocalTrack::class], version = 1)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun localTracks(): LocalTrackDao
}

data class ImportResult(val added: Int, val skipped: Int)

class LocalLibrary(private val context: Context, private val dao: LocalTrackDao) {
    val tracks = dao.observe()

    suspend fun importFiles(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val files = uris.distinct().mapNotNull { uri ->
            currentCoroutineContext().ensureActive()
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return@mapNotNull null
            if (!isAudio(doc.name, doc.type)) return@mapNotNull null
            if (!persistAccess(uri)) return@mapNotNull null
            DocumentAudio(uri, doc.name.orEmpty(), doc.type)
        }
        save(files, uris.distinct().size - files.size)
    }

    suspend fun importFolder(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        check(persistAccess(uri)) { "无法保留此文件夹的读取权限，请从系统文件选择器重新选择。" }
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: error("无法打开所选文件夹。")
        check(root.isDirectory && root.canRead()) { "无法读取所选文件夹。" }
        val pending = ArrayDeque<DocumentFile>()
        pending.add(root)
        val visited = mutableSetOf<Uri>()
        val files = mutableListOf<DocumentAudio>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeFirst()
            if (!visited.add(directory.uri)) continue
            for (child in directory.listFiles()) {
                if (child.isDirectory) {
                    pending.add(child)
                } else {
                    val name = child.name.orEmpty()
                    val mime = child.type
                    if (isAudio(name, mime)) files.add(DocumentAudio(child.uri, name, mime))
                }
            }
        }
        save(files.distinctBy { it.uri }, skipped = 0)
    }

    suspend fun remove(uri: String) = dao.remove(uri)

    suspend fun artwork(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            retriever(Uri.parse(uri)) { reader -> reader.embeddedPicture?.let(::decodeArtwork) }
        }.getOrNull()
    }

    private fun persistAccess(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess

    private suspend fun save(files: List<DocumentAudio>, skipped: Int): ImportResult {
        val records = files.map { file ->
            currentCoroutineContext().ensureActive()
            val fallback = LocalTrack(
                uri = file.uri.toString(),
                title = file.name.substringBeforeLast('.').ifBlank { "未命名曲目" },
                artist = "",
                album = "",
                format = file.name.substringAfterLast('.', "").uppercase().ifBlank {
                    file.mime.orEmpty().substringAfter('/').uppercase()
                },
                durationMs = 0,
            )
            runCatching {
                retriever(file.uri) { reader ->
                    fallback.copy(
                        title = reader.tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { fallback.title },
                        artist = reader.tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        album = reader.tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        durationMs = reader.tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                    )
                }
            }.getOrDefault(fallback)
        }
        dao.save(records)
        return ImportResult(records.size, skipped)
    }

    private inline fun <T> retriever(uri: Uri, block: (MediaMetadataRetriever) -> T): T {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(context, uri)
            block(reader)
        } finally {
            reader.release()
        }
    }

    private fun MediaMetadataRetriever.tag(key: Int) = extractMetadata(key).orEmpty().trim()

    private data class DocumentAudio(val uri: Uri, val name: String, val mime: String?)

    companion object {
        private val extensions = setOf(
            "mp3", "flac", "wav", "aac", "adts", "m4a", "mp4", "alac", "ogg", "oga", "opus",
            "aif", "aiff", "aifc", "ape", "wma", "mka", "webm", "amr", "ac3", "eac3",
        )

        private fun isAudio(name: String?, mime: String?) =
            mime?.startsWith("audio/") == true || name.orEmpty().substringAfterLast('.').lowercase() in extensions
    }
}

internal fun decodeArtwork(bytes: ByteArray): Bitmap? {
    if (bytes.size > 10_000_000) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 640) sampleSize *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sampleSize
    })
}
