package dev.ignorantshuffle.companion

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Shared action/selection states: accent for active, neutral for inactive. */
@Composable
internal fun ActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Button(onClick, modifier, enabled, colors = ButtonDefaults.buttonColors(
        containerColor = c.primary, contentColor = c.onPrimary,
        disabledContainerColor = c.surfaceContainerHigh, disabledContentColor = c.onSurfaceVariant), content = content)
}

@Composable
internal fun StateChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, leadingIcon: @Composable (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    FilterChip(selected, onClick, label, modifier, leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(containerColor = c.surfaceContainerHigh,
            labelColor = c.onSurfaceVariant, selectedContainerColor = c.primary,
            selectedLabelColor = c.onPrimary, selectedLeadingIconColor = c.onPrimary))
}
