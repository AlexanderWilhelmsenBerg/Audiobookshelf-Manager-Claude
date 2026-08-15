# ADR-0019 — BookWave keeps ShelfPlayer's application id

- **Status:** Accepted
- **Date:** 2026-08-15
- **Requirements:** PRODUCT_SPEC 1, SET-003

## Context

The owner renamed the product from **ShelfPlayer** to **BookWave**, and supplied six launcher icons to
choose between.

"The name of the app" is four different things on Android, and they are not renamed together:

| | What it is | Who sees it | Cost of changing |
| --- | --- | --- | --- |
| `app_name` | the launcher label | everyone | none |
| `applicationId` | the installation's identity | nobody | **a second, empty install** |
| Kotlin package | source organisation | nobody | a large mechanical diff |
| `namespace` | where `R` is generated | nobody | ties to the Kotlin package |

The name that was actually asked about is the first one. The third and fourth are invisible, and the
second is the one that can do harm.

## Decision

**`app_name` becomes BookWave. `applicationId`, the Kotlin package and the Gradle namespace stay
`com.example.shelfplayer`.**

Also renamed, because they are user-visible and were the product's name rather than an identifier:

- the `User-Agent` this app sends (`ShelfPlayer/…` → `BookWave/…`), which appears in the server's logs;
- every English and Norwegian string that named the product;
- this specification's title and working title.

Deliberately **not** renamed: `Theme.ShelfPlayer`, `ShelfPlayerApplication`, `ShelfPlayerDatabase`, the
`com.example.shelfplayer` package tree, and the repository name. None of them is visible to a user, and
each is load-bearing somewhere a rename could break silently — the theme name is referenced from the
manifest, the database class name is not part of the schema but the *file* name is, and the package is
compiled into every generated Hilt component.

## Why the application id must not change

Android identifies an installed app by its `applicationId`. Changing it does not rename the installed
app; it produces a **different app**. On the owner's phone that would mean:

- the existing BookWave install stays, under the old name, with all of the data;
- the new build installs beside it, empty;
- signing in again, and re-downloading every downloaded book;
- two apps in the drawer, two media sessions offering themselves to Android Auto.

That trades away product priority 2 — *do not lose progress* — to change a string nobody reads. The
right moment to change an `applicationId` is the first release to a store, before anybody has an
install to lose, and it needs its own decision about whether to migrate the database or start clean.

`com.example.` is a placeholder that Google Play will reject, so this decision has a deadline; it does
not have one today.

## The icons

Six `activity-alias` entries, one enabled at a time, is the only mechanism Android offers for a
user-chosen launcher icon — an activity's own `android:icon` is fixed at build time.

Three consequences follow, and each is handled rather than discovered:

1. **`MainActivity` loses its `LAUNCHER` filter**, because the launcher entry has to be the switchable
   thing. Its component name is therefore no longer what the home screen points at, so a shortcut an
   existing install placed by hand stops resolving. The setting's hint says so.
2. **The order of the two writes matters.** Enable the new alias, *then* disable the others. The
   opposite order leaves a window with no enabled `LAUNCHER` component, and a launcher that samples the
   package during that window removes the app from the drawer and does not put it back.
3. **`DONT_KILL_APP` is required**, not optional. Without it, changing a component's enabled state
   restarts the process, which for this app means killing whatever is playing — product priority 1.
   With it, the process is untouched and the launcher picks the change up from the system's broadcast.

The choice is **not** stored in DataStore. Android already persists component enabled state across
restarts and updates, and that state is what the launcher reads; a stored copy could only agree with it
or be wrong about it. `LauncherIcons.current()` asks the package manager.

## Consequences

- The owner's existing install keeps its data and gains the new name on next update.
- A home-screen shortcut placed by hand before this change needs placing again.
- The app's icon in Android *Settings → Apps* is the default (Indigo) whatever alias is enabled, because
  `android:icon` on `<application>` is fixed at build time. Every app that offers icon choice behaves
  this way.
- `com.example.shelfplayer` remains in the source tree, in `logcat` tags, and in crash traces. It is now
  a name with no product behind it, which is a readability cost paid deliberately.
- Renaming the package and the application id is still available, as one deliberate change with a data
  migration, whenever publishing makes it necessary.
