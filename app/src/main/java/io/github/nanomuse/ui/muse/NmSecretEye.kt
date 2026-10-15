package io.github.nanomuse.ui.muse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.openminis.app.R

/**
 * The eye at the end of a key or password field: dots while [shown] is false, the text while
 * it is true. Use with [secretTransformation] on the same field.
 */
@Composable
fun SecretEye(shown: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = stringResource(if (shown) R.string.nm_secret_hide else R.string.nm_secret_show),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Dots unless [shown]. */
fun secretTransformation(shown: Boolean): VisualTransformation =
    if (shown) VisualTransformation.None else PasswordVisualTransformation()
