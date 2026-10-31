package app.starter.ui.support

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.starter.R
import app.starter.theme.Spacing
import app.starter.ui.common.DetailScaffold
import app.starter.ui.common.LinkRow
import app.starter.ui.common.LinkTarget

/**
 * The route out of the app, for a user — or a tester — who has found something.
 *
 * **It belongs before the door, not in the polish phase.** Play's listing requires a contact address
 * whatever you do, and a closed test is the other half of the argument: a tester with no way to
 * report is a tester whose fourteen days produce nothing. The first app built from this template
 * shipped the two mail rows at its door and the rate-on-Play row later, when there was a listing to
 * rate.
 *
 * A detail screen pushed from Settings' *About* section, beside the licences row — not a third tab.
 * The bottom bar switches between roots, and support is not a root; it is somewhere you go once,
 * from the place you already go to look things up.
 *
 * No `ViewModel`, because there is no state to hold: three rows, each of which builds an intent from
 * constants and hands it to the system. The one piece of state is which app, if any, the last
 * hand-off found missing. That belongs to this composition and nothing else.
 *
 * The tip is the one exception: [TipSection], below the rows, holds the screen's one `ViewModel`,
 * for Play's prices and what the last tip became (ADR-0007).
 */
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Not `rememberSaveable`: this says "the tap you just made went nowhere", which is only true of
    // the tap you just made. A rotation clears it, and the next tap says it again if it is still so.
    // Kotlin note: `mutableStateOf<Int?>(null)` is `useState<number | null>(null)`: the id of the
    // message to show, or null for none. One slot rather than a Boolean per row, because only the
    // most recent tap is worth reporting.
    var missingApp by remember { mutableStateOf<Int?>(null) }

    // Assigned from every tap, whatever it returned, so a hand-off that works clears the message
    // an earlier one left behind.
    fun reportHandOff(
        opened: Boolean,
        @StringRes missing: Int,
    ) {
        missingApp = if (opened) null else missing
    }

    DetailScaffold(
        title = stringResource(R.string.support_title),
        onBack = onBack,
        modifier = modifier,
    ) { contentModifier ->
        Column(
            modifier =
                contentModifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            // The two requests this screen owns, named rather than `entries`: the third,
            // `SupportRequest.Language`, is a row in Settings' *Language* section instead, for the
            // reason its own doc comment gives. Its label still comes from the `when` blocks at the
            // bottom of this file — that is the one place a request's wording lives, and Settings
            // reads it from there rather than keeping a second copy.
            for (request in listOf(SupportRequest.Bug, SupportRequest.Feature)) {
                LinkRow(
                    title = stringResource(request.titleRes()),
                    subtitle = stringResource(request.hintRes()),
                    target = LinkTarget.Outside,
                    onClick = {
                        reportHandOff(context.sendSupportMail(request), R.string.support_no_mail_app)
                    },
                )
            }

            LinkRow(
                title = stringResource(R.string.support_rate),
                subtitle = stringResource(R.string.support_rate_hint),
                target = LinkTarget.Outside,
                onClick = { reportHandOff(context.rateOnPlay(), R.string.support_no_play) },
            )

            // Inline rather than a snackbar: the app has no snackbar host anywhere, and the failure
            // is about the row that was just tapped rather than about the screen. Stated as what is
            // missing on the phone — the app is not broken and neither is the address.
            // Kotlin note: `?.let { }` runs only when the value is not null, the way
            // `{missingApp != null && <Text …/>}` does in JSX.
            missingApp?.let { message ->
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug),
                )
            }

            TipSection()
        }
    }
}

/**
 * Kotlin note: extensions on the enum rather than two more fields in it. [SupportRequest] carries
 * what the *mail* is made of and has no Android UI in it; what a row is labelled is the UI layer's
 * business.
 *
 * `internal` rather than `private` because one of the three rows is not on this screen: Settings'
 * *Language* section renders [SupportRequest.Language] and reads its wording from here. The
 * alternative was a second pair of `stringResource` calls over there, which is the shape that lets a
 * row and its mail drift apart.
 */
internal fun SupportRequest.titleRes(): Int =
    when (this) {
        SupportRequest.Bug -> R.string.support_report
        SupportRequest.Feature -> R.string.support_feature
        SupportRequest.Language -> R.string.settings_language_report
    }

internal fun SupportRequest.hintRes(): Int =
    when (this) {
        SupportRequest.Bug -> R.string.support_report_hint
        SupportRequest.Feature -> R.string.support_feature_hint
        SupportRequest.Language -> R.string.settings_language_report_hint
    }
