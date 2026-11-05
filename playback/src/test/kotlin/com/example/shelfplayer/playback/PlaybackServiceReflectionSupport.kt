package com.example.shelfplayer.playback

import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy

internal inline fun <reified T> unusedPlaybackDependency(): T = playbackDependencyProxy {
    error("Unexpected repository call: $it")
}

internal inline fun <reified T> playbackDependencyProxy(crossinline result: (String) -> Any?): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> result(method.name) } as T

@Suppress("UNCHECKED_CAST")
internal inline fun <reified T> Any.playbackField(name: String): T = javaClass.getDeclaredField(name).run {
    isAccessible = true
    get(this@playbackField) as T
}

internal fun Any.setPlaybackField(name: String, value: Any?) {
    if (this is AudioOutputRouter && name == "player") {
        val guard = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
            isAccessible = true
            get(this@setPlaybackField) as HeadsetRouteGuard
        }
        guard.detachPlayer()
        if (value is ExoPlayer) guard.attachPlayer(value)
    }
    if (this is AudioOutputRouter && name == "guardedHeadsetId") {
        val owner = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
            isAccessible = true
            get(this@setPlaybackField) as HeadsetRouteGuard
        }
        if (value == null) owner.release() else owner.protect(value as String)
        return
    }
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

internal fun Any.playbackCall(name: String): Any? = javaClass.getDeclaredMethod(name).run {
    isAccessible = true
    invoke(this@playbackCall)
}

internal fun Any.playbackCall(name: String, parameterType: Class<*>, argument: Any?): Any? =
    javaClass.getDeclaredMethod(name, parameterType).run {
        isAccessible = true
        invoke(this@playbackCall, argument)
    }
