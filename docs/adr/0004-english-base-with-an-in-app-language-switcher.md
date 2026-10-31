# English is the base language, and the app carries its own language switcher

## Context

Android 13 added per-app languages: the system builds a Language entry in the app's settings from
`res/xml/locales_config.xml`, and `setApplicationLocales()` overrides the phone's locale for this app
alone. Below 13 the platform has none of that.

Two things follow that are easy to get wrong:

- **Persistence.** On 13+ the framework stores the choice. Below 13 something has to, and it has to
  be readable *before the first frame* — a `DataStore` read is asynchronous, so the app would draw
  its first screen in the phone's language and then repaint.
- **Completeness.** "Every string has a translation in every locale" is the right rule at the wrong
  boundary if it is a test: it turns a red build on the moment an English string is written, which
  puts every feature branch behind a translation round and gets the copy translated twice — once
  against the draft wording, and again after review reworded it.

## Decision

**English is the base and the fallback for every unmatched locale.** `res/values/strings.xml` is the
source; translations are made from **English only**, never from another translation.

**AppCompat provides the switcher**, which costs three things and is worth all of them: the
`appcompat` dependency, `MainActivity : AppCompatActivity`, and a root theme descending from
`Theme.AppCompat`. Persistence below 13 is AppCompat's, through the disabled
`AppLocalesMetadataHolderService` in the manifest — a manifest entry used as a storage slot, read
synchronously before anything draws.

**Completeness is a merge gate, not a test.** `scripts/translation-gate.py` refuses a merge where a
shipped language is incomplete, or where the English changed on this branch and the translation did
not. **Free while you work, strict before it merges.**

**Everything else about a translation stays a continuous test.** `TranslationTest` holds format
arguments, plural categories per CLDR, orphans, `translatable="false"` and string-array lengths.

**The switcher's list and `locales_config.xml` are asserted to agree**, because "remember to edit
both" survives right up until the translation lands and nobody does.

## Alternatives

**A DataStore key for the language.** Read asynchronously, so the first frame is in the wrong
language. Also a *second* copy of an answer the user can change in system Settings, where the app
would never see it.

**Follow the phone's language only.** Loses the case this exists for: someone whose phone is in a
language they read poorly, or who wants one app in a different language from the rest.

**Make completeness a test.** See above — it costs a translation round per branch and buys nothing a
gate does not.

## Consequences

- A locale in `locales_config.xml` with no strings behind it offers the user a language the app
  cannot speak. Add the entry when the translation is ready, not before.
- A stale translation is invisible to every other check: it parses, it carries its format arguments,
  and it says something the English no longer says. That is the case the gate's merge-base comparison
  exists for — which is why a shallow CI checkout silently disables it, and why the workflow uses
  `fetch-depth: 0`.
- Language names in the switcher are **endonyms** — each language named in its own language, in every
  locale — so someone who has landed somewhere they cannot read finds their way out.
- **Below Android 13 the chosen language reaches activities only.** AppCompat applies it in an
  activity's `attachBaseContext`, so a notification, a channel name or any other string a worker,
  receiver or service builds from the application context follows the phone's language there. Code
  outside an activity takes its strings from `context.inAppLanguage()` (`ui/settings/AppLanguage.kt`),
  which reads the stored choice itself because a process the OS started for a worker never loaded
  AppCompat's copy. From 13 the platform applies the choice to every context and the call is a no-op.
