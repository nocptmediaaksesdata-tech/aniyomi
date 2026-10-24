package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import tachiyomi.domain.watchparty.model.WatchPartyReaction
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun WatchPartyReactionsOverlay(
    reactions: List<WatchPartyReaction>,
    onReactionDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 100.dp, end = 48.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        reactions.forEach { reaction ->
            key(reaction.id) {
                FloatingReactionItem(
                    reaction = reaction,
                    onDismiss = { onReactionDismiss(reaction.id) },
                )
            }
        }
    }
}

@Composable
private fun FloatingReactionItem(
    reaction: WatchPartyReaction,
    onDismiss: () -> Unit,
) {
    val animatable = remember { Animatable(0f) }

    LaunchedEffect(reaction.id) {
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 3000,
                easing = LinearEasing,
            ),
        )
        onDismiss()
    }

    val progress = animatable.value
    val density = LocalDensity.current
    val maxDistancePx = with(density) { 320.dp.toPx() }
    val offsetY = -maxDistancePx * progress

    val wobblePx = sin(progress * 2 * PI.toFloat()) * with(density) { 14.dp.toPx() }
    val offsetX = with(density) { reaction.randomOffsetX.dp.toPx() } + wobblePx

    val scale = when {
        progress < 0.12f -> lerp(0.3f, 1.25f, progress / 0.12f)
        progress < 0.25f -> lerp(1.25f, 1.0f, (progress - 0.12f) / 0.13f)
        else -> 1.0f
    }

    val alpha = when {
        progress < 0.7f -> 1.0f
        else -> ((1.0f - progress) / 0.3f).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier.graphicsLayer {
            translationX = offsetX
            translationY = offsetY
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = reaction.emoji,
                fontSize = 32.sp,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = reaction.username,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun WatchPartyReactionBar(
    onSelectReaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val reactions = remember { listOf("❤️", "😂", "🔥", "😮", "👏", "😭") }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            reactions.forEach { emoji ->
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelectReaction(emoji)
                    },
                    modifier = Modifier.size(42.dp),
                ) {
                    Text(
                        text = emoji,
                        fontSize = 22.sp,
                    )
                }
            }
        }
    }
}
