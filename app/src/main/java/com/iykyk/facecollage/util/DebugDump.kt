package com.iykyk.facecollage.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.iykyk.facecollage.data.cluster.SweepRow
import com.iykyk.facecollage.data.quality.QualityScorer
import com.iykyk.facecollage.domain.model.Appearance
import com.iykyk.facecollage.domain.model.Detection
import com.iykyk.facecollage.domain.model.Person
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Writes aligned face crops to `getExternalFilesDir(null)/debug/<videoName>/` (no permission
 * needed) and logs a run summary to Logcat under [TAG], gated entirely by [Constants.DEBUG_DUMP].
 * This is the fastest debugging tool in the whole pipeline: numbers can be right for the wrong
 * reason, but a wrong crop is unmistakable on sight. Pull the folder with
 * `adb pull /sdcard/Android/data/<pkg>/files/debug`.
 */
object DebugDump {

    private const val TAG = "iykykDebug"

    fun dumpClusters(context: Context, videoName: String, people: List<Person>) {
        val root = File(context.getExternalFilesDir(null), "debug/$videoName")
        root.deleteRecursively()

        val allGatedSharpness = people
            .flatMap { it.appearances }
            .flatMap { it.detections }
            .filter { it.passedQualityGate }
            .map { it.sharpness }
            .sorted()
            .toFloatArray()

        for (person in people) {
            val dir = File(root, "person_%02d".format(person.id)).apply { mkdirs() }
            for ((appIndex, appearance) in person.appearances.withIndex()) {
                for (detection in appearance.detections) {
                    val aligned = detection.aligned ?: continue
                    if (!detection.passedQualityGate) continue
                    val score = QualityScorer.representativeScore(detection, allGatedSharpness)
                    val name = "app%02d_t%06d_s%.2f.jpg".format(appIndex, detection.timestampMs, score)
                    writeJpeg(File(dir, name), aligned)
                }
            }
        }
        Log.d(TAG, "dumped ${people.size} person folders to ${root.absolutePath}")
    }

    fun dumpRejected(context: Context, videoName: String, rejected: List<Detection>) {
        val dir = File(context.getExternalFilesDir(null), "debug/$videoName/rejected")
        dir.deleteRecursively()
        dir.mkdirs()
        for (detection in rejected) {
            val aligned = detection.aligned ?: continue // no crop to show without a landmark-based alignment
            val reason = rejectionReason(detection)
            val name = "t%06d_reason_%s.jpg".format(detection.timestampMs, reason)
            writeJpeg(File(dir, name), aligned)
        }
    }

    /** One row per detection with every field the quality gate decides on, plus why it was
     *  rejected (blank if it passed) — the concrete numbers behind a "why is X being dropped"
     *  question, per section 16.4's diagnostic advice. */
    fun dumpDetectionsCsv(context: Context, videoName: String, detections: List<Detection>) {
        val file = File(context.getExternalFilesDir(null), "debug/$videoName/detections.csv")
        file.parentFile?.mkdirs()
        val csv = StringBuilder("timestampMs,trackingId,passed,reason,left,top,right,bottom,frameW,frameH,yaw,roll,sharpness\n")
        for (d in detections.sortedBy { it.timestampMs }) {
            val reason = if (d.passedQualityGate) "" else rejectionReason(d)
            csv.append(
                "%d,%s,%b,%s,%.1f,%.1f,%.1f,%.1f,%d,%d,%.1f,%.1f,%.1f\n".format(
                    d.timestampMs, d.trackingId?.toString() ?: "", d.passedQualityGate, reason,
                    d.box.left, d.box.top, d.box.right, d.box.bottom, d.frameWidth, d.frameHeight,
                    d.yaw, d.roll, d.sharpness
                )
            )
        }
        file.writeText(csv.toString())
    }

    /** For every pair of FINAL people, the average cross-cluster similarity between their
     *  appearance embeddings — the exact number the clusterer compared against the threshold.
     *  Distinguishes "these two are the same person just under the threshold" (~0.40-0.50) from
     *  "genuinely different embeddings due to pose/lighting" (well below 0.40), per section 16.2. */
    fun writePersonSimilarities(context: Context, videoName: String, people: List<Person>) {
        val lines = mutableListOf<String>()
        for (i in people.indices) {
            for (j in i + 1 until people.size) {
                val a = people[i].appearances.mapNotNull { it.embedding }
                val b = people[j].appearances.mapNotNull { it.embedding }
                if (a.isEmpty() || b.isEmpty()) continue
                var sum = 0f
                var count = 0
                for (ea in a) for (eb in b) { sum += Vec.cosine(ea, eb); count++ }
                val avg = sum / count
                lines.add("person_%02d vs person_%02d: avg_sim=%.3f (%d appearance pairs)".format(people[i].id, people[j].id, avg, count))
            }
        }
        val file = File(context.getExternalFilesDir(null), "debug/$videoName/person_similarities.txt")
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n"))
    }

    /** Logs every raw (pre-discard) track's time range, and flags any pair that overlaps in
     *  time — the free co-occurrence signal clustering's cannot-link constraint relies on. */
    fun logRawTracks(videoName: String, rawTracks: List<List<Detection>>) {
        Log.d(TAG, "=== $videoName: ${rawTracks.size} raw tracks ===")
        val ranges = rawTracks.map { it.first().timestampMs to it.last().timestampMs }
        ranges.forEachIndexed { i, (start, end) ->
            Log.d(TAG, "track[$i] ${start}ms-${end}ms  detections=${rawTracks[i].size}")
        }
        for (i in ranges.indices) {
            for (j in i + 1 until ranges.size) {
                val (aStart, aEnd) = ranges[i]
                val (bStart, bEnd) = ranges[j]
                if (aStart <= bEnd && bStart <= aEnd) {
                    Log.d(TAG, "overlap: track[$i] and track[$j] share time on screen -> cannot-link")
                }
            }
        }
    }

    fun logSummary(
        videoName: String,
        framesSampled: Int,
        detections: List<Detection>,
        rawTrackCount: Int,
        appearances: List<Appearance>,
        people: List<Person>
    ) {
        val passed = detections.count { it.passedQualityGate }
        Log.d(TAG, "=== $videoName.mp4 ===")
        Log.d(TAG, "frames sampled       : $framesSampled")
        Log.d(TAG, "detections           : ${detections.size}")
        Log.d(TAG, "passed quality gate  : $passed")
        Log.d(TAG, "tracks (raw)         : $rawTrackCount")
        Log.d(TAG, "tracks after merge   : ${appearances.size}")
        Log.d(TAG, "people               : ${people.size}")
        Log.d(TAG, "per-person counts    : ${people.map { it.appearanceCount }.sortedDescending()}")
        Log.d(TAG, "threshold            : ${Constants.SIMILARITY_THRESHOLD}")
    }

    /** Threshold-sweep table (section 8): run clustering at every step from SWEEP_MIN to
     *  SWEEP_MAX and log people-count + per-person appearance counts at each. */
    fun logSweep(context: Context, videoName: String, rows: List<SweepRow>) {
        Log.d(TAG, "=== $videoName: threshold sweep ===")
        val lines = rows.map { row ->
            "thr=%.2f  people=%d  appearances=%s".format(row.threshold, row.peopleCount, row.appearanceCounts)
        }
        lines.forEach { Log.d(TAG, it) }
        val file = File(context.getExternalFilesDir(null), "debug/$videoName/threshold_sweep.txt")
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n"))
    }

    /** Persists [com.iykyk.facecollage.data.align.FaceAligner]'s per-detection transform-check
     *  lines to a file — not Logcat, since some devices' tiny ring buffers rotate app logs out
     *  within seconds under heavy system log volume. */
    fun writeAlignDebug(context: Context, videoName: String, lines: List<String>) {
        val file = File(context.getExternalFilesDir(null), "debug/$videoName/align_debug.txt")
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n"))
    }

    private fun rejectionReason(d: Detection): String = when {
        abs(d.yaw) > Constants.MAX_YAW_DEG -> "yaw"
        abs(d.roll) > Constants.MAX_ROLL_DEG -> "roll"
        d.box.width() < d.frameWidth * Constants.MIN_FACE_WIDTH_FRACTION -> "size"
        QualityScorer.touchesEdge(d.box, d.frameWidth, d.frameHeight) -> "edge"
        else -> "blur"
    }

    private fun writeJpeg(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
    }
}
