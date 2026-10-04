# BW-SLEEP-01 device acceptance — scheduled sleep and active-timer projections

**Classification:** Current hardware acceptance runbook; source tests do not accept rendered/system behavior.
GitHub #124 is historical Forgejo #33; #189 owns the timer-system projection follow-up.
Use [the roadmap](roadmap.md) and its verification register for current state.

This is the remaining Android/system acceptance for Forgejo issue #33. JVM tests prove the portable
window policy, timer-owner integration and Compose/system-button models; they do **not** prove that a
particular Android System UI, Bluetooth stack or Media3 host renders or dispatches those surfaces.

Use a debug APK from the accepted PR head. Record Android/API level, device model, local timezone and the
configured default sleep-timer length with the evidence.

## Setup

1. Set the ordinary default sleep timer to a short observable value such as 10 minutes.
2. Enable the automatic schedule and choose a start boundary a few minutes ahead and an end boundary after it.
3. Start an audiobook and keep the media notification enabled.
4. Repeat the relevant playback checks once through the phone speaker and once through a Bluetooth headset.

## Playback and schedule

- **Background/screen off crossing start:** begin playback before the start boundary, close the activity,
  turn the screen off, and leave playback running. At the start boundary, unlock only to inspect state. The
  ordinary default timer must have armed without an alarm/worker wakeup path.
- **Overnight/midnight:** configure an overnight window such as 22:00–06:00. Verify playback on both sides of
  midnight belongs to one eligible occurrence.
- **Near the end:** with a 22:00–07:00 window and a 15-minute default, begin playback at 06:46. The ordinary
  automatic timer must arm, then be cancelled at 07:00 while playback continues. It must not expire early,
  pause playback, or create manual-cancellation suppression.
- **Manual precedence at the end:** replace an automatic timer with a manual timer shortly before 07:00. The
  manual timer must survive the schedule end unchanged.
- **Manual cancel:** while an automatically-created timer is active, cancel it through the ordinary timer UI
  and keep playback running. It must not immediately rearm in the same occurrence.
- **Service/process recreation while suppressed:** after the previous cancellation, recreate the activity and
  playback service/process using the normal test procedure. Resume/continue within the same occurrence.
  Suppression must survive; restoration alone must not rearm.
- **Next occurrence:** on the next distinct nightly occurrence, start or continue playback in the window. The
  ordinary automatic timer must be eligible again.
- **Natural expiry and explicit replay:** let an automatically-created timer expire and pause playback. Press
  Play explicitly while still inside the same window. A new ordinary default timer may arm. Separately verify
  that lifecycle/service restoration without an explicit Play does not manufacture that rearm.
- **Bluetooth/headset:** repeat start-inside, boundary-crossing and manual-cancel cases while listening through
  a Bluetooth/headset route. Existing routing and audio-focus behavior must remain unchanged.

## Full player

- With no timer active, the ordinary Sleep action is present and opens the existing sleep-timer UI.
- Start a manual timer, then an automatically scheduled timer. In both cases the Sleep position must show the
  remaining countdown instead of a second/duplicate timer control.
- Tap the displayed countdown. It must open the same existing sleep-timer UI/action as the idle Sleep control.
- Extend the timer and verify the visible remaining value updates from the playback owner.
- Cancel a timer and separately let one expire. The ordinary Sleep presentation must return.
- Recreate the activity while the timer is still active. The remaining timer must be projected immediately;
  the UI must not restart or invent a countdown.
- While a timer runs, the full player and the mini-player keep showing the book title; the countdown appears
  only in the Sleep action (PLAY-008).
- Run TalkBack over the active control. It must announce the sleep timer with useful remaining-time semantics
  and remain a normal actionable control.

## Media notification

- With a timer inactive, confirm the current notification control policy is unchanged.
- Start a manual timer and an automatically scheduled timer. Each must expose the same active-timer state in
  the expanded notification and in the compact/collapsed notification.
- On the compact/background card, verify the remaining timer is readable as text: the clock (for example
  `12:34`) replaces the title, not merely a timer icon. Verify the original book title returns exactly when the
  timer is cancelled or expires. The countdown is shared with Bluetooth displays but never shown in Android
  Auto (R-111, see below).
- Confirm the active timer occupies the compact forward custom-action slot while the required back-side
  skip/car control and central transport remain available; unrelated action ordering must return when idle.
- Extend the timer and confirm the notification's remaining state updates without opening the activity.
- Cancel and expire timers independently; both expanded and compact notification layouts must remove the
  active-timer projection together.
- Close/destroy the activity while playback continues. The notification must keep projecting the correct
  timer state because the playback service, not Compose, owns it.
- Keep the screen off for part of a timer and re-open the notification. State must agree with the full player.

## Android Auto

PD-002 (amended 2026-10-03): the sleep timer never shows in Android Auto. Use a head unit or the Desktop Head
Unit.

- Start a timer on the phone, then connect Android Auto. The car's now-playing shows the book title and no
  sleep button in any slot or overflow. The phone notification also drops the countdown and the button while the
  car is connected (one shared session). The full player still shows the countdown in the Sleep action.
- Disconnect while the timer is still running. The countdown title and the sleep button return within a second.
- Start a timer while connected. Nothing appears in the car and playback is not interrupted.
- Let a timer expire while connected. Playback pauses as usual; record what the car shows.
- Plain Bluetooth car without Android Auto: the countdown appears as the title. This is the documented
  limitation.

## Shake grace and sensitivity

Before the motion tests, execute the [paused/empty timer and recovery matrix](testing/2026-10-03-series-history-sleep.md).
New manual timers require actively playing audio; pause/focus loss/buffering freeze existing countdowns.

- Enable shake-to-restart, set grace to 10 seconds and sensitivity to Normal. Let a short timer expire fully;
  playback must pause. Shake within ten seconds: playback must resume and the same timer mode must start again.
- Repeat but wait beyond the ten-second grace before shaking. Playback must remain paused and no new timer may
  appear.
- Set grace to Off. Let the timer expire and shake immediately afterwards; it must stay paused.
- While a timer is active, compare Low, Normal, High, Extra high and Ultra high with progressively gentler
  movement. Normal retains previous behavior; the new levels target gentle motion. At Ultra high, test
  stationary rest, placement, bedding-transmitted breathing, vibration and incidental movement separately.
  Record real detection and false restarts; do not assume a threshold proves bedside effectiveness.
- Disable shake-to-restart while a timer is active and after an expiry grace window has begun. Motion sensing
  must stop in both cases.
- For an automatically scheduled timer close to the schedule end, verify a grace shake after the civil window
  has ended does not revive the automatic timer.

## Wall-clock and timezone spot checks

Automated tests pin the exact DST rules. On a device/emulator, also change the timezone or wall clock while
playback is active and verify eligibility is recomputed without restarting the app. Do not alter production
time merely to simulate a DST transition on a personal device; an emulator is preferable for that check.

A pass here is Android/device evidence. A green `verifyDebug` alone is not a pass for notification rendering,
Bluetooth behavior, screen-off scheduling or process/service restoration.
