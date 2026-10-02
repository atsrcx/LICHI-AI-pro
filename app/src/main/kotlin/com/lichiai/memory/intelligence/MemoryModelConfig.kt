package com.lichiai.memory.intelligence

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Authoritative constants and data structures for Memory OS V6 Semantic Intelligence.
 */
object MemoryModelConfig {
    const val MODEL_VERSION = "memory-leaf-ir-v2-int8-256"
    const val MODEL_ID = "MongoDB/mdbr-leaf-ir"
    const val MODEL_REVISION = "b5fe372a656566aca5eae359f0b1d880e48610b1"
    const val DIMENSIONS = 256
    const val MAX_SEQUENCE_LENGTH = 512
    const val QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
    
    // Model documented quantization range for mdbr-leaf-ir 256-d normalized embeddings
    const val QUANT_RANGE_MIN = -0.3f
    const val QUANT_RANGE_MAX = 0.3f

    // In-process HNSW Index Hyperparameters
    const val HNSW_M = 12
    const val HNSW_EF_CONSTRUCTION = 64
    const val HNSW_EF_SEARCH = 32
    const val DEFAULT_TOP_K = 32
    const val MAX_CANDIDATES = 64
    const val HARD_MAX_CANDIDATES = 100

    /**
     * Quantizes a 256-dimensional float vector into an INT8 ByteArray.
     */
    fun quantizeVector(floatVector: FloatArray): ByteArray {
        val result = ByteArray(DIMENSIONS)
        val range = QUANT_RANGE_MAX - QUANT_RANGE_MIN
        val size = min(floatVector.size, DIMENSIONS)
        
        for (i in 0 until size) {
            val clamped = max(QUANT_RANGE_MIN, min(QUANT_RANGE_MAX, floatVector[i]))
            // Linear mapping from [-0.3, 0.3] to [-128, 127]
            val normalized = (clamped - QUANT_RANGE_MIN) / range // [0.0, 1.0]
            val intVal = (normalized * 255.0f - 128.0f).toInt()
            result[i] = max(-128, min(127, intVal)).toByte()
        }
        return result
    }

    /**
     * Dequantizes an INT8 ByteArray back into a 256-dimensional float vector.
     */
    fun dequantizeVector(byteVector: ByteArray): FloatArray {
        val result = FloatArray(DIMENSIONS)
        val range = QUANT_RANGE_MAX - QUANT_RANGE_MIN
        val size = min(byteVector.size, DIMENSIONS)
        
        for (i in 0 until size) {
            val b = byteVector[i].toInt() // [-128, 127]
            val normalized = (b + 128.0f) / 255.0f // [0.0, 1.0]
            result[i] = QUANT_RANGE_MIN + normalized * range
        }
        return result
    }

    /**
     * Fast dot-product cosine similarity on INT8 byte arrays.
     */
    fun int8CosineSimilarity(v1: ByteArray, v2: ByteArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dot = 0L
        var normA = 0L
        var normB = 0L
        val len = min(v1.size, DIMENSIONS)
        
        for (i in 0 until len) {
            val a = v1[i].toLong()
            val b = v2[i].toLong()
            dot += a * b
            normA += a * a
            normB += b * b
        }
        
        if (normA == 0L || normB == 0L) return 0f
        val denom = (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
        if (denom == 0f) return 0f
        val sim = (dot.toFloat() / denom)
        // Clamp and map from [-1.0, 1.0] to [0.0, 1.0]
        return max(0f, min(1f, (sim + 1f) / 2f))
    }
}

/**
 * Represents a 256-d INT8 quantized memory embedding.
 */
data class QuantizedMemoryVector(
    val memoryId: String,
    val embedding: ByteArray,
    val dimension: Int = MemoryModelConfig.DIMENSIONS,
    val modelVersion: String = MemoryModelConfig.MODEL_VERSION
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QuantizedMemoryVector
        if (memoryId != other.memoryId) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (dimension != other.dimension) return false
        if (modelVersion != other.modelVersion) return false
        return true
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + dimension
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}
