package com.ghostcapture.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

data class RecordingItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long
)

/** Lists the app's recordings from MediaStore (Movies/GhostCapture). */
object RecordingRepository {

    private const val RELATIVE_PATH = "Movies/GhostCapture"

    fun listRecordings(resolver: ContentResolver): List<RecordingItem> {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?"
        val args = arrayOf("$RELATIVE_PATH%")
        val sort = MediaStore.Video.Media.DATE_ADDED + " DESC"
        val out = ArrayList<RecordingItem>()
        val cursor = resolver.query(uri, projection, selection, args, sort) ?: return out
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                out.add(
                    RecordingItem(
                        id = id,
                        uri = ContentUris.withAppendedId(uri, id),
                        name = it.getString(nameCol) ?: "recording.mp4",
                        durationMs = it.getLong(durCol),
                        sizeBytes = it.getLong(sizeCol),
                        dateAddedSec = it.getLong(dateCol)
                    )
                )
            }
        }
        return out
    }

    fun deleteRecording(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            resolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun loadThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            resolver.loadThumbnail(uri, Size(320, 180), null)
        } catch (e: Exception) {
            null
        }
    }
}
