package com.example.shelfplayer

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.shelfplayer.core.model.settings.AppLanguage
import java.util.Locale

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — draws everything inside it in the chosen language.
 *
 * ### Why there are two mechanisms and not one
 *
 * Android's own per-app language API (`LocaleManager`) exists only from API 33. This app's `minSdk` is 26,
 * so on a third of the range it supports there is no platform feature to call — and a setting that works
 * on new phones and silently does nothing on old ones is not a setting.
 *
 * So the *composition* is what carries the language: this provides a [Configuration] and a [Context] that
 * resolve resources in the chosen locale, and `stringResource` reads both. That works identically on every
 * supported release, and it is what makes the setting real on API 26–32.
 *
 * On **API 33 and above the platform is told as well**, and it is worth saying why that is not redundant.
 * Three strings are resolved outside any composition — the download notification's title, its progress
 * line and its channel name, all read from a `Context` in a `WorkManager` worker. The composition cannot
 * reach those. `LocaleManager` can, because it changes what the *process* thinks its locale is. It also
 * puts the choice in Android's own Settings → Apps → Language, which is where a user will look for it
 * after changing it here.
 *
 * The two agree by construction: after the platform accepts the choice, [LocalConfiguration] already
 * carries it, and providing the same locale again resolves to the same resources.
 *
 * ### Why the platform call is guarded
 *
 * `setApplicationLocales` recreates the activity. Calling it with a value the platform already holds would
 * do so on every recomposition of this function — a loop that redraws the app forever. The comparison is
 * the guard, and it compares tags rather than [LocaleList] instances because the platform normalises what
 * it stores (`nb` can come back as `nb-NO`).
 */
@Composable
fun AppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    ApplyPlatformLocale(language)

    if (language == AppLanguage.System) {
        // Nothing to override: the device's own configuration is the answer. Providing a copy would be
        // harmless and would also mean every recomposition allocated a Configuration and a Context for no
        // reason, on the default path that most installs are on.
        content()
        return
    }

    val localized = remember(configuration, language) {
        val locale = Locale.forLanguageTag(language.tag)
        val copy = Configuration(configuration).apply { setLocale(locale) }
        copy to context.createConfigurationContext(copy)
    }

    CompositionLocalProvider(
        LocalConfiguration provides localized.first,
        LocalContext provides localized.second,
        content = content,
    )
}

/**
 * Hands the choice to the platform, on the releases that have somewhere to put it.
 *
 * A `LaunchedEffect` rather than a plain call, because this writes system state: composition must be free
 * to run twice without asking the platform twice, and the activity recreation this triggers must not
 * happen while the frame is being composed.
 */
@Composable
private fun ApplyPlatformLocale(language: AppLanguage) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    LaunchedEffect(language) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return@LaunchedEffect
        val wanted = if (language == AppLanguage.System) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(language.tag)
        }
        if (!manager.applicationLocales.matches(wanted)) manager.applicationLocales = wanted
    }
}

/**
 * Whether the platform already holds this choice, compared on the primary subtag.
 *
 * `LocaleList.equals` is not the right test: asked for `nb`, a device can report `nb-NO`, and treating that
 * as a difference is what would make the guard above fail to guard.
 */
private fun LocaleList.matches(wanted: LocaleList): Boolean {
    val mine = get(0)?.language?.lowercase()
    val theirs = wanted.get(0)?.language?.lowercase()
    return mine == theirs
}
