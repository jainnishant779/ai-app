package com.nishu.app.ui.settings

import android.widget.Toast
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val state: StateFlow<SettingsInfo?> = repo.info.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setName(name: String) = viewModelScope.launch { repo.setUserName(name) }
    fun setVocabulary(vocab: String) = viewModelScope.launch { repo.setCustomVocabulary(vocab) }
    fun toggleLanguage() = viewModelScope.launch {
        val current = state.value?.languageCode ?: "hinglish"
        val next = if (current.contains("english", ignoreCase = true)) "hinglish" else "english"
        repo.setLanguage(next)
    }
}

@Composable
fun SettingsRoute(onBenchmark: () -> Unit, onDiagnostics: () -> Unit, onOnboarding: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(AppGraph.settings) })
    val info by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        info = info,
        onSetName = vm::setName,
        onSetVocabulary = vm::setVocabulary,
        onToggleLanguage = vm::toggleLanguage,
        onBenchmark = onBenchmark,
        onDiagnostics = onDiagnostics,
        onOnboarding = onOnboarding,
        showDeveloper = BuildConfig.DEBUG,
    )
}

@Composable
fun SettingsScreen(
    info: SettingsInfo?,
    onSetName: (String) -> Unit,
    onSetVocabulary: (String) -> Unit,
    onToggleLanguage: () -> Unit,
    onBenchmark: () -> Unit,
    onDiagnostics: () -> Unit,
    onOnboarding: () -> Unit = {},
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
                    // STT Models Section (Screen J in reference)
                    item {
                        Text(
                            text = "STT Models",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )

                        // What is actually on this phone; models are installed with tools/push_models.ps1 for now.
                        val sttRoot = remember { java.io.File(context.filesDir, "models/stt") }
                        val models = remember {
                            listOf(
                                Triple(com.nishu.app.stt.SttModelSpec.HINGLISH_SWIFT, "Used for Hindi / Hinglish", Color(0xFF2563EB)),
                                Triple(com.nishu.app.stt.SttModelSpec.WHISPER_BASE_EN, "Used for English", Color(0xFF0284C7)),
                                Triple(com.nishu.app.stt.SttModelSpec.TINY_EN, "Fallback", Color(0xFFD97706)),
                            ).map { (spec, role, tint) -> Triple(spec, "$role • ${if (spec.isInstalled(sttRoot)) "Installed" else "Not installed"}", tint) to spec.isInstalled(sttRoot) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            models.forEach { (m, installed) ->
                                ModelCard(name = m.first.label, sizeLabel = m.second, iconTint = m.third, installed = installed)
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
                                value = if (info.languageCode == "english") "English" else "Hindi / Hinglish",
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

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() = NishuTheme {
    SettingsScreen(
        info = PreviewData.settings,
        onSetName = {},
        onSetVocabulary = {},
        onToggleLanguage = {},
        onBenchmark = {},
        onDiagnostics = {},
        showDeveloper = true,
    )
}
