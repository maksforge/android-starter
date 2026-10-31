package app.starter.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.starter.R
import app.starter.data.ThemeMode
import app.starter.theme.Spacing
import app.starter.ui.appViewModelExtras
import app.starter.ui.common.LinkRow
import app.starter.ui.common.LinkTarget
import app.starter.ui.common.SectionHeader
import app.starter.ui.common.SwitchRow
import app.starter.ui.common.WarningBanner
import app.starter.ui.support.SupportRequest
import app.starter.ui.support.hintRes
import app.starter.ui.support.sendSupportMail
import app.starter.ui.support.titleRes
import app.starter.work.NotificationPermissionOutcome
import app.starter.work.ReminderChannel
import app.starter.work.channelCanAppear
import app.starter.work.notificationsAllowed
import app.starter.work.openAppNotificationSettings
import app.starter.work.openChannelNotificationSettings
import app.starter.work.rememberNotificationPermissionAsk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenBackup: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenSupport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModel.Factory, extras = appViewModelExtras()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // **A live read, re-taken on every resume, and never a remembered outcome.** The fix for a
    // refusal is a settings screen this screen hands the user off to, and they come back — so an
    // answer cached from the ask itself is stale exactly when it decides whether to warn them.
    var notificationsAllowed by remember { mutableStateOf(context.notificationsAllowed()) }
    // The other half: a channel muted on its own blocks its notifications while the permission above
    // still reads as granted. The first muted one, so the banner can land the user on its own page.
    var mutedChannel by remember { mutableStateOf(context.firstMutedChannel()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    notificationsAllowed = context.notificationsAllowed()
                    mutedChannel = context.firstMutedChannel()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val askNotifications =
        rememberNotificationPermissionAsk { outcome ->
            notificationsAllowed = outcome == NotificationPermissionOutcome.Granted
        }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) },
    ) { insets ->
        Column(modifier = Modifier.padding(insets).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_appearance))

            Row(modifier = Modifier.padding(horizontal = Spacing.base)) {
                for (mode in ThemeMode.entries) {
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                        modifier = Modifier.padding(end = Spacing.tight),
                    )
                }
            }

            SwitchRow(
                title = stringResource(R.string.settings_material_you),
                subtitle = stringResource(R.string.settings_material_you_hint),
                checked = state.materialYou,
                onChange = viewModel::setMaterialYou,
            )

            SectionHeader(stringResource(R.string.settings_language))
            LanguageRow()
            // The channel that stands in for a native read-through (docs/translator-brief.md §8),
            // beside the picker because that is where somebody is standing the moment a translation
            // reads wrong. Its wording lives with the other two requests, in ui/support/.
            LinkRow(
                title = stringResource(SupportRequest.Language.titleRes()),
                subtitle = stringResource(SupportRequest.Language.hintRes()),
                target = LinkTarget.Outside,
                onClick = { context.sendSupportMail(SupportRequest.Language) },
            )

            SectionHeader(stringResource(R.string.settings_reminders))
            SwitchRow(
                title = stringResource(R.string.settings_reminders_enabled),
                subtitle = stringResource(R.string.settings_reminders_hint),
                checked = state.remindersEnabled,
                onChange = { enabled ->
                    viewModel.setRemindersEnabled(enabled)
                    // **The ask rides on the moment the feature is switched on, and never before**
                    // (`PLAN.md` rule 4). Android permits two denials before the dialog stops
                    // appearing for good, so spending one at launch — for a feature the user may
                    // never turn on — is spending half the budget on nothing.
                    if (enabled && !notificationsAllowed) askNotifications()
                },
            )

            // The third clause of the same rule: the phase that introduces an ask owns what the user
            // sees when the answer is no. Without this the switch reads as on, the preference *is*
            // on, and nothing is ever posted — the app knows and the user does not. Only while
            // reminders are enabled: a warning about a permission a disabled feature would want is
            // the app asking for something it is not using.
            //
            // One banner for both halves, because the loss is the same. The button differs: the
            // app-wide switch lives on the app's page, a channel's level on the channel's own.
            val muted = mutedChannel
            if (state.remindersEnabled && (!notificationsAllowed || muted != null)) {
                WarningBanner(
                    title = stringResource(R.string.settings_reminders_blocked_title),
                    body = stringResource(R.string.settings_reminders_blocked_body),
                    actionLabel = stringResource(R.string.settings_reminders_blocked_action),
                    onAct = {
                        if (!notificationsAllowed || muted == null) {
                            context.openAppNotificationSettings()
                        } else {
                            context.openChannelNotificationSettings(muted)
                        }
                    },
                )
            }

            SectionHeader(stringResource(R.string.settings_data))
            LinkRow(stringResource(R.string.settings_backup), LinkTarget.Inside, onClick = onOpenBackup)
            RestoreDefaultsRow(onRestore = viewModel::restoreDefaults)

            SectionHeader(stringResource(R.string.settings_about))
            LinkRow(stringResource(R.string.settings_support), LinkTarget.Inside, onClick = onOpenSupport)
            LinkRow(stringResource(R.string.settings_licences), LinkTarget.Inside, onClick = onOpenLicences)

            // The developer-only section. In a release build this composable is a no-op that
            // renders nothing — see `src/release/…/DebugSettings.kt`. It is a source-set seam
            // rather than an `if (BuildConfig.DEBUG)` because with `isMinifyEnabled = false` a
            // statically-false branch is still compiled into the AAB, and its strings are still
            // inside the translation gate. A hide is not a strip.
            DebugSettings()
        }
    }
}

@Composable
private fun LanguageRow() {
    // Read on every composition rather than held in state: `setAppLanguage` recreates the Activity,
    // so this composable is rebuilt from scratch and the fresh read is always correct.
    val current = currentAppLanguage()
    Row(modifier = Modifier.padding(horizontal = Spacing.base)) {
        FilterChip(
            selected = current == null,
            onClick = { setAppLanguage(null) },
            label = { Text(stringResource(R.string.settings_language_system)) },
            modifier = Modifier.padding(end = Spacing.tight),
        )
        for (language in AppLanguage.entries) {
            FilterChip(
                selected = current == language,
                onClick = { setAppLanguage(language) },
                label = { Text(stringResource(language.labelRes)) },
                modifier = Modifier.padding(end = Spacing.tight),
            )
        }
    }
}

/**
 * *Restore to default*, behind a confirmation because nothing brings the old values back. What it
 * resets and what it keeps is `AppPreferences.Keys.SETTINGS` and `KEPT`.
 */
@Composable
private fun RestoreDefaultsRow(onRestore: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    LinkRow(
        title = stringResource(R.string.settings_restore),
        subtitle = stringResource(R.string.settings_restore_hint),
        target = LinkTarget.Here,
        onClick = { confirming = true },
    )

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_restore_confirm_title)) },
            text = { Text(stringResource(R.string.settings_restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onRestore()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

private fun Context.firstMutedChannel(): ReminderChannel? =
    ReminderChannel.entries.firstOrNull {
        !channelCanAppear(it)
    }
