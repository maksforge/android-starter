package app.starter.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.starter.MainApplication
import app.starter.data.AppPreferences
import app.starter.data.ItemEntity
import app.starter.data.ItemRepository
import app.starter.tip.TipJar
import app.starter.tip.tipAskDue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * **One immutable data class for the whole screen's state.**
 *
 * Not one `StateFlow` per field. Two flows that must agree — a list and a "loading" flag, say — can
 * emit out of step, and the frame in between is a UI state that should be impossible. One class
 * makes every rendered combination one the type system approved.
 *
 * `loading` starts true and is the reason there is no flicker of the empty state before the first
 * database read lands. That single frame is the most common visible bug in a Room-backed list.
 */
data class ItemsUiState(
    val items: List<ItemEntity> = emptyList(),
    val loading: Boolean = true,
    /** The tip's one ask (ADR-0007). False wherever Play will not sell a tip: nothing is asked for that cannot be bought. */
    val showTipAsk: Boolean = false,
)

class ItemsViewModel(
    repository: ItemRepository,
    private val preferences: AppPreferences,
    tipJar: TipJar,
    installedAt: Instant,
) : ViewModel() {
    /**
     * Kotlin note: `stateIn` turns a cold `Flow` into a hot `StateFlow` with a current value — the
     * shape a UI needs, since a composition has to render *something* on its first pass.
     *
     * `WhileSubscribed(5_000)` is the important argument: the upstream query stays alive for five
     * seconds after the last collector goes away. That keeps the data warm across a configuration
     * change (a rotation tears down and rebuilds the composition in well under five seconds) while
     * still releasing it when the user actually leaves. `Eagerly` would keep querying forever;
     * `Lazily` would never release.
     */
    val state: StateFlow<ItemsUiState> =
        combine(repository.observeAll(), preferences.tipAsked, tipJar.offers) { items, asked, offers ->
            ItemsUiState(
                items = items,
                loading = false,
                showTipAsk = offers.isNotEmpty() && tipAskDue(asked, items.size, installedAt, Instant.now()),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemsUiState())

    init {
        // Prices are asked for only once the ask could otherwise be due — one product query saved
        // on every other visit. The binding itself happens on every foreground anyway, to settle tips.
        viewModelScope.launch {
            val items = repository.observeAll().first().size
            if (tipAskDue(preferences.tipAsked.first(), items, installedAt, Instant.now())) tipJar.refresh()
        }
    }

    /** Either answer — *Leave a tip* or *No thanks* — ends the ask on this install. */
    fun answerTipAsk() {
        viewModelScope.launch { preferences.markTipAsked() }
    }

    companion object {
        /**
         * Reaches `AppContainer` through the application, which is the only object a bare
         * `ViewModelStoreOwner` can be given. `appViewModelExtras()` is what supplies it inside a
         * Nav3 entry — without that, `this[APPLICATION_KEY]` is null and this cast throws.
         */
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[APPLICATION_KEY] as MainApplication
                    val container = app.container
                    ItemsViewModel(container.items, container.preferences, container.tipJar, container.installedAt)
                }
            }
    }
}
