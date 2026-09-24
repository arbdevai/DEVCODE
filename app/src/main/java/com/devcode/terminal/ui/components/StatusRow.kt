package com.devcode.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A modern 2026 status row with semantic glowing indicator pill, label, and detail value. */
@Composable
fun StatusRow(
    label: String,
    ok: Boolean?,
    detail: String,
    modifier: Modifier = Modifier,
    isMonospaceDetail: Boolean = false,
) {
    val indicatorColor = statusColor(ok)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        // Glowing status indicator dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = indicatorColor,
                    shape = CircleShape,
                )
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier = Modifier
                .background(
                    color = indicatorColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = detail,
                style = if (isMonospaceDetail) {
                    MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                },
                fontWeight = FontWeight.SemiBold,
                color = indicatorColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
}

/** Returns the semantic status color for a nullable Boolean. */
@Composable
fun statusColor(ok: Boolean?): Color = when (ok) {
    true  -> Color(0xFF10B981) // Emerald-500
    false -> Color(0xFFF43F5E) // Rose-500
    null  -> Color(0xFF0EA5E9) // Sky-500 / active / checking
}
