package app.starter.ui.wipe

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.starter.R
import app.starter.theme.Spacing

/**
 * The consent half of [SchemaMismatchScreen]: continuing lets the app through, and Room's
 * destructive fallback empties the file. Shown only where that fallback is on (ADR-0001).
 */
@Composable
fun SchemaMismatchContinue(onContinue: () -> Unit) {
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.section),
    ) {
        Text(stringResource(R.string.schema_mismatch_continue))
    }
}
