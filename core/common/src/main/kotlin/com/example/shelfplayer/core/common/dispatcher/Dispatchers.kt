package com.example.shelfplayer.core.common.dispatcher

import javax.inject.Qualifier

/**
 * PRODUCT_SPEC 16.3 / 22 — no raw coroutine dispatchers outside the injected provider.
 *
 * Every class that needs a dispatcher takes one through a constructor parameter annotated with
 * [Dispatcher]. Tests replace it with a `TestDispatcher`, which is what makes scheduling behavior
 * (progress journalling cadence, debounce windows, retry backoff) deterministic instead of flaky.
 *
 * Apply it with an explicit `@param:` use-site target on a constructor `val`:
 *
 * ```kotlin
 * class Example @Inject constructor(
 *     @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
 * )
 * ```
 *
 * Dagger reads a qualifier from the constructor *parameter*, and Kotlin 2.2 warns (KT-73255) that
 * an un-targeted annotation on a constructor property will change meaning in a future release.
 * Naming the target keeps today's behavior and survives that change.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val kind: ShelfDispatcher)

enum class ShelfDispatcher {
    /** CPU-bound work: parsing, sorting, diffing. */
    Default,

    /** Disk and network I/O. */
    Io,

    /** The Android main thread. */
    Main,

    /** The Android main thread, dispatched immediately when already on it. */
    MainImmediate,
}

/**
 * PRODUCT_SPEC 22.10 — a process-lifetime scope, so nothing ever needs `GlobalScope`.
 *
 * Work that must outlive a screen (flushing playback progress, committing a download) is launched
 * here. Everything else belongs to a `viewModelScope` or a service scope.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
