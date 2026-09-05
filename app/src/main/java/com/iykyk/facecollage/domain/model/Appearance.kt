package com.iykyk.facecollage.domain.model

/**
 * One continuous visible segment of a single person — the unit the assignment's brief calls
 * "an appearance." Produced by [com.iykyk.facecollage.data.track.TrackBuilder] from raw
 * per-frame [Detection]s that share an ML Kit tracking id (or a synthetic one).
 *
 * [embedding] is the mean of this track's sharpest gated detections' embeddings, re-normalised —
 * null only if every detection in the track failed the quality gate, in which case the track has
 * already been filtered out before clustering ever sees it.
 */
data class Appearance(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val detections: List<Detection>,
    val embedding: FloatArray?
)
