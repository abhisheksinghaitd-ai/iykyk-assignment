package com.iykyk.facecollage.util

/**
 * Every tunable in the pipeline lives here. Each value carries a comment explaining what it
 * does and, where relevant, why it was chosen. See README.md "Similarity threshold" for the
 * sweep-based reasoning behind [SIMILARITY_THRESHOLD] specifically.
 */
object Constants {

    // ---- Debugging ----

    /** Master switch for [com.iykyk.facecollage.util.DebugDump]. Off by default in a shipped
     *  build; flip to true to re-dump aligned crops, per-detection CSVs, cluster similarities and
     *  the threshold sweep under `getExternalFilesDir(null)/debug/<videoName>/` for debugging. */
    const val DEBUG_DUMP = false

    // ---- Frame extraction ----

    /** 10 fps sampling (100ms step). A 30s clip yields ~300 frames; enough to never miss a
     *  whip-pan-free glimpse of a person while staying well under the processing time budget. */
    const val FRAME_SAMPLE_INTERVAL_MS = 100L

    /** Fallback sampling interval if a run is measured to take longer than ~45s on-device. */
    const val FRAME_SAMPLE_INTERVAL_MS_FALLBACK = 125L // 8 fps

    /** Longest edge frames are downscaled to before detection/embedding. Detection and embedding
     *  do not benefit from more; this is the single biggest processing-time lever available. */
    const val FRAME_MAX_DIMENSION = 720

    // ---- Detection (ML Kit) ----

    /** Minimum face size as a fraction of the frame's smaller dimension, per ML Kit's own scale. */
    const val MIN_FACE_SIZE = 0.08f

    // ---- Quality gate (section 6a) — decides embedding/representative-shot eligibility ----

    /** Reject a detection from the embedding path if it is turned more than this many degrees
     *  off-axis (yaw). Profile faces embed badly with an ArcFace-family model. */
    const val MAX_YAW_DEG = 35f

    /** Reject a detection with more than this much head roll (extreme tilt breaks alignment). */
    const val MAX_ROLL_DEG = 25f

    /** Reject a face whose box is narrower than this fraction of the frame width — too small
     *  to carry identity detail once aligned down to 112x112. */
    const val MIN_FACE_WIDTH_FRACTION = 0.08f

    /** A box is treated as clipped only if less than this fraction of its own width OR height
     *  remains inside the frame. NOT a fixed pixel margin (e.g. "within 4px of an edge") — real
     *  on-device data showed ML Kit's estimated face box routinely overshoots the frame by tens
     *  of pixels (a known characteristic: the box is extrapolated from landmark geometry, not
     *  clamped to the image) even for fully-visible, well-posed, sharp faces, especially on a
     *  narrow downscaled portrait frame. A hard few-pixel margin flagged ~85% of all detections
     *  as "edge" on a real test video and silently discarded entire real people's tracks (every
     *  detection failed, so the whole appearance got zero gated detections and was dropped).
     *  This proportional test still catches genuinely-clipped boxes (large overshoot relative to
     *  box size) while tolerating small estimation overshoot on faces that are actually fully
     *  in frame. See detections.csv from a real run for the measurements behind this. */
    const val EDGE_VISIBLE_FRACTION_MIN = 0.85f

    /** Sharpness is compared against this percentile (computed per-video, not a fixed number,
     *  since absolute Laplacian-variance scale varies with video compression/resolution) and
     *  anything below it is rejected as motion blur / whip-pan. */
    const val SHARPNESS_GATE_PERCENTILE = 0.25f

    // ---- Tracking -> appearances (section 7) ----

    /** A track is split into two appearances wherever the gap between consecutive detections
     *  sharing a trackingId exceeds this — guards against ML Kit reusing an ID across a cut. */
    const val TRACK_SPLIT_GAP_MS = 500L

    /** A track is ALSO split wherever two consecutive gated detections sharing a trackingId have
     *  cosine similarity below this — guards against ML Kit reusing an ID across a cut with NO
     *  time gap at all (verified on real footage: same trackingId ran continuously, 100ms steps,
     *  zero gaps, while the actual face silently switched from one person to a completely
     *  different one mid-track — the time-gap guard above cannot catch that case). Consecutive
     *  100ms-apart frames of the same person score far above this in practice (spec's own
     *  same-person expectation is >0.55); this sits comfortably below that so it only fires on a
     *  genuine identity change, not ordinary frame-to-frame noise. */
    const val TRACK_IDENTITY_SPLIT_SIMILARITY = 0.35f

    /** Tracks shorter than this many detections are discarded as false positives. */
    const val MIN_TRACK_DETECTIONS = 3

    /** Tracks spanning less than this are discarded as noise, even if they cleared the count
     *  filter above (e.g. 3 detections crammed into 40ms of unstable double-detection). */
    const val MIN_TRACK_SPAN_MS = 250L

    /** Tracks with fewer than this many GATED detections are discarded, even if their raw
     *  detection count clears [MIN_TRACK_DETECTIONS]. Real footage showed 1-2 isolated gated
     *  frames surrounded almost entirely by blur/edge-rejected neighbours during a whip-pan or
     *  fast head turn — technically clearing the sharpness/edge thresholds for a frame or two,
     *  but not a reliable enough identity signal to promote to its own appearance. Every such
     *  case measured was visibly unrecognisable to a human, then got embedded and clustered as a
     *  spurious extra "person" since its lone atypical embedding didn't match anyone else. Set
     *  equal to [MIN_TRACK_DETECTIONS] rather than a new arbitrary number. */
    const val MIN_TRACK_GATED_DETECTIONS = 3

    /** A track's embedding is the mean of its N sharpest gated detections (fewer if it has
     *  fewer). Averaging several good frames is far less noisy than any single frame. Raised
     *  from 5 to 7 (section 16.2's own suggested remedy) after a real same-person pair measured
     *  0.48 average similarity, just under the merge threshold — widening the averaging window
     *  reduces per-track noise without touching the global threshold that every other pair in
     *  the video already sits comfortably on the correct side of. */
    const val TRACK_EMBED_TOP_N = 7

    // ---- Clustering (section 8) ----

    /** Cosine-similarity threshold to merge two clusters, on L2-normalised 512-d embeddings.
     *  Chosen as the middle of a wide plateau, not a value that only happens to work at a knife's
     *  edge: a real on-device sweep (threshold_sweep.txt from a run with this codebase's
     *  alignment/embedding/tracking fixes applied) held a stable 5-people result across every
     *  step from 0.30 to 0.45 — the correct pair's own measured similarity was ~0.48, and the
     *  highest similarity between two different people in that same run was ~0.21, so 0.40 sits
     *  with comfortable margin on both sides of the plateau rather than at either edge. The
     *  original starting point of 0.50 sat just past the plateau's upper edge, splitting one
     *  real person into two clusters. See README's threshold-sweep table for the full numbers. */
    const val SIMILARITY_THRESHOLD = 0.40f

    const val SWEEP_MIN = 0.30f
    const val SWEEP_MAX = 0.70f
    const val SWEEP_STEP = 0.05f

    // ---- Representative shot (section 9) ----

    const val REP_WEIGHT_FRONTALITY = 0.30f
    const val REP_WEIGHT_SHARPNESS = 0.25f
    const val REP_WEIGHT_EYES = 0.20f
    const val REP_WEIGHT_SMILE = 0.15f
    const val REP_WEIGHT_SIZE = 0.10f

    /** Yaw beyond which frontality contributes nothing (fully off-axis). */
    const val REP_FRONTALITY_YAW_NORM = 45f
    /** Roll beyond which frontality contributes nothing. */
    const val REP_FRONTALITY_ROLL_NORM = 30f
    /** Weight split between the yaw and roll terms inside frontality. */
    const val REP_FRONTALITY_YAW_WEIGHT = 0.7f
    const val REP_FRONTALITY_ROLL_WEIGHT = 0.3f

    /** Box width at which the size term saturates to 1.0 (fraction of frame width). */
    const val REP_SIZE_NORM_FRACTION = 0.35f

    /** Hard reject: never pick a representative shot with either eye this closed, unless every
     *  other candidate for this person is equally or more closed. */
    const val REP_HARD_MIN_EYE_OPEN = 0.35f

    /** Hard reject: never pick a representative shot yawed past this, unless no alternative exists. */
    const val REP_HARD_MAX_YAW = 30f

    // ---- Collage rendering (section 10) ----

    const val COLLAGE_WIDTH = 1080
    const val COLLAGE_HEIGHT = 1920

    const val COLLAGE_OUTER_MARGIN = 16f
    const val COLLAGE_GUTTER = 10f
    const val COLLAGE_TILE_CORNER_RADIUS = 24f

    /** How much a collage crop is expanded around the detected face box before being fit to
     *  the tile — the brief explicitly warns against cropping tightly to the box. */
    const val COLLAGE_CROP_EXPAND_FACTOR = 3.0f

    /** Crop centre is biased upward by this fraction of the box height so tiles include
     *  shoulders rather than forehead-heavy headroom. */
    const val COLLAGE_CROP_UPWARD_BIAS = 0.10f

    const val COLLAGE_HEADER_HEIGHT = 160f
    const val COLLAGE_FOOTER_HEIGHT = 0f

    // ---- Save ----

    const val SAVE_JPEG_QUALITY = 95
    const val SAVE_SUBDIRECTORY = "iykyk"
}
