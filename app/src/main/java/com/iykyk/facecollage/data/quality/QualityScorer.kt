package com.iykyk.facecollage.data.quality

import android.graphics.Bitmap
import android.graphics.RectF
import com.iykyk.facecollage.domain.model.Detection
import com.iykyk.facecollage.util.Constants
import com.iykyk.facecollage.util.Vec
import kotlin.math.abs
import kotlin.math.min

/**
 * Two responsibilities that share the same building blocks (sharpness, frontality):
 *  1. The quality gate (section 6a) — decides whether a detection is eligible for embedding.
 *  2. Representative-shot scoring (section 9) — picks the best already-gated detection per person.
 */
object QualityScorer {

    /**
     * Structural checks that don't depend on the rest of the video: pose, size, edge-clipping,
     * landmark availability. Sharpness is deliberately NOT checked here — it needs a per-video
     * percentile computed across every candidate first (see [sharpnessCutoff]).
     */
    fun passesStructuralGate(
        yaw: Float,
        roll: Float,
        box: RectF,
        frameWidth: Int,
        frameHeight: Int,
        hasAllLandmarks: Boolean
    ): Boolean {
        if (abs(yaw) > Constants.MAX_YAW_DEG) return false
        if (abs(roll) > Constants.MAX_ROLL_DEG) return false
        if (box.width() < frameWidth * Constants.MIN_FACE_WIDTH_FRACTION) return false
        if (touchesEdge(box, frameWidth, frameHeight)) return false
        if (!hasAllLandmarks) return false
        return true
    }

    /** True only if a large enough fraction of the box falls outside the frame to call it
     *  genuinely clipped, rather than ML Kit's usual small box-estimation overshoot. See
     *  [Constants.EDGE_VISIBLE_FRACTION_MIN] for why this isn't a fixed pixel margin. */
    fun touchesEdge(box: RectF, frameWidth: Int, frameHeight: Int): Boolean {
        val boxWidth = box.width().coerceAtLeast(1e-3f)
        val boxHeight = box.height().coerceAtLeast(1e-3f)
        val visibleWidth = (box.right.coerceAtMost(frameWidth.toFloat()) - box.left.coerceAtLeast(0f)).coerceAtLeast(0f)
        val visibleHeight = (box.bottom.coerceAtMost(frameHeight.toFloat()) - box.top.coerceAtLeast(0f)).coerceAtLeast(0f)
        return visibleWidth / boxWidth < Constants.EDGE_VISIBLE_FRACTION_MIN ||
            visibleHeight / boxHeight < Constants.EDGE_VISIBLE_FRACTION_MIN
    }

    /** Variance of the Laplacian over a greyscale aligned crop. Higher = sharper. */
    fun laplacianVariance(alignedCrop: Bitmap): Float {
        val w = alignedCrop.width
        val h = alignedCrop.height
        val pixels = IntArray(w * h)
        alignedCrop.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val lap = 4f * gray[idx] - gray[idx - 1] - gray[idx + 1] - gray[idx - w] - gray[idx + w]
                sum += lap
                sumSq += lap.toDouble() * lap.toDouble()
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        return variance.toFloat().coerceAtLeast(0f)
    }

    /** The per-video sharpness cutoff: the 25th percentile of every structurally-eligible
     *  detection's sharpness. Anything below this is rejected as motion blur. */
    fun sharpnessCutoff(candidateSharpness: List<Float>): Float {
        if (candidateSharpness.isEmpty()) return 0f
        return Vec.percentile(candidateSharpness, Constants.SHARPNESS_GATE_PERCENTILE)
    }

    // ---- Representative shot (section 9) ----

    fun representativeScore(detection: Detection, sortedGatedSharpness: FloatArray): Float {
        val frontality = frontality(detection.yaw, detection.roll)
        val sharpNorm = Vec.percentileRank(sortedGatedSharpness, detection.sharpness)
        val eyes = eyeOpenScore(detection) ?: 0.5f
        val smile = detection.smiling ?: 0.5f
        val sizeNorm = (detection.box.width() / (detection.frameWidth * Constants.REP_SIZE_NORM_FRACTION))
            .coerceIn(0f, 1f)

        return Constants.REP_WEIGHT_FRONTALITY * frontality +
            Constants.REP_WEIGHT_SHARPNESS * sharpNorm +
            Constants.REP_WEIGHT_EYES * eyes +
            Constants.REP_WEIGHT_SMILE * smile +
            Constants.REP_WEIGHT_SIZE * sizeNorm
    }

    /**
     * Picks the best representative [Detection] from a person's pooled gated detections.
     * Hard rejects (edge-clipped, closed eyes, extreme yaw) are applied first; the eye/yaw
     * rejects are relaxed if they would eliminate every candidate.
     */
    fun selectRepresentative(gatedDetections: List<Detection>, sortedGatedSharpness: FloatArray): Detection {
        require(gatedDetections.isNotEmpty())

        val nonClipped = gatedDetections.filterNot { touchesEdge(it.box, it.frameWidth, it.frameHeight) }
        val pool1 = nonClipped.ifEmpty { gatedDetections }

        val eyesOk = pool1.filter { d -> (eyeOpenScore(d) ?: 1f) >= Constants.REP_HARD_MIN_EYE_OPEN }
        val pool2 = eyesOk.ifEmpty { pool1 }

        val yawOk = pool2.filter { abs(it.yaw) <= Constants.REP_HARD_MAX_YAW }
        val pool3 = yawOk.ifEmpty { pool2 }

        return pool3.maxBy { representativeScore(it, sortedGatedSharpness) }
    }

    private fun eyeOpenScore(detection: Detection): Float? {
        val l = detection.leftEyeOpen
        val r = detection.rightEyeOpen
        return if (l != null && r != null) min(l, r) else null
    }

    private fun frontality(yaw: Float, roll: Float): Float {
        val yawPenalty = (abs(yaw) / Constants.REP_FRONTALITY_YAW_NORM).coerceIn(0f, 1f) * Constants.REP_FRONTALITY_YAW_WEIGHT
        val rollPenalty = (abs(roll) / Constants.REP_FRONTALITY_ROLL_NORM).coerceIn(0f, 1f) * Constants.REP_FRONTALITY_ROLL_WEIGHT
        return (1f - yawPenalty - rollPenalty).coerceIn(0f, 1f)
    }
}
