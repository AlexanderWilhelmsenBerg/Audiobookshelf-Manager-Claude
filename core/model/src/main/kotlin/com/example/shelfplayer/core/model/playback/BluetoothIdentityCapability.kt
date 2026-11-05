package com.example.shelfplayer.core.model.playback

/** Privacy-safe discovery capability, never a claim that audio is reaching an endpoint. */
enum class BluetoothIdentityCapability {
    Verified,
    PermissionMissing,
    Redacted,
    Empty,
    Invalid,
    Duplicate,
    ReadFailure,
    Unsupported,
}
