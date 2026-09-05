# Restored paused playback freshness

Device testing while validating the realtime socket lifecycle exposed a separate SYNC-002 gap.

After switching profiles, BookWave restores the incoming account's last book paused. The restored item is opened through `/play`, so the server supplies its current start position. The previous implementation nevertheless discarded the old `ResumeBaseline` on the media-item transition and established no replacement because the restored item never went through a play -> pause transition.

If another device then moved the book while BookWave's realtime socket was reconnecting, the next in-app Play saw no acknowledged baseline and skipped the server freshness check. The device run observed BookWave resume the older restored position and begin syncing it back after History showed the newer remote session.

The fix keeps the `/play` start position as staged evidence until Media3 actually transitions to the incoming item. At that transition it becomes the acknowledged baseline for the restored paused item. A local play, seek, pause, or later item transition retains the existing invalidation rules. R-61's single-file fallback stages no baseline because its player position is file-relative while the server position is book-relative.

This defect is independent of the realtime socket lifecycle change that exposed it: a socket cannot replay an event that happened before it authenticated, so the playback fallback must remain correct when realtime misses an event.
