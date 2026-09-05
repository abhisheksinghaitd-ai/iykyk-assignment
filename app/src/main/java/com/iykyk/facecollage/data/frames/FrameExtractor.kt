package com.iykyk.facecollage.data.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.iykyk.facecollage.util.Constants
import kotlin.math.max

/** One downscaled frame sampled from a video, at [timestampMs] into playback. */
data class VideoFrame(val bitmap: Bitmap, val timestampMs: Long)

/**
 * Samples a video with [MediaMetadataRetriever], one frame at a time. Deliberately not
 * MediaCodec: a plain seek-based retriever is simple, correct, and fast enough once frames are
 * downscaled before detection (see [FRAME_MAX_DIMENSION][Constants.FRAME_MAX_DIMENSION]).
 */
class FrameExtractor(private val context: Context) {

    /** Lazily yields one downscaled [VideoFrame] every [intervalMs], for detection/embedding. */
    fun frames(uri: Uri, intervalMs: Long = Constants.FRAME_SAMPLE_INTERVAL_MS): Sequence<VideoFrame> =
        sequence {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val rotation = readRotationDegrees(retriever)
                if (rotation != 0) {
                    Log.d(TAG, "container rotation metadata = $rotation degrees (retriever normally applies this already)")
                }
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                var t = 0L
                while (t <= durationMs) {
                    val raw = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (raw != null) {
                        val downscaled = downscale(raw, Constants.FRAME_MAX_DIMENSION)
                        yield(VideoFrame(downscaled, t))
                    }
                    t += intervalMs
                }
            } finally {
                retriever.release()
            }
        }

    /** Re-fetches one frame at full resolution for a collage crop. Never downscaled. */
    fun frameAt(uri: Uri, timestampMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            retriever.release()
        }
    }

    fun durationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    /** Estimated frame count for a given sampling interval, for progress reporting. */
    fun estimatedFrameCount(uri: Uri, intervalMs: Long = Constants.FRAME_SAMPLE_INTERVAL_MS): Int {
        val duration = durationMs(uri)
        return (duration / intervalMs + 1).toInt().coerceAtLeast(1)
    }

    private fun readRotationDegrees(retriever: MediaMetadataRetriever): Int =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val matrix = Matrix().apply { setScale(scale, scale) }
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    companion object {
        private const val TAG = "FrameExtractor"
    }
}
