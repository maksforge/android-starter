# Versioning & releasing

Versions are automated from git — you never hand-edit them. This mirrors what
`standard-version` does in the JS world, adapted to Android's two version fields.

## The two Android version fields (they are not the same thing)

| Field | Type | Who sets it | What it is |
| --- | --- | --- | --- |
| `versionCode` | `Int` | **git commit count**, computed in `app/build.gradle.kts` | The build's true identity. Must strictly increase or the installer/Play Store refuses it. Not semver. |
| `versionName` | `String` | **release-please**, from Conventional Commits | The `1.4.2` semver users see. The one you think of as "the version". |

Both are derived; neither is edited by hand.

## Writing commits (this is the part that requires discipline)

Every commit subject must be a [Conventional Commit](https://www.conventionalcommits.org):

```
<type>[optional scope][!]: <description>
```

- `feat: …`  → next release bumps the **minor** (1.0.0 → 1.1.0)
- `fix: …`   → next release bumps the **patch** (1.0.0 → 1.0.1)
- `feat!: …` or a `BREAKING CHANGE:` footer → bumps the **major** (1.0.0 → 2.0.0)
- `docs / style / refactor / perf / test / build / ci / chore` → no version bump, but `chore`/`docs` etc. still show in history

A committed hook (`.githooks/commit-msg`) rejects messages that don't match, so a
bad message can't sneak in. **Activate it once after cloning:**

```bash
git config core.hooksPath .githooks
```

(It's plain shell — no husky/Node. Bypass in an emergency with `git commit --no-verify`.)

The same setting turns on `.githooks/pre-push`, which prints what the branch still
owes the translation gate before you push. It is **advisory and cannot fail a push**:
completeness is a merge boundary, not a work boundary, and a branch mid-translation is
ordinary. It exists only so you hear it in a second rather than
from CI four minutes later. Silent when there is nothing outstanding.

## How a release happens

1. You push normal Conventional Commits to `main`.
2. The **Release Please** GitHub Action keeps a PR open titled like
   `chore(main): release 1.1.0`. That PR bumps `versionName` and updates
   `CHANGELOG.md` — the changelog is generated from your commit subjects.
3. When you want to cut the release, **merge that PR**. release-please then tags
   the repo (`v1.1.0`) and creates a GitHub Release. Merge whenever you like;
   nothing ships until you do.

Config lives in `release-please-config.json` + `.release-please-manifest.json`
(the manifest holds the current version — release-please rewrites it).

### Merge pull requests with **rebase**, never a merge commit

The repo allows rebase merging only, and that setting is load-bearing for the
changelog rather than a matter of taste.

GitHub writes the PR title into a merge commit's *body*. `conventional-commits-parser`
strips the `Merge pull request #N from …` header and parses what follows as the
commit — so a PR titled `feat: …` whose branch also carries that `feat: …` commit
is counted **twice**, and the release notes list the feature twice. That is exactly
what 1.1.0's first draft did, for all five of its features; the four `fix:` entries
escaped only because those PR titles happened to be `chore:` or plain prose, which
is luck, not a rule.

Rebase replays the branch commits onto `main` with no merge commit in between, so
every conventional subject is counted exactly once and each commit keeps its own
changelog line. Squash would also de-duplicate, but it folds a PR's individual
subjects into a single entry taken from the PR title — and this repo writes a
meaningful subject per commit (PR #72 alone contributed three separate `fix:`
lines), so squashing would throw away detail the changelog exists to carry.

There is no release-please option for this. The merge strategy *is* the fix, which
is why it is enforced by the repo setting instead of by remembering.

## Release notes are a merge gate, not an afterthought

`scripts/notes-gate.py` runs in CI on every pull request, beside the schema and translation gates. It
asserts one thing: the newest `### x.y.z` under `## Release notes` in
[`store-listing.md`](store-listing.md) is the `versionName` about to ship.

It exists because `play-metadata.py` uploads **the newest notes it finds** and nothing else consults
them. Bump `versionName` without moving the notes and the previous release's text goes up attached to
the new build, with nothing to notice it. That is how, in the app this template was extracted from,
**1.9.0 reached production describing 1.8.0** — a note predating four features users could now see. ⚠️ **Play does not allow release notes to be edited on a live release**, so the correction
could not be made where it was needed; it waited for the next upload.

The gate is invisible on ordinary branches — `versionName` is still the last released version and the
notes already match. It fails on **release-please's PR**, which is the one moment the two are allowed
to disagree and the last moment it is free to fix.

**Where the notes get written, and where they must not be.** The obvious place is release-please's
own PR branch, because that is where `versionName` already carries the new number. It is the wrong
place: the action **force-pushes that branch** every time anything lands on `main`, so a note
committed there survives only until the next merge and its loss is silent. Write them on an ordinary
branch instead, before the release PR merges. **The gate allows notes to run ahead of `versionName`
and fails only when they fall behind**, which is what makes that possible — and behind is the
direction that actually shipped 1.9.0 describing 1.8.0, so that half stays fatal.

Nothing can ship the wrong text in the window between the two. `publish-play.yml` uploads the bundle
with **no release notes at all**, and `publish-play-production.yml` — the only workflow that runs
`play-metadata.py` — is manual, held by the `production` environment's reviewer, and points at a
released tag where `versionName` and the newest heading agree again.

**When nothing owner-visible changed, satisfying it is a rename, not a rewrite.** Move the heading to
the new version and say why the bodies stand unchanged; 1.8.0 is the worked example. The gate reads
the heading, so "these notes still hold" stays a decision someone made rather than the default.

```bash
python3 scripts/notes-gate.py            # the gate itself
python3 scripts/notes-gate.py --report   # what does this branch owe?
python3 scripts/notes-gate.py --pending  # will the release this branch feeds have notes?
```

It also checks what the notes must satisfy to be usable at all: a note for every locale the AAB
carries, and every one inside Play's 500 characters.

**`--pending` moves the failure from the release PR to the branch that caused it.** Left alone, the
gate fails once per release, on release-please's PR, and the fix is a separate docs PR every time.
`--pending` predicts the number instead: the manifest's version on `origin/main`, bumped by the
conventional commits since the last release plus this branch's own, read the way release-please reads
them — including the pre-1.0 options in `release-please-config.json`. Replayed against nine releases
of an app built from this template, it proposed the version release-please actually cut every time.
It exits **2** when that version has no `### x.y.z` on the branch or on `main`, and 0 when no release
is pending — a docs-only branch never owes notes.

**Claude Code runs it before every `git push` it makes — once `bootstrap.py` has installed the hook.**
`.claude/settings.json` gets a `PreToolUse` hook on every Bash call, and `scripts/push-notes-hook.py`
decides which calls are a real `git push` and runs the gate in the checkout that push comes from — a
linked worktree, a `cd`, or a `git -C`. Exit 2 blocks the push and hands Claude the message, so the
notes are written on the branch before it leaves; any other failure only warns, because a broken
check must not stop every push — and so does a push whose checkout it cannot find. It fetches
`origin/main` first, since a stale `main` would miss a `feat:` merged elsewhere.
⚠ **The template carries no hook, on purpose, and that is why it is a per-repo step.** The template
never releases (below), so its own `feat:` commits would make every push to it owe notes for a version
that will never ship. `bootstrap.py` writes the hook as part of turning the template into an app.
⚠ Claude Code reads project hooks only from the directory it was **launched in**, after that folder is
trusted. Started from a parent directory, it never loads them and nothing says so.
⚠ It checks that the heading **exists**, not that it describes everything: a second `feat:` branch
toward an already-noted version passes, so adding its line is still a judgement. Pushes from your own
terminal never run it — `.githooks/pre-push` is a different, advisory hook.

## Checking the artifact before it reaches Play

`bundleRelease`, never `assembleRelease` — Play wants an AAB and an AAB can't be
`adb install`ed, so the only build ever put on the phone is the one Play delivers.
Then read the version fields back **out of the artifact**, not out of the config
that was supposed to produce it:

```bash
./gradlew bundleRelease
python3 scripts/aab-version.py        # versionCode/versionName vs. git
python3 scripts/aab-permissions.py    # the <uses-permission> set, vs. an allowlist
python3 scripts/aab-locale.py         # every string of every shipped locale, vs. the resource table
python3 scripts/aab-reflection.py     # the classes only the manifest names, vs. what R8 left in the dex
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

All four scripts **exit non-zero** rather than printing and leaving you to read.
Each exists because the corresponding claim was once wrong in an artifact while
every source-side check was green, in the app this template was extracted from:
`versionCode` 1 on a signed bundle, Polish missing from the build that went up, a
permission set that had quietly grown from two to six, and R8 shrinking away the
no-arg constructor of a class only the manifest names, which disabled a feature
without raising anything. The pattern is the same every time — the config said one
thing, the artifact said another, and nothing compared them.

⚠ **The allowlists in these scripts describe the template, not your app.** An app
built from it failed its first release upload at `aab-permissions.py`, because the
table still listed the previous app's permissions. Whenever you delete a feature
(reminders, WorkManager, the camera path), delete its rows in the same commit — and
run the four scripts against a local `bundleRelease` before the first tag, not in
the workflow that is supposed to ship it.

`aab-reflection.py` is the newest and the one whose failure is quietest, because
its subject never crashes: a class discovered by `Class.forName` and built with a
no-arg constructor just isn't there, and the framework that wanted it carries on
without it. It reads the classes to check **out of the manifest** — every
`<meta-data>` whose value marks one — rather than from a list that would go stale,
and looks each up in the dex. `aab-permissions.py` does the other half of the
same job: it asserts the only `android:screenOrientation` values in the artifact are
the ones listed in its `EXPECTED_ORIENTATION` — none, as shipped — so a dependency
bump cannot quietly lock a screen, and an edit cannot quietly unlock one you decided
to lock. (A library AAR pinning its own delegate activity to portrait is a real Play
policy finding, and it is invisible in your own source.) It was a blanket ban once,
and that is how an app built from this template tagged a release that never reached
Play: the app had grown a deliberate lock and the gate still asserted it had none.
Lock a screen, add the row, same commit.

Don't reach for `aapt2 dump xmltree` here. An AAB stores its manifest as
**protobuf**, not the binary XML aapt2 reads, so it prints nothing and exits `0` —
it doesn't fail, it just declines to answer. That silence is how a release
produced a *signed* bundle carrying `versionCode` 1 and didn't find out until
later. `scripts/aab-version.py` decodes the protobuf and asserts the count matches
`git rev-list --count HEAD`.

## Reaching Play automatically (internal testing)

`.github/workflows/publish-play.yml` builds and uploads to the **internal testing** track
when a **GitHub Release is published** — that is, when you merge the release-please PR.
Nothing uploads on a bare push to `main`, so cutting a version is still a deliberate act;
this only removes the hand-build that used to follow it.

What the workflow does, in order: full checkout (`fetch-depth: 0`, because `versionCode`
is the commit count), materialise the upload key from secrets, `bundleRelease`, run all
four `aab-*.py` artifact checks, print the signing certificate, upload the AAB **and its
R8 mapping**, keep both as a build artifact for 90 days, delete the key.

The mapping matters because R8 is on: without it every Play crash report is
obfuscated frames.

### The five secrets this workflow needs

Four for the upload key, one for Play. `RELEASE_PLEASE_TOKEN` is a sixth, needed
earlier and by a different workflow — see *Setting up a new repository*. Set them under *Settings → Secrets and variables →
Actions*:

| Secret | What it is |
| --- | --- |
| `UPLOAD_KEYSTORE_BASE64` | the keystore file itself, base64: `base64 -w0 /path/to/upload.jks` |
| `UPLOAD_STORE_PASSWORD` | same value as `upload.storePassword` in `local.properties` |
| `UPLOAD_KEY_ALIAS` | same value as `upload.keyAlias` |
| `UPLOAD_KEY_PASSWORD` | same value as `upload.keyPassword` |
| `PLAY_SERVICE_ACCOUNT_JSON` | the whole service-account JSON, pasted as-is |

⚠️ **The keystore is still never committed** — this changes where a copy *lives*, not the
rule. The one on disk stays outside the repo (*The upload key*, below); base64 in a GitHub
secret is a second copy, and losing control of it means resetting the upload key. That is recoverable
(Google holds the permanent app-signing key) but it is not free.

### Creating the service account — the part that is not in this repo

Once, by hand, and it is the only step CI cannot do for itself:

1. Google Cloud console → the project linked to your Play developer account → **enable the
   Google Play Android Developer API**.
2. **IAM & Admin → Service Accounts → Create**. No project roles are needed; its authority
   comes from Play, not from GCP.
3. On that account, **Keys → Add key → JSON**. The file downloads once — that is the
   `PLAY_SERVICE_ACCOUNT_JSON` value.
4. Play Console → **Users and permissions → Invite new user**, the service account's email.
   **Account permissions: none — every box empty.** That is the field that would quietly make
   this a credential for every app on the developer account rather than for this one.
   **App permissions, on this app only** (your `applicationId`), exactly two boxes:
   *View app information (read-only)* — the baseline the others build on — and
   *Release apps to testing tracks*, which is the one that does the work.
   Deliberately **not** *Release to production*: production stays a human decision behind the
   environment gate. Also not *Manage testing tracks and edit tester lists* — CI uploads builds,
   it does not manage people.

**One service account per app, scoped with a permission group.** *Users and permissions* →
**Permission groups** → *Create permission group* lets you tick the two boxes once and attach the
group to an email afterwards. Name it for the app (`<App> — CI release`), not for the role: a group
carries its own app scoping, so one role-shaped group reused across apps would have to list every
app in it — which merges exactly the credentials that a separate service account per app exists to
keep apart. Invite the email before creating the group; a user must exist to be selectable in it.
⚠ **Leave *Set access expiry date* unchecked.** It is offered on the create screen and it is wrong
for a machine identity: on that date publishing starts failing, wearing the same 401 as the
propagation delay below.

⚠ **The production workflow uses this same secret, and wants two more boxes.** With testing-track
rights only, the first run of `publish-play-production.yml` **403s** — a permission missing, not the
pipeline broken. It needs *Release to production, exclude devices, and use Play App Signing*, and —
**only when run with `update_listing: true`** — *Manage store presence*, because that is the run that
pushes descriptions, images and screenshots. **Release notes need neither**: they belong to the track
release rather than the listing. Granting these is the deliberate act that opens the production
door. Nothing beyond the two boxes is needed for `publish-play-closed.yml`, which never touches the
listing, nor for `publish-play.yml`, which sends the AAB and its mapping and nothing else.

**Retry before you debug.** Propagation between Play and the API is not instant — a permission
granted in the Console can take minutes to reach the API, so a first run that 401s is worth simply
re-running. The first green run of an app built from this template came on the fourth attempt, and
all three failures were one cause: the Google Play Android Developer API not yet enabled in the
Cloud project (step 1), then its own propagation delay. Re-run with `workflow_dispatch` — those
attempts cost nothing, whereas a new tag costs a version.

⚠ **The upload step warns on every run**: `r0adkll/upload-google-play` reports
`'track' is deprecated and will be removed in a future release. Please migrate to 'tracks'`. It
uploads correctly today; it is written down because a pinned-action bump breaking a release is the
worst possible moment to read a deprecation notice for the first time.

### The upload key

Created once, outside the repo, and the only one there will ever be short of a reset with Google.
**Fill this table in the day you create it** — Play shows the fingerprint as the expected *upload*
certificate, and a value that only ever existed in your scrollback is one you will not have when
it is asked for.

| | |
| --- | --- |
| File | `~/.keystores/<app>-upload.jks` — **outside the repo**; `*.jks` is gitignored as a second line of defence |
| Type / alias | PKCS12, alias `upload`. PKCS12 keeps **one** password, so `upload.storePassword` and `upload.keyPassword` are the same string |
| Key | *(algorithm, size, valid until)* |
| SHA-256 | *(fingerprint)* |
| Backed up to | *(somewhere that is not this machine)* |

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore ~/.keystores/<app>-upload.jks \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab   # read it back
```

**That fingerprint is the one to compare** against what Play shows as the expected upload
certificate. It is not the app-signing certificate: Play re-signs with a key Google holds, which is
why losing this one is recoverable at all — and why a build signed with the wrong key is rejected at
upload rather than breaking installs. The four values live in `local.properties`, which is
gitignored.

### Why internal, and what it does not prove

Internal processes in minutes with no Google review, which is what makes it a sane
automatic target. ⚠️ **It is not the track for an upgrade proof.** An internal-track
install demands an uninstall on the device where a closed-track one updates in place, so a
build that arrives this way cannot stand in for "an existing owner's install survived the
update". Promoting to closed testing is what `publish-play-closed.yml` below is for, and
production has its own workflow after it. Both are manual: neither happens without you clicking
*Run workflow*.

## Reaching closed testing (manual, promotes)

`.github/workflows/publish-play-closed.yml` — **Run workflow**, never automatic, for the same reason
production is not: no event means "the build on internal is ready for the testers". You decide that,
so you trigger it. It promotes the build already sitting on internal to a **closed** track and, like
the production workflow, never runs `bundleRelease` and never materialises the upload key.

⚠ **A new personal developer account cannot reach production without a closed test first**: at the
time of writing, at least 12 testers opted in for 14 continuous days, then an access application in
the Console. The window is measured on testers being opted in, which is what shapes this workflow.

| Input | Default | What it does |
| --- | --- | --- |
| `track` | `alpha` | destination closed track. Free text, because Console's *Create track* makes ids nobody can enumerate here |
| `version_code` | *(blank)* | which build to promote; blank asks Play what is on internal |
| `release_version` | *(blank)* | which release's notes go up; blank takes the newest in `docs/store-listing.md` |
| `dry_run` | `false` | validate against Play, commit nothing |

The source track is hardcoded to `internal`. That is the whole point of a second workflow: it is the
one promotion made over and over during a closed test, and a dialog field whose only correct answer
is `internal` is a field that can be got wrong.

**It cannot reach the public, by construction.** Play has exactly three tracks that are not closed
testing — `production`, `beta` (open testing) and `internal` — and every other track id is a closed
one, because Console's *Create track* only makes those. So the first step is a deny list of three,
which is a complete list, and a typo in the `track` box fails the run instead of publishing something.
That guard is also what pays for the missing approval gate: this job declares no `environment`,
because a required reviewer is what stands between a build and every *owner*, and there is nobody to
protect from a build reaching people who asked to test it.

⚠ **`dry_run` defaults to false here**, where production's defaults to true. A closed promotion is
reversible from the Console — halt the release — and cannot reach anyone who did not opt in, so making
every routine promotion a two-run ritual would only teach the habit of clicking through the first one.
Turn it on for the first run of this workflow, and any time the dialog holds a value you are unsure of.

**It needs no new Play permissions.** The three `--skip_upload_*` flags for metadata, images and
screenshots are unconditional rather than an input, and that is a permission boundary rather than a
preference: descriptions, screenshots and graphics belong to the *app* and not to a track, so pushing
them from a test promotion would edit the **public** listing and would want *Manage store presence*.
Release notes are part of the track release, so they still ride up — `--skip_upload_metadata` does
not skip changelogs.

**Always `completed`, never a rollout fraction.** A closed test wants every tester on the same build.
A fraction would quietly hold some of them back on the previous one, and a tester who never receives
the build is a tester quietly not testing.

⚠ **`release_version` is the field that goes wrong in silence.** `play-metadata.py` renders the newest
`### x.y.z` under *Release notes* in `docs/store-listing.md`, which is the right answer only while the
checkout still points at the release being promoted. Promote an older build from a `main` that has
moved on and the testers are told about a version they do not have. Run the workflow from the tag, or
name the version in the dialog.

⚠ **The first release on a closed track waits on Play review.** A green run means the promotion was
accepted, not that any tester has the build. Later releases on a reviewed track appear within minutes.

⚠ **A green closed promotion says nothing about the listing.** It skips the listing by construction,
and every *dry* listing run is validated and discarded. Nor does the listing wait for production: it
belongs to the app rather than to a track, so the first `publish-play-production.yml` run with
`update_listing: true` and `dry_run: false` — to any track — is the one that changes what the Console
shows. Re-promoting a build a track already holds is accepted, so a listing-only push is that
workflow re-promoting what the track already has. And before production access is granted, a dry run
`internal → production` fails with `Precondition check failed.` rather than a 403: that is the
production track not being open to the account yet, and says nothing about the service account.

## Going to production (manual, gated, staged)

`.github/workflows/publish-play-production.yml` — **Run workflow**, never automatic. It is the only
path that can reach every owner, so it carries three independent brakes:

1. **You trigger it.** `workflow_dispatch` only; no push, tag or schedule reaches it.
2. **You approve it.** The job declares `environment: production`. ⚠️ **Add yourself as the sole
   required reviewer on that environment in repo settings** — without a reviewer configured the
   environment is just a label and gates nothing.
3. **Staged rollout.** `rollout` defaults to `0.1`, so a bad build reaches a tenth of installs and
   you widen from the Console once it looks clean.

And a fourth for the first run: **`dry_run` defaults to true**, which passes `--validate_only` —
Play validates the whole edit and discards it. Nothing publishes, nothing is sent for review. Untick
it when you mean it.

⚠ **A new app's production track targets no countries, and the API cannot fix that.** The first
committed production run of the first app built from this template uploaded every listing,
screenshot and changelog, then failed at the commit with `Release in track targeting no countries`.
Play discards a failed edit whole, so nothing went live. The fix is one-time and Console-only:
*Test and release → Production → Countries / regions → Add countries / regions*. **A dry run does
not catch it** — tick the countries before the first real run.

### It promotes; it does not build

The artifact that reaches production is the **exact one `publish-play.yml` already put on the
internal track** — same bytes, same signature, the copy you actually tested. This workflow never
runs `bundleRelease` and never materialises the upload key, because it has nothing to sign.

Rebuilding here was the original shape and it could not have worked. `versionCode` is the commit
count, so rebuilding a released tag reproduces a code Play has already accepted on internal, and
Play refuses a **new upload** of a versionCode it has seen:

```
[!] Google Api Error: Invalid request - Version code 423 has already been used.
```

Two tracks *sharing* a versionCode is not the problem — that is precisely what promotion produces,
and it is why promoting by hand in the Console never hit this. The refusal is about uploading the
same code twice. `--skip_upload_aab` / `--skip_upload_apk` are what turn the supply call into a
promotion: it moves the release already sitting on `from_track` instead of looking for a binary.

| Input | Default | What it does |
| --- | --- | --- |
| `track` | `production` | destination — also `beta` / `alpha`, though closed testing has its own workflow above |
| `from_track` | `internal` | source track holding the build being promoted |
| `rollout` | `0.1` | fraction of users; `1.0` is everyone |
| `version_code` | *(blank)* | which build to promote; blank asks Play what is on `from_track` |
| `update_listing` | `false` | push descriptions, screenshots + feature graphics as well |
| `dry_run` | `true` | validate against Play, commit nothing |

**The versionCode comes from Play, not from git.** Every other workflow derives it from the commit
count, and doing that here is wrong in a way that looks right: the count describes the *checkout*,
while a promotion is about a build uploaded earlier. They agree only while `main` still points at the
release tag, and stop agreeing the moment anything else merges — which is a normal state to be in and
produces `Track 'internal' doesn't have any releases`, an error about the version filter that reads
like an error about the track. So the workflow asks the source track what it is holding. Any ref
works, and there is no dialog field to get right.

⚠️ A **halted** release is not promotable — Play hides the Promote button and the API will not
report it. Resume it in the Console first.

### One edit, one review

The bundle, the release notes and — when `update_listing` is on — the descriptions, screenshots and
feature graphics all go up in a **single** `supply` invocation, which is a single Play edit. Play
reviews an *edit*, not a field, so that is **one** review cycle. Uploading the build now and fixing
the listing after is two edits, two reviews and two waits.

Release notes always accompany the build; they are scoped to a version and mean nothing without it.
`update_listing` governs only the descriptions, screenshots and graphics.

### Why fastlane here and Python everywhere else

`scripts/play-metadata.py` renders `docs/store-listing.md` into the tree `supply` expects, at run
time, and it is gitignored — the markdown stays the only authored copy, so a listing change is still
one reviewable diff. What `supply` is bought for is one specific transaction: replacing a listing's
screenshots means **deleting the old set before uploading the new one**, and first-time code that
dies between those steps leaves the public listing with no screenshots at all. That is worth someone
else's mileage. Ruby is confined to this workflow; the internal-track one never loads the `Gemfile`.

⚠️ **Changelog files are named by `versionCode`, not semver** — `409.txt`, not `1.9.0.txt`. supply
keys notes to the build, and a name Play cannot match is ignored in silence rather than rejected.

## Selling the tip (ADR-0007)

The code ships with the template; the products are Console work, and the order is fixed by Play.

**Once per developer account**, not per app — skip it if another app on the account already sells
something:

1. *Settings → Payments profile*: a merchant profile with a verified bank account under *How you get
   paid*. Without one, purchases work and payouts are held. Payouts are monthly, from a threshold of
   about 2 PLN.
2. The short statement name buyers see on their card statement.
3. Enrol in the **15% service fee** tier.
4. *Settings → License testing*: a list with your own personal Google account, ticked for every app.
   A licence tester's purchases use Play's test card and move no money.

**Per app:**

1. **Upload a build with the tip to internal testing first.** Console refuses to create a one-time
   product until a bundle carrying `com.android.vending.BILLING` is on a track. That build shows no
   tip, because Play has no products to return — the intended fallback.
2. *Monetise with Play → Products → One-time products*: create `tip_small`, `tip_medium`, `tip_large`
   and `tip_xlarge`, each with a purchase option (id `tip`), **multi-quantity on**, and activate them.
   An id that differs from `TIP_PRODUCT_IDS` is silently left out of the app.
3. **Prices: set Poland by hand.** Bulk pricing treats the amount you type as excluding VAT, so 5 PLN
   comes out as 6.19 in Poland. Set the base price, let Play convert the rest, then override Poland to
   exactly 5.00 / 10.00 / 20.00 / 50.00.
4. **One real purchase on the phone, as a licence tester**, from the internal-testing install: the
   Support section shows Play's prices, Play's sheet opens, and *Thank you. It means a lot.* appears.
   That line is only shown after a successful consume, so it is the proof the consume works. Play
   refuses adb-injected taps on its own Buy and Update buttons — a person taps those.
5. **Re-submit the content rating** with *digital purchases* Yes, before the build reaches production
   (`play-app-content.md` — expect Brazil at 14+).
6. After production: one real tip from an account that is **not** a licence tester, seen in *Order
   management*, refunded if you like. Until then the payout path has never actually run.

## Setting up a new repository (once)

**None of this is inherited.** GitHub's template mechanism copies files and nothing else: no
rulesets, no branch protection, no secrets, no Pages setting, no merge-strategy preference. A repo
created from the template starts with `main` wide open and every item below undone — and the build
stays green throughout, so nothing tells you. The first symptom is a release PR that never appears,
which reads like a broken template rather than an unconfigured repository.

Most of it is one command:

```bash
python3 scripts/repo-setup.py --dry-run   # what it would change, and what is already right
python3 scripts/repo-setup.py             # do it
```

It creates the `main: require CI` ruleset with the right check names, sets rebase-only merging,
enables Pages from `docs/`, and reports which secrets are still missing. It is idempotent — run it
again after any manual change and it reports what is already correct rather than fighting you. It
verifies the required check names against `ci.yml` first, because a required check that CI never
publishes leaves every PR stuck at *"Expected — waiting for status to be reported"* with no way out
but deleting the ruleset.

**The one step it cannot do is the token.** GitHub has no API for minting a personal access token,
so do this by hand, first:

1. https://github.com/settings/personal-access-tokens/new — fine-grained, *Only select
   repositories* → this one, and exactly two permissions: **Contents: read and write**,
   **Pull requests: read and write**. (`Metadata: read-only` is added automatically; leave it.)
   The token's *name* is an account-unique label of your choosing and has nothing to do with the
   secret name — reuse across repos is impossible, so name it for the repo it serves.
2. `gh secret set RELEASE_PLEASE_TOKEN --repo <owner>/<repo>` and paste the value.

Without it `release-please.yml` fails in about seven seconds with `Input required and not supplied:
token`, and no release PR is ever opened.

What the script sets, and why each one matters, is in the sections above: **rebase-only merging**
(*Merge pull requests with rebase*), **the `main` ruleset** — no bypass actors, requiring both CI
contexts, and note how it interacts with the token, since a release PR opened by `GITHUB_TOKEN`
gets zero CI jobs and this rule then blocks its merge permanently — and **Pages**, because Play
demands a *hosted* privacy-policy URL and an app with no backend has no server of its own.

The **five Play secrets** (table above) are the exception: wanted at the first upload, not before.

### The template repository does not version itself

`release-please.yml` carries `if: github.event.repository.is_template != true`. The template ships
no app, so a release PR on it bumps a `versionName` nothing consumes and then fails the notes gate —
correctly, since that gate demands Play release notes for a version that will never reach Play.

**A repo generated from the template is not itself a template**, so the condition is false there and
release-please runs in full: release PR, `CHANGELOG.md`, the `versionName` bump, the tag, the GitHub
Release and the Play upload. Nothing is disabled downstream, and there is nothing for `bootstrap.py`
to rewrite.

## Gotchas

- **`versionCode` in CI debug builds is `1`.** GitHub's checkout is shallow, so the
  commit count can't be read. That's fine for debug. For a *release* build, do a full
  checkout (`fetch-depth: 0`) or build locally so the count is real.
- **The release workflow needs a `RELEASE_PLEASE_TOKEN` secret** — a fine-grained PAT
  with *contents: write* and *pull-requests: write* on this repo. The built-in
  `GITHUB_TOKEN` can't be used: GitHub won't run workflows on a PR that `GITHUB_TOKEN`
  opened, so the release PR's CI sits at `action_required` with zero jobs, and the
  `main: require CI` ruleset (no bypass actors) then blocks the merge forever. If the
  secret is missing or expired, the workflow fails and no release PR appears at all.
- **We start at `0.1.0`** on purpose — an app is pre-1.0 until you decide it is not.
  While the major is `0`, release-please stays in pre-release: `fix:` → `0.1.1`,
  `feat:` → `0.2.0`, and even a breaking `feat!:` bumps the *minor* (`0.2.0`), it
  does **not** auto-jump to `1.0.0`.
- **Cutting `1.0.0`** is a deliberate act: put a footer in a commit body —

  ```
  feat: the last thing 1.0 was waiting on

  Release-As: 1.0.0
  ```

  — and the next release PR targets `1.0.0`.
