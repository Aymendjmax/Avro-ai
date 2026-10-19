package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.AvroEndpoints

@Composable
fun DocumentationFab(docPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val langTag = locale.toLanguageTag()
    val baseDocsUrl = AvroEndpoints.DOCUMENTATION_URL.trimEnd('/')

    val lang = when {
        langTag == "zh-Hant" -> "zh-Hant"
        langTag.startsWith("zh") -> "zh"
        locale.language == "ar" -> "ar"
        else -> "en"
    }

    AnimatedActionFab(
        label = stringResource(R.string.documentation),
        icon = Icons.AutoMirrored.Filled.MenuBook,
        onClick = {
            val page = docPath.removeSuffix(".md")
            val url = if (baseDocsUrl.contains("github.com")) {
                val repoBase = baseDocsUrl.substringBefore("/tree/").substringBefore("/blob/")
                "$repoBase/blob/main/docs/$lang/$page.md"
            } else {
                "$baseDocsUrl/$lang/$page"
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
        modifier = modifier,
    )
}
