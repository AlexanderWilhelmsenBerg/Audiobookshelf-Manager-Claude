package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.model.download.StorageVolumeOption
import java.io.File

/**
 * PRODUCT_SPEC DL-003 / ADR-0020 — app-specific volume roots.
 *
 * [roots] remains the single abstract method so existing tests can use a lambda. #20 adds default owner-aware
 * operations; the Android implementation overrides them, while simple test fakes retain internal semantics.
 */
fun interface DownloadRoots {

    /** Every currently reachable writable root, preferred future destination first. Never empty. */
    fun roots(): List<File>

    /** Actual volume a brand-new physical copy will own now, after any selected-card fallback. */
    fun destinationVolumeUuid(): String = StorageVolumeOption.INTERNAL_UUID

    /** Currently reachable root for a known physical owner, or null while that owner is unavailable/unknown. */
    fun rootForVolume(uuid: String?): File? = when (uuid) {
        StorageVolumeOption.INTERNAL_UUID -> roots().firstOrNull()
        else -> null
    }

    /** Currently reachable physical volume UUIDs. Internal is always represented by the empty UUID. */
    fun availableVolumeUuids(): Set<String> = setOf(StorageVolumeOption.INTERNAL_UUID)
}
