package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R

/** Resolve model-sync labels at the original lazy adapter initialization boundary. */
internal fun Context.providerModelSyncUiText() = ProviderModelSyncUiText(
    failureLabels = ModelSyncFailureLabels(
        noModels = getString(R.string.sync_error_no_models),
        timeout = getString(R.string.sync_error_timeout),
        invalidResponse = getString(R.string.sync_error_invalid_response),
        unknown = getString(R.string.unknown_error),
    ),
    globalProviderName = getString(R.string.models_title),
    successfulProviders = { count ->
        getString(R.string.sync_success_providers, count)
    },
    noProviders = getString(R.string.sync_no_providers),
    completed = getString(R.string.sync_completed),
)
