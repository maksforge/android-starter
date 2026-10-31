package app.starter

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every route in the app, as a key.
 *
 * **Decide the navigation structure before the first screen.** Routes that exist from the start —
 * even where the screen behind them is still a stub — are what stop navigation from being reverse
 * engineered out of whatever the screens happened to need.
 *
 * Kotlin note: `data object` is a singleton with a sensible `toString`/`equals` — the right shape
 * for a route with no arguments. A route that carries arguments is a `data class` instead, and
 * `@Serializable` is what lets Nav3 save the whole back stack across process death.
 */
@Serializable data object Items : NavKey

@Serializable data object Settings : NavKey

/**
 * The item detail screen, and the editor behind it.
 *
 * A nullable [itemId] means "adding" — one route for add and edit rather than two, because the two
 * screens differ by which fields start filled and by nothing else. Two routes would be two screens
 * to keep in step.
 */
@Serializable
data class ItemDetail(
    val itemId: String,
) : NavKey

@Serializable
data class ItemEditor(
    val itemId: String? = null,
) : NavKey

@Serializable data object Backup : NavKey

/**
 * Help and feedback, and the licences below it — both pushed from Settings' *About* section
 * rather than being roots of their own. Somewhere you go once, from the place you already go to
 * look things up.
 */
@Serializable data object Support : NavKey

@Serializable data object Licences : NavKey

@Serializable
data class LicenceText(
    val spdxId: String,
) : NavKey

/**
 * The tabs, in bar order.
 *
 * An enum rather than a list of tuples so the bar, the back-stack root logic and any test asserting
 * the set all read the same declaration. Adding a tab is one entry here.
 *
 * The label is a `@StringRes` id, not a `String`: a bottom bar is composed once and would otherwise
 * cache the label from whichever locale was current at the time, which is exactly the bug an in-app
 * language switcher creates (ADR-0004).
 */
enum class TopLevelDestination(
    val key: NavKey,
    @param:StringRes val label: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    ItemsTab(Items, R.string.tab_items, Icons.Filled.Home, Icons.Outlined.Home),
    SettingsTab(Settings, R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}
