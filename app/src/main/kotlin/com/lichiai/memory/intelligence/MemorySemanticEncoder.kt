package com.lichiai.memory.intelligence

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * On-Device Semantic Embedding Encoder for Memory OS V6.
 * Powered by MongoDB/mdbr-leaf-ir via ONNX Runtime Android 1.30.0.
 * Produces deterministic 256-dimensional INT8 embeddings.
 */
class MemorySemanticEncoder(private val context: Context) {

    companion object {
        private const val TAG = "MemorySemanticEncoder"
        private const val CLS_TOKEN = "[CLS]"
        private const val SEP_TOKEN = "[SEP]"
        private const val UNK_TOKEN = "[UNK]"
        private const val PAD_TOKEN = "[PAD]"

        @Volatile
        private var INSTANCE: MemorySemanticEncoder? = null

        fun getInstance(context: Context): MemorySemanticEncoder {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemorySemanticEncoder(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val mutex = Mutex()
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false

    /**
     * Explicit check for model binary and active runtime availability.
     */
    fun isModelAvailable(): Boolean {
        return isInitialized && ortSession != null && MemoryModelLoader.isModelAvailable(context)
    }

    // Fast Tokenizer Structures
    private val vocabMap = HashMap<String, Long>(32000)
    private var clsId = 101L
    private var sepId = 102L
    private var unkId = 100L
    private var padId = 0L

    /**
     * Initializes the ONNX session and tokenizer vocabulary.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (isInitialized && ortSession != null) return@withContext true

            try {
                if (!MemoryModelLoader.isModelAvailable(context)) {
                    Log.w(TAG, "[MemorySemanticEncoder] ONNX model binary not found. Gracefully degrading to Lexical/FTS5 and structured fact retrieval.")
                    isInitialized = false
                    return@withContext false
                }

                // 1. Load Vocab
                val vocabLines = MemoryModelLoader.loadVocab(context)
                if (vocabLines.isEmpty()) {
                    Log.w(TAG, "[MemorySemanticEncoder] ONNX model binary not found. Gracefully degrading to Lexical/FTS5 and structured fact retrieval.")
                    return@withContext false
                }
                vocabMap.clear()
                for ((index, word) in vocabLines.withIndex()) {
                    vocabMap[word] = index.toLong()
                }
                clsId = vocabMap[CLS_TOKEN] ?: 101L
                sepId = vocabMap[SEP_TOKEN] ?: 102L
                unkId = vocabMap[UNK_TOKEN] ?: 100L
                padId = vocabMap[PAD_TOKEN] ?: 0L

                // 2. Load Model File
                val modelFile = MemoryModelLoader.getOrExtractModelFile(context)
                if (modelFile == null || !modelFile.exists()) {
                    Log.w(TAG, "[MemorySemanticEncoder] ONNX model binary not found. Gracefully degrading to Lexical/FTS5 and structured fact retrieval.")
                    return@withContext false
                }

                // 3. Initialize ORT Session
                val env = OrtEnvironment.getEnvironment()
                ortEnvironment = env
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                }
                ortSession = env.createSession(modelFile.absolutePath, opts)
                isInitialized = true
                Log.i(TAG, "MemorySemanticEncoder initialized successfully with ORT Android 1.30.0.")
                
                // Warm up
                warmUpInternal()
                true
            } catch (e: Exception) {
                Log.w(TAG, "[MemorySemanticEncoder] ONNX model binary not found. Gracefully degrading to Lexical/FTS5 and structured fact retrieval.", e)
                isInitialized = false
                false
            }
        }
    }

    /**
     * Encodes a search query, prepending the official model query prefix.
     */
    suspend fun encodeQuery(queryText: String): QuantizedMemoryVector? = withContext(Dispatchers.Default) {
        val formulated = MemoryModelConfig.QUERY_PREFIX + queryText.trim()
        val floatVector = encodeToFloatArray(formulated) ?: return@withContext null
        val int8Bytes = MemoryModelConfig.quantizeVector(floatVector)
        QuantizedMemoryVector(
            memoryId = "query_${System.currentTimeMillis()}",
            embedding = int8Bytes,
            dimension = MemoryModelConfig.DIMENSIONS
        )
    }

    /**
     * Encodes a structured memory item for vector indexing.
     */
    suspend fun encodeMemory(memoryId: String, memoryText: String): QuantizedMemoryVector? = withContext(Dispatchers.Default) {
        val floatVector = encodeToFloatArray(memoryText.trim()) ?: return@withContext null
        val int8Bytes = MemoryModelConfig.quantizeVector(floatVector)
        QuantizedMemoryVector(
            memoryId = memoryId,
            embedding = int8Bytes,
            dimension = MemoryModelConfig.DIMENSIONS
        )
    }

    /**
     * Executes internal warm-up inference to prime the ONNX runtime session.
     */
    fun warmUp() {
        if (!isInitialized) return
        try {
            warmUpInternal()
        } catch (e: Exception) {
            Log.w(TAG, "Warmup skipped: ${e.message}")
        }
    }

    private fun warmUpInternal() {
        val session = ortSession ?: return
        val env = ortEnvironment ?: return
        val dummyIds = longArrayOf(clsId, 2023L, 2003L, 1037L, 3231L, sepId)
        val dummyMask = LongArray(dummyIds.size) { 1L }
        val dummyTypes = LongArray(dummyIds.size) { 0L }

        val tensorShape = longArrayOf(1, dummyIds.size.toLong())
        val inIds = OnnxTensor.createTensor(env, LongBuffer.wrap(dummyIds), tensorShape)
        val inMask = OnnxTensor.createTensor(env, LongBuffer.wrap(dummyMask), tensorShape)
        val inTypes = OnnxTensor.createTensor(env, LongBuffer.wrap(dummyTypes), tensorShape)

        val inputs = mapOf(
            "input_ids" to inIds,
            "attention_mask" to inMask,
            "token_type_ids" to inTypes
        )
        val results = session.run(inputs)
        results.close()
        inIds.close()
        inMask.close()
        inTypes.close()
    }

    private fun encodeToFloatArray(text: String): FloatArray? {
        if (!isInitialized) {
            val success = kotlinx.coroutines.runBlocking { initialize() }
            if (!success) return null
        }

        val session = ortSession ?: return null
        val env = ortEnvironment ?: return null

        // 1. Tokenize Text
        val tokens = tokenize(text, MemoryModelConfig.MAX_SEQUENCE_LENGTH)
        val seqLen = tokens.size.toLong()
        val tensorShape = longArrayOf(1, seqLen)

        val maskArray = LongArray(tokens.size) { 1L }
        val typesArray = LongArray(tokens.size) { 0L }

        var inIds: OnnxTensor? = null
        var inMask: OnnxTensor? = null
        var inTypes: OnnxTensor? = null

        return try {
            inIds = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), tensorShape)
            inMask = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArray), tensorShape)
            inTypes = OnnxTensor.createTensor(env, LongBuffer.wrap(typesArray), tensorShape)

            val inputs = mapOf(
                "input_ids" to inIds,
                "attention_mask" to inMask,
                "token_type_ids" to inTypes
            )

            val results = session.run(inputs)
            val outputTensor = results[0].value as Array<FloatArray>
            val rawEmbedding = outputTensor[0] // 256-d normalized float array
            results.close()
            rawEmbedding
        } catch (e: Exception) {
            Log.e(TAG, "ORT embedding inference failed for text: ${text.take(30)}", e)
            null
        } finally {
            inIds?.close()
            inMask?.close()
            inTypes?.close()
        }
    }

    /**
     * Exact BERT WordPiece Tokenizer (preserving casing as configured by mdbr-leaf-ir).
     */
    fun tokenize(text: String, maxLen: Int = MemoryModelConfig.MAX_SEQUENCE_LENGTH): LongArray {
        val tokenIds = ArrayList<Long>()
        tokenIds.add(clsId)

        // Split text by whitespace and basic punctuation boundaries while preserving tokens
        val rawWords = splitTokens(text)

        for (word in rawWords) {
            if (word.isEmpty()) continue
            if (tokenIds.size >= maxLen - 1) break

            // Direct match
            val exactId = vocabMap[word]
            if (exactId != null) {
                tokenIds.add(exactId)
                continue
            }

            // WordPiece subword breakdown
            var isBad = false
            var start = 0
            val subTokens = ArrayList<Long>()

            while (start < word.length) {
                var end = word.length
                var curSubId: Long? = null

                while (start < end) {
                    val subStr = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                    val id = vocabMap[subStr]
                    if (id != null) {
                        curSubId = id
                        break
                    }
                    end--
                }

                if (curSubId == null) {
                    isBad = true
                    break
                }

                subTokens.add(curSubId)
                start = end
            }

            if (isBad || subTokens.isEmpty()) {
                tokenIds.add(unkId)
            } else {
                for (id in subTokens) {
                    if (tokenIds.size >= maxLen - 1) break
                    tokenIds.add(id)
                }
            }
        }

        tokenIds.add(sepId)
        return tokenIds.toLongArray()
    }

    private fun splitTokens(text: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()

        for (ch in text) {
            if (Character.isWhitespace(ch)) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
            } else if (isPunctuation(ch)) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                result.add(ch.toString())
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }

    private fun isPunctuation(ch: Char): Boolean {
        val type = Character.getType(ch)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.START_PUNCTUATION.toInt() ||
                type == Character.END_PUNCTUATION.toInt() ||
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt()
    }
}
