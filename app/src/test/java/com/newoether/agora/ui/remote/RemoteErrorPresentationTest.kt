package com.newoether.agora.ui.remote

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.RemoteNotice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RemoteErrorPresentationTest {
    private fun context(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        })
    }

    @Test fun knownCodesFollowTheUiLocaleAndKeepDiagnosticsOutOfTheSnackbar() {
        val notice = RemoteNotice("read_failed", RemoteFailure.SERVICE, 1,
            "Original desktop owner is unavailable", "desktop_unavailable")
        val english = remoteNoticeMessage(context("en"), notice)
        val chinese = remoteNoticeMessage(context("zh-CN"), notice)
        assertTrue(english.contains("Open Codex"))
        assertTrue(chinese.contains("目标电脑"))
        assertFalse(chinese.contains("Original desktop owner"))
        assertTrue(remoteNoticeMessage(context("zh-TW"), notice).contains("目標電腦"))
        assertTrue(notice.canRetryRead)
    }

    @Test fun unknownAndLegacyErrorsKeepProviderDetailsWithALocalizedHeading() {
        for (code in listOf(null, "native_error", "future_server_code")) {
            val notice = RemoteNotice("send_failed", RemoteFailure.SERVICE, 1, "Provider limit: 429", code)
            val text = remoteNoticeMessage(context("zh-CN"), notice)
            assertTrue(text.contains("Provider limit: 429"))
            assertTrue(text.contains("Codex 未能"))
            assertFalse(notice.canRetryRead)
        }
    }

    @Test fun uncertainDeliveryNeverOffersABlindSendRetry() {
        val notice = RemoteNotice("send_failed", RemoteFailure.SERVICE, 1, code = "delivery_unconfirmed")
        assertTrue(remoteNoticeMessage(context("en"), notice).contains("before sending again"))
        assertFalse(notice.canRetryRead)
    }
}
