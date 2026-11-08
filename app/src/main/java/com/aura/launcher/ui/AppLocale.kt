package com.aura.launcher.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import com.aura.launcher.data.AppLanguage
import java.util.Locale

/**
 * Runs the whole UI in the language the user picked.
 *
 * Android has per-app locales from API 33 and a support-library backport, but
 * both route through AppCompat, which this app deliberately does not use — it
 * is a Compose activity on ComponentActivity, and pulling in AppCompat to set
 * one field would be a large dependency for a small job.
 *
 * So the locale is applied where it is actually read. `stringResource` resolves
 * against [LocalContext]'s resources and [LocalConfiguration]; overriding both
 * with a configuration-wrapped context makes every string in the tree resolve in
 * the chosen language, and makes a change take effect on the next frame rather
 * than on the next launch. Anything outside the composition — a notification
 * posted by a service, say — keeps using the system locale, which is the honest
 * behaviour: those are drawn by the system, not by this tree.
 */
@Composable
fun WithAppLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val locale = remember(language) { language.toLocale() }
    val localized = remember(locale, configuration) {
        val config = Configuration(configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        context.createConfigurationContext(config) to config
    }

    // Locale.setDefault so anything that formats a date or a number outside a
    // composable — SimpleDateFormat in a repository, a Calendar's day names —
    // agrees with what the strings around it are written in. Without it the
    // sheet would read "Seu dia" above "Sat, 2 Aug".
    remember(locale) { Locale.setDefault(locale); locale }

    // createConfigurationContext returns a bare Context whose baseContext
    // chain does not lead back to the Activity, so any CompositionLocal
    // whose default reads the Activity out of LocalContext would resolve to
    // null once we replace LocalContext below. rememberLauncherForActivityResult
    // does exactly that, and OnboardingScreen crashes with "No
    // ActivityResultRegistryOwner was provided" on a fresh install.
    //
    // Capture the owners the Activity provided BEFORE the replacement, then
    // re-provide them verbatim under the localized context. The strings
    // localize, and everything that needed the Activity still finds it.
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val onBackPressedDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    val savedStateRegistryOwner = LocalSavedStateRegistryOwner.current

    CompositionLocalProvider(
        LocalContext provides localized.first,
        LocalConfiguration provides localized.second,
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner!!,
        LocalOnBackPressedDispatcherOwner provides onBackPressedDispatcherOwner!!,
        LocalLifecycleOwner provides lifecycleOwner,
        LocalViewModelStoreOwner provides viewModelStoreOwner!!,
        LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
        content = content,
    )
}

/**
 * The locale a choice means.
 *
 * Portuguese is pinned to Brazil because that is the only Portuguese this app
 * has strings for, and a `pt` that resolved to European Portuguese would fall
 * back to English rather than to the translations that exist.
 */
fun AppLanguage.toLocale(): Locale = when (this) {
    AppLanguage.ENGLISH -> Locale.forLanguageTag("en")
    AppLanguage.PORTUGUESE -> Locale.forLanguageTag("pt-BR")
    AppLanguage.SPANISH -> Locale.forLanguageTag("es")
}

/**
 * What to preselect before the user has been asked.
 *
 * Reads the phone's own language rather than defaulting to English, so the
 * onboarding step opens on the answer most people are about to give.
 */
fun defaultLanguageFor(context: Context): AppLanguage {
    val tag = context.resources.configuration.locales.takeIf { !it.isEmpty }
        ?.get(0)?.language.orEmpty().lowercase()
    return when (tag) {
        "pt" -> AppLanguage.PORTUGUESE
        "es" -> AppLanguage.SPANISH
        else -> AppLanguage.ENGLISH
    }
}
