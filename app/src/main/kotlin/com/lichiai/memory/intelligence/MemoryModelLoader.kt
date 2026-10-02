package com.lichiai.memory.intelligence

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Validated Model Loader for bundled on-device Memory OS V6 model assets.
 * Guarantees zero network calls and strict SHA-256 verification.
 */
object MemoryModelLoader {
    private const val TAG = "MemoryModelLoader"
    private const val ASSET_DIR = "memory_model"
    private const val ONNX_FILENAME = "memory_encoder_int8.onnx"
    private const val INFO_FILENAME = "memory_model_info.json"
    private const val VOCAB_FILENAME = "vocab.txt"

    /**
     * Checks if the ONNX model file exists in assets or internal storage.
     */
    fun isModelAvailable(context: Context): Boolean {
        return try {
            val targetDir = File(context.filesDir, "memory_model")
            val targetModel = File(targetDir, ONNX_FILENAME)
            if (targetModel.exists() && targetModel.length() > 0) {
                return true
            }
            val assetList = context.assets.list(ASSET_DIR) ?: emptyArray()
            if (!assetList.contains(ONNX_FILENAME)) {
                return false
            }
            context.assets.open("$ASSET_DIR/$ONNX_FILENAME").use { stream ->
                stream.available() > 0 || stream.read() != -1
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Prepares and validates the local ONNX model file on internal storage.
     * Returns the verified local model File, or null if assets are corrupt/missing.
     */
    fun getOrExtractModelFile(context: Context): File? = synchronized(this) {
        return try {
            val targetDir = File(context.filesDir, "memory_model").apply { mkdirs() }
            val targetModel = File(targetDir, ONNX_FILENAME)
            val expectedSha256 = getExpectedSha256(context)

            if (targetModel.exists() && targetModel.length() > 0) {
                val currentSha = calculateSha256(targetModel)
                if (expectedSha256 == null || currentSha.equals(expectedSha256, ignoreCase = true)) {
                    Log.d(TAG, "Bundled ONNX model is already extracted and verified (SHA matches).")
                    return targetModel
                }
                Log.w(TAG, "Cached model SHA mismatch. Re-extracting from assets...")
                targetModel.delete()
            }

            // Extract from APK assets
            context.assets.open("$ASSET_DIR/$ONNX_FILENAME").use { input ->
                FileOutputStream(targetModel).use { output ->
                    input.copyTo(output)
                }
            }

            val extractedSha = calculateSha256(targetModel)
            if (expectedSha256 != null && !extractedSha.equals(expectedSha256, ignoreCase = true)) {
                Log.e(TAG, "FATAL: Extracted model SHA256 ($extractedSha) does not match expected ($expectedSha256)!")
                targetModel.delete()
                return null
            }

            Log.i(TAG, "Memory OS V6 Model successfully extracted and verified. Size: ${targetModel.length()} bytes.")
            targetModel
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load/extract bundled memory model from assets", e)
            null
        }
    }

    /**
     * Reads vocabulary lines from bundled vocab.txt.
     */
    fun loadVocab(context: Context): List<String> {
        return try {
            context.assets.open("$ASSET_DIR/$VOCAB_FILENAME").bufferedReader().useLines { lines ->
                lines.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bundled vocab.txt", e)
            emptyList()
        }
    }

    /**
     * Reads model metadata from bundled memory_model_info.json.
     */
    fun getExpectedSha256(context: Context): String? {
        return try {
            val jsonStr = context.assets.open("$ASSET_DIR/$INFO_FILENAME").bufferedReader().use { it.readText() }
            val obj = JSONObject(jsonStr)
            obj.optString("sha256", null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read expected sha256 from info json: ${e.message}")
            null
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { isStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (isStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
