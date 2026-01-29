package com.kunk.singbox.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.kunk.singbox.model.AppLanguage
import java.util.Locale

object LocaleHelper {

    fun setLocale(context: Context, language: AppLanguage): Context {
        val locale = when (language) {
            AppLanguage.SYSTEM -> getSystemLocale()
        
            else -> Locale(language.localeCode) 
        }

        return updateResources(context, locale)
    }

    private fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList.getDefault().get(0)
        } else {
            @Suppress("DEPRECATION")
            Locale.getDefault()
        }
    }

    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        }
        configuration.setLocale(locale)

        return context.createConfigurationContext(configuration)
    }

    fun wrap(context: Context, language: AppLanguage): Context {
        return setLocale(context, language)
    }
}
