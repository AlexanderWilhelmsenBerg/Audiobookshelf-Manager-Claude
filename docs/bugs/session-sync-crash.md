# Session-sync stress crash and permanent local crash reports

PR #81 serializes playback-session transitions. While it was being device-tested, a repeatable process
termination appeared under a profile/session stress sequence. The exact initiating exception was lost because
the in-app Event Log is intentionally memory-only and therefore vanished with the process.

## Reproduction that remains a merge blocker

1. Switch profiles A -> B -> A -> B -> A fairly quickly.
2. Leave A's restored book paused.
3. On another Audiobookshelf client, move/play the same A book and pause at a clearly different position.
4. Put BookWave in the background for roughly 30-60 seconds.
5. Return to BookWave and leave it untouched for at least 30 seconds.
6. Press Play once and leave it running for another 30 seconds.

The previous build terminated a few seconds after returning. This sequence stresses profile switching,
restored playback, session close/open ordering, background/foreground lifecycle and remote progress at once.
The realtime event itself is not assumed to be the cause.

## Why crash reporting belongs in this PR

#81 cannot be accepted while the process can disappear without enough evidence to identify the failing
thread/path. ADB remains useful, but a permanent product-quality diagnostic means the next failure survives
the restart and can be copied from the phone itself.

This is diagnostics, not a fix for the crash. The crash reproduction above still has to pass before #81 can
merge.

## Storage and privacy contract

BookWave now keeps exactly one **sanitized fatal-process envelope** in app-private `noBackupFilesDir`.
It survives process death and normal restarts, but not uninstall/app-data clearing, and it is never uploaded.
The user can remove it from Settings -> About -> Debug console.

For an uncaught Java exception the envelope contains only:

- build/version identifiers;
- Android SDK level and whether the fatal thread was the main thread;
- exception **class names**, never exception messages;
- bounded code-owned stack frames (prefer BookWave frames);
- at most the last 80 Event Log lines, which have already passed through the normal redactor.

It deliberately excludes exception messages, raw URLs/hosts, file/media paths, tokens, HTTP bodies, library
or book names, user names and device product names.

On Android 11+ (`ApplicationExitInfo`), the next process also checks the previous process' exit reason. ANR,
native crash, initialization failure and excessive-resource termination can therefore leave a small report
even when the Java uncaught-exception handler never ran. That fallback contains the reason/status only; it
does not pretend the new process can recover the old process' in-memory event ring.

A separate handled-exit timestamp prevents a report that the user cleared from being recreated from the same
historical Android exit record on the next launch.

## Retrieval after a crash

1. Reopen BookWave normally.
2. Open Settings -> About -> **Debug console**.
3. The normal diagnostics block now ends with `[previous crash]`.
4. Use **Copy everything** and paste the block into the issue/PR discussion.
5. Use **Clear** once the report is no longer needed.

For an uncaught exception, useful fields are `source: uncaught_exception`, `exception:`, `[stack]` and the
persisted `[events]` tail. For an OS-recorded termination, `source: process_exit` plus `reason:` is the key
evidence.

## #81 acceptance after this change

- The original A/B session-ownership checks still pass with no cross-profile/book progress contamination.
- The exact crash reproduction above no longer terminates the app.
- If it does terminate, the next launch must show a previous-crash report; capture it before further testing.
- #89's restored-paused freshness baseline must remain intact because #81 changes the same `BookChanges`
  handoff.
