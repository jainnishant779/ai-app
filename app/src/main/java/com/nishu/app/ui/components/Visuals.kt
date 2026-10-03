package com.nishu.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nishu.app.domain.model.StepState
import com.nishu.app.ui.theme.NishuPalette
import com.nishu.app.ui.theme.NishuTheme

/**
 * Concentric radar circles with responsive audio waveform in center (Screen C - Recording).
 */
@Composable
fun RecordingRadarWaveform(
    levels: List<Float>,
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "radarPulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    Canvas(modifier = modifier) {
        val center = center
        val maxR = size.minDimension / 2f
        val boost = 1f + (level.coerceIn(0f, 1f) * 0.12f)

        // Draw concentric subtle rings
        val rings = 5
        for (i in 1..rings) {
            val baseFrac = i.toFloat() / rings
            val animatedFrac = if (active) (baseFrac + phase * 0.2f) % 1f else baseFrac
            val r = maxR * animatedFrac * boost
            val alpha = (1f - (r / maxR).coerceIn(0f, 1f)) * 0.22f
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = r,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        // Draw centered audio waveform bars
        val barCount = 31
        val barWidth = 3.5.dp.toPx()
        val spacing = 3.5.dp.toPx()
        val totalWidth = barCount * barWidth + (barCount - 1) * spacing
        val startX = (size.width - totalWidth) / 2f
        val maxBarH = size.height * 0.58f
        val minBarH = 6.dp.toPx()

        for (i in 0 until barCount) {
            val distFromCenter = kotlin.math.abs(i - barCount / 2).toFloat() / (barCount / 2)
            val bellCurve = (1f - distFromCenter * 0.65f).coerceAtLeast(0.15f)

            val sampleIdx = if (levels.isNotEmpty()) {
                (i * levels.size / barCount).coerceIn(0, levels.lastIndex)
            } else 0
            val rawLevel = if (levels.isNotEmpty()) levels[sampleIdx] else 0.1f
            val dynamicLevel = if (active) (rawLevel * 0.7f + level * 0.3f).coerceIn(0.08f, 1f) else 0.08f

            val h = (minBarH + (maxBarH - minBarH) * dynamicLevel * bellCurve).coerceIn(minBarH, maxBarH)
            val x = startX + i * (barWidth + spacing)
            val y = center.y - h / 2f

            drawRoundRect(
                color = Color.White.copy(alpha = if (active) 0.95f else 0.45f),
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
fun RecordingOrb(level: Float, active: Boolean, modifier: Modifier = Modifier, size: Dp = 168.dp) {
    RecordingRadarWaveform(
        levels = listOf(level),
        level = level,
        active = active,
        modifier = modifier.size(size),
    )
}

@Composable
fun AudioWaveform(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    activeColor: Color = NishuPalette.Primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant,
    progress: Float? = null,
    barWidth: Dp = 3.dp,
    gap: Dp = 3.dp,
) {
    Canvas(modifier) {
        if (levels.isEmpty()) {
            // Draw a subtle placeholder line
            drawLine(
                color = inactiveColor,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        val bw = barWidth.toPx()
        val g = gap.toPx()
        val count = ((size.width + g) / (bw + g)).toInt().coerceAtLeast(1)
        val minH = bw
        for (i in 0 until count) {
            val level = levels[(i * levels.size / count).coerceIn(0, levels.lastIndex)].coerceIn(0f, 1f)
            val h = (minH + (size.height - minH) * level).coerceAtMost(size.height)
            val x = i * (bw + g)
            val played = progress?.let { i.toFloat() / count <= it } ?: true
            drawRoundRect(
                color = if (played) activeColor else inactiveColor,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f, bw / 2f),
            )
        }
    }
}

@Composable
fun ProcessingStep(label: String, state: StepState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ext = NishuTheme.extended
    val stateText = when (state) {
        StepState.COMPLETED -> "done"
        StepState.RUNNING -> "in progress"
        StepState.PENDING -> "waiting"
        StepState.FAILED -> "failed"
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (state == StepState.RUNNING) NishuPalette.Mint else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $stateText" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.COMPLETED -> Box(Modifier.size(26.dp).clip(CircleShape).background(NishuPalette.Primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                StepState.RUNNING -> CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp), color = NishuPalette.Primary)
                StepState.PENDING -> Box(Modifier.size(24.dp).border(1.5.dp, scheme.outlineVariant, CircleShape))
                StepState.FAILED -> Box(Modifier.size(26.dp).clip(CircleShape).background(scheme.error), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (state == StepState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (state == StepState.PENDING) scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
}

@Composable
fun NishuRobot(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val transition = rememberInfiniteTransition(label = "robot")
    val bob by transition.animateFloat(
        initialValue = -4f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "bob",
    )
    val primary = NishuPalette.Primary
    val secondary = NishuPalette.Secondary
    val dark = MaterialTheme.colorScheme.onBackground
    Canvas(modifier.size(size).graphicsLayer { translationY = bob.dp.toPx() }.semantics { contentDescription = "Nishu is working" }) {
        val w = this.size.width
        val h = this.size.height
        drawLine(primary, Offset(w * 0.5f, h * 0.06f), Offset(w * 0.5f, h * 0.2f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
        drawCircle(secondary, radius = w * 0.045f, center = Offset(w * 0.5f, h * 0.06f))
        drawRoundRect(
            brush = Brush.linearGradient(listOf(secondary, primary)),
            topLeft = Offset(w * 0.12f, h * 0.2f), size = Size(w * 0.76f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.24f, w * 0.24f),
        )
        drawRoundRect(
            color = dark.copy(alpha = 0.92f),
            topLeft = Offset(w * 0.2f, h * 0.3f), size = Size(w * 0.6f, h * 0.38f),
            cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
        )
        drawCircle(Color.White, radius = w * 0.05f, center = Offset(w * 0.38f, h * 0.46f))
        drawCircle(Color.White, radius = w * 0.05f, center = Offset(w * 0.62f, h * 0.46f))
        drawArc(
            color = Color.White, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(w * 0.42f, h * 0.5f), size = Size(w * 0.16f, h * 0.1f),
            style = Stroke(width = w * 0.025f, cap = StrokeCap.Round),
        )
        drawCircle(primary, radius = w * 0.06f, center = Offset(w * 0.07f, h * 0.5f))
        drawCircle(primary, radius = w * 0.06f, center = Offset(w * 0.93f, h * 0.5f))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(NishuPalette.Mint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = NishuPalette.Primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = NishuPalette.Primary, modifier = Modifier.semantics { contentDescription = "Loading" })
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again", color = NishuPalette.Primary) }
    }
}
