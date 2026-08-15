package com.example.shelfplayer.core.model.download

/**
 * PRODUCT_SPEC DL-003 / ADR-0018 decision 4 — a place downloads can be written to.
 *
 * The owner's words: *"the files should live in the app folder, but it should also be possible to select a
 * folder, and sd should be possible to select."* This is the **volume** half — internal storage, or an SD
 * card. ADR-0020 records why that half arrives on its own and what it deliberately leaves out.
 *
 * @property uuid the volume's identifier as the platform reports it. Empty is the device's own internal
 *   storage, which is both the default and the only volume guaranteed to exist.
 * @property label what to call it on screen. The platform's description where there is one, because a
 *   removable card is named by whoever formatted it and the app has nothing better to offer.
 * @property freeBytes what is left on it, which is usually the whole reason somebody is looking at this
 *   list.
 * @property isRemovable whether it can be taken out of the device. The one fact that changes what
 *   choosing it *means*, so the screen says it rather than leaving it to be discovered.
 */
data class StorageVolumeOption(val uuid: String, val label: String, val freeBytes: Long, val isRemovable: Boolean) {
    val isInternal: Boolean get() = uuid.isEmpty()

    companion object {
        /** The volume every device has, and the one a missing or unreadable choice falls back to. */
        const val INTERNAL_UUID: String = ""
    }
}
