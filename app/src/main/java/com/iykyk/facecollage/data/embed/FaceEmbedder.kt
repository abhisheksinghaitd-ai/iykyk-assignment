package com.iykyk.facecollage.data.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.iykyk.facecollage.data.align.FaceAligner
import com.iykyk.facecollage.util.Vec
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Runs `w600k_mbf.onnx` (InsightFace `buffalo_s`, MobileFaceNet trained on WebFace600K) via ONNX
 * Runtime Mobile. Input `float32[1,3,112,112]` NCHW RGB, normalised `(pixel-127.5)/127.5`.
 * Output `float32[1,512]`, L2-normalised here (the model does not normalise its own output).
 *
 * The environment and session are created exactly once — creating an [OrtSession] per call would
 * make processing take minutes instead of seconds. Callers must feed *aligned* 112x112 crops (see
 * [FaceAligner]); embedding an unaligned crop is the other classic way this pipeline silently
 * produces useless embeddings.
 */
class FaceEmbedder(context: Context) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val modelBytes = context.assets.open(MODEL_ASSET_NAME).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            // NNAPI deliberately left off: the spec calls for correctness over speed here, and
            // NNAPI execution can subtly change embedding values on some devices/drivers.
        }
        session = env.createSession(modelBytes, options)
        inputName = session.inputNames.iterator().next()
    }

    /** Embeds one aligned 112x112 crop, returning a 512-d L2-normalised vector. */
    fun embed(aligned112: Bitmap): FloatArray {
        require(aligned112.width == FaceAligner.ALIGNED_SIZE && aligned112.height == FaceAligner.ALIGNED_SIZE) {
            "expected a ${FaceAligner.ALIGNED_SIZE}x${FaceAligner.ALIGNED_SIZE} aligned crop, " +
                "got ${aligned112.width}x${aligned112.height}"
        }
        val inputBuffer = toNchwBuffer(aligned112)
        val tensor = OnnxTensor.createTensor(env, inputBuffer, INPUT_SHAPE)
        try {
            val result = session.run(mapOf(inputName to tensor))
            try {
                val entry = result.iterator().next()
                @Suppress("UNCHECKED_CAST")
                val raw = entry.value.value as Array<FloatArray>
                return Vec.l2Normalize(raw[0])
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }
    }

    fun embedBatch(aligned: List<Bitmap>): List<FloatArray> = aligned.map { embed(it) }

    /** Pixel loop: NCHW order — every red value, then every green value, then every blue value. */
    private fun toNchwBuffer(bitmap: Bitmap): FloatBuffer {
        val size = FaceAligner.ALIGNED_SIZE
        val pixelCount = size * size
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        val buffer = FloatBuffer.allocate(3 * pixelCount)
        for (i in 0 until pixelCount) {
            val r = (pixels[i] shr 16) and 0xFF
            buffer.put(i, (r - 127.5f) / 127.5f)
        }
        for (i in 0 until pixelCount) {
            val g = (pixels[i] shr 8) and 0xFF
            buffer.put(pixelCount + i, (g - 127.5f) / 127.5f)
        }
        for (i in 0 until pixelCount) {
            val b = pixels[i] and 0xFF
            buffer.put(2 * pixelCount + i, (b - 127.5f) / 127.5f)
        }
        return buffer
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val MODEL_ASSET_NAME = "w600k_mbf.onnx"
        private val INPUT_SHAPE = longArrayOf(1, 3, FaceAligner.ALIGNED_SIZE.toLong(), FaceAligner.ALIGNED_SIZE.toLong())
    }
}
