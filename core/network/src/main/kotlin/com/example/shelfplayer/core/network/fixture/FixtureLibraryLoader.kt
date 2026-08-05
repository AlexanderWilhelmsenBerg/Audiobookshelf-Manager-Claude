package com.example.shelfplayer.core.network.fixture

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.resultOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a [FixtureLibraryDocument] from the module's own resources.
 *
 * Failure modes are mapped to [AppError.ApiCompatibility] rather than to a crash, on purpose: the
 * fake gateway exercises the same error path the real one will use in Phase 1 when a server omits a
 * required field (PRODUCT_SPEC SYNC-001).
 */
@Singleton
class FixtureLibraryLoader @Inject constructor() {
    private val json = Json {
        // PRODUCT_SPEC SYNC-001: unknown fields are tolerated.
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    fun load(resourcePath: String = DEFAULT_FIXTURE): AppResult<FixtureLibraryDocument> {
        val raw = javaClass.classLoader?.getResourceAsStream(resourcePath)?.use { stream ->
            stream.readBytes().decodeToString()
        } ?: return AppResult.Failure(
            AppError.ApiCompatibility(
                summary = "The bundled demo library is missing from this build.",
                missingField = resourcePath,
            ),
        )

        return resultOf(
            onError = { throwable ->
                AppError.ApiCompatibility(
                    summary = "The bundled demo library could not be read.",
                    missingField = if (throwable is SerializationException) throwable.javaClass.name else null,
                )
            },
        ) {
            json.decodeFromString(FixtureLibraryDocument.serializer(), raw)
        }.let(::validate)
    }

    private fun validate(result: AppResult<FixtureLibraryDocument>): AppResult<FixtureLibraryDocument> = when (result) {
        is AppResult.Failure -> result
        is AppResult.Success -> when {
            result.value.schemaVersion != SUPPORTED_SCHEMA_VERSION -> AppResult.Failure(
                AppError.ApiCompatibility(
                    summary = "The bundled demo library uses an unsupported fixture schema.",
                    missingField = "schemaVersion=${result.value.schemaVersion}",
                ),
            )
            result.value.libraries.isEmpty() -> AppResult.Failure(
                AppError.ApiCompatibility(
                    summary = "The bundled demo library contains no libraries.",
                    missingField = "libraries",
                ),
            )
            else -> result
        }
    }

    companion object {
        const val DEFAULT_FIXTURE: String = "fixtures/demo-library.json"
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}
