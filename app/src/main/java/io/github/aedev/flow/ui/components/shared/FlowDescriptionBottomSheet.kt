package io.github.aedev.flow.ui.components.shared

import android.text.style.URLSpan
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat
import io.github.aedev.flow.ui.theme.DescriptionLinkBlue

fun parseHtmlDescription(
    rawHtml: String,
    linkColor: Color = DescriptionLinkBlue,
): AnnotatedString {
    // 1. Parse HTML into an Android Spanned object (Handles <br>, <a>, &amp;)
    val spanned = HtmlCompat.fromHtml(rawHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
    val text = spanned.toString()

    return buildAnnotatedString {
        // 2. Append the clean text (no tags)
        append(text)

        // 3. Find all URLSpans created by the HTML parser and apply Compose styles
        val urlSpans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        val htmlLinkRanges: List<IntRange> =
            urlSpans.map {
                spanned.getSpanStart(it) until spanned.getSpanEnd(it)
            }
        for (span in urlSpans) {
            val start = spanned.getSpanStart(span).coerceAtMost(text.length)
            val end = spanned.getSpanEnd(span).coerceAtMost(text.length)
            if (start >= end) continue
            val rawUrl = span.url
            val absoluteUrl = if (rawUrl.startsWith("/")) "https://www.youtube.com$rawUrl" else rawUrl
            addStyle(
                style =
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.SemiBold,
                    ),
                start = start,
                end = end,
            )
            addStringAnnotation(tag = "URL", annotation = absoluteUrl, start = start, end = end)
        }

        // 4. Find plain-text URLs (https://... not covered by an anchor tag)
        val htmlUrlStarts = urlSpans.map { spanned.getSpanStart(it) }.toSet()
        val urlRegex = Regex("""https?://[^\s]+""")
        urlRegex.findAll(text).forEach { matchResult ->
            val start = matchResult.range.first
            // Skip if already covered by an HTML anchor
            if (start !in htmlUrlStarts) {
                val end = matchResult.range.last + 1
                addStyle(
                    style =
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    start = start,
                    end = end,
                )
                addStringAnnotation(tag = "URL", annotation = matchResult.value, start = start, end = end)
            }
        }

        val timestampRegex = Regex("""\b(?:[0-9]{1,2}:)?[0-9]{1,2}:[0-9]{2}\b""")
        timestampRegex.findAll(text).forEach { matchResult ->
            val start = matchResult.range.first
            val end = matchResult.range.last + 1
            addStyle(
                style =
                    SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.SemiBold,
                    ),
                start = start,
                end = end,
            )
            addStringAnnotation(tag = "TIMESTAMP", annotation = matchResult.value, start = start, end = end)
        }
    }
}
