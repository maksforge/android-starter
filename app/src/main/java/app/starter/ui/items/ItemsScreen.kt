package app.starter.ui.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.starter.R
import app.starter.theme.Spacing
import app.starter.ui.appViewModelExtras
import app.starter.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onOpenItem: (String) -> Unit,
    onAddItem: () -> Unit,
    onOpenSupport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemsViewModel =
        viewModel(factory = ItemsViewModel.Factory, extras = appViewModelExtras()),
) {
    // Kotlin note: `by` unwraps the State object, so `state` reads as the value itself.
    // `collectAsStateWithLifecycle` subscribes only while the screen is actually on screen — the
    // Compose equivalent of subscribing in an effect and unsubscribing on unmount. Plain
    // `collectAsState` would keep collecting while the app is backgrounded.
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_items)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddItem) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.item_add))
            }
        },
    ) { insets ->
        when {
            // Nothing at all during the first read. An empty state shown for one frame and then
            // replaced by a list is worse than a blank frame: it says "you have nothing", which is
            // a claim, and it turns out to be false.
            state.loading -> Unit

            state.items.isEmpty() ->
                EmptyState(
                    message = stringResource(R.string.items_empty),
                    modifier = Modifier.padding(insets),
                )

            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(insets),
                    contentPadding = PaddingValues(Spacing.base),
                ) {
                    item(key = "count") {
                        Text(
                            text =
                                pluralStringResource(
                                    R.plurals.items_count,
                                    state.items.size,
                                    state.items.size,
                                ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = Spacing.tight),
                        )
                    }
                    if (state.showTipAsk) {
                        item(key = "tip-ask") {
                            TipAskCard(
                                onTip = {
                                    viewModel.answerTipAsk()
                                    onOpenSupport()
                                },
                                onDecline = viewModel::answerTipAsk,
                            )
                        }
                    }
                    // `key` is what lets Compose recycle the right row when the list reorders. With
                    // no key it matches by index, so inserting at the top re-renders everything and
                    // any per-row state moves to the wrong row.
                    items(state.items, key = { it.id }) { item ->
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = Spacing.tight)
                                    .clickable { onOpenItem(item.id) },
                        ) {
                            Column(modifier = Modifier.padding(Spacing.base)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                item.notes?.takeIf(String::isNotBlank)?.let { notes ->
                                    Text(
                                        text = notes,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = Spacing.hair),
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}
