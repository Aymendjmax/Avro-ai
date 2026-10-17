package com.newoether.agora.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** The locale for the app-language setting [code], or null for "system". */
internal fun appLocaleFor(code: String): Locale? = when (code) {
    "zh" -> Locale("zh", "CN")
    "en" -> Locale("en")
    "es" -> Locale("es")
    "fr" -> Locale("fr")
    "de" -> Locale("de")
    "ru" -> Locale("ru")
    "pt-BR" -> Locale("pt", "BR")
    "ja" -> Locale("ja")
    "ko" -> Locale("ko")
    "ar" -> Locale("ar")
    "vi" -> Locale("vi")
    "zh-Hant" -> Locale.forLanguageTag("zh-Hant")
    else -> null
}

/** [context] configured with the locale of the app-language setting [code]. */
internal fun Context.withAppLocale(code: String): Context {
    val locale = appLocaleFor(code) ?: return this
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}

/** Strings in the language the app shows, for surfaces outside an Activity. */
internal fun Context.appLanguageResources(code: String): Resources = withAppLocale(code).resources
