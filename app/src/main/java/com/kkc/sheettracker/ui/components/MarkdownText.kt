package com.kkc.sheettracker.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

private val URL_REGEX = Regex("""https?://[^\s<>"')\]]+""")
private val MD_LINK_REGEX = Regex("""\[([^\[\]]+)]\((https?://[^\s()]+)\)""")
private val BOLD_REGEX = Regex("""\*\*(.+?)\*\*""")
private val ITALIC_REGEX = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")

/**
 * Renders plain text with lightweight markdown: **bold**, *italic*,
 * [text](url) links, and auto-linked bare http(s):// URLs. Not a full
 * markdown parser — just enough for supply notes/comments to look
 * readable and let links be tapped.
 */
fun buildLightMarkdown(text: String, linkColor: Color): AnnotatedString {
    // Find markdown links and bare URLs first so bold/italic scanning skips over them.
    val mdLinkMatches = MD_LINK_REGEX.findAll(text).toList()
    val mdLinkRanges = mdLinkMatches.map { it.range }
    val urlRanges = URL_REGEX.findAll(text)
        .map { it.range }
        .filter { candidate -> mdLinkRanges.none { candidate.first in it } }
        .toList()

    fun linkSpanStyle() = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)

    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val mdLink = mdLinkMatches.firstOrNull { it.range.first == i }
            if (mdLink != null) {
                val label = mdLink.groupValues[1]
                val url = mdLink.groupValues[2]
                withLink(
                    LinkAnnotation.Url(url = url, styles = TextLinkStyles(style = linkSpanStyle()))
                ) {
                    append(label)
                }
                i = mdLink.range.last + 1
                continue
            }

            val url = urlRanges.firstOrNull { it.first == i }
            if (url != null) {
                val urlText = text.substring(url.first, url.last + 1)
                withLink(
                    LinkAnnotation.Url(url = urlText, styles = TextLinkStyles(style = linkSpanStyle()))
                ) {
                    append(urlText)
                }
                i = url.last + 1
                continue
            }

            val insideUrl = urlRanges.any { i in it } || mdLinkRanges.any { i in it }
            if (insideUrl) {
                i++
                continue
            }

            val bold = BOLD_REGEX.find(text, i)?.takeIf { it.range.first == i }
            if (bold != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(bold.groupValues[1])
                }
                i = bold.range.last + 1
                continue
            }

            val italic = ITALIC_REGEX.find(text, i)?.takeIf { it.range.first == i }
            if (italic != null) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(italic.groupValues[1])
                }
                i = italic.range.last + 1
                continue
            }

            // Backslash-escaped punctuation (e.g. "\$210" from old imports) — drop the
            // backslash and show the literal character, same as standard markdown escaping.
            if (text[i] == '\\' && i + 1 < text.length) {
                append(text[i + 1])
                i += 2
                continue
            }

            append(text[i])
            i++
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val linkColor = MaterialTheme.colorScheme.primary
    Text(
        text = buildLightMarkdown(text, linkColor),
        modifier = modifier,
        style = style,
        color = color
    )
}
