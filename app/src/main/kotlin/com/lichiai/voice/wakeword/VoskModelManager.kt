package com.lichiai.voice.wakeword

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

sealed interface VoskModelState {
    data object NotInstalled : VoskModelState
    data class Downloading(val progress: Float, val statusText: String) : VoskModelState
    data class Extracting(val statusText: String) : VoskModelState
    data class Ready(val path: String) : VoskModelState
    data class Error(val message: String) : VoskModelState
}

object VoskModelManager {

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR_NAME = "vosk-model"

    private val mutex = Mutex()
    private var cachedModel: Model? = null

    private val _modelState = MutableStateFlow<VoskModelState>(VoskModelState.NotInstalled)
    val modelState: StateFlow<VoskModelState> = _modelState.asStateFlow()

    fun checkModelState(context: Context): VoskModelState {
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        val validDir = findValidModelDir(modelDir)
        if (validDir != null) {
            val state = VoskModelState.Ready(validDir.absolutePath)
            _modelState.value = state
            return state
        }
        val current = _modelState.value
        if (current !is VoskModelState.Downloading && current !is VoskModelState.Extracting) {
            _modelState.value = VoskModelState.NotInstalled
        }
        return _modelState.value
    }

    suspend fun getOrInitModel(
        context: Context,
        onStatus: (String) -> Unit = {}
    ): Model? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (cachedModel != null) {
                return@withContext cachedModel
            }

            try {
                val modelDir = File(context.filesDir, MODEL_DIR_NAME)
                var validDir = findValidModelDir(modelDir)

                if (validDir == null) {
                    // Try to extract from assets if available
                    onStatus("Checking app assets for bundled model...")
                    extractModelFromAssets(context, modelDir)
                    validDir = findValidModelDir(modelDir)
                }

                if (validDir != null) {
                    onStatus("Loading Vosk acoustic model...")
                    _modelState.value = VoskModelState.Ready(validDir.absolutePath)
                    val model = Model(validDir.absolutePath)
                    cachedModel = model
                    onStatus("Model loaded successfully")
                    return@withContext model
                } else {
                    _modelState.value = VoskModelState.NotInstalled
                    onStatus("No acoustic model installed")
                    return@withContext null
                }
            } catch (e: Throwable) {
                _modelState.value = VoskModelState.Error(e.message ?: "Model loading failed")
                onStatus("Model loading exception: ${e.message}")
                return@withContext null
            }
        }
    }

    suspend fun downloadAndInstallModel(
        context: Context,
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val targetDir = File(context.filesDir, MODEL_DIR_NAME)
            val existing = findValidModelDir(targetDir)
            if (existing != null) {
                _modelState.value = VoskModelState.Ready(existing.absolutePath)
                return@withContext true
            }

            val tempZip = File(context.cacheDir, "vosk_model_temp.zip")
            val connection: HttpURLConnection?
            try {
                _modelState.value = VoskModelState.Downloading(0.01f, "Connecting to model server...")
                onStatus("Connecting to model server...")

                val url = URL(MODEL_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 LichiAI-Android")
                }
                connection = conn
                conn.connect()

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Download failed with HTTP code: $responseCode")
                }

                val totalLength = conn.contentLength.toLong()
                var downloadedBytes = 0L

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var lastProgressUpdate = 0L

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 150) {
                                lastProgressUpdate = now
                                val progress = if (totalLength > 0) {
                                    (downloadedBytes.toFloat() / totalLength).coerceIn(0f, 1f)
                                } else 0.5f

                                val mbDownloaded = downloadedBytes / (1024 * 1024)
                                val mbTotal = if (totalLength > 0) totalLength / (1024 * 1024) else 40
                                val status = "$mbDownloaded MB / $mbTotal MB (${(progress * 100).toInt()}%)"
                                _modelState.value = VoskModelState.Downloading(progress, status)
                                onStatus(status)
                            }
                        }
                    }
                }

                _modelState.value = VoskModelState.Extracting("Extracting acoustic model...")
                onStatus("Extracting acoustic model...")

                targetDir.mkdirs()
                tempZip.inputStream().use { inputStream ->
                    unzip(inputStream, targetDir)
                }
                tempZip.delete()

                val validDir = findValidModelDir(targetDir)
                if (validDir != null) {
                    _modelState.value = VoskModelState.Ready(validDir.absolutePath)
                    onStatus("Model downloaded and ready!")
                    try {
                        cachedModel?.close()
                    } catch (_: Throwable) {}
                    cachedModel = Model(validDir.absolutePath)
                    return@withContext true
                } else {
                    _modelState.value = VoskModelState.Error("Model files extraction incomplete")
                    return@withContext false
                }
            } catch (e: CancellationException) {
                tempZip.delete()
                _modelState.value = VoskModelState.NotInstalled
                throw e
            } catch (e: Throwable) {
                tempZip.delete()
                val errorMsg = e.message ?: "Failed to download model"
                _modelState.value = VoskModelState.Error(errorMsg)
                onStatus("Download error: $errorMsg")
                return@withContext false
            }
        }
    }

    private fun findValidModelDir(baseDir: File): File? {
        if (!baseDir.exists() || !baseDir.isDirectory) return null

        // Check if current directory has model contents
        if (isModelFolder(baseDir)) return baseDir

        // Check immediate subdirectories (e.g. if zip unpacked into vosk-model-small-en-us-0.15)
        val children = baseDir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory && isModelFolder(child)) {
                return child
            }
        }
        return null
    }

    private fun isModelFolder(dir: File): Boolean {
        val files = dir.list() ?: return false
        return files.contains("am") || files.contains("conf") || files.contains("graph") || files.any { it.endsWith(".conf") }
    }

    private fun extractModelFromAssets(context: Context, targetDir: File) {
        try {
            val assetManager = context.assets
            val assetFiles = assetManager.list("") ?: return
            
            val zipFile = assetFiles.firstOrNull { it.endsWith(".zip") && (it.contains("model") || it.contains("vosk")) }
            if (zipFile != null) {
                targetDir.mkdirs()
                assetManager.open(zipFile).use { input ->
                    unzip(input, targetDir)
                }
                return
            }

            val modelSubdir = assetFiles.firstOrNull { it == "model" || it == "vosk-model" || it == "model-en-us" }
            if (modelSubdir != null) {
                targetDir.mkdirs()
                copyAssetFolder(context, modelSubdir, targetDir)
            }
        } catch (_: Throwable) {
            // Best effort
        }
    }

    private fun copyAssetFolder(context: Context, srcFolder: String, destDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(srcFolder) ?: return
        if (files.isEmpty()) {
            destDir.parentFile?.mkdirs()
            assetManager.open(srcFolder).use { input ->
                FileOutputStream(destDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            destDir.mkdirs()
            for (file in files) {
                val subSrc = if (srcFolder.isEmpty()) file else "$srcFolder/$file"
                val subDest = File(destDir, file)
                copyAssetFolder(context, subSrc, subDest)
            }
        }
    }

    private fun unzip(inputStream: InputStream, targetDir: File) {
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val safeName = entry.name.replace("\\", "/")
                val newFile = File(targetDir, safeName)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun deleteModel(context: Context): Boolean {
        return try {
            release()
            val modelDir = File(context.filesDir, MODEL_DIR_NAME)
            modelDir.deleteRecursively()
            _modelState.value = VoskModelState.NotInstalled
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun release() {
        try {
            cachedModel?.close()
        } catch (_: Throwable) {}
        cachedModel = null
    }
}
