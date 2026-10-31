package app.starter.ui.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.net.toUri
import app.starter.BuildConfig
import app.starter.R
import app.starter.ui.common.startActivitySafely

/**
 * Where a report goes, **frozen the day a build leaves this machine**: a sideloaded APK is never
 * forced to update, so whatever is compiled in here keeps being mailed for as long as any build
 * survives. So it is an address on a domain we own, whose routing can move to any mailbox without a
 * release, and the domain stays registered for as long as any build does. The Play listing's contact
 * field carries the same address — `docs/DOD.md`, *Before the first upload*.
 */
private const val SUPPORT_ADDRESS = "support@example.com"

/**
 * The id Play knows this app by — **a fact about the listing rather than about this build.**
 *
 * A literal rather than `BuildConfig.APPLICATION_ID`, because debug builds carry
 * `applicationIdSuffix = ".debug"` so they install beside a Play copy, and Play has never heard of
 * the suffixed id. Deriving it by stripping the suffix would work and would write the suffix down in
 * two places. `bootstrap.py` rewrites this line along with every other statement of the
 * applicationId outside Gradle.
 */
private const val PLAY_PACKAGE = "com.example.starter"

/** The Play app itself, which is the only store this app is listed in. */
private const val PLAY_STORE_APP = "com.android.vending"

/**
 * The things a user can send, as a lookup table.
 *
 * An enum rather than two `@StringRes` parameters on [sendSupportMail], because the subject and the
 * prompt are a **pair**: a bug subject over a feature prompt is a mail that sorts into the wrong
 * folder and reads as a mistake, and separate parameters are exactly the shape that lets it happen.
 *
 * **The subject is `translatable="false"` and the prompt is not.** The subject carries a filter tag
 * you sort on and the user never reads; the prompt is a sentence addressed to them. A Polish bug
 * report arriving under the untranslated `#bug` subject is the correct outcome of an app with a
 * Polish locale, not a bug in this pairing.
 */
enum class SupportRequest(
    @param:StringRes val subject: Int,
    @param:StringRes val prompt: Int,
) {
    Bug(R.string.support_subject_bug, R.string.support_body_bug),
    Feature(R.string.support_subject_feature, R.string.support_body_feature),

    /**
     * A word that reads wrong in the user's own language.
     *
     * **Reached from Settings' *Language* section, not from the Support screen** — which is why
     * [SupportScreen] names the requests it shows instead of rendering `entries`. It is the channel
     * that stands in for a native read-through (`docs/translator-brief.md` §8), and it belongs
     * beside the picker because that is where somebody is standing the moment a translation reads
     * wrong. A second row for it on Support would only ask the user to choose between two words for
     * the same thing.
     */
    Language(R.string.support_subject_language, R.string.support_body_language),
}

/**
 * Hand the user off to their own mail app with a report half-written.
 *
 * **The subject and body travel in the `mailto:` query string, not in `EXTRA_SUBJECT`.** That is a
 * device finding rather than a design: Gmail silently ignores `EXTRA_SUBJECT` for `ACTION_SENDTO`,
 * so nothing about the intent fails — the composer just opens with an empty subject, which is the
 * sort of thing found by a user rather than by a build.
 *
 * `Uri.encode` per component, then the pieces concatenated: a `mailto:` URI is **opaque** (there is
 * no `//authority`), so `Uri.Builder` would percent-encode the `?` and `&` that separate the fields
 * and produce one long unparseable opaque part. Encoding each value and joining them by hand is the
 * only shape that survives. `Uri.encode` also writes a space as `%20` rather than `+`, which matters
 * here — RFC 6068 does not read `+` as a space, so a `+`-encoded body arrives full of plus signs.
 *
 * **No `resolveActivity` pre-check.** The manifest's `<queries>` declares the `mailto:` intent so one
 * added later would answer honestly, but the launch itself is already the check — and a pre-check
 * that disagreed with the launch would be a row disabled on a phone that can mail perfectly well.
 *
 * @return whether a mail app opened, so the caller can say so rather than leave a tap doing nothing.
 */
fun Context.sendSupportMail(request: SupportRequest): Boolean {
    val uri =
        (
            "mailto:$SUPPORT_ADDRESS" +
                "?subject=" + Uri.encode(getString(request.subject)) +
                "&body=" + Uri.encode(supportMailBody(getString(request.prompt)))
        ).toUri()
    return startActivitySafely(Intent(Intent.ACTION_SENDTO, uri))
}

/**
 * Open this app's Play listing: in the Play app if the phone has it, in a browser if not.
 *
 * **Not a third [SupportRequest].** That enum is what a *mail* is made of, and a rating is not mail.
 *
 * **An `https://` link pinned to the Play app, not `market://`.** That is a device finding: on
 * HyperOS the `market` scheme is also claimed by Xiaomi's own store, so an unpinned `market://` tap
 * opens the system chooser with that store listed first — and the app is not in it. `setPackage` is
 * the platform's way of saying *this app and no other*; with it, the launch either opens Play or
 * throws `ActivityNotFoundException` because Play is not installed. The `https` link is the same one
 * either way, so the fallback is that link unpinned, which a browser answers.
 *
 * @return whether anything opened, the same contract as [sendSupportMail].
 */
fun Context.rateOnPlay(): Boolean {
    val listing = "https://play.google.com/store/apps/details?id=$PLAY_PACKAGE".toUri()
    val inPlay = Intent(Intent.ACTION_VIEW, listing).setPackage(PLAY_STORE_APP)
    return startActivitySafely(inPlay) || startActivitySafely(Intent(Intent.ACTION_VIEW, listing))
}

/**
 * The prompt, room to write, and the facts a bug report always needs and a reporter never has to
 * hand.
 *
 * Composed from [BuildConfig] and [Build] — nothing read here that the app does not already know
 * about itself, and nothing that needs a permission. It is not a privacy question in the ordinary
 * sense either: this app declares no `INTERNET` permission and has no route off the device, so this
 * is the *user's own* mail, composed in their client, visible to them before they send it and
 * deletable line by line.
 *
 * `app_name` rather than a literal, because the debug source set overrides it — a report from a
 * sideloaded debug build carries the debug label and is told apart from a Play one at a glance.
 *
 * The version block is deliberately **not** in `strings.xml`: it is data rather than prose, there is
 * nothing in it a translator could improve, and `docs/translator-brief.md` §4 keeps exactly this
 * kind of string out of their hands.
 */
private fun Context.supportMailBody(prompt: String): String =
    buildString {
        appendLine(prompt)
        appendLine()
        appendLine()
        appendLine("---")
        appendLine("${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        // The **resolved** locale, which is the one the sender is actually reading, rather than
        // `currentAppLanguage()` — that returns null for "follow the phone", which is the ordinary
        // state and tells a translation report nothing. Read off the configuration because that is
        // what `stringResource` resolved against, so it is true whether the locale came from the
        // in-app picker or from the phone. It is the fact a `#language` report cannot do without and
        // the one nobody should have to type.
        append("Locale ${resources.configuration.locales[0].toLanguageTag()}")
    }
