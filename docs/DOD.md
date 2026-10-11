# Definition of done — what is still open

The **live checklist**. Keep it short: when an item closes, tick it, write the *result* into whatever
long-form record you keep, and delete the detail from here. A session should be able to pick up the
work by reading this file alone.

## The standing schema gate — never ticked, checked at every bump

**An update must migrate an existing install without losing anything** (ADR-0001). This item does not
close. Whenever `APP_SCHEMA_VERSION` changes, all five hold before the release goes out:

1. `MIGRATION_x_y` written **and registered in** `APP_MIGRATIONS` for every step. A migration Room
   never runs is not a migration, and nothing about the build complains.
2. The exported `app/schemas/*/N.json` committed — every later migration is transcribed from it,
   never from the entity classes.
3. `SchemaGateTest` extended, so the **launch gate** is proven to let the upgrade through. Every
   migration test opens the database directly and walks past that gate; this assertion is the only
   thing standing in front of it.
4. A migration test proving the **rows survive** — read values back, do not merely assert nothing
   threw. `runMigrationsAndValidate` compares *shapes* and is blind to a table emptied by a cascading
   `DROP`.
5. **An actual upgrade watched on a phone**: install the previous build, put real data in it, install
   the new one over the top, confirm the app opens and the data is there.
   `./gradlew assembleDebug -PreleaseShapedDebug` is how to do this without touching a Play install —
   it is minified *and* `BuildConfig.DEBUG == false`, so migrations are registered rather than the
   destructive fallback. ⚠️ **Both halves are load-bearing**: a plain debug build takes the fallback
   and proves the opposite of what you wanted.

`scripts/schema-gate.py` enforces 1–3 mechanically in CI. Items 4 and 5 are yours.

## Before the first upload

- [ ] **Delete what the app does not need**, while deleting is free — [`optional-modules.md`](optional-modules.md)
      lists each module's files and the five places around the code a strip reaches. Room, photos,
      backup and reminders are each a data-loss or Data safety story you will otherwise defend forever.
- [ ] **Decide `minSdk` deliberately, before launch.** The direction is asymmetric: raising it later
      strands existing installs on the last build that fitted them, lowering it later is free. If the
      only phone in the loop sits at the top of the range, every `SDK_INT` branch below it ships
      unvalidated — the first app built from this template raised 26 to 33 for exactly that reason. Move CI's floor legs with it,
      and write the reason beside `minSdk` in `app/build.gradle.kts`, where the next person to touch it reads it.
- [ ] **Replace the placeholder mark.** `art/mark.py`, then `python3 art/make-launcher-icon.py` and
      `make-feature-graphic.py`. Commit the concept image and record its provenance in
      `art/README.md` — where the art came from is the thing most likely to block a first upload, and
      it is discovered late.
- [ ] **Replace the placeholder domain.** `data/ItemEntity.kt` and `ui/items/` — or delete them
      (above).
- [ ] **Rewrite `scripts/edge-to-edge.py`'s SCENES** against your screens. Until then the nightly
      walks screens that do not exist, and `screenshots.py` cannot shoot a listing.
- [ ] **Make the artifact allowlists yours.** `aab-permissions.py`'s EXPECTED, FORBIDDEN and
      EXPECTED_ORIENTATION describe the template. Run all four `aab-*.py` scripts against a local
      `bundleRelease` **before the first tag** — the first app built from it found its stale table in the publish
      workflow, after the tag, with the version already spent.
- [ ] **Choose the palette.** Four seeds in `scripts/gen_scheme.py`, regenerate `theme/Color.kt`,
      read the contrast report it prints on stderr.
- [ ] **Make the support address work both ways.** `bootstrap.py` compiled in
      `<repo>.support@maksforge.com` (or `--support`) and wrote it into the site, the privacy policy
      and the Play docs. Mail to it arrives through the domain's catch-all; replying from it needs the
      receiving Gmail's *Send mail as* entry, confirmed. Send *Report a problem* from a non-Gmail
      account and check it lands outside Spam. One address everywhere — the listing's contact field
      and IARC's rating contact too; `SupportHandoff.kt` says why it lives on our own domain.
- [ ] **Write the listing.** `docs/store-listing.md` — every heading in it is parsed by a script.
- [ ] **Capture the screenshots** with `scripts/screenshots.py` from a release-shaped build, in every
      listing language, into `art/play-screenshots/`. Play wants at least two before the first review.
- [ ] **Put the app on maksforge.com.** Its icon goes at `assets/icons/<repo>.webp` in
      maksforge/maksforge.github.io, which the app's own site loads, and the app goes on that home
      page. Replace `description` in `docs/_config.yml` too: it is the tagline under the app's name.
- [ ] **Write the privacy policy** and confirm GitHub Pages is serving `docs/`. Play requires a
      *hosted* URL, and an offline app has no server of its own.
- [ ] **Answer the Console's App content pages and write [`play-app-content.md`](play-app-content.md)
      in the same sitting** — the reasons are in front of you then and gone a week later. It moves in
      the same commit as the privacy policy, every time.
- [ ] **`LICENSE` and `README.md`.** Bootstrap wrote a source-available notice and a *Contributing*
      that refuses pull requests, or with `--licence reserved` a notice that grants nothing and a
      placeholder. Keep both or replace both; they must agree.
- [ ] **Create the upload keystore, outside the repo**, and put its four values in
      `local.properties`. Back it up somewhere that is not this machine: losing it means never being
      able to update the app on Play again. Fill in *The upload key* table in `RELEASING.md` the same
      day — the fingerprint is what Play asks you to compare.
- [ ] **Set up the GitHub repository** — `python3 scripts/repo-setup.py` does the ruleset, the
      merge setting and Pages; the release-please PAT is the one step it cannot do, and without it
      no release PR is ever opened. *Setting up a new repository* in [`RELEASING.md`](RELEASING.md).
      **None of this is inherited from the template** — GitHub copies files, never settings — and
      the build stays green while it is all still undone. **Write the PAT's expiry date into this
      item** the day you create it: when it lapses the release PR simply stops appearing.
- [ ] **Set the five Play secrets and create the service account** (`docs/RELEASING.md`) — the
      service account is the one step CI cannot do for itself. No account permissions; two app
      permissions, on this app only; no expiry date.
- [ ] **Plan the closed test.** A new personal developer account reaches production only after a
      closed test — at the time of writing 12 testers opted in for 14 continuous days, then an access
      application. Recruit the testers before the build is ready, not after; `publish-play-closed.yml`
      moves each build to them. A tester-swap community, where developers test each other's closed
      tests, filled the apps built from this template within days.
- [ ] **Decide the `applicationId` deliberately.** It is fixed the moment the Play entry is created —
      not renameable, not transferable without losing every install and review.

## The tip — open until a real purchase is consumed

ADR-0007. The code is in; these are the parts only Play and a phone can prove. The steps are
*Selling the tip* in [`RELEASING.md`](RELEASING.md).

- [ ] **Keep it or delete it, before the first upload.** Deleting is in
      [`optional-modules.md`](optional-modules.md); keeping it costs Brazil's rating (14+).
- [ ] **A build with billing on internal testing**, then the four products created and active.
- [ ] **One purchase from a licence tester** on the internal install, ending in the thank-you line,
      and the *same* tip bought again — the second purchase is the proof that the first was consumed.
- [ ] **The debug build shows no tip section and no card.** Play has never heard of the `.debug`
      package, so absent is the correct reading, not a failure.
- [ ] **Content rating re-submitted** with *digital purchases* Yes, before production.

## Before the next upload

Re-declarations a change owes Play, written down the day the change merges and ticked when the
Console matches. A new permission, foreground service or data type is a Console change that waits in
*Publishing overview* for *Send for review* with the upload that needs it. Empty most of the time.

## After the closed test

- [ ] **The production-access application is in, with its answers recorded verbatim** in
      [`play-app-content.md`](play-app-content.md). Until it is granted the production track does not
      exist. The app this template was extracted from waited one day; that is one datapoint, not a
      promise. **If it comes back rejected, read the reason before changing any artifact.** Changing
      the build first leaves you arguing the reason against a different build.
- [ ] **Add the production countries in the Console** — *Test and release → Production → Countries /
      regions*. A new app's production track targets none, the API cannot add them, and a dry run
      does not catch it ([`RELEASING.md`](RELEASING.md)).
- [ ] **Decide the first production number.** release-please never jumps to `1.0.0` by itself (with
      the major at 0 even `feat!:` bumps the minor), so it takes a `Release-As: 1.0.0` footer
      ([`RELEASING.md`](RELEASING.md)), or a deliberate decision to stay on 0.x. In order: production
      access granted, *then* that version's release notes in every locale on an ordinary branch (never
      on release-please's own, which it force-pushes), *then* the AAB and the listing go up together.

## Standing checks that never close

- [ ] **Every release: run the artifact checks on the built AAB**, not on the source.
      `aab-permissions.py` is the one that finds what a *dependency* merged into your manifest —
      a permission you never declared, or a `uses-feature` that quietly filters the app off devices.
- [ ] **Every release: read the release notes gate's output** rather than trusting it passed.
- [ ] **Every Play declaration: confirm Data safety still holds** against
      [`play-app-content.md`](play-app-content.md), and write the date of the re-read here.
- [ ] **Every dependency bump: re-run `licensee`.** The build fails on an unallowed licence, which is
      the point — but the fix is two things, the allowlist *and* the bundled text.
- [ ] **Test the release-shaped build on a device.** A missing R8 keep rule does not crash; it makes
      a feature silently stop working.
