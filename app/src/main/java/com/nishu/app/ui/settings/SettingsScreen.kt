package com.nishu.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.BuildConfig
import com.nishu.app.domain.model.EngineStatus
import com.nishu.app.domain.model.SettingsInfo
import com.nishu.app.domain.repo.SettingsRepository
import com.nishu.app.sync.ManifestFile
import com.nishu.app.sync.ModelComponentStatus
import com.nishu.app.sync.ModelSyncManager
import com.nishu.app.sync.SyncState
import com.nishu.app.ui.bench.availableModels
import com.nishu.app.ui.components.ModelCard
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.TextInputDialog
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuPalette
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val syncManager: ModelSyncManager = AppGraph.modelSync,
) : ViewModel() {
    val state: StateFlow<SettingsInfo?> = repo.info.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val syncState: StateFlow<SyncState> = syncManager.state
    val syncComponents: StateFlow<List<ModelComponentStatus>> = syncManager.components

    fun setName(name: String) = viewModelScope.launch { repo.setUserName(name) }
    fun setVocabulary(vocab: String) = viewModelScope.launch { repo.setCustomVocabulary(vocab) }
    fun setSttModel(modelId: String) = viewModelScope.launch { repo.setSttModel(modelId) }
    fun toggleLanguage() = viewModelScope.launch {
        val current = state.value?.languageCode ?: "hinglish"
        val next = if (current.contains("english", ignoreCase = true)) "hinglish" else "english"
        repo.setLanguage(next)
    }

    fun refreshSync() = viewModelScope.launch { syncManager.refreshStatus() }
    fun syncAll() = syncManager.syncAllMissing()
    fun syncSingle(file: ManifestFile) = syncManager.syncSingle(file)
    fun cancelSync() = syncManager.cancel()
    fun syncForSpec(specId: String) {
        val path = when (specId) {
            "hinglish-swift-int8", "hinglish-swift" -> "stt/hinglish-swift.zip"
            "whisper-base-en-int8", "whisper-base" -> "stt/whisper-base.zip"
            "whisper-small-en-int8", "whisper-small-hinglish-int8", "whisper-small" -> "stt/whisper-small.zip"
            "qwen3-asr-hinglish-int8", "qwen3-hinglish" -> "stt/qwen3-hinglish.zip"
            else -> null
        }
        val match = syncComponents.value.firstOrNull { it.file.path == path || it.file.installsTo.contains(specId) }
        if (match != null) {
            syncManager.syncSingle(match.file)
        } else {
            syncManager.syncAllMissing()
        }
    }
}

@Composable
fun SettingsRoute(onBenchmark: () -> Unit, onDiagnostics: () -> Unit, onOnboarding: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(AppGraph.settings) })
    val info by vm.state.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val syncComponents by vm.syncComponents.collectAsStateWithLifecycle()

    SettingsScreen(
        info = info,
        syncState = syncState,
        syncComponents = syncComponents,
        onSetName = vm::setName,
        onSetVocabulary = vm::setVocabulary,
        onSetSttModel = vm::setSttModel,
        onToggleLanguage = vm::toggleLanguage,
        onBenchmark = onBenchmark,
        onDiagnostics = onDiagnostics,
        onOnboarding = onOnboarding,
        onSyncAll = vm::syncAll,
        onSyncSingle = vm::syncSingle,
        onCancelSync = vm::cancelSync,
        onRefreshSync = vm::refreshSync,
        onSyncSpec = vm::syncForSpec,
        showDeveloper = BuildConfig.DEBUG,
    )
}

@Composable
fun SettingsScreen(
    info: SettingsInfo?,
    syncState: SyncState = SyncState.Idle,
    syncComponents: List<ModelComponentStatus> = emptyList(),
    onSetName: (String) -> Unit,
    onSetVocabulary: (String) -> Unit,
    onSetSttModel: (String) -> Unit,
    onToggleLanguage: () -> Unit,
    onBenchmark: () -> Unit,
    onDiagnostics: () -> Unit,
    onOnboarding: () -> Unit = {},
    onSyncAll: () -> Unit = {},
    onSyncSingle: (ManifestFile) -> Unit = {},
    onCancelSync: () -> Unit = {},
    onRefreshSync: () -> Unit = {},
    onSyncSpec: (String) -> Unit = {},
    showDeveloper: Boolean,
) {
    val context = LocalContext.current
    var editingName by remember { mutableStateOf(false) }
    var editingVocabulary by remember { mutableStateOf(false) }
    var developerOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = Dimens.ContentMaxWidth)
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            NishuTopBar("Settings")

            if (info != null) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    // App Brand & Logo Card
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nishuCard()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Image(
                                painter = painterResource(com.nishu.app.R.drawable.ic_nishu_logo),
                                contentDescription = "Nishu Logo",
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Nishu AI",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Private, offline AI companion • v${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Hugging Face Model Hub Card (OTA Downloader)
                    item {
                        HuggingFaceSyncCard(
                            syncState = syncState,
                            components = syncComponents,
                            onSyncAll = onSyncAll,
                            onSyncSingle = onSyncSingle,
                            onCancelSync = onCancelSync,
                            onRefresh = onRefreshSync,
                        )
                    }

                    // STT Models Section (Screen J in reference)
                    item {
                        Text(
                            text = "STT Models (Select Model)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )

                        val sttRoot = remember { java.io.File(context.filesDir, "models/stt") }
                        val models = remember {
                            listOf(
                                Triple(com.nishu.app.stt.SttModelSpec.HINGLISH_SWIFT, "Swift Roman Hinglish (Base - 72M)", Color(0xFF10B981)),
                                Triple(com.nishu.app.stt.SttModelSpec.WHISPER_SMALL_EN, "Whisper Small High Accuracy (244M) 🔥 Max Accuracy", Color(0xFF2563EB)),
                                Triple(com.nishu.app.stt.SttModelSpec.WHISPER_BASE_EN, "Whisper Base Multilingual (90M)", Color(0xFF0284C7)),
                                Triple(com.nishu.app.stt.SttModelSpec.QWEN3_HINGLISH, "Qwen3 Hinglish (0.6B) 🚀 SOTA", Color(0xFF8B5CF6)),
                                Triple(com.nishu.app.stt.SttModelSpec.TINY_EN, "Fallback English (39M)", Color(0xFFD97706)),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Option: Auto select
                            val isAuto = info.selectedSttModel == "auto"
                            ModelCard(
                                name = "Auto (Best model for language)",
                                sizeLabel = "Automatically picks the best installed model • Active: ${info.sttLabel}",
                                iconTint = Color(0xFF8B5CF6),
                                installed = true,
                                checked = isAuto,
                                onCheckedChange = { onSetSttModel("auto") },
                            )
                            models.forEach { (spec, desc, tint) ->
                                val installed = spec.isInstalled(sttRoot)
                                val isSelected = info.selectedSttModel == spec.id || info.selectedSttModel == spec.dir
                                ModelCard(
                                    name = spec.label,
                                    sizeLabel = "$desc • ${if (installed) "Installed" else "Not installed"}",
                                    iconTint = tint,
                                    installed = installed,
                                    checked = isSelected,
                                    onCheckedChange = if (installed) { { onSetSttModel(if (isSelected) "auto" else spec.id) } } else null,
                                    onActionClick = if (!installed) { { onSyncSpec(spec.id) } } else null,
                                )
                            }
                        }
                    }

                    // App Settings Section (Screen J in reference)
                    item {
                        Text(
                            text = "App Settings",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                        )

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .nishuCard(),
                        ) {
                            SettingsRow(
                                icon = Icons.Rounded.Language,
                                label = "Language",
                                value = if (info.languageCode == "english") "English" else "Hinglish",
                                onClick = onToggleLanguage,
                            )
                            SettingsRow(
                                icon = Icons.Rounded.Folder,
                                label = "Storage Location",
                                value = "On this phone only",
                            )
                            SettingsRow(
                                icon = Icons.Rounded.Palette,
                                label = "Theme",
                                value = "Light",
                            )
                            val vocabCount = if (info.customVocabulary.isBlank()) 0 else {
                                info.customVocabulary.split(Regex("[,\\r\\n]+")).filter { it.isNotBlank() }.size
                            }
                            SettingsRow(
                                icon = Icons.Rounded.Code,
                                label = "Custom Vocabulary",
                                value = if (vocabCount == 0) "Manage >" else "$vocabCount term${if (vocabCount > 1) "s" else ""} >",
                                onClick = { editingVocabulary = true },
                            )
                            SettingsRow(
                                icon = Icons.Rounded.Info,
                                label = "About",
                                value = "v${BuildConfig.VERSION_NAME} • works offline",
                            )
                            SettingsRow(
                                icon = Icons.Rounded.AutoAwesome,
                                label = "Welcome Tour",
                                value = "View >",
                                onClick = onOnboarding,
                            )
                        }
                    }

                    // Developer Diagnostics
                    if (showDeveloper) {
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .nishuCard(),
                            ) {
                                SettingsRow(
                                    icon = Icons.Rounded.Build,
                                    label = "Developer Tools",
                                    value = if (developerOpen) "Hide" else "Show",
                                    onClick = { developerOpen = !developerOpen },
                                )
                                if (developerOpen) {
                                    SettingsRow(Icons.Rounded.Speed, "Model Compare & Benchmarks", onClick = onBenchmark)
                                    SettingsRow(Icons.Rounded.Terminal, "Audio Pipeline Diagnostics", onClick = onDiagnostics)
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (editingName && info != null) {
        TextInputDialog("Your name", info.userName, "Save", { onSetName(it); editingName = false }, { editingName = false })
    }

    if (editingVocabulary && info != null) {
        TextInputDialog(
            title = "Custom Vocabulary",
            initial = info.customVocabulary,
            confirmLabel = "Save",
            subtitle = "Add project terms, CLI commands, teammate names, or tech keywords (e.g. nested_fs.sh, Terraform, chmod, Ubuntu) to guarantee 100% accurate recognition.",
            allowBlank = true,
            singleLine = false,
            onConfirm = { onSetVocabulary(it); editingVocabulary = false },
            onDismiss = { editingVocabulary = false },
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            trailing()
        } else if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        if (onClick != null && trailing == null) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun HuggingFaceSyncCard(
    syncState: SyncState,
    components: List<ModelComponentStatus>,
    onSyncAll: () -> Unit,
    onSyncSingle: (ManifestFile) -> Unit,
    onCancelSync: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val allInstalled = components.isNotEmpty() && components.all { it.isInstalled }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .nishuCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFEF3C7)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.CloudDownload,
                    contentDescription = null,
                    tint = Color(0xFFD97706),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Model Hub (Hugging Face)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "nishantj-123/Nishant_finetune",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = NishuPalette.Primary,
                )
            }

            if (syncState is SyncState.Downloading) {
                Surface(
                    onClick = onCancelSync,
                    shape = CircleShape,
                    color = Color(0xFFFFEBEB),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = NishuPalette.Danger, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = NishuPalette.Danger)
                    }
                }
            } else {
                Surface(
                    onClick = {
                        if (allInstalled) onRefresh() else onSyncAll()
                    },
                    shape = CircleShape,
                    color = if (allInstalled) NishuPalette.Mint else NishuPalette.Primary,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (allInstalled) Icons.Rounded.Check else Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            tint = if (allInstalled) NishuPalette.Primary else Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (allInstalled) "Up to Date" else "Sync All",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (allInstalled) NishuPalette.Primary else Color.White,
                        )
                    }
                }
            }
        }

        // Active State Display
        when (syncState) {
            is SyncState.Checking -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NishuPalette.Primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking Hugging Face repository...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is SyncState.Downloading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF0FDF4))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Downloading: ${syncState.currentFileName.substringAfterLast("/")}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = NishuPalette.Primary,
                        )
                        Text(
                            "${(syncState.currentProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = NishuPalette.Primary,
                        )
                    }

                    LinearProgressIndicator(
                        progress = { syncState.currentProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = NishuPalette.Primary,
                        trackColor = Color(0xFFD1FAE5),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${formatBytes(syncState.currentBytes)} / ${formatBytes(syncState.currentTotalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (syncState.speedBytesPerSec > 0) "${formatBytes(syncState.speedBytesPerSec)}/s" else "Connecting...",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            is SyncState.Unpacking -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEF3C7).copy(alpha = 0.5f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFD97706))
                    Spacer(Modifier.width(10.dp))
                    Text("Extracting ${syncState.currentFileName}...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E))
                }
            }
            is SyncState.Completed -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NishuPalette.Mint)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = NishuPalette.Primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(syncState.message, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = NishuPalette.Primary)
                }
            }
            is SyncState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFEBEB))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(syncState.error, style = MaterialTheme.typography.bodySmall, color = NishuPalette.Danger)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Surface(onClick = onRefresh, shape = CircleShape, color = Color.White) {
                            Text("Retry", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = NishuPalette.Danger)
                        }
                    }
                }
            }
            else -> {}
        }

        // Expand/collapse component breakdown
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val installedCount = components.count { it.isInstalled }
            val totalCount = components.size
            Text(
                if (totalCount > 0) "$installedCount of $totalCount components installed" else "Models in repository",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (expanded) "Hide details ▲" else "View details ▼",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NishuPalette.Primary,
            )
        }

        if (expanded && components.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                components.forEach { comp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                comp.file.displayName,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${comp.file.sizeFormatted} • ${comp.file.description}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (comp.isInstalled) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(NishuPalette.Mint)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("Installed", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = NishuPalette.Primary)
                            }
                        } else {
                            Surface(
                                onClick = { onSyncSingle(comp.file) },
                                shape = CircleShape,
                                color = NishuPalette.Primary,
                            ) {
                                Text("Download", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() = NishuTheme {
    SettingsScreen(
        info = PreviewData.settings,
        syncState = SyncState.Idle,
        syncComponents = emptyList(),
        onSetName = {},
        onSetVocabulary = {},
        onSetSttModel = {},
        onToggleLanguage = {},
        onBenchmark = {},
        onDiagnostics = {},
        onSyncAll = {},
        onSyncSingle = {},
        onCancelSync = {},
        onRefreshSync = {},
        onSyncSpec = {},
        showDeveloper = true,
    )
}
