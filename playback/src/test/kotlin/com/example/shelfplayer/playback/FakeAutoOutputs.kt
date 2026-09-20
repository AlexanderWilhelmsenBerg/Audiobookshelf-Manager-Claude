package com.example.shelfplayer.playback

/**
 * PD-001 retired the Android Auto audio-output browse subtree.
 *
 * Audio routing remains covered at the PlaybackService/AudioOutputRouter boundary; this marker keeps the old
 * test-file path harmless until repository cleanup can remove it with a file-delete capable client.
 */
internal object FakeAutoOutputs
