package io.github.aedev.flow.utils

import android.content.Context
import android.content.Intent
import io.github.aedev.flow.R

/** Shares [url] as plain text through the system sheet, with [title] as the subject. */
fun shareLink(
    context: Context,
    url: String,
    title: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
}

const val PLAYLIST_FILE_MIME_TYPE = "application/json"
