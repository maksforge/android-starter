# Play Console — App content answers

The answers this app gave in the Console's **App content** pages, what each one rests on, and **the
fact that would change it**. The Console shows what was answered but never why, and Play asks again:
when the app changes, when the questionnaire changes, and once a year. A re-declaration made from
memory a year from now is how a true answer turns false.

<!--
    A template with real structure. Every section below is a page the Console will make you answer
    before a closed release, and the answers are pre-filled for the template *as shipped*: no
    network, no account, no ads, reminders and photos on the device. Delete what your app does not
    have, re-reason what it adds, and date every answer the day you give it in the Console.

    The first app built from this template wrote this file in its fifth phase, after `DOD.md` had
    carried it as owed since before the first upload. Writing it while you fill in the Console is
    cheaper: the reasons are in front of you then, and gone a week later.
-->

Two rules keep this file honest:

- **The privacy policy and the Data safety form describe the same facts to two audiences**, and Play
  cross-checks them. [`privacy-policy.md`](privacy-policy.md) and this file move in the same commit.
  A change to one without the other is the drift this file exists to stop.
- **A claim about permissions is read off the built artifact, never off the manifest.** A dependency
  can merge a permission the source never declared, and that is invisible from `AndroidManifest.xml`.
  `scripts/aab-permissions.py` is the reader, it runs before every upload, and its `EXPECTED` and
  `FORBIDDEN` tables are this section's twin — edit them together.

## What the artifact carries

Read at **versionCode _, versionName _** by `publish-play.yml`'s verify step on _<date>_:

> _paste the summary line `aab-permissions.py` prints_

| Permission | Whose | What it is for | Console consequence |
| --- | --- | --- | --- |
| `POST_NOTIFICATIONS` | ours | reminder notifications, asked from a screen that explains them | none |
| `RECEIVE_BOOT_COMPLETED` | ours | `BootReceiver` re-arms the sweep after a restart | none: normal, install-time |
| `SCHEDULE_EXACT_ALARM` | ours | a reminder that must land on its minute (ADR-0003) | **none today, but watch it**: Play restricts exact alarms, and `USE_EXACT_ALARM` is forbidden outright |
| `WAKE_LOCK` | WorkManager | the worker's own wake lock | none |
| `ACCESS_NETWORK_STATE` | WorkManager, and Play Billing's `datatransport` | WorkManager reads connectivity for job constraints, `datatransport` before its (blocked) log upload; neither opens anything | none, but a reviewer may ask why an app with no network reads it — this row is the answer |
| `FOREGROUND_SERVICE` | WorkManager | its `SystemForegroundService`, never started by this app | none while nothing declares a foreground service *type* |
| `com.android.vending.BILLING` | Play Billing Library | the tip (ADR-0007): IPC to the Play app's billing service | none: not on Play's declaration list. The listing shows *Contains in-app purchases* once products exist |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX | its own non-exported receivers, signature-level | none: not user-visible |

The billing library's `datatransport` dependency merges `INTERNET`; the manifest removes it with
`tools:node="remove"`, and the gate is what proves it stayed out. It merges `ACCESS_NETWORK_STATE`
too, so that row stays until both WorkManager and the tip are gone.

**Forbidden, and asserted absent**: `INTERNET`, `AD_ID`, `QUERY_ALL_PACKAGES`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `CAMERA`, `USE_EXACT_ALARM`. The first two are what the Data
safety and advertising-ID answers rest on.

**Two asks are not permissions and appear on no Play list**: the battery-optimisation exemption and
Xiaomi's autostart. Both are hand-offs to Settings. They need no declaration, and the privacy policy
names them anyway, because a user sent to two Settings screens should find both explained.

---

## Ads

**No.** Answered _<date>_.

Would change: any ad SDK, ever. `AD_ID` absent from the artifact is the check that would catch one
arriving through a dependency.

## App access

**All functionality is available without special access.** Answered _<date>_. The Console now calls
this page *Sign-in details*.

There is no account and no sign-in. Would change: a feature behind a login.

## Content rating

**The IARC questionnaire.** Answered _<date>_, category _<category>_, rating contact
`<support email>` — the listing's address, so there is one address to keep alive, not two. Every
question answered No gives Everyone (ESRB) and PEGI 3.

The category is *All other app types* unless the app's purpose is something the questionnaire names.
Not *Social or communication* for an app that only touches calls or messages: that category means
people talking to each other through the app.

**The tip turns one answer to Yes**: *does the app allow users to purchase digital goods?*
(ADR-0007). Its follow-up, *can these purchases include random items* (loot boxes), is
**No**. The first two apps built from this template, both category *All other app types*, came out
at PEGI 3, ESRB Everyone, USK 0 and 3+ elsewhere, with an *In-app purchases* notice — and **Brazil's
ClassInd at 14+**, which it gives any app selling digital goods. Expect the same, and decide before
submitting that a tip is worth it; deleting the tip is the only answer that keeps Brazil at L.

In the Console the questionnaire shows one section at a time, and *Next* stays disabled until
*Save* has been pressed. With managed publishing off, saving the summary sends it for review at once.

Would change: user-generated content, communication between users, or a link out to anything that
has either. A support `mailto:` hand-off is not user-to-user communication — it opens the user's own
mail app, addressed to the developer — and a rate row opens Play's own listing, whose reviews are
Play's surface rather than the app's.

## Target audience and content

**18 and over** _(or your answer)_. Answered _<date>_.

Would change: lowering it. Any age band under 18 brings in the Families policy and its design and
SDK requirements.

## Government, financial and health features

**No to all three** _(or your answer)_. Answered _<date>_.

Health is the one to guard, for an app anywhere near wellbeing. **Keep health claims out of the
listing, the site and the tags** — Play's enforcement has treated a linked page as part of the
listing. Would change: copy or a tag that sells the app as good for you.

## Data safety

**No data collected. No data shared.** Answered _<date>_ against versionCode _.

Play's definitions, from its Data safety help page (support.google.com, answer 10787469) — re-read
them at each re-declaration rather than trusting this quote:

> "'Collect' means transmitting data from your app off a user's device."
>
> "User data accessed by your app that is only processed locally on the user's device and not sent
> off device does **not** need to be disclosed."

The answer rests on one fact per route data could take:

1. **The app cannot transmit anything.** `INTERNET` is absent from the artifact, and
   `aab-permissions.py` fails the upload if it ever appears. This one fact is most of the answer.
2. **Photos are processed only on the device.** Picked or taken, re-encoded by `media/MediaFiles.kt`
   with their metadata — GPS included — stripped, and stored in the app's private storage.
3. **Auto Backup is still "no data collected".** The platform moves the user's backup to the user's
   own Google Drive; the developer never sees it. That is Android transmitting the user's backup, not
   the app transmitting to the developer. ⚠ Every file the backup carries lands on the user's *next*
   phone too — see ADR-0005 for what is included and why.
4. **A support mail is still "no data collected", and the reason is the definition rather than an
   exemption.** A `mailto:` hands a draft to **another app**; the user reads it, can edit every line,
   and sends it from their own mail client, or does not. ⚠ **Do not write "Play exempts support
   flows" in a re-declaration.** The help page has no such exemption; the claim is true because of
   what "collect" means, and that is the reason to give.
5. **A tip is still "no data collected", by the same help page's FAQ on payment services.** It names
   Google Play's billing system as a service whose payment data you don't need to declare when the
   app never accesses that information and the service collects it directly from the user under its
   own terms. The tip meets both: Play's own sheet takes the payment details, under Google's terms,
   and the app never sees them. The FAQ also asks about other financial data and about what the app
   receives back: that is a purchase token and a product id, which the app hands straight back to Play
   to consume, never stores and never sends anywhere else, so by the definition above it is not
   collected. The developer's order report comes from Google, not from the app.

Would change any of it:

- **`INTERNET` on the artifact**, from our code or a dependency. Every answer here becomes a question.
- **Anything the app sends by itself**, such as crash reporting or analytics. There is none today.
- **The app keeping a purchase**, such as a tip history or a supporter badge: that is *Purchase
  history* the app collects, and the form has no way to declare it as Google Play's.
- **Location of any kind**, including through a photo's metadata surviving into storage or a backup.

## Advertising ID

**No.** The artifact carries no `com.google.android.gms.permission.AD_ID`, and `aab-permissions.py`
forbids it. Answered _<date>_.

## Foreground service permissions

**None declared** as shipped: WorkManager merges `FOREGROUND_SERVICE` but no foreground service
*type*, and nothing here starts one. If the app gains a foreground service, this section records the
type, the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text if it is `specialUse`, and the three answers the
declaration asks for: what the user sees, why it cannot wait, and why no named type fits.

The declaration also wants **a video** of the service doing its job. Record its link here, and keep
it viewable by anyone with the link for as long as the declaration is on file. `adb screenrecord`
captures no audio, so say so in the declaration if the service's job is a sound.

## Store settings (not App content, recorded here because it is asked for the same reason)

- **Category:** _<category>_, and why not the obvious neighbour.
- **Tags:** _<tags>_.
- **Contact email:** `<support email>`, public on the listing and the same address
  `SupportHandoff.kt` compiles in. The Console field changes without a release; the app's copy needs
  one.
- **Privacy policy:** `https://maksforge.com/<repo>/privacy-policy.html`, served from `docs/` by
  Pages. It updates on a merge to `main`, with no release, which is why it has to be right before the
  app it describes ships rather than after.

## Production access — the application, and the answers it was given

**Submitted _<date>_.** Asked once, from the Console's Dashboard, after the closed test's 14 days.
Record the answers **verbatim**, not summarised: the Console asks the same set again for the next app
on this account, and an answer re-read a year later has to be the one that was given.

**Draft the answers here before pasting them**, under a *Drafted <date>, not submitted* line that
becomes the submission date once they are in. Every claim must trace to a dated record in
[`DOD.md`](DOD.md) or a phase file: a test you remember but never wrote down is cut, not paraphrased.
Leave `_<…>_` gaps only for the testers' own words, which only the owner has.

**About your closed test**

- *How did you recruit users for your closed test?* — _<answer>_
- *How easy was it to recruit testers?* — _<answer>_
- *Describe the engagement you received from testers* — _<answer>_
- *Provide a summary of the feedback that you received* — _<answer>_

**About your app**

- *Who is the intended audience?* — _<answer>_
- *Describe how your app provides value to users* — _<answer>_
- *How many installs do you expect in your first year?* — _<answer>_ (the apps built from this
  template answered 0–1,000)

**Your production readiness**

- *What changes did you make based on what you learned during your closed test?* — _<answer>_
- *How did you decide that your app is ready for production?* — _<answer>_

**Three things the apps built from this template kept deliberate, so a later answer does not undo
them:**

1. **The audience answer agrees with *Target audience* above.** A reviewer reads the two side by side.
   A mismatch is a question rather than a rejection, but it can cost days.
2. **Engagement is answered from what the app can know.** With no `INTERNET` permission there is no
   telemetry, so the true answer is what is visible without it: *"The app has no internet permission
   and no analytics, so I cannot see how often testers used it. What I can see is the testers who
   stayed opted in through the 14 days, and the messages some of them sent."* A guessed figure would
   contradict *Data safety* in this same file.
3. **The changes answer says where the changes came from.** If the testers reported nothing, say so and
   name what came from your own use instead. Presenting your own work as tester-driven is an easy
   dishonest sentence, and the application does not need one.

## A payment-link tip (Payments policy — not a question Play asks, but one a reviewer answers)

**Default: no payment link on any surface the release controls**, whatever the app sells through Play
Billing. Not in the app, and not on the pages the listing links to. Read this before adding one,
because the policy text and its enforcement disagree.

- **The text allows it.** Payments policy §3.2 treats a tip as a peer-to-peer payment, outside Play
  Billing, when **100% reaches the creator** and **nothing unlocks** — no badge, no theme, no
  thanks-screen. §4's anti-steering rule excepts §3. So: one personal payment link, worded as a *tip to
  a person*, never a *donation to a project*.
- **Enforcement has rejected apps anyway.** StreetComplete (February 2022) was rejected for in-app
  Patreon, Liberapay and GitHub Sponsors links — and **for a link to its own home page, because that
  page carried donation information**. A Buy Me a Coffee link in the app this template was extracted
  from was flagged the same way. None of those is the §3.2 shape (tiers unlock things, a platform takes
  a cut, a project is not a person), but the distinction has to survive a reviewer skimming for
  "external payment link", and a pre-launch rejection costs the closed-test window.
- **The reach is one level out.** The first app built from this template moved its tip from the app to
  the repository and the Pages site, then noticed that the listing's *Website* field and the last line
  of its full description point at exactly those pages. So "nothing the app links to" includes what
  the **listing** links to.
- **When to revisit:** once the app is live, with a track record to appeal from. The cheapest shape
  then is a tip on the repository alone, with the listing's Website field and full description kept
  off it. A Play Billing consumable is the unambiguous alternative, at the cost of a billing
  dependency, Google's cut, and an *in-app purchases* badge on the listing.

Region carve-outs (US external links since October 2025, the EEA's external-offers program) do not
help: both are fee programs with reporting obligations, which is not a serious route for occasional
tips. A sentence promising a *future* tip, with nothing to click, is a statement about pricing and is
fine anywhere.

## The tip (ADR-0007)

**A Play Billing consumable, not a link** — the section above is why. The template ships it; an app
that does not want it deletes it before the first upload (`optional-modules.md`), and the section
above still holds after it is gone.

What a reviewer can still ask about, and the answer:

- **The *Contains in-app purchases* label** on a free app: the tip unlocks nothing, and the Support
  section says so in the user's language.
- **The content rating**: re-answered above, with Brazil at 14+.
- **Data safety**: point 5 above.
- **Financial features**: unchanged. A tip is an in-app purchase, not a financial feature.

---

## When Play asks again

1. Run `python3 scripts/aab-permissions.py` against the newest bundle, and compare its table with the
   one above. A new row is a new question.
2. Read `docs/privacy-policy.md` against the answers here. If one has moved, both move, in one commit.
3. Copy any value this file does not hold while the Console is open — a category, a date, a
   declaration's exact wording — so the next re-declaration can compare against it.
4. Re-date the sections you re-answered.
