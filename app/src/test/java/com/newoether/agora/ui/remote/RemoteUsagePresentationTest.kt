package com.newoether.agora.ui.remote

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RemoteUsagePresentationTest {
    @Test fun windowsUseHoursAndDaysWithoutLosingRemainders() {
        assertEquals("5 hours", usageWindowDuration(300, Locale.US))
        assertEquals("7 days", usageWindowDuration(10080, Locale.US))
        assertEquals("1 hour", usageWindowDuration(60, Locale.US))
        assertEquals("1 day", usageWindowDuration(1440, Locale.US))
        assertEquals("1 hour, 30 minutes", usageWindowDuration(90, Locale.US))
        assertEquals("1 day, 1 hour, 1 minute", usageWindowDuration(1501, Locale.US))
        assertEquals("59 minutes", usageWindowDuration(59, Locale.US))
        assertEquals("0 minutes", usageWindowDuration(0, Locale.US))
    }

    @Test fun unitsFollowEveryAppLocaleAndAreNotAddedTwiceByResources() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (tag in listOf("en", "ar", "de", "es", "fr", "ja", "ko", "pt-BR", "ru", "vi", "zh-CN", "zh-TW")) {
            val locale = Locale.forLanguageTag(tag)
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
            val duration = usageWindowDuration(300, locale)
            val label = context.getString(R.string.remote_usage_window, duration)
            assertTrue(tag, label.contains(duration))
            assertFalse(tag, label.contains("%1"))
            assertFalse(tag, label.contains("300"))
        }
        assertTrue(usageWindowDuration(300, Locale.SIMPLIFIED_CHINESE).contains("小时"))
        assertTrue(usageWindowDuration(10080, Locale.SIMPLIFIED_CHINESE).contains("天"))
    }
}
