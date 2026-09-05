package com.iykyk.facecollage.data.detect

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.iykyk.facecollage.util.Constants
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/**
 * Thin wrapper around ML Kit's face detector. `enableTracking()` is required for appearance
 * counting to work at all: [Face.getTrackingId] is the backbone of
 * [com.iykyk.facecollage.data.track.TrackBuilder]. Tracking IDs are only stable in stream mode,
 * which this uses by default — callers MUST feed frames from a single video in strictly
 * increasing timestamp order, and must not share one instance across two interleaved streams.
 * Create a fresh instance per video.
 */
class FaceDetectorSource : Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(Constants.MIN_FACE_SIZE)
            .enableTracking()
            .build()
    )

    suspend fun detect(bitmap: Bitmap): List<Face> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            detector.process(image).await()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun close() {
        detector.close()
    }
}
