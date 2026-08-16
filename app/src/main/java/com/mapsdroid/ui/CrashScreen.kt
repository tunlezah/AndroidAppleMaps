package com.mapsdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shows the last captured crash stack trace so a failure is visible on-device without adb. Only
 * appears when a crash was recorded on the previous run.
 */
@Composable
fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("The app crashed on the previous launch", style = MaterialTheme.typography.titleMedium)
        Text(
            "Please share this with the developer:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            trace,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Button(onClick = onDismiss) { Text("Continue") }
    }
}
