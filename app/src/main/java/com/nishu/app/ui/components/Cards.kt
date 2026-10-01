package com.nishu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContactPhone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nishu.app.domain.model.Confidence
import com.nishu.app.domain.model.ConversationCategory
import com.nishu.app.domain.model.ConversationStatus
import com.nishu.app.domain.model.ConversationUiModel
import com.nishu.app.domain.model.DecisionUiModel
import com.nishu.app.domain.model.MemoryFactUiModel
import com.nishu.app.domain.model.MemoryKind
import com.nishu.app.domain.model.TaskUiModel
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme

data class TileStyle(val icon: ImageVector, val tint: Color)

fun categoryStyle(category: ConversationCategory): TileStyle = when (category) {
    ConversationCategory.MEETING -> TileStyle(Icons.Rounded.Groups, Color(0xFFE08A2E))
    ConversationCategory.CALL -> TileStyle(Icons.Rounded.Call, Color(0xFF22A860))
    ConversationCategory.WORK -> TileStyle(Icons.Rounded.Work, Color(0xFF8B5CF6))
    ConversationCategory.PERSONAL -> TileStyle(Icons.Rounded.Person, Color(0xFF4F8CFF))
    ConversationCategory.OTHER -> TileStyle(Icons.Rounded.Forum, Color(0xFF7A7F99))
}

fun categoryLabel(category: ConversationCategory): String = when (category) {
    ConversationCategory.MEETING -> "Meeting"
    ConversationCategory.CALL -> "Call"
    ConversationCategory.WORK -> "Work"
    ConversationCategory.PERSONAL -> "Personal"
    ConversationCategory.OTHER -> "Other"
}

fun memoryStyle(kind: MemoryKind): TileStyle = when (kind) {
    MemoryKind.FACT -> TileStyle(Icons.Rounded.Lightbulb, Color(0xFF4F8CFF))
    MemoryKind.PREFERENCE -> TileStyle(Icons.Rounded.Favorite, Color(0xFFE0568A))
    MemoryKind.CONTACT -> TileStyle(Icons.Rounded.ContactPhone, Color(0xFF22A860))
    MemoryKind.OTHER -> TileStyle(Icons.Rounded.AutoAwesome, Color(0xFF8B5CF6))
}

private val speakerPalette = listOf(Color(0xFF635BFF), Color(0xFF14A38B), Color(0xFFE08A2E), Color(0xFFE0568A), Color(0xFF4F8CFF), Color(0xFF8B5CF6))

/** A speaker keeps the same colour on every screen: the palette is indexed by the speaker's number. */
fun speakerColor(index: Int): Color = speakerPalette[index.coerceAtLeast(0) % speakerPalette.size]

@Composable
fun SpeakerChip(name: String, index: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val color = speakerColor(index)
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Rename speaker", onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun IconTile(style: TileStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(Dimens.IconTile)
            .clip(MaterialTheme.shapes.small)
            .background(style.tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(style.icon, contentDescription = null, tint = style.tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun StatCard(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .nishuCard()
            .padding(vertical = 16.dp, horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$value $label" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class MenuAction(val label: String, val onClick: () -> Unit)

@Composable
fun OverflowMenu(actions: List<MenuAction>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, Modifier.size(Dimens.MinTouchTarget)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; a.onClick() })
            }
        }
    }
}

@Composable
fun ConversationCard(
    item: ConversationUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: List<MenuAction> = emptyList(),
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().pressScale(source).nishuCard(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(categoryStyle(item.category))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.timestampLabel} • ${item.durationLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.status != ConversationStatus.DONE) {
                val (text, kind) = when (item.status) {
                    ConversationStatus.FAILED -> "Failed" to BadgeKind.DANGER
                    ConversationStatus.RECORDING -> "Recording" to BadgeKind.WARNING
                    else -> "Processing" to BadgeKind.INFO
                }
                StatusBadge(text, kind)
            }
            if (menu.isNotEmpty()) OverflowMenu(menu) else Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
fun MemoryCard(item: MemoryFactUiModel, modifier: Modifier = Modifier, menu: List<MenuAction> = emptyList()) {
    Row(
        modifier.fillMaxWidth().nishuCard().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(memoryStyle(item.kind))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.sourceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (menu.isNotEmpty()) OverflowMenu(menu) else Spacer(Modifier.width(12.dp))
    }
}

@Composable
fun TaskCard(item: TaskUiModel, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().nishuCard().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.done, onCheckedChange = onToggle)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                item.text,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                item.dueHint?.let { StatusBadge(it, BadgeKind.INFO) }
                if (item.confidence == Confidence.LOW) StatusBadge("Low confidence", BadgeKind.WARNING)
            }
        }
    }
}

@Composable
fun DecisionCard(item: DecisionUiModel, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (item.confidence == Confidence.LOW) {
                StatusBadge("Low confidence", BadgeKind.WARNING, Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun SummaryCard(bullets: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().nishuCard().padding(Dimens.CardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("AI Summary", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(12.dp))
        bullets.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
fun KeyPointRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LinkRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
