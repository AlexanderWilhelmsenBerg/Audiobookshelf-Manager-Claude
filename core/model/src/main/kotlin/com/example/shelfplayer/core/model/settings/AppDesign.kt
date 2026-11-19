package com.example.shelfplayer.core.model.settings

/** SET-002 — the device's visual design, independent of its theme and accent. */
enum class AppDesign(val key: String) {
    Classic("classic"),
    Minimal("minimal"),
    Editorial("editorial"),
    ;

    companion object {
        fun ofKey(key: String): AppDesign = entries.firstOrNull { it.key == key } ?: Classic
    }
}
