package com.nishu.app.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishu.app.ui.components.NishuButton
import com.nishu.app.ui.components.NishuWaveformLogo
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuPalette
import com.nishu.app.ui.theme.NishuTheme

@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Botanical corner decorations & landscape in background
            BotanicalBackgroundDecoration(Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .widthIn(max = Dimens.ContentMaxWidth)
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = Dimens.ScreenGutter)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))

                // Nishu waveform logo (5 vertical bars)
                NishuWaveformLogo(height = 48.dp)

                Spacer(Modifier.height(16.dp))

                // Title
                Text(
                    text = "Nishu",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(8.dp))

                // Tagline
                Text(
                    text = "Turn your voice into\nclear notes, tasks and insights.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(28.dp))

                // Botanical landscape illustration box
                BotanicalLandscapeIllustration(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(MaterialTheme.shapes.large),
                )

                Spacer(Modifier.height(28.dp))

                // Privacy and feature highlights
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FeatureHighlightRow(
                        icon = Icons.Rounded.Lock,
                        title = "Private & Offline",
                        subtitle = "Your data stays on your device",
                    )
                    FeatureHighlightRow(
                        icon = Icons.Rounded.Description,
                        title = "Smart Transcription",
                        subtitle = "Hindi • Hinglish • English",
                    )
                    FeatureHighlightRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "AI Summaries",
                        subtitle = "Get notes, tasks and decisions",
                    )
                }

                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))

                // Primary CTA button
                NishuButton(
                    text = "Get Started",
                    onClick = onGetStarted,
                    trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FeatureHighlightRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(NishuPalette.Mint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = NishuPalette.Primary,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Draws subtle botanical leaf sprays on top-left and top-right corners */
@Composable
private fun BotanicalBackgroundDecoration(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sage = Color(0xFF789681).copy(alpha = 0.25f)
        val forest = Color(0xFF174C3C).copy(alpha = 0.20f)

        // Top-left leaf branch
        val leftPath = Path().apply {
            moveTo(0f, 0f)
            quadraticTo(60f, 100f, 40f, 180f)
        }
        drawPath(leftPath, color = forest)

        // Left leaves
        drawOval(
            color = sage,
            topLeft = Offset(10f, 50f),
            size = androidx.compose.ui.geometry.Size(32f, 70f),
        )
        drawOval(
            color = forest,
            topLeft = Offset(35f, 110f),
            size = androidx.compose.ui.geometry.Size(30f, 65f),
        )

        // Top-right leaf branch
        val w = size.width
        drawOval(
            color = sage,
            topLeft = Offset(w - 45f, 40f),
            size = androidx.compose.ui.geometry.Size(35f, 75f),
        )
        drawOval(
            color = forest,
            topLeft = Offset(w - 55f, 120f),
            size = androidx.compose.ui.geometry.Size(32f, 60f),
        )
    }
}

/** Minimal botanical / misty rolling hills landscape illustration */
@Composable
private fun BotanicalLandscapeIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Layer 1 - distant misty hills
        val hill1 = Path().apply {
            moveTo(0f, h * 0.45f)
            cubicTo(w * 0.25f, h * 0.35f, w * 0.55f, h * 0.55f, w, h * 0.35f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = hill1,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFE2EFE5), Color(0xFFD4E6D9)),
                startY = 0f,
                endY = h,
            ),
        )

        // Layer 2 - mid-distance hill
        val hill2 = Path().apply {
            moveTo(0f, h * 0.65f)
            cubicTo(w * 0.35f, h * 0.50f, w * 0.70f, h * 0.75f, w, h * 0.58f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = hill2,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF9FBFA8).copy(alpha = 0.85f), Color(0xFF789681)),
                startY = h * 0.4f,
                endY = h,
            ),
        )

        // Layer 3 - foreground rich green hill
        val hill3 = Path().apply {
            moveTo(0f, h * 0.82f)
            cubicTo(w * 0.30f, h * 0.70f, w * 0.60f, h * 0.90f, w, h * 0.75f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = hill3,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF356958), Color(0xFF174C3C)),
                startY = h * 0.6f,
                endY = h,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() = NishuTheme {
    OnboardingScreen(onGetStarted = {})
}
