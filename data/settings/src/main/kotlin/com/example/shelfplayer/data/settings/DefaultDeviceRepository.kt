package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.repository.DeviceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC ROUTE-002 — the known-device list, in the preferences store.
 *
 * Proto DataStore rather than Room, because SET-001 says settings are stored there and this *is* a setting:
 * a per-device answer to "what should happen when this connects". The list is small — the number of
 * headsets a person owns — so a document rewritten on each change costs nothing.
 *
 * ### What is deliberately not logged
 *
 * Never the device's name. PRODUCT_SPEC 14.5 keeps private data out of the log, and "Ada's AirPods" names a
 * person as surely as a username does. The kind and the policy are logged, because those describe the app's
 * behaviour and are what a diagnostic reader needs.
 */
@Singleton
class DefaultDeviceRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : DeviceRepository {

    override fun observeDevices(): Flow<List<KnownDevice>> = settings.knownDevices

    override suspend fun remember(device: KnownDevice): AppResult<Unit> = withContext(ioDispatcher) {
        resultOf { settings.rememberDevice(device) }
    }

    override suspend fun setPolicy(deviceId: String, policy: DevicePolicy): AppResult<Unit> =
        withContext(ioDispatcher) {
            resultOf {
                settings.setDevicePolicy(deviceId, policy)
                logger.info(
                    LogCategory.Playback,
                    "A device's connection policy was changed",
                    // The policy, never the device's name (PRODUCT_SPEC 14.5).
                    LogField.Public("policy", policy.name),
                )
            }
        }

    override suspend fun forget(deviceId: String): AppResult<Unit> = withContext(ioDispatcher) {
        resultOf { settings.forgetDevice(deviceId) }
    }

    /**
     * A device nobody has seen answers with the default, not with nothing.
     *
     * The connection callback runs before [remember] has finished writing on a first-ever connection, so
     * this has to be correct for an id that is not in the store yet — and `Arm only` is exactly the right
     * answer for a device the user has never had the chance to configure.
     */
    override suspend fun policyFor(deviceId: String): DevicePolicy = withContext(ioDispatcher) {
        settings.knownDevices.first().firstOrNull { it.id == deviceId }?.policy ?: DevicePolicy.Default
    }
}
