package app.starter.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import app.starter.R

/**
 * The languages this app ships in (ADR-0004).
 *
 * **This list and `res/xml/locales_config.xml` are the same claim in two files**, and they have to
 * agree: the XML is what Android 13+ reads to build the app's entry in system Settings and what
 * AppCompat's backport reads below 13, while this enum is what the in-app switcher offers.
 * `AppLanguageTest` parses the XML and asserts the two match, because "remember to edit both" is
 * exactly the kind of promise that survives right up until the translation lands and nobody does.
 *
 * Adding a language is three things: an entry here, a line of XML, and a complete
 * `res/values-<tag>/strings.xml` — which `scripts/translation-gate.py` checks before a merge.
 *
 * The labels are **endonyms** — each language named in its own language, in every locale — so a user
 * who has landed somewhere they cannot read finds their way out by a name they recognise. That is
 * why `settings_language_english` reads "English" in `values-pl` too, and why those strings carry
 * `translatable="false"`.
 */
enum class AppLanguage(
    val tag: String,
    @param:StringRes val labelRes: Int,
) {
    ENGLISH("en", R.string.settings_language_english),
    POLISH("pl", R.string.settings_language_polish),
}

/**
 * The language the user has chosen for the app, or `null` for "follow the phone".
 *
 * `null` is the ordinary state and not a missing value: an app locale is an *override*, and having
 * never set one is what most users will do forever.
 *
 * Read from [AppCompatDelegate] rather than from a preference of our own — deliberately. On Android
 * 13+ this is stored by the platform and is editable from system Settings too, so a DataStore key
 * would be a second copy of an answer the user can change somewhere the app never sees.
 */
fun currentAppLanguage(): AppLanguage? {
    val locales = AppCompatDelegate.getApplicationLocales()
    val language = locales[0]?.language ?: return null
    // Matched on the **language subtag of both sides**, and two different things push that way. The
    // platform may hand back a region-qualified locale ("en-GB") for an entry that names only a
    // language ("en") — and an entry such as `pt-BR` is the opposite case, a region-qualified tag for
    // a locale `Locale.getLanguage()` reports as plain "pt". Comparing whole tags misses both, and the
    // symptom is identical either way: a chip the user just tapped that never looks selected. The
    // first app built from this template shipped the one-sided match and found it with `pt-BR`.
    //
    // Sound only because no two entries share a language subtag, which `AppLanguageTest` asserts
    // rather than trusts — the day a second Portuguese or a second Spanish is offered, this match
    // becomes ambiguous instead of merely wrong, and a failing test is how that gets noticed.
    return AppLanguage.entries.firstOrNull {
        it.tag.substringBefore('-').equals(language, ignoreCase = true)
    }
}

/**
 * Applies [language], or clears the override when it is `null`.
 *
 * This **recreates the Activity** — that is how a locale change reaches every already-composed
 * string, and it is the platform's behaviour on 13+ and AppCompat's below it, not something this
 * app arranges. Nothing else has to be told: `MainActivity` is rebuilt and every `stringResource`
 * resolves against the new configuration.
 *
 * Persistence is AppCompat's, via the `AppLocalesMetadataHolderService` in the manifest — a
 * DataStore key would be read asynchronously and let the app draw a frame in the wrong language.
 */
fun setAppLanguage(language: AppLanguage?) {
    AppCompatDelegate.setApplicationLocales(
        language?.let { LocaleListCompat.forLanguageTags(it.tag) } ?: LocaleListCompat.getEmptyLocaleList(),
    )
}

/**
 * This context in the app's chosen language, for strings built outside an activity.
 *
 * Below Android 13 AppCompat applies the chosen language to activities only, so a notification, a
 * channel name or anything else a worker, receiver or service builds from the application context
 * comes out in the phone's language. From 13 the platform applies it to every context and follows a
 * later change, which a pinned configuration would stop, so this returns the context untouched there.
 *
 * Read through [LocaleManagerCompat] rather than [AppCompatDelegate]: AppCompat loads its stored
 * language only when an activity attaches, so in a process the OS started for a worker or a broadcast
 * [AppCompatDelegate.getApplicationLocales] is still empty. The read is a small file, so call this
 * off the main thread.
 */
fun Context.inAppLanguage(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val locales = LocaleManagerCompat.getApplicationLocales(this)
    if (locales.isEmpty) return this
    val configuration =
        Configuration(resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        }
    return createConfigurationContext(configuration)
}
