# ADR-0019 — BookWave initially keeps ShelfPlayer's application id

- **Status:** Superseded in part by ADR-0024 on 2026-08-21. The launcher-icon, product-name, namespace, and
  Kotlin-package decisions remain accepted; only the temporary `applicationId` decision was superseded.
- **Date:** 2026-08-15
- **Requirements:** PRODUCT_SPEC 1, SET-003

## Context

The owner renamed the product from **ShelfPlayer** to **BookWave**, and supplied six launcher icons to
choose between.

On 2026-08-22 the owner supplied the wave-and-open-book brand mark as a seventh choice and made it the
fresh-install default. Existing explicit icon choices continue to win during an upgrade.

"The name of the app" is four different things on Android, and they are not renamed together:

| | What it is | Who sees it | Cost of changing |
| --- | --- | --- | --- |
| `app_name` | the launcher label | everyone | none |
| `applicationId` | the installation's identity | nobody | **a second, empty install** |
| Kotlin package | source organisation | nobody | a large mechanical diff |
| `namespace` | where `R` is generated | nobody | ties to the Kotlin package |

The name that was actually asked about is the first one. The third and fourth are invisible, and the
second is the one that can do harm.

## Decision at the 2026-08-15 rename

**`app_name` becomes BookWave. At this rename step, `applicationId`, the Kotlin package and the Gradle
namespace stay `com.example.shelfplayer`.** ADR-0024 subsequently moved the release `applicationId` to
`org.homebord.bookwave` before the first public release. Kotlin packages and Gradle namespaces still remain
`com.example.shelfplayer`, exactly as this ADR intended.

Also renamed, because they are user-visible and were the product's name rather than an identifier:

- the `User-Agent` this app sends (`ShelfPlayer/…` → `BookWave/…`), which appears in the server's logs;
- every English and Norwegian string that named the product;
- this specification's title and working title.

Deliberately **not** renamed: `Theme.ShelfPlayer`, `ShelfPlayerApplication`, `ShelfPlayerDatabase`, the
`com.example.shelfplayer` package tree, and the repository name. None of them is visible to a user, and
each is load-bearing somewhere a rename could break silently — the theme name is referenced from the
manifest, the database class name is not part of the schema but the *file* name is, and the package is
compiled into every generated Hilt component.

## Why the application id did not change during the rename

Android identifies an installed app by its `applicationId`. Changing it does not rename the installed
app; it produces a **different app**. On the owner's phone that would mean:

- the existing BookWave install stays, under the old name, with all of the data;
- the new build installs beside it, empty;
- signing in again, and re-downloading every downloaded book;
- two apps in the drawer, two media sessions offering themselves to Android Auto.

That trades away product priority 2 — *do not lose progress* — to change a string nobody reads. The
right moment to change an `applicationId` is the first release to a store, before anybody has an
install to lose, and it needs its own decision about whether to migrate the database or start clean.

`com.example.` is a placeholder that Google Play rejects, so this was explicitly a temporary decision with
a pre-release deadline. ADR-0024 met that deadline while there was still no public install to migrate.

## The icons

Multiple `activity-alias` entries, one enabled at a time, is the only mechanism Android offers for a
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

Adding the BookWave mark as a new default has one upgrade trap: Android preserves an alias explicitly
enabled by an older install, while also applying a newly enabled manifest default, which can expose two
drawer entries before Settings is ever opened. The migration therefore keeps the original component name
`IndigoAlias` as the manifest default and deliberately changes the artwork it renders to BookWave. The old
Indigo artwork moves to disabled `IndigoClassicAlias`. An implicit old default (`DEFAULT`) becomes the new
BookWave default; an explicitly enabled legacy `IndigoAlias` is moved once to `IndigoClassicAlias` by
`LauncherIconUpgradeReceiver` on `MY_PACKAGE_REPLACED`. Other explicit choices remain enabled and the
reconciliation normalizes any corrupted multi-enabled state back to one alias. An all-disabled state is
also repaired by explicitly enabling BookWave; otherwise reading “BookWave” and then applying it would
both be no-ops while the app remained absent from the drawer.

## Consequences

- The owner's pre-release install under the old debug identity could keep its data through the rename, but
  moving to ADR-0024's final application ID later required one deliberate clean install. No released user
  had an old application ID to migrate.
- A home-screen shortcut placed by hand before this change needs placing again.
- The app's icon in Android *Settings → Apps* is the default (BookWave) whatever alias is enabled, because
  `android:icon` on `<application>` is fixed at build time. Every app that offers icon choice behaves
  this way.
- `com.example.shelfplayer` remains in the source tree, in `logcat` tags, and in crash traces. It is now
  a name with no product behind it, which is a readability cost paid deliberately.
- The release application ID is now `org.homebord.bookwave`. Renaming the Kotlin package or Gradle namespace
  is neither necessary for publishing nor planned.
