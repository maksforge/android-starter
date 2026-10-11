package app.starter.ui.support

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.starter.MainApplication
import app.starter.tip.TipJar
import app.starter.tip.TipOffer
import app.starter.tip.TipOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @param offers the tips Play will sell here, cheapest first. Empty means no tip section at all.
 * @param outcome what the last tip from this screen became, or `null` before one finishes.
 */
data class SupportUiState(
    val offers: List<TipOffer> = emptyList(),
    val outcome: TipOutcome? = null,
)

/** The tip's half of Support (ADR-0007); every other row on the screen is stateless. */
class SupportViewModel(
    private val tipJar: TipJar,
) : ViewModel() {
    private val outcome = MutableStateFlow<TipOutcome?>(null)

    val uiState: StateFlow<SupportUiState> =
        combine(tipJar.offers, outcome, ::SupportUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SupportUiState())

    init {
        viewModelScope.launch { tipJar.refresh() }
        viewModelScope.launch { tipJar.outcomes.collect { outcome.value = it } }
    }

    /** The activity goes straight to Play's sheet and is never kept: a `ViewModel` outlives it. */
    fun tip(
        activity: Activity,
        offer: TipOffer,
    ) {
        outcome.value = null
        tipJar.tip(activity, offer)
    }

    companion object {
        val Factory =
            viewModelFactory {
                initializer {
                    val app = this[APPLICATION_KEY] as MainApplication
                    SupportViewModel(app.container.tipJar)
                }
            }
    }
}
