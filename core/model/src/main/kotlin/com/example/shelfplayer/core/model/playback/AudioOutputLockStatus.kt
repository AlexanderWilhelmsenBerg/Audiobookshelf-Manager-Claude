package com.example.shelfplayer.core.model.playback

/** UI-safe summary of the transient output lock; contains no endpoint identity or routing proof. */
enum class AudioOutputLockStatus { Active, Waiting, Unverified }
