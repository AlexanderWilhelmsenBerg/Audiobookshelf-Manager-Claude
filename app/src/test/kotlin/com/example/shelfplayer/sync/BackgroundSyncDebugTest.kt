package com.example.shelfplayer.sync

import com.example.shelfplayer.core.model.ProfileId
import org.junit.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackgroundSyncDebugTest {

    @Test
    fun `debug work cannot replace the production periodic work`() {
        val profileId = ProfileId("profile-test")
        val debugName = debugBackgroundSyncNameFor(profileId)

        assertNotEquals(LibrarySyncWorker.nameFor(profileId), debugName)
        assertTrue(debugName.startsWith("debug-library-sync-"))
    }
}
