package com.nishu.app.data

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.nishu.app.domain.model.EngineStatus
import com.nishu.app.domain.model.SettingsInfo
import com.nishu.app.domain.repo.SettingsRepository
import com.nishu.app.llm.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import java.io.File

class RealSettingsRepository(private val context: Context) : SettingsRepository {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val name = MutableStateFlow(prefs.getString("user_name", "") ?: "")
    private val language = MutableStateFlow(prefs.getString("audio_language", "hinglish") ?: "hinglish")
    private val model = ModelInfo(context)

    override val info: Flow<SettingsInfo> = kotlinx.coroutines.flow.combine(name, language) { userName, lang ->
        val sttRoot = File(context.filesDir, "models/stt")
        val currentStt = com.nishu.app.stt.SttModelSpec.select(sttRoot, lang)
        val langLabel = if (lang.equals("english", ignoreCase = true)) "English (Meetings)" else "Hinglish (Conversations)"
        SettingsInfo(
            modelLabel = model.label(),
            runtimeLabel = "llama.cpp (CPU)",
            soc = soc(),
            // The model loads on demand, so an installed model is "ready"; only a missing file is a problem.
            engineStatus = if (model.exists) EngineStatus.READY else EngineStatus.MODEL_MISSING,
            sttLabel = currentStt.label,
            languageLabel = langLabel,
            storageUsedBytes = usedBytes(),
            storageTotalBytes = StatFs(context.filesDir.absolutePath).totalBytes,
            userName = userName,
            languageCode = lang,
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun setUserName(name: String) {
        prefs.edit().putString("user_name", name).apply()
        this.name.value = name
    }

    override suspend fun setLanguage(language: String) {
        prefs.edit().putString("audio_language", language).apply()
        this.language.value = language
    }

    private fun soc(): String {
        val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        val maker = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        return listOf(maker, model).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ").ifBlank { Build.HARDWARE }
    }

    private fun usedBytes(): Long =
        listOf("recordings", "models", "kvcache", "databases").sumOf { dir(File(context.filesDir.parentFile, if (it == "databases") it else "files/$it")) }

    private fun dir(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
