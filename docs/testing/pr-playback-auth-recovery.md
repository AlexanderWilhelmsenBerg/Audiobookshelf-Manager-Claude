# Playback authentication recovery device test

This change is intentionally separate from the resume/task-removal work.

The device log that prompted it showed healthy `200` session syncs followed by a sustained switch to `401`
for session sync, playback-session open, and media-file GET requests. Android Auto continued browsing the
cached tree, but a selected book could not open a server session and the media source ultimately failed.

## Device checks

1. Start a server-backed audiobook and let it play normally.
2. Confirm an ordinary notification sound may interrupt audio briefly but playback resumes after transient
   audio focus returns. If it does not, capture the `Playback was asked to change` line and its `reason`.
3. Reproduce an expired access token while retaining a valid refresh token.
4. While a book is already playing, let the next media range request receive `401`.
5. Confirm BookWave performs one session renewal and playback continues without three retries using the old
   token.
6. Close the UI, connect Android Auto, browse Continue/Recent, and select a book with the same expired-access
   / valid-refresh state.
7. Confirm the first failed playback-session open renews once, retries once, and Android Auto plays rather
   than showing Source error.
8. Confirm a truly refused refresh does not loop login/refresh requests and surfaces a stopped/auth-required
   state instead.
