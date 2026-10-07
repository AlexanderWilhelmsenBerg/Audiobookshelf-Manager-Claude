# BookWave Complications feed v1

Both apps publish protected complication **id0** with ComplicationPublisher permission. A future
consumer needs the same retained developer signing key; this is not a public feed for arbitrary faces.
CI generates temporary per-job keys, so use the retained local key for cross-app testing and upgrades.
No watch face or Data Field consumer is built in this change.

The value is one atomic string, with unit UNIT_INVALID, constant shortLabel BookWave and **no numeric
ranges**. Numeric ranges compile but Garmin rejects them at runtime for this string-valued resource.

`1|source|observedAtMs|eventAtMs|state|positionMs|durationMs|privacy|title|chapter`

Source is PHONE or GARMIN. Event time -1 means unknown; duration0 means unknown. State is playing,
paused or unknown; privacy authorized or redacted. Title/chapter are bounded to80 characters before
escaping `%`, `|`, newline and carriage return as `%25`, `%7C`, `%0A`, `%0D`. Other Unicode is retained.
No book/profile/server/credential identifiers are published. Unknown version/invalid field values must
render unavailable. Split before decoding escapes; absence/uninstalled publishers also render unavailable.

Companion publishes validated persisted PHONE snapshots; their updatedAt is observation time, not an
original listening event. Restored/closed playing snapshots become unknown. Provider publishes actual
GARMIN progress/events; native chunk transitions are not known ABS chapter titles. Closing publishes
unknown state and preserves the actual event time. Neither projection writes server playback progress.
A later consumer must label cached/stale state using observation age; stored playing never proves
continued playback or current connection. Source ambiguity never resolves by max(position).

Normal updates persist before publishing. Privacy clears overwrite all title/chapter/progress/event/state
values, trying runtime clearing even when storage fails. A disconnected watch cannot receive a phone
clear until reconnect. Protected access and cross-app delivery/battery behavior still need GF01–07
physical acceptance; simulator publish/clear success does not prove subscriber behavior on hardware.
