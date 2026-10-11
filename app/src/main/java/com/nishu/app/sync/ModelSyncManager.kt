package com.nishu.app.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

sealed class SyncState {
    object Idle : SyncState()
    object Checking : SyncState()
    data class Downloading(
        val currentFileName: String,
        val itemIndex: Int,
        val totalItems: Int,
        val currentBytes: Long,
        val currentTotalBytes: Long,
        val totalBytesDownloaded: Long,
        val totalBytesToDownload: Long,
        val speedBytesPerSec: Long,
    ) : SyncState() {
        val currentProgress: Float get() = if (currentTotalBytes > 0) (currentBytes.toFloat() / currentTotalBytes).coerceIn(0f, 1f) else 0f
        val overallProgress: Float get() = if (totalBytesToDownload > 0) (totalBytesDownloaded.toFloat() / totalBytesToDownload).coerceIn(0f, 1f) else 0f
    }
    data class Unpacking(val currentFileName: String) : SyncState()
    data class Completed(val message: String) : SyncState()
    data class Error(val error: String) : SyncState()
}

data class ModelComponentStatus(
    val file: ManifestFile,
    val isInstalled: Boolean,
    val sizeOnDisk: Long,
)

class ModelSyncManager(private val context: Context) {
    companion object {
        private const val TAG = "ModelSyncManager"
        const val REPO_NAME = "nishantj-123/Nishant_finetune"
        private const val MANIFEST_URL = "https://huggingface.co/nishantj-123/Nishant_finetune/raw/main/manifest.json"
        private const val RESOLVE_BASE_URL = "https://huggingface.co/nishantj-123/Nishant_finetune/resolve/main/"
        private const val BUFFER_SIZE = 64 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _components = MutableStateFlow<List<ModelComponentStatus>>(emptyList())
    val components: StateFlow<List<ModelComponentStatus>> = _components.asStateFlow()

    val modelsDir: File get() = File(context.filesDir, "models")
    private val cacheManifestFile: File get() = File(context.filesDir, "manifest_cached.json")

    init {
        scope.launch {
            refreshStatus()
        }
    }

    /**
     * Checks installed state of all models listed in the manifest.
     */
    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        _state.value = SyncState.Checking
        try {
            val manifest = getOrFetchManifest()
            val list = mutableListOf<ModelComponentStatus>()
            for (f in manifest.files) {
                val installed = checkIfInstalled(f)
                val diskSize = calculateDiskSize(f)
                list.add(ModelComponentStatus(file = f, isInstalled = installed, sizeOnDisk = diskSize))
            }
            _components.value = list
            _state.value = SyncState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh model status", e)
            _state.value = SyncState.Error("Could not check models: ${e.localizedMessage ?: "Network error"}")
        }
    }

    private fun checkIfInstalled(f: ManifestFile): Boolean {
        val target = File(modelsDir, f.installsTo)
        return if (f.isZip) {
            if (!target.exists() || !target.isDirectory) return false
            if (f.contains.isNotEmpty()) {
                f.contains.all { File(target, it).exists() && File(target, it).length() > 0 }
            } else {
                target.listFiles()?.isNotEmpty() == true
            }
        } else {
            target.exists() && target.length() > 0
        }
    }

    private fun calculateDiskSize(f: ManifestFile): Long {
        val target = File(modelsDir, f.installsTo)
        return if (f.isZip) {
            if (target.exists() && target.isDirectory) {
                target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else 0L
        } else {
            if (target.exists()) target.length() else 0L
        }
    }

    private suspend fun getOrFetchManifest(): ModelManifest = withContext(Dispatchers.IO) {
        // Try fetching fresh manifest from Hugging Face
        try {
            val json = fetchStringUrl(MANIFEST_URL)
            cacheManifestFile.writeText(json)
            return@withContext ModelManifest.fromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Network fetch of manifest failed, checking cached manifest", e)
            if (cacheManifestFile.exists()) {
                return@withContext ModelManifest.fromJson(cacheManifestFile.readText())
            }
            // Fallback default manifest if offline on very first run
            return@withContext defaultEmbeddedManifest()
        }
    }

    fun syncAllMissing() {
        if (_state.value is SyncState.Downloading) return
        syncJob?.cancel()
        syncJob = scope.launch {
            try {
                val manifest = getOrFetchManifest()
                val missingFiles = manifest.files.filter { !checkIfInstalled(it) }
                if (missingFiles.isEmpty()) {
                    _state.value = SyncState.Completed("All models are already installed!")
                    refreshStatus()
                    return@launch
                }

                val totalBytes = missingFiles.sumOf { it.sizeBytes }
                var accumulatedDownloaded = 0L

                missingFiles.forEachIndexed { index, file ->
                    downloadAndInstallFile(
                        file = file,
                        itemIndex = index + 1,
                        totalItems = missingFiles.size,
                        totalBytesToDownload = totalBytes,
                        accumulatedPreviousBytes = accumulatedDownloaded,
                    )
                    accumulatedDownloaded += file.sizeBytes
                }

                _state.value = SyncState.Completed("All models installed successfully!")
                refreshStatus()
            } catch (e: CancellationException) {
                Log.i(TAG, "Model download cancelled by user")
                _state.value = SyncState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Error during model sync", e)
                _state.value = SyncState.Error("Download failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun syncSingle(file: ManifestFile) {
        if (_state.value is SyncState.Downloading) return
        syncJob?.cancel()
        syncJob = scope.launch {
            try {
                downloadAndInstallFile(
                    file = file,
                    itemIndex = 1,
                    totalItems = 1,
                    totalBytesToDownload = file.sizeBytes,
                    accumulatedPreviousBytes = 0L,
                )
                _state.value = SyncState.Completed("${file.displayName} installed successfully!")
                refreshStatus()
            } catch (e: CancellationException) {
                Log.i(TAG, "Model download cancelled by user")
                _state.value = SyncState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading ${file.displayName}", e)
                _state.value = SyncState.Error("Failed to download ${file.displayName}: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun cancel() {
        syncJob?.cancel()
        syncJob = null
        _state.value = SyncState.Idle
    }

    private fun checkStorageAvailable(requiredBytes: Long) {
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val safetyMargin = 50L * 1024 * 1024 // 50MB safety margin
            if (available < requiredBytes + safetyMargin) {
                val needMb = (requiredBytes + safetyMargin) / (1024 * 1024)
                val availMb = available / (1024 * 1024)
                throw IOException("Insufficient storage: need at least ${needMb}MB, only ${availMb}MB free")
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            Log.w(TAG, "StatFs check warning: ${e.message}")
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun downloadAndInstallFile(
        file: ManifestFile,
        itemIndex: Int,
        totalItems: Int,
        totalBytesToDownload: Long,
        accumulatedPreviousBytes: Long,
    ) = withContext(Dispatchers.IO) {
        val requiredBytes = file.sizeBytes + (if (file.isZip) file.unpackedBytes else file.sizeBytes)
        checkStorageAvailable(requiredBytes)

        val downloadUrl = RESOLVE_BASE_URL + file.path
        val tempDir = File(context.cacheDir, "model_downloads").apply { mkdirs() }
        val tempFile = File(tempDir, "${System.currentTimeMillis()}_${file.path.substringAfterLast("/")}")

        try {
            openStreamWithRedirects(downloadUrl) { inputStream, contentLength ->
                val actualSize = if (contentLength > 0) contentLength else file.sizeBytes
                var bytesReadTotal = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L
                var currentSpeed = 0L

                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytes: Int
                    while (inputStream.read(buffer).also { bytes = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, bytes)
                        bytesReadTotal += bytes

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 200) {
                            val timeDeltaSec = (now - lastTime) / 1000.0
                            if (timeDeltaSec > 0) {
                                currentSpeed = ((bytesReadTotal - lastBytes) / timeDeltaSec).toLong()
                            }
                            lastBytes = bytesReadTotal
                            lastTime = now

                            _state.value = SyncState.Downloading(
                                currentFileName = file.displayName,
                                itemIndex = itemIndex,
                                totalItems = totalItems,
                                currentBytes = bytesReadTotal,
                                currentTotalBytes = actualSize,
                                totalBytesDownloaded = accumulatedPreviousBytes + bytesReadTotal,
                                totalBytesToDownload = totalBytesToDownload,
                                speedBytesPerSec = currentSpeed,
                            )
                        }
                    }
                    output.flush()
                }
            }

            // Verify checksum if specified
            if (file.sha256.isNotBlank()) {
                val computedSha = calculateSha256(tempFile)
                if (!computedSha.equals(file.sha256.trim(), ignoreCase = true)) {
                    throw IOException("Checksum verification failed for ${file.displayName}: expected ${file.sha256}, got $computedSha")
                }
                Log.i(TAG, "Checksum verified for ${file.displayName}")
            }

            // Atomic install step
            val destination = File(modelsDir, file.installsTo)
            if (file.isZip) {
                _state.value = SyncState.Unpacking(file.displayName)
                val stagingDir = File(modelsDir, "staging_${System.currentTimeMillis()}_${file.installsTo.replace('/', '_')}")
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()
                try {
                    unzipFile(tempFile, stagingDir)
                    if (file.contains.isNotEmpty()) {
                        val missing = file.contains.filter { !File(stagingDir, it).exists() }
                        if (missing.isNotEmpty()) {
                            throw IOException("Unpacked archive missing required files: $missing")
                        }
                    }
                    destination.parentFile?.mkdirs()
                    if (destination.exists()) {
                        destination.deleteRecursively()
                    }
                    if (!stagingDir.renameTo(destination)) {
                        stagingDir.copyRecursively(destination, overwrite = true)
                        stagingDir.deleteRecursively()
                    }
                } finally {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                }
            } else {
                destination.parentFile?.mkdirs()
                val tempDest = File(destination.parentFile, "${destination.name}.tmp_${System.currentTimeMillis()}")
                if (tempDest.exists()) tempDest.delete()
                tempFile.copyTo(tempDest, overwrite = true)
                if (destination.exists()) destination.delete()
                if (!tempDest.renameTo(destination)) {
                    tempDest.copyTo(destination, overwrite = true)
                    tempDest.delete()
                }
            }
            Log.i(TAG, "Successfully installed ${file.displayName} to $destination")
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun unzipFile(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val canonicalTarget = targetDir.canonicalPath
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                // Security: prevent zip path traversal vulnerability
                if (!newFile.canonicalPath.startsWith(canonicalTarget)) {
                    throw SecurityException("Zip traversal exploit detected: ${entry.name}")
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos, BUFFER_SIZE)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun openStreamWithRedirects(
        initialUrl: String,
        block: (InputStream, Long) -> Unit,
    ) {
        var urlStr = initialUrl
        var redirects = 0
        val maxRedirects = 6

        while (redirects < maxRedirects) {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NishuApp/1.0 (Android; Offline-AI)")
            }

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                val newLocation = conn.getHeaderField("Location")
                conn.disconnect()
                if (newLocation.isNullOrBlank()) {
                    throw IOException("HTTP redirect received with no Location header")
                }
                urlStr = newLocation
                redirects++
                continue
            }

            if (status != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("Server returned HTTP $status: ${conn.responseMessage}")
            }

            val length = conn.contentLengthLong
            conn.inputStream.use { stream ->
                block(stream, length)
            }
            conn.disconnect()
            return
        }
        throw IOException("Too many redirects ($redirects) fetching $initialUrl")
    }

    private fun fetchStringUrl(urlStr: String): String {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "NishuApp/1.0 (Android; Offline-AI)")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun defaultEmbeddedManifest(): ModelManifest = ModelManifest(
        generated = "2026-10-05T13:38:43",
        files = listOf(
            ManifestFile(
                path = "llm/nishu-Q4_K_M.gguf",
                installsTo = "llm/model.gguf",
                sizeBytes = 396704544,
                sha256 = "bf4589614654c2b4a9c476fbe6abbe0b594b660cfe9ae2c3f302e27c57a16437",
                unpackedBytes = 0,
                contains = emptyList(),
            ),
            ManifestFile(
                path = "stt/silero_vad.onnx",
                installsTo = "stt/silero_vad.onnx",
                sizeBytes = 643854,
                sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                unpackedBytes = 0,
                contains = emptyList(),
            ),
            ManifestFile(
                path = "stt/hinglish-swift.zip",
                installsTo = "stt/hinglish-swift/",
                sizeBytes = 133612290,
                sha256 = "fa9bcb01ca4b6414cdbb0d3ee23779052858744cf1e0142a53e3a174c72130c7",
                unpackedBytes = 160630827,
                contains = listOf(
                    "hinglish-swift-encoder.int8.onnx",
                    "hinglish-swift-decoder.int8.onnx",
                    "hinglish-swift-tokens.txt",
                ),
            ),
            ManifestFile(
                path = "stt/whisper-base.zip",
                installsTo = "stt/whisper-base/",
                sizeBytes = 94464537,
                sha256 = "6a1ad2fb93dc2c5a93aaca175325cc33d057e7f0f61a99fbacc8f8cf32620c0c",
                unpackedBytes = 160609290,
                contains = listOf(
                    "base-encoder.int8.onnx",
                    "base-decoder.int8.onnx",
                    "base-tokens.txt",
                ),
            ),
            ManifestFile(
                path = "stt/diar.zip",
                installsTo = "stt/diar/",
                sizeBytes = 26983889,
                sha256 = "9f9fcbe7fdf8e9d1f18d81b24f22bd322ff5147d94779f431fece528bc965033",
                unpackedBytes = 29821670,
                contains = listOf(
                    "pyannote-segmentation-3-0.int8.onnx",
                    "3dspeaker-campplus-zh-en.onnx",
                ),
            ),
        ),
    )
}
