# Vocabulary

<The words this app uses, and the ones it does not. Fill this in early — it is cheap now and
expensive later, because a name that is wrong in the model is wrong in the schema, the UI copy, every
translation of it, and the URL scheme.>

**The rule this file exists to enforce: one concept, one word, everywhere.** The entity, the DAO, the
route key, the string resource and the sentence in the UI all use it. When the domain word and the
user-facing word genuinely differ, both are written here with the difference stated.

## The domain

| Term | Means | Not |
| --- | --- | --- |
| **Item** | *the placeholder — replace it* | |
| **Tip** | a one-off payment to the developer through Google Play that **unlocks nothing** (ADR-0007). The home screen mentions it once per install; Help and feedback offers it for good | donation, support the project, premium, supporter |

## Words we deliberately avoid

<For example: "'delete' is reserved for something irreversible; anything recoverable is 'archived'."
The value here is in the distinctions, not the definitions.>

## Naming conventions in code

Some names in this codebase are deliberately generic — `AppDatabase`, `AppTheme`, `AppPreferences`,
`AppContainer`, `MainApplication`, `MainActivity`. They stay correct whatever the app is called, so
renaming the app is a package move rather than a repo-wide find-and-replace. Don't rename them to
match the product; name the *domain* types after the domain instead.
