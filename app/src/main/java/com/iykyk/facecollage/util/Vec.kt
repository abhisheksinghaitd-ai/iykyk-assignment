package com.iykyk.facecollage.util

import kotlin.math.sqrt

/** Small vector-math helpers shared by the embedder, tracker and clusterer. */
object Vec {

    /** Cosine similarity between two vectors of equal length. Callers pass L2-normalised
     *  embeddings, so this reduces to a plain dot product; kept generic for safety. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na.toDouble() * nb.toDouble()).toFloat()
        return if (denom < 1e-12f) 0f else dot / denom
    }

    /** Returns a new array, `v` scaled so its L2 norm is 1. */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0f
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq.toDouble()).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Element-wise mean of L2-normalised vectors, re-normalised. This is the track-embedding
     *  operation: a single frame's embedding is noisy, the mean of several good frames is not. */
    fun meanNormalized(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty())
        val size = vectors.first().size
        val sum = FloatArray(size)
        for (v in vectors) {
            require(v.size == size)
            for (i in 0 until size) sum[i] += v[i]
        }
        for (i in 0 until size) sum[i] /= vectors.size.toFloat()
        return l2Normalize(sum)
    }

    /** Value at the given percentile (0..1) of a list, using linear interpolation between
     *  the two nearest ranks. Used for the sharpness quality-gate cutoff. */
    fun percentile(values: List<Float>, p: Float): Float {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = p.coerceIn(0f, 1f) * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /** Fraction (0..1) of a pre-sorted list of values that are <= [value]. Used to turn a raw
     *  sharpness number into a "how sharp relative to the rest of this video" score. */
    fun percentileRank(sorted: FloatArray, value: Float): Float {
        if (sorted.isEmpty()) return 0.5f
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo.toFloat() / sorted.size.toFloat()
    }
}
