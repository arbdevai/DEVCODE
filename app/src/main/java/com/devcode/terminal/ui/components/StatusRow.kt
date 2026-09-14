package com.devcode.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A compact status row with a colored square dot, label, and trailing detail text. */
@Composable
fun StatusRow(
    label: String,
    ok: Boolean?,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        // Colored indicator dot
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    color = statusColor(ok),
                    shape = RoundedCornerShape(1.dp),
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Text(
            text     = detail,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    HorizontalDivider(
        color     = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        thickness = 0.5.dp,
        modifier  = Modifier.padding(start = 27.dp, end = 12.dp),
    )
}

/** Returns the semantic status color for a nullable Boolean. */
@Composable
fun statusColor(ok: Boolean?): Color = when (ok) {
    true  -> Color(0xFF4ADE80) // green-400
    false -> Color(0xFFFB7185) // rose-400
    null  -> Color(0xFF6B7280) // gray-500
}
