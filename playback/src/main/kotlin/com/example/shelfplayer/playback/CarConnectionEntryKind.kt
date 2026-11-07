package com.example.shelfplayer.playback

/** Classifies a bind protocol for timing only; an action never identifies or authorizes its caller. */
internal object CarConnectionEntryKind {
    fun forAction(action: String?): String = when (action) {
        "android.media.browse.MediaBrowserService" -> "legacy-browser"
        "androidx.media3.session.MediaLibraryService" -> "media-library"
        "androidx.media3.session.MediaSessionService" -> "media-session"
        else -> "other-or-missing"
    }
}
