package com.iykyk.facecollage.data.track

import com.iykyk.facecollage.domain.model.Appearance
import com.iykyk.facecollage.domain.model.Detection
import com.iykyk.facecollage.util.Constants
import com.iykyk.facecollage.util.Vec

/**
 * Converts a flat list of per-frame [Detection]s into [Appearance]s — one appearance per
 * continuous visible segment of one person. This is the single biggest accuracy lever in the
 * whole pipeline: clustering must run on one embedding per appearance, never on every raw
 * per-frame embedding, or a handful of bad embeddings bridge-merge two different people.
 *
 * A track (grouped by ML Kit's trackingId) is split on a time gap AND on an identity
 * discontinuity — real footage showed ML Kit carry one trackingId across a hard scene cut with
 * zero time gap between frames, silently switching which physical person it was tracking
 * partway through. See [Constants.TRACK_IDENTITY_SPLIT_SIMILARITY].
 */
object TrackBuilder {

    fun build(detections: List<Detection>): List<Appearance> {
        val rawTracks = buildRawTracks(detections)

        var nextId = 0
        return rawTracks
            .mapNotNull { track -> toAppearance(track, nextId).also { if (it != null) nextId++ } }
            .sortedBy { it.startMs }
    }

    /** Tracks grouped by tracking id and split on time gaps, BEFORE the minimum-length/span/
     *  zero-gated discard filter. Exposed for [com.iykyk.facecollage.util.DebugDump] — Checkpoint
     *  B inspects these raw ranges for co-occurrence sanity-checking before anything is thrown away. */
    fun buildRawTracks(detections: List<Detection>): List<List<Detection>> {
        val sorted = detections.sortedBy { it.timestampMs }
        return groupByTrackingIdWithSplits(sorted)
    }

    private fun groupByTrackingIdWithSplits(sorted: List<Detection>): List<List<Detection>> {
        val byTrackingId = sorted.groupBy { it.trackingId }
        val rawTracks = mutableListOf<List<Detection>>()

        for ((trackingId, group) in byTrackingId) {
            if (trackingId == null) {
                // A null tracking id becomes its own single-detection track.
                group.forEach { rawTracks.add(listOf(it)) }
                continue
            }
            val ordered = group.sortedBy { it.timestampMs }
            var current = mutableListOf(ordered.first())
            // Running mean of this segment's gated embeddings so far — compared against, rather
            // than just the single immediately-preceding frame, so one noisy/transitional frame
            // (e.g. a face rushing toward the camera right before exiting) can't fracture an
            // otherwise-solid run of a single real person into a spurious extra appearance.
            val segmentGatedEmbeddings = mutableListOf<FloatArray>()
            ordered.first().embedding.takeIf { ordered.first().passedQualityGate }?.let { segmentGatedEmbeddings.add(it) }

            for (detection in ordered.drop(1)) {
                val gap = detection.timestampMs - current.last().timestampMs
                val embedding = detection.embedding
                val identityBreak = detection.passedQualityGate && embedding != null && segmentGatedEmbeddings.isNotEmpty() &&
                    Vec.cosine(embedding, Vec.meanNormalized(segmentGatedEmbeddings)) < Constants.TRACK_IDENTITY_SPLIT_SIMILARITY

                if (gap > Constants.TRACK_SPLIT_GAP_MS || identityBreak) {
                    rawTracks.add(current)
                    current = mutableListOf(detection)
                    segmentGatedEmbeddings.clear()
                } else {
                    current.add(detection)
                }
                if (detection.passedQualityGate && embedding != null) {
                    segmentGatedEmbeddings.add(embedding)
                }
            }
            rawTracks.add(current)
        }
        return rawTracks
    }

    private fun toAppearance(track: List<Detection>, id: Int): Appearance? {
        val start = track.first().timestampMs
        val end = track.last().timestampMs
        val gatedCount = track.count { it.passedQualityGate }

        if (track.size < Constants.MIN_TRACK_DETECTIONS) return null
        if (end - start < Constants.MIN_TRACK_SPAN_MS) return null
        if (gatedCount < Constants.MIN_TRACK_GATED_DETECTIONS) return null

        return Appearance(
            id = id,
            startMs = start,
            endMs = end,
            detections = track,
            embedding = trackEmbedding(track)
        )
    }

    private fun trackEmbedding(track: List<Detection>): FloatArray? {
        val gated = track.filter { it.passedQualityGate && it.embedding != null }
        if (gated.isEmpty()) return null
        val topSharpest = gated.sortedByDescending { it.sharpness }.take(Constants.TRACK_EMBED_TOP_N)
        return Vec.meanNormalized(topSharpest.map { it.embedding!! })
    }
}
