package com.nishu.app.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContactPhone
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishu.app.domain.model.Confidence
import com.nishu.app.domain.model.ConversationCategory
import com.nishu.app.domain.model.ConversationStatus
import com.nishu.app.domain.model.ConversationUiModel
import com.nishu.app.domain.model.DecisionUiModel
import com.nishu.app.domain.model.MemoryFactUiModel
import com.nishu.app.domain.model.MemoryKind
import com.nishu.app.domain.model.TaskUiModel
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuPalette
import com.nishu.app.ui.theme.NishuTheme

data class CategoryStyle(val label: String, val bg: Color, val fg: Color)

fun categoryStyle(category: ConversationCategory): CategoryStyle = when (category) {
    ConversationCategory.MEETING -> CategoryStyle("Meeting", NishuPalette.Peach, Color(0xFFB56214))
    ConversationCategory.WORK -> CategoryStyle("Work", NishuPalette.SoftBlue, Color(0xFF2563EB))
    ConversationCategory.PERSONAL -> CategoryStyle("Personal", NishuPalette.Lavender, Color(0xFF635BFF))
    ConversationCategory.CALL -> CategoryStyle("Call", NishuPalette.SoftBlue, Color(0xFF0D9488))
    ConversationCategory.OTHER -> CategoryStyle("Note", NishuPalette.Mint, NishuPalette.Primary)
}

fun categoryLabel(category: ConversationCategory): String = categoryStyle(category).label

data class TileStyle(val icon: ImageVector, val tint: Color)

fun memoryStyle(kind: MemoryKind): TileStyle = when (kind) {
    MemoryKind.FACT -> TileStyle(Icons.Rounded.Lightbulb, Color(0xFF2563EB))
    MemoryKind.PREFERENCE -> TileStyle(Icons.Rounded.Favorite, Color(0xFFE0568A))
    MemoryKind.CONTACT -> TileStyle(Icons.Rounded.ContactPhone, NishuPalette.Primary)
    MemoryKind.OTHER -> TileStyle(Icons.Rounded.AutoAwesome, Color(0xFF635BFF))
}

private val speakerPalette = listOf(
    Color(0xFF635BFF), Color(0xFFE08A2E), Color(0xFFF04444), Color(0xFF2563EB),
    Color(0xFF14A38B), Color(0xFF8B5CF6)
)

/** A speaker keeps the same colour on every screen: the palette is indexed by the speaker's number. */
fun speakerColor(index: Int): Color = speakerPalette[index.coerceAtLeast(0) % speakerPalette.size]

@Composable
fun SpeakerChip(name: String, index: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val color = speakerColor(index)
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
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
            .clip(RoundedCornerShape(12.dp))
            .background(style.tint.copy(alpha = 0.12f)),
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
        Text(value.toString(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
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

/** Conversation row card matching the visual reference (Screen B and Screen I). */
@Composable
fun ConversationCard(
    item: ConversationUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: List<MenuAction> = emptyList(),
    showCategoryBadge: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val catStyle = categoryStyle(item.category)

    Surface(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().pressScale(source).nishuCard(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play button circle: Pale Mint background with Forest Green triangle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(NishuPalette.Mint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = NishuPalette.Primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
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
                Spacer(Modifier.width(8.dp))
            } else if (showCategoryBadge) {
                // Category pill tag (e.g. Meeting [peach], Work [blue], Personal [lavender], Note [mint])
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(catStyle.bg)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        catStyle.label,
                        color = catStyle.fg,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }

            if (menu.isNotEmpty()) {
                OverflowMenu(menu)
            }
        }
    }
}

/** Model card used in Model Compare and Settings. */
@Composable
fun ModelCard(
    name: String,
    sizeLabel: String,
    iconTint: Color,
    modifier: Modifier = Modifier,
    installed: Boolean = false,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .nishuCard()
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = onCheckedChange != null,
                onClick = { onCheckedChange?.invoke(!(checked ?: false)) },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null && onCheckedChange != null) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = NishuPalette.Primary,
                    checkmarkColor = Color.White,
                ),
            )
            Spacer(Modifier.width(10.dp))
        }

        // Model Icon container
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                sizeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (installed) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(NishuPalette.Mint)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "Installed",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NishuPalette.Primary,
                )
            }
        } else if (onActionClick != null) {
            Surface(
                onClick = onActionClick,
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, Color(0xFF2563EB)),
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(
                        "Download",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = Color(0xFF2563EB),
                    )
                }
            }
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

/** Action Item with checkbox and priority pill (Screen E - AI Summary). */
@Composable
fun TaskCard(
    item: TaskUiModel,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    priority: String? = null,
) {
    val prio = priority ?: when (item.confidence) {
        Confidence.HIGH -> "High"
        Confidence.LOW -> "Low"
        else -> "Medium"
    }

    val (prioBg, prioFg) = when (prio.lowercase()) {
        "high" -> Color(0xFFFFEBEB) to NishuPalette.Danger
        "low" -> NishuPalette.Mint to NishuPalette.Primary
        else -> NishuPalette.Peach to Color(0xFFD97706)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .nishuCard()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = item.done,
            onCheckedChange = onToggle,
            colors = CheckboxDefaults.colors(
                checkedColor = NishuPalette.Primary,
                checkmarkColor = Color.White,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (item.done) FontWeight.Normal else FontWeight.Medium,
            ),
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(prioBg)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "▶ $prio",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                    color = prioFg,
                )
            }
        }
    }
}

@Composable
fun DecisionCard(item: DecisionUiModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(NishuPalette.Mint)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = NishuPalette.Primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.text, style = MaterialTheme.typography.titleSmall, color = NishuPalette.Primary)
            if (item.confidence == Confidence.LOW) {
                StatusBadge("Low confidence", BadgeKind.WARNING, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Meeting Summary card matching Screen E in reference mockup. */
@Composable
fun MeetingSummaryCard(
    summaryText: String,
    modifier: Modifier = Modifier,
    title: String = "Meeting Summary",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .nishuCard()
            .padding(Dimens.CardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NishuPalette.Lavender),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Description,
                    contentDescription = null,
                    tint = Color(0xFF635BFF),
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            summaryText,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SummaryCard(bullets: List<String>, modifier: Modifier = Modifier) {
    MeetingSummaryCard(
        summaryText = bullets.joinToString("\n\n"),
        modifier = modifier,
    )
}

@Composable
fun KeyPointRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(NishuPalette.Primary),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun LinkRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.medium,
        color = NishuPalette.Mint,
        contentColor = NishuPalette.Primary,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
