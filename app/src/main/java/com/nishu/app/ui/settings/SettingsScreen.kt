package com.nishu.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.BuildConfig
import com.nishu.app.domain.model.EngineStatus
import com.nishu.app.domain.model.SettingsInfo
import com.nishu.app.domain.repo.SettingsRepository
import com.nishu.app.ui.components.BadgeKind
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.StatusBadge
import com.nishu.app.ui.components.TextInputDialog
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.formatBytes
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val state: StateFlow<SettingsInfo?> = repo.info.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setName(name: String) = viewModelScope.launch { repo.setUserName(name) }
}

@Composable
fun SettingsRoute(onBenchmark: () -> Unit, onDiagnostics: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(AppGraph.settings) })
    val info by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(info, vm::setName, onBenchmark, onDiagnostics, showDeveloper = BuildConfig.DEBUG)
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        Column(Modifier.fillMaxWidth().nishuCard().padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, label: String, value: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (trailing != null) trailing() else if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsScreen(
    info: SettingsInfo?,
    onSetName: (String) -> Unit,
    onBenchmark: () -> Unit,
    onDiagnostics: () -> Unit,
    showDeveloper: Boolean,
) {
    var editingName by remember { mutableStateOf(false) }
    var developerOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar("Settings")
            if (info != null) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
                ) {
                    item {
                        SettingsSection("AI Model") {
                            SettingsRow(Icons.Rounded.Memory, "Model", info.modelLabel)
                            SettingsRow(Icons.Rounded.Tune, "Runtime", info.runtimeLabel)
                            SettingsRow(Icons.Rounded.PhoneAndroid, "Device", info.soc)
                            SettingsRow(Icons.Rounded.VerifiedUser, "Status", trailing = {
                                when (info.engineStatus) {
                                    EngineStatus.READY -> StatusBadge("● On-device • Ready", BadgeKind.SUCCESS)
                                    EngineStatus.NOT_LOADED -> StatusBadge("Not loaded", BadgeKind.NEUTRAL)
                                    EngineStatus.MODEL_MISSING -> StatusBadge("Model missing", BadgeKind.DANGER)
                                }
                            })
                        }
                    }
                    item {
                        SettingsSection("Audio & Transcription") {
                            SettingsRow(Icons.Rounded.GraphicEq, "STT Model", info.sttLabel)
                            SettingsRow(Icons.Rounded.Language, "Language", info.languageLabel)
                            SettingsRow(Icons.Rounded.Mic, "Microphone", "System Default")
                        }
                    }
                    item {
                        SettingsSection("Profile") {
                            SettingsRow(Icons.Rounded.Person, "Your name", info.userName.ifBlank { "Not set" }, onClick = { editingName = true })
                        }
                    }
                    item {
                        SettingsSection("Storage & Data") {
                            Column(Modifier.padding(16.dp)) {
                                Row {
                                    Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(14.dp))
                                    Text("Storage Used", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${formatBytes(info.storageUsedBytes)} / ${formatBytes(info.storageTotalBytes)}",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { (info.storageUsedBytes.toFloat() / info.storageTotalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            }
                        }
                    }
                    if (showDeveloper) {
                        item {
                            SettingsSection("Developer") {
                                SettingsRow(
                                    Icons.Rounded.Build, "Developer options",
                                    value = if (developerOpen) "Hide" else "Show", onClick = { developerOpen = !developerOpen },
                                )
                                if (developerOpen) {
                                    SettingsRow(Icons.Rounded.Speed, "Benchmark", onClick = onBenchmark)
                                    SettingsRow(Icons.Rounded.Terminal, "Diagnostics", onClick = onDiagnostics)
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
}

@Preview(widthDp = 360, heightDp = 860, showBackground = true)
@Composable
private fun SettingsPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        SettingsScreen(PreviewData.settings, {}, {}, {}, showDeveloper = true)
    }
}
