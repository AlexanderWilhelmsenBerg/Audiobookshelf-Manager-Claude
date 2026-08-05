package com.example.shelfplayer.core.model

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.cancellation.CancellationException

/**
 * PRODUCT_SPEC 14.2 — the typed result every repository and gateway returns.
 *
 * Exceptions may be used inside a module; they are translated here at the boundary. Coroutine
 * cancellation is never translated — [resultOf] rethrows it.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(val value: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>
}

@OptIn(ExperimentalContracts::class)
fun <T> AppResult<T>.isSuccess(): Boolean {
    contract { returns(true) implies (this@isSuccess is AppResult.Success<T>) }
    return this is AppResult.Success
}

@OptIn(ExperimentalContracts::class)
fun <T> AppResult<T>.isFailure(): Boolean {
    contract { returns(true) implies (this@isFailure is AppResult.Failure) }
    return this is AppResult.Failure
}

fun <T> AppResult<T>.getOrNull(): T? = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> null
}

fun <T> AppResult<T>.errorOrNull(): AppError? = when (this) {
    is AppResult.Success -> null
    is AppResult.Failure -> error
}

inline fun <T> AppResult<T>.getOrElse(fallback: (AppError) -> T): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> fallback(error)
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.mapError(transform: (AppError) -> AppError): AppResult<T> = when (this) {
    is AppResult.Success -> this
    is AppResult.Failure -> AppResult.Failure(transform(error))
}

@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
    if (this is AppResult.Success) action(value)
    return this
}

@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
    if (this is AppResult.Failure) action(error)
    return this
}

/** Convenience constructor so call sites read as `value.asSuccess()` instead of a nested generic. */
fun <T> T.asSuccess(): AppResult<T> = AppResult.Success(this)

/** Convenience constructor mirroring [asSuccess]. */
fun AppError.asFailure(): AppResult<Nothing> = AppResult.Failure(this)

/**
 * Runs [block], translating a thrown exception into [AppResult.Failure] via [onError].
 *
 * PRODUCT_SPEC 14.2 / 22: [CancellationException] is always rethrown so that structured concurrency
 * keeps working. This is the only place in the codebase that is allowed to catch [Throwable], and it
 * exists precisely so that no other layer needs to.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> resultOf(
    onError: (Throwable) -> AppError = { AppError.Unknown(cause = it) },
    block: () -> T,
): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    AppResult.Failure(onError(throwable))
}
