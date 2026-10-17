package com.newoether.agora.ui.remote

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.RemoteNotice

/** Stable wire codes select local resources. Native diagnostic text stays in the notice. */
internal fun remoteNoticeMessage(context: Context, notice: RemoteNotice): String {
    val resource = when (notice.code) {
        "authentication_failed" -> R.string.remote_auth_failed
        "invalid_request" -> R.string.remote_invalid_request
        "not_found" -> R.string.remote_not_found
        "not_supported" -> R.string.remote_not_supported
        "session_busy" -> R.string.remote_session_busy
        "desktop_unavailable" -> R.string.remote_desktop_unavailable
        "session_unavailable" -> R.string.remote_session_unavailable
        "helper_unavailable" -> R.string.remote_helper_unavailable
        "runtime_unavailable" -> R.string.remote_runtime_unavailable
        "service_restarting" -> R.string.remote_service_restarting
        "request_timeout" -> R.string.remote_request_timeout
        "delivery_unconfirmed" -> R.string.remote_unknown
        "turn_changed" -> R.string.remote_turn_changed
        "image_unavailable" -> R.string.remote_image_unavailable
        "upload_invalid" -> R.string.remote_upload_invalid
        "upload_unavailable" -> R.string.remote_upload_unavailable
        "busy" -> R.string.remote_busy
        "content_too_large" -> R.string.remote_response_too_large
        "usage_unavailable" -> R.string.remote_usage_unavailable
        "settings_unavailable" -> R.string.remote_settings_unavailable
        else -> null
    }
    if (resource != null) return context.getString(resource)
    val fallback = context.getString(remoteFailureResource(notice.failure))
    // Older servers and provider failures can carry the only useful explanation.
    // Keep it, alongside a localized heading, rather than silently hiding it.
    return notice.detail?.takeIf { it.isNotBlank() }?.let { "$fallback\n$it" } ?: fallback
}

private fun remoteFailureResource(failure: RemoteFailure): Int = when (failure) {
    RemoteFailure.NETWORK -> R.string.remote_network_failed
    RemoteFailure.AUTHENTICATION -> R.string.remote_auth_failed
    RemoteFailure.CONFIGURATION -> R.string.remote_configuration_failed
    RemoteFailure.PROTOCOL -> R.string.remote_protocol_failed
    RemoteFailure.SESSION_BUSY -> R.string.remote_session_busy
    RemoteFailure.CONTENT_TOO_LARGE -> R.string.remote_response_too_large
    RemoteFailure.SERVICE -> R.string.remote_service_failed
    RemoteFailure.STORAGE -> R.string.remote_storage_failed
    RemoteFailure.UNKNOWN -> R.string.remote_failed
}
