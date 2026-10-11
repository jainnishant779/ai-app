package com.nishu.app.sync

import org.json.JSONObject

data class ModelManifest(
    val generated: String,
    val files: List<ManifestFile>,
) {
    companion object {
        fun fromJson(jsonStr: String): ModelManifest {
            val root = JSONObject(jsonStr)
            val filesList = mutableListOf<ManifestFile>()
            val arr = root.optJSONArray("files") ?: return ModelManifest(root.optString("generated"), emptyList())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val containsList = mutableListOf<String>()
                val cArr = obj.optJSONArray("contains")
                if (cArr != null) {
                    for (j in 0 until cArr.length()) containsList.add(cArr.getString(j))
                }
                filesList.add(
                    ManifestFile(
                        path = obj.getString("path"),
                        installsTo = obj.getString("installsTo"),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        sha256 = obj.optString("sha256", ""),
                        unpackedBytes = obj.optLong("unpackedBytes", 0L),
                        contains = containsList,
                    ),
                )
            }
            return ModelManifest(root.optString("generated"), filesList)
        }
    }
}

data class ManifestFile(
    val path: String,
    val installsTo: String,
    val sizeBytes: Long,
    val sha256: String,
    val unpackedBytes: Long,
    val contains: List<String>,
) {
    val isZip: Boolean get() = path.endsWith(".zip", ignoreCase = true)

    val displayName: String get() = when {
        path.contains("nishu-Q4_K_M", ignoreCase = true) || path.contains("llm", ignoreCase = true) -> "Nishu LLM (0.6B Q4_K_M)"
        path.contains("hinglish-swift", ignoreCase = true) -> "Hinglish Swift STT"
        path.contains("whisper-base", ignoreCase = true) -> "Whisper Base STT"
        path.contains("whisper-small", ignoreCase = true) -> "Whisper Small STT"
        path.contains("diar", ignoreCase = true) -> "Speaker Diarization & Biometrics"
        path.contains("silero_vad", ignoreCase = true) -> "Silero VAD"
        else -> path.substringAfterLast("/")
    }

    val description: String get() = when {
        path.contains("nishu-Q4_K_M", ignoreCase = true) || path.contains("llm", ignoreCase = true) -> "Fine-tuned on-device summarization & task extractor"
        path.contains("hinglish-swift", ignoreCase = true) -> "Fast Hinglish roman speech recognizer"
        path.contains("whisper-base", ignoreCase = true) -> "Multilingual speech-to-text recognizer"
        path.contains("whisper-small", ignoreCase = true) -> "High accuracy speech recognizer"
        path.contains("diar", ignoreCase = true) -> "3D-Speaker CAM++ biometric speaker memory"
        path.contains("silero_vad", ignoreCase = true) -> "Ultra-fast voice activity detector"
        else -> "On-device AI component"
    }

    val sizeFormatted: String get() = when {
        sizeBytes <= 0 -> "0 B"
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        sizeBytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes.toDouble() / (1024 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", sizeBytes.toDouble() / (1024 * 1024 * 1024))
    }
}
