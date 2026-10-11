package app.starter

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.starter.ui.backup.BackupScreen
import app.starter.ui.items.ItemDetailScreen
import app.starter.ui.items.ItemEditorScreen
import app.starter.ui.items.ItemsScreen
import app.starter.ui.settings.SettingsScreen
import app.starter.ui.support.LicenceTextScreen
import app.starter.ui.support.LicencesScreen
import app.starter.ui.support.SupportScreen

/**
 * What every back-stack entry is wrapped in — above all, **one `ViewModelStore` per entry**.
 *
 * Nav3 does not do this on its own. The ViewModel decorator ships in a separate artifact
 * (`lifecycle-viewmodel-navigation3`), which `navigation3-ui` does not depend on, so `NavDisplay`'s
 * default list cannot contain it. Without it every `viewModel()` resolves to the **Activity's**
 * store and outlives the screen that created it — an editor comes back with its `saved` flag still
 * set and bounces straight out of the second "Add" of a session, and only killing the process
 * clears it. It is a genuinely confusing bug, because the screen is correct and its state is not.
 *
 * `rememberSaveableStateHolderNavEntryDecorator` is Nav3's own default, restated because passing a list
 * replaces the defaults rather than adding to them.
 *
 * Extracted from [MainNavigation] so a test can assert the scoping directly.
 */
@Composable
internal fun appEntryDecorators(): List<NavEntryDecorator<NavKey>> =
    listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
    )

/**
 * The app shell: a bottom bar over a [NavDisplay].
 *
 * The back stack is rooted at [Items], so Back from any top-level destination returns there and
 * Back from there exits. That is one rule rather than per-screen behaviour, and it is why
 * [showTopLevel] rewrites the stack instead of pushing onto it — pushing would let a user walk
 * Items → Settings → Items → Settings and need four Backs to leave.
 */
@Composable
fun MainNavigation(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(Items)
    val activity = LocalActivity.current

    // Which top-level destination the bar should show as selected. Derived from the *bottom* of the
    // stack rather than the top, so a detail screen pushed on top does not deselect its own tab.
    val current =
        remember(backStack.firstOrNull()) {
            TopLevelDestination.entries.firstOrNull { it.key == backStack.firstOrNull() }
                ?: TopLevelDestination.ItemsTab
        }
    val onDetailScreen = backStack.size > 1

    Scaffold(
        modifier = modifier,
        bottomBar = {
            // Hidden on a detail screen: a detail screen has its own back affordance, and a bar
            // that stays visible invites a tab tap that silently discards an unsaved form.
            if (!onDetailScreen) {
                AppNavigationBar(
                    current = current,
                    onSelect = { destination -> backStack.showTopLevel(destination) },
                )
            }
        },
    ) { insets ->
        NavDisplay(
            backStack = backStack,
            // **The keyboard is an inset like any other, and this Scaffold owns them.**
            //
            // `MainActivity` sets `decorFitsSystemWindows = false` for edge-to-edge, which makes the
            // manifest's `adjustResize` inoperative from API 30 — the window manager downgrades it
            // to a *pan*. With nothing consuming `WindowInsets.ime`, the system then pans the whole
            // window to keep the focused field in view, which slides the top of a long form under
            // the status bar and pushes its top bar — Save included — off the screen entirely.
            //
            // `consumeWindowInsets` before `imePadding` is what stops it double-counting: the
            // keyboard's inset is measured from the bottom of the *screen*, so it already contains
            // the navigation-bar height that `padding(insets)` just applied. Consuming says "that
            // part is handled", leaving `imePadding` to add only the rest.
            modifier = Modifier.padding(insets).consumeWindowInsets(insets).imePadding(),
            entryDecorators = appEntryDecorators(),
            onBack = {
                if (backStack.size > 1) backStack.removeLastOrNull() else activity?.finish()
            },
            // Kotlin note: this is a builder DSL, not a map literal — `entry<Items> { … }` registers
            // the composable that renders that key, and the lambda receives the key itself, which is
            // how a key carrying arguments passes them in.
            entryProvider =
                entryProvider {
                    entry<Items> {
                        ItemsScreen(
                            onOpenItem = { id -> backStack.add(ItemDetail(id)) },
                            onAddItem = { backStack.add(ItemEditor()) },
                            onOpenSupport = { backStack.add(Support) },
                        )
                    }
                    entry<ItemDetail> { key ->
                        ItemDetailScreen(
                            itemId = key.itemId,
                            onEdit = { backStack.add(ItemEditor(key.itemId)) },
                            onBack = { backStack.removeLastOrNull() },
                            // A deleted item's detail screen has nothing left to show, so the
                            // screen leaves rather than rendering an empty state nobody asked for.
                            onDeleted = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<ItemEditor> { key ->
                        ItemEditorScreen(
                            itemId = key.itemId,
                            onDone = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Settings> {
                        SettingsScreen(
                            onOpenBackup = { backStack.add(Backup) },
                            onOpenLicences = { backStack.add(Licences) },
                            onOpenSupport = { backStack.add(Support) },
                        )
                    }
                    entry<Backup> {
                        BackupScreen(onBack = { backStack.removeLastOrNull() })
                    }
                    entry<Support> {
                        SupportScreen(onBack = { backStack.removeLastOrNull() })
                    }
                    entry<Licences> {
                        LicencesScreen(
                            onBack = { backStack.removeLastOrNull() },
                            onOpenLicence = { spdxId -> backStack.add(LicenceText(spdxId)) },
                        )
                    }
                    entry<LicenceText> { key ->
                        LicenceTextScreen(
                            spdxId = key.spdxId,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                },
        )
    }
}

@Composable
private fun AppNavigationBar(
    current: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar {
        for (destination in TopLevelDestination.entries) {
            val label = stringResource(destination.label)
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null)
                },
                // `contentDescription = null` on the icon is correct **because** the label is
                // present: a screen reader would otherwise announce the same word twice.
                label = { Text(label) },
            )
        }
    }
}

/**
 * Replace the stack with just this destination.
 *
 * Not `add`: a bottom bar is a switch between roots, not a path to walk deeper into. Pushing would
 * let a user tap between two tabs a dozen times and need a dozen Backs to leave the app.
 */
private fun NavBackStack<NavKey>.showTopLevel(destination: TopLevelDestination) {
    clear()
    add(destination.key)
}
