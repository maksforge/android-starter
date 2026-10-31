package app.starter.ui.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.starter.R
import app.starter.data.backup.AutoBackupMarker
import app.starter.data.backup.BackupScope
import app.starter.theme.Spacing
import app.starter.ui.appViewModelExtras
import app.starter.ui.common.DetailScaffold
import app.starter.ui.common.shareFiles
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel =
        viewModel(factory = BackupViewModel.Factory, extras = appViewModelExtras()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // `GetContent` rather than the Storage Access Framework's `OpenDocument`: this needs one file
    // once, not a durable grant on a tree. Fewer permissions, fewer dialogs, same result.
    val pickArchive =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) viewModel.restore(uri)
        }

    LaunchedEffect(state.share) {
        state.share?.let { archive ->
            context.shareFiles(listOf(archive), "application/zip")
            viewModel.consumeShare()
        }
    }

    BackHandler(enabled = state.restoring) {}

    DetailScaffold(
        title = stringResource(R.string.settings_backup),
        onBack = onBack.takeUnless { state.restoring },
        modifier = modifier,
    ) { contentModifier ->
        Column(modifier = contentModifier.padding(Spacing.base)) {
            Text(
                text = stringResource(R.string.backup_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AutoBackupStatus(state.autoBackup)
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.base),
                )
            }
            Button(
                onClick = { viewModel.export(BackupScope.Records) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.base),
            ) {
                Text(stringResource(R.string.backup_export_records))
            }
            Button(
                onClick = { viewModel.export(BackupScope.Everything) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.tight),
            ) {
                Text(stringResource(R.string.backup_export_everything))
            }
            OutlinedButton(
                onClick = { pickArchive.launch("application/zip") },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.section),
            ) {
                Text(stringResource(R.string.backup_restore))
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.base),
                )
            }
        }
    }
}

/**
 * What the last automatic backup recorded. Over quota is the one worth shouting about: Android
 * rejected the whole set, the database included, and nothing else would tell the user.
 */
@Composable
private fun AutoBackupStatus(marker: AutoBackupMarker?) {
    val date =
        marker?.lastBackupAt?.let {
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .format(it.atZone(ZoneId.systemDefault()))
        }
    val text =
        when {
            marker?.overQuota == true && date != null -> stringResource(R.string.backup_auto_over_quota, date)
            marker?.overQuota == true -> stringResource(R.string.backup_auto_over_quota_none_earlier)
            date == null -> stringResource(R.string.backup_auto_none)
            else -> {
                val last = stringResource(R.string.backup_auto_last, date)
                val leftOut = marker?.leftOutThumbnails ?: 0
                if (leftOut == 0) {
                    last
                } else {
                    "$last ${pluralStringResource(R.plurals.backup_auto_left_out, leftOut, leftOut)}"
                }
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (marker?.overQuota == true) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = Modifier.padding(top = Spacing.tight),
    )
}
