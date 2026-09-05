package com.iykyk.facecollage.data.align

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.iykyk.facecollage.util.Constants

/**
 * 5-point similarity-transform alignment to the canonical ArcFace 112x112 layout. This is what
 * makes the embedding model's output actually useful for clustering strangers — ArcFace-family
 * models are trained on faces warped so the eyes land on fixed pixels, and feeding them a raw
 * `Bitmap.createBitmap(frame, box...)` crop produces near-useless embeddings (same-person and
 * different-person similarity fall into the same range).
 *
 * Landmark mapping verified empirically against this project's actual ML Kit version/device via
 * [com.iykyk.facecollage.util.DebugDump]'s alignment diagnostics (map each src landmark through
 * the fitted matrix and compare to the ARCFACE target): [FaceLandmark.RIGHT_EYE] and
 * [FaceLandmark.LEFT_EYE] land directly on the image's right and left respectively — i.e. ML
 * Kit's naming already matches image-perspective here, the opposite of the commonly-cited
 * "subject's own perspective" convention. Getting this backwards doesn't just mirror the crop:
 * feeding a same-handedness (non-reflective) similarity-transform solver mirror-flipped
 * correspondences produces a degenerate near-zero-scale fit, since no rotation+scale+translation
 * can satisfy contradictory left/right pairs — that surfaced as faces collapsing into a small
 * patch instead of filling the 112x112 canvas. If this ever needs re-checking on a different ML
 * Kit build, dump the aligned crops: mirrored faces mean swap back; collapsed/tiny faces mean the
 * scale is degenerate exactly as described above.
 */
object FaceAligner {

    const val ALIGNED_SIZE = 112

    /** Diagnostic lines for the "is the transform actually correct" check — each entry maps this
     *  detection's own src landmarks through the computed matrix and reports how far the result
     *  landed from the ARCFACE canonical target. Populated only while [Constants.DEBUG_DUMP] is on,
     *  capped, and persisted to a file by [com.iykyk.facecollage.util.DebugDump] rather than relied
     *  on via Logcat (small ring buffers on some devices rotate app logs out within seconds). */
    private val debugLines = mutableListOf<String>()
    val DEBUG_LINES: List<String> get() = debugLines

    fun resetDebug() {
        debugLines.clear()
    }

    // Canonical ArcFace 5-point destination for a 112x112 output.
    // index: 0=image-left eye, 1=image-right eye, 2=nose, 3=mouth image-left, 4=mouth image-right
    private val ARCFACE_112 = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * Returns a 112x112 aligned crop, or null if there aren't enough landmarks to align at all
     * (needs at least both eyes).
     */
    fun align(frame: Bitmap, face: Face): Bitmap? {
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position // -> ARCFACE index 1 (image-right)
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position  // -> ARCFACE index 0 (image-left)
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position // -> ARCFACE index 4 (image-right)
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position   // -> ARCFACE index 3 (image-left)

        val src: FloatArray
        val dst: FloatArray
        when {
            rightEye != null && leftEye != null && nose != null && mouthRight != null && mouthLeft != null -> {
                src = floatArrayOf(
                    leftEye.x, leftEye.y,
                    rightEye.x, rightEye.y,
                    nose.x, nose.y,
                    mouthLeft.x, mouthLeft.y,
                    mouthRight.x, mouthRight.y
                )
                dst = ARCFACE_112
            }
            rightEye != null && leftEye != null -> {
                // Two-point fallback: fit only the eye pair. This is mathematically identical to
                // "rotate so the eye line is horizontal, scale inter-ocular distance to 35.24px,
                // translate the eye midpoint to (55.91, 51.60)" since those are exactly the
                // distance and midpoint of ARCFACE_112's own two eye points.
                src = floatArrayOf(leftEye.x, leftEye.y, rightEye.x, rightEye.y)
                dst = floatArrayOf(ARCFACE_112[0], ARCFACE_112[1], ARCFACE_112[2], ARCFACE_112[3])
            }
            else -> return null
        }
        val matrix = similarityTransform(src, dst)

        val out = Bitmap.createBitmap(ALIGNED_SIZE, ALIGNED_SIZE, Bitmap.Config.ARGB_8888)
        out.density = frame.density // rule out any implicit density-based rescale in drawBitmap
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        Canvas(out).drawBitmap(frame, matrix, paint)

        if (Constants.DEBUG_DUMP && debugLines.size < 40) {
            val mapped = FloatArray(src.size)
            matrix.mapPoints(mapped, src)
            debugLines.add(
                "frame=${frame.width}x${frame.height} density(frame=${frame.density},out=${out.density}) " +
                    "src=${src.toList()} mapped=${mapped.map { "%.1f".format(it) }} target=${dst.toList()}"
            )
        }
        return out
    }

    /**
     * Closed-form 4-DOF similarity transform (uniform scale, rotation, translation) by least
     * squares over n point pairs. `src` = detected landmarks in frame coordinates, `dst` = the
     * canonical target coordinates, both as interleaved (x0,y0,x1,y1,...) arrays.
     */
    private fun similarityTransform(src: FloatArray, dst: FloatArray): Matrix {
        val n = src.size / 2
        var sxMean = 0f; var syMean = 0f; var dxMean = 0f; var dyMean = 0f
        for (i in 0 until n) {
            sxMean += src[2 * i]; syMean += src[2 * i + 1]
            dxMean += dst[2 * i]; dyMean += dst[2 * i + 1]
        }
        sxMean /= n; syMean /= n; dxMean /= n; dyMean /= n

        var a = 0f      // sum of (sx*dx + sy*dy)
        var b = 0f      // sum of (sx*dy - sy*dx)
        var norm = 0f   // sum of (sx^2 + sy^2)
        for (i in 0 until n) {
            val sx = src[2 * i] - sxMean
            val sy = src[2 * i + 1] - syMean
            val dx = dst[2 * i] - dxMean
            val dy = dst[2 * i + 1] - dyMean
            a += sx * dx + sy * dy
            b += sx * dy - sy * dx
            norm += sx * sx + sy * sy
        }
        val c = a / norm          // scale * cos(theta)
        val s = b / norm          // scale * sin(theta)
        val tx = dxMean - (c * sxMean - s * syMean)
        val ty = dyMean - (s * sxMean + c * syMean)

        return Matrix().apply {
            setValues(floatArrayOf(c, -s, tx, s, c, ty, 0f, 0f, 1f))
        }
    }
}
