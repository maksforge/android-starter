package app.starter.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.starter.R
import app.starter.theme.Spacing
import app.starter.tip.TipOffer
import app.starter.tip.tipAskDue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant

internal fun Flow<ItemsUiState>.withTipAsk(
    asked: Flow<Boolean>,
    offers: Flow<List<TipOffer>>,
    installedAt: Instant,
): Flow<ItemsUiState> =
    combine(this, asked, offers) { state, isAsked, tips ->
        state.copy(
            showTipAsk = tips.isNotEmpty() && tipAskDue(isAsked, state.items.size, installedAt, Instant.now()),
        )
    }

/**
 * The one time the app mentions the tip unasked (ADR-0007). Either button answers it for this install, and
 * nothing here says what a tip buys, because it buys nothing.
 */
@Composable
internal fun TipAskCard(
    onTip: () -> Unit,
    onDecline: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.tight)) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(stringResource(R.string.items_tip_ask_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.items_tip_ask_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.hair),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                modifier = Modifier.padding(top = Spacing.tight),
            ) {
                TextButton(onClick = onTip) { Text(stringResource(R.string.items_tip_ask_accept)) }
                TextButton(onClick = onDecline) { Text(stringResource(R.string.items_tip_ask_decline)) }
            }
        }
    }
}
