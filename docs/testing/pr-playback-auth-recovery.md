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

   **Settings → About → Authentication recovery (debug) → Expire the access token.** That is exactly this
   state: the stored and cached access token become a string the server will refuse, and the refresh token
   is left alone. The row exists in debug builds only.

   The button reports what it did. *"No profile with a refresh token"* means the check would prove nothing
   — the profile would be sent to a sign-in prompt rather than through a recovery — so sign in again first.

   Two things it cannot do, for which an intercepting proxy (mitmproxy, Charles) is still the tool:

   - **fail one request and pass the next** — the token stays bad until a renewal replaces it, so step 9's
     concurrent case is reachable but not finely controllable;
   - **step 10** — an external HTTP media URL returning `401` without an Audiobookshelf credential in play.
4. While a book is already playing, let the next media range request receive `401`.
5. Confirm BookWave performs one session renewal and playback continues without three retries using the old
   token.
6. Close the UI, connect Android Auto, browse Continue/Recent, and select a book with the same expired-access
   / valid-refresh state.
7. Confirm the first failed playback-session open renews once, retries once, and Android Auto plays rather
   than showing Source error.
8. Confirm a truly refused refresh does not loop login/refresh requests and surfaces a stopped/auth-required
   state instead.
9. Force playback-session open and an active range request to receive `401` concurrently. Confirm the server
   sees one refresh-token exchange, both callers retry with the replacement access token, and neither sends
   the rotated refresh token a second time.
10. Repeat with an external HTTP media URL. Confirm it receives no Audiobookshelf `Authorization` header and
    its `401` causes no Audiobookshelf refresh request.
11. Start renewal and sign out before it completes. Confirm sign-out wins finally: the local access and
    refresh credentials remain cleared and playback does not silently authenticate again.
