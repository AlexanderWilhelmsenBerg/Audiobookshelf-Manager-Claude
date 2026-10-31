package com.example.shelfplayer.playback

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent

/** Raw descriptor is transient input to capture-local aliasing, never a rendered/logged value. */
internal data class MediaInputDevice(
    val id: Int,
    val sources: Int,
    val keyboardType: Int,
    val virtual: Boolean,
    val external: Boolean?,
    val descriptor: String?,
)

internal enum class MediaInputChange { Added, Removed, Changed }

internal interface MediaInputSource {
    fun devices(): List<MediaInputDevice>
    fun device(event: KeyEvent): MediaInputDevice?

    /** A source must roll back partial registration if it throws before returning cleanup. */
    fun subscribe(changed: (MediaInputChange) -> Unit): () -> Unit
}

/** Public input APIs only; no Bluetooth permissions, connections or audio-policy operations. */
internal class PlatformMediaInputSource(private val context: Context) : MediaInputSource {
    override fun devices(): List<MediaInputDevice> {
        val manager = manager()
        return manager.inputDeviceIds.asSequence().mapNotNull { id ->
            manager.getInputDevice(id)?.let(::facts)
        }.toList()
    }

    override fun device(event: KeyEvent): MediaInputDevice? = event.device?.let(::facts)

    override fun subscribe(changed: (MediaInputChange) -> Unit): () -> Unit {
        val manager = manager()
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = changed(MediaInputChange.Added)
            override fun onInputDeviceRemoved(deviceId: Int) = changed(MediaInputChange.Removed)
            override fun onInputDeviceChanged(deviceId: Int) = changed(MediaInputChange.Changed)
        }
        try {
            manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        } catch (expected: RuntimeException) {
            runCatching { manager.unregisterInputDeviceListener(listener) }
            throw expected
        }
        return { manager.unregisterInputDeviceListener(listener) }
    }

    private fun manager(): InputManager = checkNotNull(context.getSystemService(InputManager::class.java))

    private fun facts(device: InputDevice): MediaInputDevice = MediaInputDevice(
        id = device.id,
        sources = device.sources,
        keyboardType = device.keyboardType,
        virtual = device.isVirtual,
        external = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else null,
        descriptor = device.descriptor,
    )
}
