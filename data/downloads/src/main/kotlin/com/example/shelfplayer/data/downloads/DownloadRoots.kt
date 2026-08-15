package com.example.shelfplayer.data.downloads

import java.io.File

/**
 * PRODUCT_SPEC DL-003 / ADR-0020 — the directories this app may write downloads into.
 *
 * One method, because that is the whole of what [DownloadStorage] needs to know about volumes: a list of
 * roots with the current one first. Everything else — which volume the user picked, whether the card is
 * still in the device, what to call it on screen — is `StorageVolumes`' business and would only make this
 * class harder to test.
 *
 * The **first** entry is where new downloads go. The rest exist because the manifest holds absolute
 * locations: a book downloaded before the volume changed is still on the old root, so deletes, sweeps and
 * verification have to look at all of them.
 */
fun interface DownloadRoots {

    /** Every writable root, current first. Never empty — internal storage is always available. */
    fun roots(): List<File>
}
