package com.mapsdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapsdroid.R

/**
 * One-time notice shown on first startup. Satisfies Apple's requirement that route-guidance apps
 * present a "sole risk / location may not be accurate" notice, and doubles as a plain statement that
 * this is an unofficial, personal client.
 */
@Composable
fun EulaDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* must be acknowledged; no dismiss */ },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("I understand") }
        },
        title = { Text("Before you start") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.realtime_guidance_notice),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "This is an unofficial, personal client that displays Apple Maps through its " +
                        "public website. It is not affiliated with or endorsed by Apple. Map data and " +
                        "directions are provided by Apple and may be unavailable or change without notice.",
                )
                Text(
                    "Offline maps and offline routing use OpenStreetMap data — © OpenStreetMap contributors.",
                )
            }
        },
        modifier = Modifier.padding(8.dp),
    )
}
