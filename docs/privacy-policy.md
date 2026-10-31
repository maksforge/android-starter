---
layout: default
title: Privacy Policy
---

# Privacy Policy

_Last updated: <date>_

<!--
    **This is a template with real structure, not boilerplate to paste unread.** Play requires a
    *hosted* privacy policy URL and an offline app has no server of its own, which is why this repo
    carries a `docs/_config.yml` — GitHub Pages serves this file for nothing.

    The part people get wrong is the last section. An app that stores everything locally still has
    things happening around it that a policy has to be honest about, and Play's Data safety form
    asks about them separately. Go through each and delete what does not apply.
-->

<App name> is an Android app for <one line>. This policy describes what the app does with the
information you put into it. It is short because the app does very little.

## The app collects nothing

<App name> has no account, no sign-in, and no server. Nothing you enter is transmitted anywhere.

Everything you record is stored **only in the app's private storage on your own device**, and is
readable only by the app.

There is no analytics, no crash reporting, no advertising, and no third-party SDK that phones home.
The Google Play Billing library, which handles tips, carries usage logging of its own; without the
`INTERNET` permission it has no way to send it.

## What the app can access, and why

- **Photos you choose.** The app uses Android's Photo Picker, which hands it the single file you
  picked and nothing else — it never has access to your gallery. A picture you attach is copied into
  the app's own storage, **re-encoded so that the original's metadata does not travel with it**;
  the location a camera stamps on a photo is removed in the process.
- **The camera**, only when you tap to take a picture, through the system camera app. The app
  declares no camera permission.
- **Notifications**, only if you turn reminders on.
- **Google Play billing.** Lets the app ask the Google Play app for the price of a tip and open its
  payment sheet. It gives the app nothing about you.

## The three things that happen outside the app

These are done by the operating system or by Google, not by <app name>, but they are the honest
answer to "where could my data be":

1. **Android's automatic backup.** If it is enabled on your phone, Android backs up the app's data
   to your own Google Drive, in a space that does not count against your storage quota and that
   Google states apps cannot read. You can turn this off in your phone's settings; the app has a
   link to that screen.
2. **Backups you export yourself.** A backup is a file, and once you have shared or saved it, where
   it goes is up to you and to whatever app you sent it to. Some phones' share screens only hand a
   file over if every app that can receive one may read it before you choose, so the app allows
   that when you share a backup. Once you pick an app, the others lose that access, and if you close
   the share screen without picking, all of them lose it as soon as you are back in the app (or, if
   the phone closed the app meanwhile, when you next share a backup). The one
   you picked keeps it until you next share a backup or restart the phone.
3. **Google Play.** Installing and updating an app is a transaction between your phone and Google,
   with its own privacy policy. About installs, the developer of this app receives aggregate,
   anonymous statistics — install counts, crash rates, country-level breakdowns — and no information
   about individuals.

   **A tip, if you leave one, is paid to Google Play.** The app asks the Play app on your phone for
   the prices and opens Play's own payment sheet; your payment details go to Google and never to the
   app. Tips unlock nothing, so the app keeps no record of one. The developer receives the payment
   and Google's order reports, which show for each tip the product, the price, your country, state
   and postcode, and the model of your phone — and your city, in the few countries where the developer
   rather than Google is the seller — but not your name or payment details. The developer uses them
   only for accounting.

## Children

The app is not directed at children and collects nothing from anyone.

## Deleting your data

Uninstalling the app removes everything it stored. There is nowhere else to delete it from, because
there is nowhere else it went.

## Changes

If this policy changes, the date at the top changes with it, and the history of this file is public
in the repository.

## Contact

**<support email>**
