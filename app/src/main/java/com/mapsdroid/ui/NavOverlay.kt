package com.mapsdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mapsdroid.nav.NavPhase
import com.mapsdroid.nav.NavigationState

/**
 * The turn-by-turn overlay drawn on top of the Apple Maps WebView during guidance: a maneuver card
 * at the top (icon + distance-to-turn + instruction) and an ETA bar at the bottom with an End button.
 */
@Composable
fun NavOverlay(
    state: NavigationState,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.phase == NavPhase.IDLE) return
    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ManeuverCard(state)
        }
        EtaBar(
            state = state,
            onEnd = onEnd,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ManeuverCard(state: NavigationState) {
    Surface(
        color = Color(0xFF1D6FF2),
        contentColor = Color.White,
        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = state.maneuverType.icon(),
                contentDescription = null,
                modifier = Modifier.size(52.dp),
            )
            Column {
                val distance = when (state.phase) {
                    NavPhase.GUIDING -> formatDistanceMetric(state.distanceToNextManeuverMetres)
                    else -> ""
                }
                if (distance.isNotEmpty()) {
                    Text(distance, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    state.primaryInstruction,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                state.nextManeuver?.roadName?.let {
                    Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun EtaBar(state: NavigationState, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    formatEta(state.durationRemainingSeconds),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    formatDistanceMetric(state.distanceRemainingMetres),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onEnd,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0362C)),
            ) {
                Text("End")
            }
        }
    }
}

private fun formatEta(seconds: Double): String {
    val mins = (seconds / 60).toInt()
    return when {
        mins >= 60 -> "${mins / 60} hr ${mins % 60} min"
        else -> "$mins min"
    }
}
