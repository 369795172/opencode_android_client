package ai.opencode.client.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.m3.Markdown
import ai.opencode.client.ui.theme.markdownTypographyCompact
import ai.opencode.client.ui.util.DataUriImageTransformer
import com.mikepenz.markdown.model.rememberMarkdownState

@Composable
internal fun WorkspaceLinkMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit
) {
    CompositionLocalProvider(
        LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) {
                onLinkClick(uri)
            }
        }
    ) {
        Markdown(
            markdownState = rememberMarkdownState(content = content, retainState = true),
            typography = markdownTypographyCompact(),
            modifier = modifier,
            imageTransformer = DataUriImageTransformer
        )
    }
}
