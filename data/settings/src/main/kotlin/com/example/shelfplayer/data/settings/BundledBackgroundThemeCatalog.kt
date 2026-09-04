package com.example.shelfplayer.data.settings

import android.content.Context
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.ThemeAccents
import com.example.shelfplayer.core.model.settings.ThemeGlass
import com.example.shelfplayer.core.model.settings.ThemeGround
import com.example.shelfplayer.core.model.settings.ThemeSurfaces
import com.example.shelfplayer.core.model.settings.ThemeText
import com.example.shelfplayer.domain.settings.BackgroundThemeCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SET-002 (Appearance) — the bundled theme packs, read from assets once.
 *
 * ### Why the JSON is kept and parsed rather than turned into Kotlin
 *
 * Converting the packs into a table of constants would have been less code at runtime and would have made
 * a malformed pack a compile error. It was rejected because it throws away the thing the packs are for:
 * a pack is a directory, and adding a theme should be dropping one in beside the others rather than
 * hand-transcribing forty colours without a typo. The compile-time safety is bought back by
 * `BundledBackgroundThemeCatalogTest`, which parses **every** bundled theme and asserts its shape — so a
 * malformed pack fails the build, just later and with a better message.
 *
 * ### Why the DTOs are private
 *
 * PRODUCT_SPEC 9.3 — a DTO never leaves its data module. These are `private` to this file and are mapped
 * to `BackgroundTheme` inside [parse], so the shape of somebody else's JSON cannot become the shape of
 * this app's domain.
 *
 * ### `ignoreUnknownKeys`
 *
 * On, deliberately. The packs carry roles this app has no use for yet — `progress`, `state`, the pressed
 * variants — and a pack that gains a role in its next version must not stop loading in a build that does
 * not read it. The keys this app *does* need are non-optional, so a pack missing one of those still fails
 * loudly rather than loading with holes in it.
 */
@Singleton
class BundledBackgroundThemeCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
    private val logger: Logger,
) : BackgroundThemeCatalog {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cached: List<BackgroundTheme>? = null

    override suspend fun themes(): List<BackgroundTheme> = lock.withLock {
        cached ?: withContext(io) { read() }.also { cached = it }
    }

    override suspend fun theme(id: String): BackgroundTheme? = themes().firstOrNull { it.id == id }

    /**
     * The manifest, then each theme it names.
     *
     * A theme that fails to parse is **skipped and logged** rather than taking the others down with it:
     * these are bundled assets, so a failure here is a build fault that the test above should have caught,
     * and the useful behaviour when one slips through is that the app still starts with the themes that
     * are fine. The catch is at a genuine boundary — asset IO and third-party JSON — and it names what it
     * could not read.
     */
    private fun read(): List<BackgroundTheme> {
        val manifest = runCatching {
            json.decodeFromString<ManifestDto>(context.assets.open(MANIFEST).bufferedReader().readText())
        }.getOrElse { error ->
            logger.warn(LogCategory.Settings, "No background theme manifest: ${error.javaClass.simpleName}")
            return emptyList()
        }
        return manifest.themes.mapNotNull { entry ->
            runCatching { parse(entry, load(entry.id)) }.getOrElse { error ->
                logger.warn(
                    LogCategory.Settings,
                    "Skipped background theme ${entry.id}: ${error.javaClass.simpleName}",
                )
                null
            }
        }
    }

    private fun load(id: String): ThemeDto =
        json.decodeFromString(context.assets.open("$ROOT/$id/theme.json").bufferedReader().readText())

    private fun parse(entry: ManifestEntryDto, dto: ThemeDto) = BackgroundTheme(
        id = entry.id,
        name = entry.name,
        pack = entry.pack,
        // Read from the palette, not from which pack it arrived in — see `BackgroundTheme`.
        isDark = argb(dto.background.base).luminance() < DARK_GROUND_LUMINANCE,
        ground = ThemeGround(
            asset = "$ROOT/${entry.id}/${dto.background.asset}",
            base = argb(dto.background.base),
            scrim = argb(dto.background.scrim),
        ),
        surfaces = ThemeSurfaces(
            card = argb(dto.surface.card),
            cardElevated = argb(dto.surface.cardElevated),
            navigation = argb(dto.surface.navigation),
            divider = argb(dto.surface.divider),
        ),
        glass = ThemeGlass(
            tint = argb(dto.glass.tint),
            border = argb(dto.glass.border),
            blurDp = dto.glass.blurRadiusDp,
        ),
        accents = ThemeAccents(
            primary = argb(dto.accent.primary),
            primaryContainer = argb(dto.accent.primaryContainer),
            onPrimary = argb(dto.accent.onPrimary),
            secondary = argb(dto.accent.secondary),
            tertiary = argb(dto.accent.tertiary),
            error = argb(dto.state.error),
        ),
        text = ThemeText(
            primary = argb(dto.text.primary),
            secondary = argb(dto.text.secondary),
            muted = argb(dto.text.muted),
            inverse = argb(dto.text.inverse),
        ),
    )

    @Serializable
    private data class ManifestDto(val themes: List<ManifestEntryDto>)

    @Serializable
    private data class ManifestEntryDto(val id: String, val name: String, val pack: String)

    @Serializable
    private data class ThemeDto(
        val background: BackgroundDto,
        val surface: SurfaceDto,
        val glass: GlassDto,
        val accent: AccentDto,
        val text: TextDto,
        val state: StateDto,
    )

    @Serializable
    private data class BackgroundDto(val asset: String, val base: String, val scrim: String)

    @Serializable
    private data class SurfaceDto(
        val card: String,
        @SerialName("cardElevated") val cardElevated: String,
        val navigation: String,
        val divider: String,
    )

    @Serializable
    private data class GlassDto(
        val tint: String,
        val border: String,
        @SerialName("blurRadiusDp") val blurRadiusDp: Int,
    )

    @Serializable
    private data class AccentDto(
        val primary: String,
        @SerialName("primaryContainer") val primaryContainer: String,
        @SerialName("onPrimary") val onPrimary: String,
        val secondary: String,
        val tertiary: String,
    )

    @Serializable
    private data class TextDto(val primary: String, val secondary: String, val muted: String, val inverse: String)

    @Serializable
    private data class StateDto(val error: String)

    private companion object {
        const val ROOT = "themes"
        const val MANIFEST = "$ROOT/manifest.json"

        /** Below this the ground is dark enough to want light text on it. See `BackgroundTheme.isDark`. */
        const val DARK_GROUND_LUMINANCE = 0.4
    }
}

/**
 * `#RRGGBB` or `#AARRGGBB`, as an ARGB long — the notation both packs' manifests declare.
 *
 * A six-digit value is opaque, which is the packs' own rule and also the only reading that could be
 * right: a colour with no alpha written is not a transparent colour.
 *
 * Throws on anything else, which is what makes a malformed pack a skipped theme with a named cause
 * rather than a black screen.
 */
internal fun argb(hex: String): Long {
    val digits = hex.removePrefix("#")
    require(digits.length == RGB_DIGITS || digits.length == ARGB_DIGITS) { "Not a colour: $hex" }
    val value = digits.toLong(radix = HEX)
    return if (digits.length == RGB_DIGITS) value or OPAQUE else value
}

/** Rec. 709 relative luminance, 0..1, for deciding whether a ground wants light text. */
internal fun Long.luminance(): Double {
    val r = (this shr RED_SHIFT and CHANNEL_MASK) / CHANNEL_MAX
    val g = (this shr GREEN_SHIFT and CHANNEL_MASK) / CHANNEL_MAX
    val b = (this and CHANNEL_MASK) / CHANNEL_MAX
    return RED_WEIGHT * r + GREEN_WEIGHT * g + BLUE_WEIGHT * b
}

private const val RGB_DIGITS = 6
private const val ARGB_DIGITS = 8
private const val HEX = 16
private const val OPAQUE = 0xFF000000L
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFFL
private const val CHANNEL_MAX = 255.0
private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722
