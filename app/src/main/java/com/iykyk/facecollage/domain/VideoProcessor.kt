package com.iykyk.facecollage.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.iykyk.facecollage.data.align.FaceAligner
import com.iykyk.facecollage.data.cluster.AppearanceClusterer
import com.iykyk.facecollage.data.collage.CollageRenderer
import com.iykyk.facecollage.data.collage.TileSource
import com.iykyk.facecollage.data.detect.FaceDetectorSource
import com.iykyk.facecollage.data.embed.FaceEmbedder
import com.iykyk.facecollage.data.frames.FrameExtractor
import com.iykyk.facecollage.data.quality.QualityScorer
import com.iykyk.facecollage.data.track.TrackBuilder
import com.iykyk.facecollage.domain.model.CollageResult
import com.iykyk.facecollage.domain.model.Detection
import com.iykyk.facecollage.util.Constants
import com.iykyk.facecollage.util.DebugDump
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** The UI-facing label for each pipeline phase (section 12's stage-label sequence). */
enum class ProcessingStage(val label: String) {
    EXTRACTING_FRAMES("Extracting frames"),
    DETECTING_FACES("Detecting faces"),
    GROUPING_PEOPLE("Grouping people"),
    BUILDING_COLLAGE("Building collage")
}

/** One progress tick: which stage, and how far through it. */
data class Progress(val stage: ProcessingStage, val current: Int, val total: Int)

/**
 * The one orchestrator ("use case") for the whole pipeline: extract frames, detect + gate +
 * align + embed faces, build appearance tracks, cluster them into people, pick a representative
 * shot each, render the collage. See README.md for the reasoning behind each stage.
 */
class VideoProcessor(private val context: Context) {

    private val frameExtractor = FrameExtractor(context)

    /**
     * Runs the full pipeline for one video. Creates a fresh detector + embedder for this run and
     * releases them when the flow completes or is cancelled, so a second call never shares a
     * tracking stream with a video it wasn't fed from.
     */
    fun process(uri: Uri, videoLabel: String): Flow<Pair<Progress, CollageResult?>> = flow {
        val detector = FaceDetectorSource()
        val embedder = FaceEmbedder(context)
        if (Constants.DEBUG_DUMP) FaceAligner.resetDebug()
        try {
            val totalFrames = frameExtractor.estimatedFrameCount(uri)
            var framesDone = 0
            emit(Progress(ProcessingStage.EXTRACTING_FRAMES, 0, totalFrames) to null)

            val preDetections = mutableListOf<Detection>()
            for (frame in frameExtractor.frames(uri)) {
                val faces = detector.detect(frame.bitmap)
                for (face in faces) {
                    preDetections.add(buildPreDetection(frame.bitmap, frame.timestampMs, face))
                }
                frame.bitmap.recycle()
                framesDone++
                val stage = if (framesDone <= 1) ProcessingStage.EXTRACTING_FRAMES else ProcessingStage.DETECTING_FACES
                emit(Progress(stage, framesDone, totalFrames) to null)
            }

            val detections = finalizeQualityGate(preDetections, embedder)

            emit(Progress(ProcessingStage.GROUPING_PEOPLE, 0, 1) to null)
            val rawTracks = TrackBuilder.buildRawTracks(detections)
            val appearances = TrackBuilder.build(detections)

            val sortedGatedSharpness = detections
                .filter { it.passedQualityGate }
                .map { it.sharpness }
                .sorted()
                .toFloatArray()

            val people = AppearanceClusterer.cluster(appearances, Constants.SIMILARITY_THRESHOLD, sortedGatedSharpness)
                .sortedByDescending { it.appearanceCount }
                .mapIndexed { index, person -> person.copy(id = index + 1) }

            emit(Progress(ProcessingStage.BUILDING_COLLAGE, 0, 1) to null)
            val tiles = people.map { person ->
                val fullRes = requireNotNull(frameExtractor.frameAt(uri, person.representative.timestampMs)) {
                    "could not re-fetch frame at ${person.representative.timestampMs}ms"
                }
                val scaledBox = scaleBoxToFullRes(person.representative, fullRes)
                TileSource(person.id, fullRes, scaledBox, person.appearanceCount)
            }
            val collageBitmap = CollageRenderer.render(tiles, videoLabel)
            val result = CollageResult(collageBitmap, people, videoLabel)

            if (Constants.DEBUG_DUMP) {
                DebugDump.dumpClusters(context, videoLabel, people)
                DebugDump.writeAlignDebug(context, videoLabel, FaceAligner.DEBUG_LINES)
                DebugDump.dumpRejected(context, videoLabel, detections.filter { !it.passedQualityGate })
                DebugDump.dumpDetectionsCsv(context, videoLabel, detections)
                DebugDump.writePersonSimilarities(context, videoLabel, people)
                DebugDump.logRawTracks(videoLabel, rawTracks)
                DebugDump.logSummary(videoLabel, totalFrames, detections, rawTracks.size, appearances, people)
                DebugDump.logSweep(context, videoLabel, AppearanceClusterer.sweep(appearances, sortedGatedSharpness))
            }

            emit(Progress(ProcessingStage.BUILDING_COLLAGE, 1, 1) to result)
        } finally {
            detector.close()
            embedder.close()
        }
    }.flowOn(Dispatchers.Default)

    /** Structural gate + alignment + sharpness. Embedding is filled in later, only for
     *  detections that survive the sharpness cutoff too (section 6a/6c). */
    private fun buildPreDetection(frame: Bitmap, timestampMs: Long, face: Face): Detection {
        val box = RectF(face.boundingBox)
        val hasAllLandmarks = ALL_FIVE_LANDMARKS.all { face.getLandmark(it) != null }
        val structuralPass = QualityScorer.passesStructuralGate(
            yaw = face.headEulerAngleY,
            roll = face.headEulerAngleZ,
            box = box,
            frameWidth = frame.width,
            frameHeight = frame.height,
            hasAllLandmarks = hasAllLandmarks
        )
        val aligned = FaceAligner.align(frame, face)
        val sharpness = if (aligned != null) QualityScorer.laplacianVariance(aligned) else 0f

        return Detection(
            timestampMs = timestampMs,
            trackingId = face.trackingId,
            box = box,
            frameWidth = frame.width,
            frameHeight = frame.height,
            yaw = face.headEulerAngleY,
            roll = face.headEulerAngleZ,
            leftEyeOpen = face.leftEyeOpenProbability,
            rightEyeOpen = face.rightEyeOpenProbability,
            smiling = face.smilingProbability,
            sharpness = sharpness,
            passedQualityGate = structuralPass && aligned != null,
            aligned = aligned,
            embedding = null
        )
    }

    /** Second pass: apply the per-video sharpness percentile cutoff, then embed the survivors. */
    private fun finalizeQualityGate(pre: List<Detection>, embedder: FaceEmbedder): List<Detection> {
        val candidateSharpness = pre.filter { it.passedQualityGate }.map { it.sharpness }
        val cutoff = QualityScorer.sharpnessCutoff(candidateSharpness)

        return pre.map { d ->
            if (d.passedQualityGate && d.sharpness >= cutoff) {
                d.copy(embedding = embedder.embed(d.aligned!!))
            } else {
                d.copy(passedQualityGate = false)
            }
        }
    }

    private fun scaleBoxToFullRes(detection: Detection, fullRes: Bitmap): RectF {
        val scaleX = fullRes.width.toFloat() / detection.frameWidth
        val scaleY = fullRes.height.toFloat() / detection.frameHeight
        return RectF(
            detection.box.left * scaleX,
            detection.box.top * scaleY,
            detection.box.right * scaleX,
            detection.box.bottom * scaleY
        )
    }

    companion object {
        private val ALL_FIVE_LANDMARKS = listOf(
            FaceLandmark.RIGHT_EYE, FaceLandmark.LEFT_EYE, FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_LEFT
        )
    }
}
