package com.iykyk.facecollage.data.cluster

import com.iykyk.facecollage.data.quality.QualityScorer
import com.iykyk.facecollage.domain.model.Appearance
import com.iykyk.facecollage.domain.model.Person
import com.iykyk.facecollage.util.Constants
import com.iykyk.facecollage.util.Vec

/** One row of the threshold-sweep debug table: how many people/appearances a given threshold yields. */
data class SweepRow(val threshold: Float, val peopleCount: Int, val appearanceCounts: List<Int>)

/**
 * Agglomerative clustering of appearance-level embeddings into people, with a hard co-occurrence
 * constraint: two appearances whose time ranges overlap cannot possibly be the same person, so
 * they (and by extension the clusters containing them) can never merge, regardless of how similar
 * their embeddings look.
 *
 * Deliberately **average linkage**, never single linkage: single linkage merges two clusters as
 * soon as any one pair is similar, which is exactly how one noisy embedding chain-merges two
 * different people. Average linkage requires the *whole* cross-cluster similarity to clear the
 * bar.
 */
object AppearanceClusterer {

    fun cluster(
        appearances: List<Appearance>,
        threshold: Float,
        sortedGatedSharpness: FloatArray
    ): List<Person> {
        val withEmbedding = appearances.filter { it.embedding != null }
        if (withEmbedding.isEmpty()) return emptyList()

        val n = withEmbedding.size
        val sim = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val s = Vec.cosine(withEmbedding[i].embedding!!, withEmbedding[j].embedding!!)
                sim[i][j] = s; sim[j][i] = s
            }
        }

        val cannotLink = Array(n) { BooleanArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val overlap = withEmbedding[i].startMs <= withEmbedding[j].endMs &&
                    withEmbedding[j].startMs <= withEmbedding[i].endMs
                cannotLink[i][j] = overlap; cannotLink[j][i] = overlap
            }
        }

        val clusters = mergeClusters(n, sim, cannotLink, threshold)

        return clusters.mapIndexed { index, memberIndices ->
            val members = memberIndices.map { withEmbedding[it] }.sortedBy { it.startMs }
            val gatedDetections = members.flatMap { it.detections }.filter { it.passedQualityGate }
            val representative = QualityScorer.selectRepresentative(gatedDetections, sortedGatedSharpness)
            Person(id = index, appearances = members, representative = representative)
        }
    }

    /** Runs [cluster] at every threshold from [Constants.SWEEP_MIN] to [Constants.SWEEP_MAX] in
     *  [Constants.SWEEP_STEP] steps, for the manual threshold-sweep debug workflow (section 8). */
    fun sweep(appearances: List<Appearance>, sortedGatedSharpness: FloatArray): List<SweepRow> {
        val rows = mutableListOf<SweepRow>()
        var t = Constants.SWEEP_MIN
        while (t <= Constants.SWEEP_MAX + 1e-6f) {
            val people = cluster(appearances, t, sortedGatedSharpness)
            rows.add(SweepRow(t, people.size, people.map { it.appearanceCount }.sortedDescending()))
            t += Constants.SWEEP_STEP
        }
        return rows
    }

    private fun mergeClusters(
        n: Int,
        sim: Array<FloatArray>,
        cannotLink: Array<BooleanArray>,
        threshold: Float
    ): List<MutableList<Int>> {
        val clusters = (0 until n).map { mutableListOf(it) }.toMutableList()

        while (clusters.size > 1) {
            var bestI = -1
            var bestJ = -1
            var bestAvg = Float.NEGATIVE_INFINITY

            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    if (isBlocked(clusters[i], clusters[j], cannotLink)) continue
                    val avg = averageSimilarity(clusters[i], clusters[j], sim)
                    if (avg > bestAvg) {
                        bestAvg = avg; bestI = i; bestJ = j
                    }
                }
            }

            if (bestI == -1 || bestAvg < threshold) break
            clusters[bestI].addAll(clusters[bestJ])
            clusters.removeAt(bestJ)
        }
        return clusters
    }

    /** A pair of clusters cannot merge if any member pair across them is cannot-linked — this is
     *  the "propagate" step: a cluster's cannot-link set is implicitly the union of all its
     *  members', since every member pair is checked. */
    private fun isBlocked(a: List<Int>, b: List<Int>, cannotLink: Array<BooleanArray>): Boolean {
        for (i in a) for (j in b) if (cannotLink[i][j]) return true
        return false
    }

    private fun averageSimilarity(a: List<Int>, b: List<Int>, sim: Array<FloatArray>): Float {
        var sum = 0f
        var count = 0
        for (i in a) for (j in b) { sum += sim[i][j]; count++ }
        return if (count > 0) sum / count else Float.NEGATIVE_INFINITY
    }
}
