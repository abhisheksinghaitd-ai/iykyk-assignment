package com.iykyk.facecollage.domain.model

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * One face detected in one sampled frame. Detections are the atomic unit fed into
 * [com.iykyk.facecollage.data.track.TrackBuilder]; a single appearance is built from many of
 * these sharing a tracking id.
 *
 * A detection that fails the quality gate (see [passedQualityGate]) is still kept for track
 * continuity — a person turning their head for a few frames has not ended their appearance —
 * but is excluded from embedding, clustering and representative-shot selection.
 */
data class Detection(
    val timestampMs: Long,
    val trackingId: Int?,
    val box: RectF,
    val frameWidth: Int,
    val frameHeight: Int,
    val yaw: Float,
    val roll: Float,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smiling: Float?,
    val sharpness: Float,
    val passedQualityGate: Boolean,
    val aligned: Bitmap?,
    val embedding: FloatArray?
)
