package app.starter.ui.support

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.starter.R
import app.starter.theme.Spacing
import app.starter.tip.TipOutcome
import app.starter.ui.appViewModelExtras

/**
 * Tipping the developer (ADR-0007). The buttons are Play's own prices in the user's currency, so
 * there is no label to translate on them, and nothing here says what a tip buys, because it buys
 * nothing.
 */
@Composable
fun TipSection(
    modifier: Modifier = Modifier,
    viewModel: SupportViewModel =
        viewModel(factory = SupportViewModel.Factory, extras = appViewModelExtras()),
) {
    val activity = LocalActivity.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Absent rather than disabled where Play sells nothing: no Play, a debug build, or products not
    // yet created in the Console.
    if (state.offers.isEmpty() || activity == null) return

    Column(modifier = modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug)) {
        Text(stringResource(R.string.support_tip), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.support_tip_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Wraps rather than scrolls: four prices do not fit one row on a narrow phone.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            modifier = Modifier.padding(top = Spacing.tight),
        ) {
            state.offers.forEach { offer ->
                OutlinedButton(onClick = { viewModel.tip(activity, offer) }) { Text(offer.price) }
            }
        }
        state.outcome?.let {
            Text(
                text =
                    stringResource(
                        when (it) {
                            TipOutcome.Thanked -> R.string.support_tip_thanks
                            TipOutcome.Pending -> R.string.support_tip_pending
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.tight),
            )
        }
    }
}
