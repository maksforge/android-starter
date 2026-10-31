package app.starter.ui.wipe

import androidx.compose.runtime.Composable

/**
 * No button: a release build has no destructive fallback, so [SchemaMismatchScreen] is a dead end
 * that offers the copy. A source set rather than a flag, for the reason in `DebugSettings.kt`.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SchemaMismatchContinue(onContinue: () -> Unit) = Unit
