package app.starter.ui.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.starter.BuildConfig
import app.starter.data.APP_DATABASE_FILE
import app.starter.data.backup.BackupExporter
import app.starter.data.backup.BackupRestorer
import app.starter.data.backup.BackupScope
import app.starter.data.backup.EXPORTS_DIRECTORY
import app.starter.data.backup.RestoreResult
import app.starter.data.backup.exportFileName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

data class BackupUiState(
    val busy: Boolean = false,
    /** A message to show once and clear — an export's result, a restore's refusal. */
    val message: String? = null,
    /** Set when an export is ready to hand to the share sheet. */
    val share: File? = null,
)

class BackupViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val context get() = getApplication<Application>()

    fun export(scope: BackupScope) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val now = Instant.now()
            val exporter =
                BackupExporter(
                    databaseFile = context.getDatabasePath(APP_DATABASE_FILE),
                    filesDir = context.filesDir,
                    scratchDir = File(context.cacheDir, "backup-scratch"),
                    appVersion = BuildConfig.VERSION_NAME,
                )
            val target = File(File(context.cacheDir, EXPORTS_DIRECTORY), exportFileName(scope, now))
            val result = runCatching { exporter.exportTo(target, scope, now) }
            _state.update { current ->
                result.fold(
                    onSuccess = { file -> current.copy(busy = false, share = file) },
                    onFailure = { error -> current.copy(busy = false, message = error.message) },
                )
            }
        }
    }

    fun restore(source: Uri) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val restorer =
                BackupRestorer(
                    databaseFile = context.getDatabasePath(APP_DATABASE_FILE),
                    filesDir = context.filesDir,
                    stagingDir = File(context.cacheDir, "restore-staging"),
                )
            // A lambda that opens the stream, not a stream: the restorer reads the archive twice —
            // once for the manifest, once to extract — and a `ZipInputStream` cannot be rewound.
            val result =
                runCatching {
                    restorer.restoreFrom { context.contentResolver.openInputStream(source)!! }
                }.getOrElse { RestoreResult.NotABackup }

            _state.update {
                it.copy(
                    busy = false,
                    message =
                        when (result) {
                            is RestoreResult.Restored -> "Restored. Restart the app."
                            RestoreResult.NotABackup -> "That file is not a backup of this app."
                            is RestoreResult.TooNew ->
                                "That backup was made by a newer version " +
                                    "(schema ${result.schemaVersion}, this build reads ${result.supported})."
                        },
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun consumeShare() = _state.update { it.copy(share = null) }

    companion object {
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer { BackupViewModel(this[APPLICATION_KEY] as Application) }
            }
    }
}
