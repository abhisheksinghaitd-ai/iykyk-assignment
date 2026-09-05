package com.iykyk.facecollage.data.collage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.iykyk.facecollage.util.Constants
import java.io.File
import java.io.FileOutputStream

/** Saves the collage to the shared Pictures gallery and shares it via a [FileProvider] uri. */
object MediaStoreSaver {

    /** Returns the saved [android.net.Uri], or null on failure. */
    fun saveToGallery(context: Context, bitmap: Bitmap): android.net.Uri? {
        val displayName = "iykyk_collage_${System.currentTimeMillis()}.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Constants.SAVE_SUBDIRECTORY)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, Constants.SAVE_JPEG_QUALITY, out)
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    Constants.SAVE_SUBDIRECTORY
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, displayName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, Constants.SAVE_JPEG_QUALITY, out)
                }
                // Legacy (<API 29) path: media-scan it so it shows up in the gallery immediately.
                @Suppress("DEPRECATION")
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file)))
                android.net.Uri.fromFile(file)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Writes the collage to `cacheDir/shared/` and opens the system share sheet via
     *  [FileProvider] — never a raw MediaStore uri, so sharing works regardless of save state. */
    fun share(context: Context, bitmap: Bitmap) {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(sharedDir, "iykyk_collage_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, Constants.SAVE_JPEG_QUALITY, out)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share collage"))
    }
}
