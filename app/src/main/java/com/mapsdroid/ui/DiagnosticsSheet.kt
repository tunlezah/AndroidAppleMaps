package com.mapsdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen diagnostics readout: the page's console output, JS/resource errors, and the DOM probe.
 * Exists so a blank Apple Maps page can be explained (and copied out) without adb.
 */
@Composable
fun DiagnosticsSheet(
    lines: List<String>,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onReload: () -> Unit,
    onRenderTest: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Text(
            "Page console, JS errors and DOM probe. Copy this and send it over.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(lines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
            if (lines.isEmpty()) {
                item {
                    Text(
                        "No diagnostics captured yet. Tap Reload, wait ~10s, then reopen this panel.",
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) {
                Text("Copy")
            }
            TextButton(onClick = onReload) { Text("Reload") }
            TextButton(onClick = onClear) { Text("Clear") }
            TextButton(onClick = onClose) { Text("Close") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // If this shows colour blocks, the WebView draws fine and the blankness is Apple's page.
            Button(onClick = onRenderTest) { Text("Render test") }
            TextButton(onClick = onOpenExternally) { Text("Open in browser") }
        }
    }
}
