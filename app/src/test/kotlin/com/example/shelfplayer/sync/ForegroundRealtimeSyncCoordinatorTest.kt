package com.example.shelfplayer.sync

import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForegroundRealtimeSyncCoordinatorTest {
    @Test
    fun `foreground owns exactly one collector independent of Home`() = runTest {
        val profiles = FakeProfiles(profile("a"))
        val events = mutableListOf<String>()
        val coordinator = coordinator(profiles, events)

        coordinator.onForegrounded()
        coordinator.onForegrounded()
        runCurrent()

        assertEquals(1, events.count { it == "start:a" })
        assertEquals(1, events.count { it == "reconcile:a" })
    }

    @Test
    fun `profile switch cancels A before B becomes authoritative`() = runTest {
        val profiles = FakeProfiles(profile("a"))
        val events = mutableListOf<String>()
        val coordinator = coordinator(profiles, events)

        coordinator.onForegrounded()
        runCurrent()
        profiles.activate(profile("b"))
        runCurrent()

        assertEquals(1, events.count { it == "start:a" })
        assertEquals(1, events.count { it == "stop:a" })
        assertEquals(1, events.count { it == "start:b" })
        assertEquals(1, events.count { it == "reconcile:b" })
        assertTrue(events.indexOf("stop:a") < events.indexOf("start:b"))
    }

    @Test
    fun `background closes collector and foreground return reconciles missed progress`() = runTest {
        val profiles = FakeProfiles(profile("a"))
        val events = mutableListOf<String>()
        val coordinator = coordinator(profiles, events)

        coordinator.onForegrounded()
        runCurrent()
        coordinator.onBackgrounded()
        runCurrent()
        coordinator.onForegrounded()
        runCurrent()

        assertEquals(2, events.count { it == "start:a" })
        assertEquals(1, events.count { it == "stop:a" })
        assertEquals(2, events.count { it == "reconcile:a" })
        assertTrue(events.indexOf("stop:a") < events.lastIndexOf("start:a"))
    }

    @Test
    fun `rapid background foreground transition fully stops old collector before replacement`() = runTest {
        val profiles = FakeProfiles(profile("a"))
        val events = mutableListOf<String>()
        val coordinator = coordinator(profiles, events)

        coordinator.onForegrounded()
        runCurrent()
        coordinator.onBackgrounded()
        coordinator.onForegrounded()
        runCurrent()

        assertEquals(2, events.count { it == "start:a" })
        assertEquals(1, events.count { it == "stop:a" })
        assertEquals(2, events.count { it == "reconcile:a" })
        assertTrue(events.indexOf("stop:a") < events.lastIndexOf("start:a"))
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        profiles: ProfileRepository,
        events: MutableList<String>,
    ) = ForegroundRealtimeSyncCoordinator(
        profiles = profiles,
        applicationScope = backgroundScope,
        observeRealtime = { profileId ->
            events += "start:${profileId.value}"
            try {
                awaitCancellation()
            } finally {
                events += "stop:${profileId.value}"
            }
        },
        reconcile = { profileId -> events += "reconcile:${profileId.value}" },
        logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        },
    )

    private class FakeProfiles(initial: Profile?) : ProfileRepository {
        private val active = MutableStateFlow(initial)

        fun activate(profile: Profile?) {
            active.value = profile
        }

        override fun observeProfiles(): Flow<List<Profile>> = active.map { listOfNotNull(it) }

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = active

        override suspend fun activeProfileId(): ProfileId? = active.value?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            val current = active.value ?: return AppResult.Success(Unit)
            active.value = current.copy(id = profileId)
            return AppResult.Success(Unit)
        }
    }

    private companion object {
        fun profile(id: String) = Profile(
            id = ProfileId(id),
            serverId = ServerId("server"),
            username = "fixture",
            displayName = "Fixture",
            role = ProfileRole.Listener,
            requiresReauthentication = false,
            lastUsedAt = Instant.EPOCH,
            isFixture = true,
        )
    }
}
