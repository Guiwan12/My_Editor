package com.example.videoeditor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where the exported video goes.
 *
 * The canonical output is written to the app's own external Movies folder
 * (needs no permission on any Android version). On Android 10+ we ALSO publish a
 * copy into the shared gallery via MediaStore (still no permission). Either way
 * the user can Share the file out through a FileProvider URI.
 */
object Output {

    private const val AUTHORITY = "com.example.videoeditor.fileprovider"

    /** App-private external Movies dir, e.g. .../Android/data/<pkg>/files/Movies. */
    fun outputDir(context: Context): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir).apply { mkdirs() }

    /** Copy [file] into the system gallery (MediaStore). Returns its URI, or null pre-Q. */
    fun saveToGallery(context: Context, file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyVideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
