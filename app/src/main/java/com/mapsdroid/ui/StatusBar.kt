package com.mapsdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mapsdroid.web.AppleMapsSession.PageStatus

/**
 * Compact, always-visible state line. Its job is diagnostic: if the map area is empty, this still
 * renders, so "nothing happened" becomes a readable reason (offline, HTTP error, still loading).
 */
@Composable
fun StatusBar(
    online: Boolean,
    pageStatus: PageStatus,
    offlineRegions: Int,
    onReload: () -> Unit,
    onToggleTray: () -> Unit,
    trayHidden: Boolean,
    onShowDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageText = when (pageStatus) {
        is PageStatus.Idle -> "page: idle"
        is PageStatus.Loading -> "page: loading…"
        is PageStatus.Finished -> "page: loaded"
        is PageStatus.Error -> "page: error ${pageStatus.code} ${pageStatus.description}"
    }
    val isProblem = pageStatus is PageStatus.Error || !online

    Surface(
        color = if (isProblem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isProblem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "net: ${if (online) "online" else "offline"} · $pageText",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "offline regions: $offlineRegions",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row {
                TextButton(onClick = onToggleTray) {
                    Text(if (trayHidden) "Panel" else "Hide panel", fontSize = 11.sp)
                }
                TextButton(onClick = onReload) { Text("Reload", fontSize = 11.sp) }
                TextButton(onClick = onShowDiagnostics) { Text("Logs", fontSize = 11.sp) }
            }
        }
    }
}
